# Curve：一个全栈响应式 Clojure Web 框架的技术方案

> 状态：草案 v0.2，2026-10-03（v0.1 增补安全、隔离与部署、测试、调试、协作、local-first、静态站点、路由等章节）
> 许可证：MIT
> 原暂定名 "Arc"，2026-10-03 定名为 "Curve"。
>
> 本方案以 Electric Clojure v3 的设计为出发点，借鉴 React 19、Svelte 5、SolidJS、Meteor 和 Phoenix LiveView，
> 目标是一个更小、更快、通信更少、支持 SSR、REPL 体验更好的同类框架。
> 标注「已核实」的结论来自阅读 Electric 源码和 `electric-sqlite-app` 的实测；标注「待核实」的需要在原型阶段验证。

---

## 0. 两条硬约束

### 0.1 Clean-room

Electric v3 采用 Hyperfiddle Business Source License，**Curve 不得复制、改写或翻译 Electric 的源码**。可以借鉴的是公开的设计思想：表达式级网络边界、增量序列、协议的 monoid 结构、结构化并发。所有代码必须独立实现。

- 可直接依赖的库：Missionary（EPL）、transit、tools.analyzer 等，许可证与 MIT 兼容。
- 参与核心编译器和运行时编写的人，不应同时逐行阅读 Electric 源码。设计讨论引用 Electric 时只引用其文档、协议说明和公开演讲。
- Electric v2 的许可证与 v3 不同，是否可参考其代码需单独核实（待核实）。

### 0.2 复杂度预算

每新增一项能力都要回答：它是不是**已有四个基础机制**的复用？

| 基础机制 | 由它派生的能力 |
|---|---|
| 程序表（产物是数据） | 小产物、紧凑协议、快照、SSR 续航、热替换、代码分割、静态构建、边界报告、污点分析 |
| 无头 DOM | SSR、测试渲染、静态构建 |
| 共享层（跨会话的响应式值） | 查询复用、协作状态、在线状态 |
| 消息日志（monoid） | 批处理、背压、回放调试、从日志生成测试 |

不是这四者复用的能力，要么进入可选模块，要么进入 dev / test 专用 artifact。运行时核心的模块与体积预算见 §12。

---

## 1. 目标与非目标

### 目标

| 维度 | 目标 | 对照 Electric 现状（已核实） |
|---|---|---|
| 客户端体积 | 运行时核心 gzip ≤ 40 KB；Hello World 全量 ≤ 80 KB | 实测 175 KB，其中约 200 KB（未压缩）是测试库与 pprint 的连带依赖 |
| 编译产物 | 每个响应式函数的产物 ≤ 同等 ClojureScript 函数的 3 倍 | 80 行 `main.cljc` 编译出 60 KB |
| 通信 | 二进制协议；单字段更新 ≤ 20 字节有效载荷 | transit 文本编码 |
| 首屏 | SSR、流式输出、hydration；无 JS 也能看到内容和提交表单 | 纯客户端渲染 |
| 静态站点 | 无服务端依赖的页面可构建为纯静态 HTML + 小 JS | 不支持 |
| 服务端成本 | 相同查询跨会话只执行一次；会话可快照与恢复；故障按会话隔离 | 每连接独立执行；断线后重建 |
| 安全 | 边界可审计、slot 请求受服务端授权、敏感值不可能流向客户端 | 应用层自觉 |
| 数据源 | 增量数据源协议和若干适配器 | 用户自行处理 |
| 表达力 | 保留表达式级边界和完整 Lisp 组合能力；语法更紧凑 | — |
| 可测试性 | 两端在一个 JVM 内确定性地测试，可断言"跨网络传了什么" | 无专门工具 |
| 可调试性 | 实时图检视、回放、跨站点栈、"为什么更新" | 有限 |
| REPL | 热替换线上会话的图，状态尽量保留 | 单 JVM 热重载 |

### 非目标（明确不做）

- 无状态 / edge 部署。Curve 是有状态服务端，这是模型本身的选择。
- 完整的 Differential Dataflow（任意增量 join / 迭代）。
- CRDT 与离线同步引擎。Curve 可以**承载**它们（§10），但不实现它们。
- 非浏览器客户端。
- Clojure 以外的宿主语言。

---

## 2. 架构总览

```
┌──────────────── 编译期（JVM，宏展开时）─────────────────────┐
│  源码 (r/defn ...)                                            │
│    │ expand  ：宏展开为内建形式，保留行列元数据                 │
│    │ analyze ：数据流图，站点推断，DCE，内联，静态模板提取       │
│    │ check   ：污点分析（^:secret 不得流向 client），边界报告    │
│    │ split   ：切成 client / server 两份程序表，按路由分块       │
│    ▼                                                          │
│  程序表：纯数据，节点 / 边 / 站点 / 模板 / 稳定 ID               │
└──────┬───────────────────────────────────┬────────────────────┘
       │ 嵌入 CLJS 产物                      │ 嵌入 CLJ 产物
┌──────▼────────────┐                ┌──────▼────────────┐
│ 客户端运行时       │   二进制 WS    │ 服务端运行时       │
│ · 表驱动求值器     │◄──────────────►│ · 表驱动求值器     │
│ · incseq/incmap    │  diff 流       │ · incseq/incmap    │
│ · DOM 挂载器       │                │ · 共享层           │
│ · hydration        │                │ · 会话隔离与预算   │
│ · 乐观投影（可选）  │                │ · 快照 / 续航      │
│ · 本地服务端（可选）│                │ · 数据源适配器     │
└───────────────────┘                └──────┬────────────┘
                                            │
                                     ┌──────▼────────────┐
                                     │ Datomic / Postgres │
                                     │ CDC / SQLite / 轮询 │
                                     └───────────────────┘
```

与 Electric 的结构性差别：产物是程序表而非闭包（§4）；SSR 与静态构建是一等公民（§7）；服务端有共享层、隔离与快照（§8）；编译器做安全检查（§11）。

---

## 3. 语言与语法

### 3.1 保留的核心

- 表达式级站点标注，编译器推断边界，未标注的代码在调用方的站点执行。
- 响应式函数是真正的函数：闭包、高阶函数、递归、动态绑定跨网络工作。
- 生命周期等于词法作用域（Missionary 的结构化并发）。

### 3.2 两级语言，减少"函数染色"的困惑

```clojure
(ns app.main (:require [curve.core :as r] [curve.dom :as d]))

;; 响应式函数。内部任何表达式都是响应式的。
(r/defn ProductRow [{:keys [id name price] :as p}]     ; 对 incmap 的解构 = 字段级订阅
  [:tr
   [:td.id id]
   [:td (d/input {:value name
                  :on-change (r/mutation #(db/update-field! id :name %))})]
   [:td (fmt-price price)]])                            ; 普通函数：参数变则重算

;; 普通函数：原样的 defn。
(defn fmt-price [x] (format "%.2f" x))
```

规则：**响应式代码里的普通函数调用就是"参数变则重算"**，不需要 `e/call` 之类的区分。只有需要延迟求值的响应式闭包才用 `r/fn`。

### 3.3 站点标注的三种写法

```clojure
(r/server (db/query ds))                                  ; 1. 块级

(let [^:server rows (db/query ds)                         ; 2. 绑定级元数据
      ^:client w   (.-innerWidth js/window)] ...)

(r/defn ^:server Products [] ...)                         ; 3. 函数级默认站点
```

三种只是语法糖，编译器做同样的推断。

### 3.4 Hiccup 模板，编译期静态提取（借鉴 Svelte / Solid）

`r/defn` 内直接写 hiccup，编译器把静态子树提取成 HTML 模板字符串，运行时 `cloneNode` 一次创建，只给动态"洞"布线。DOM 创建从 O(节点数) 降到 O(动态洞数)。

```clojure
[:div.card
 [:header [:h2 "商品表"] [:span "当前用户：" user]]
 (ProductTable user)]
;; => {:tpl "<div class=card><header><h2>商品表</h2><span>当前用户：<!></span></header><!></div>"
;;     :holes [...]}   ; 洞的定位与文本合并规则见 §17.2
```

### 3.5 少量但完整的控制与上下文形式

为了表达力，只增加五个形式，其余全是普通 Clojure：

| 形式 | 作用 | 说明 |
|---|---|---|
| `if / when / case / cond` | 条件 | 普通形式，响应式语义；分支切换 = 子图挂载/卸载 |
| `(r/for [p rows :by :id] ...)` | 带 key 的迭代 | `:by` 省略时按值；底层是 incseq |
| `(r/binding [*theme* :dark] ...)` | 动态作用域 | 相当于 React Context，但就是 Clojure 的 binding，可跨站点 |
| `(r/effect setup cleanup)` | 带清理的副作用 | 清理在作用域结束时自动执行 |
| `(r/flow <missionary-flow>)` | 逃生舱 | 把任意 Missionary flow 接入图；用户 API 其他地方不暴露 Missionary |

线程宏、`->>` 管道对 incseq 直接可用，因为算子都是普通函数。

### 3.6 事件与写操作：token + 可选乐观投影

```clojure
(d/button {:on-click (r/mutation
                       #(db/delete-product! user id)
                       {:optimistic #(dissoc-row % id)})}   ; 可选
  "删除")
```

每次事件产生一个 token，服务端完成后释放。`:optimistic` 是对客户端投影状态的纯函数（§9）。

### 3.7 Pending 与错误：三态与边界

每个响应式值处于 `value | pending | error` 之一，两个边界形式：

```clojure
(r/suspense [:p "加载中…"] (ProductTable user))
(r/boundary (fn [err retry] [:div.error (ex-message err) [:button {:on-click retry} "重试"]])
  (ProductTable user))
```

无边界时根处有默认处理，不会静默失败。

### 3.8 路由：URL 是响应式输入

```clojure
(r/defn App []
  (let [{:keys [page id]} (r/route)]          ; URL 解析后的响应式值
    (case page
      :products (Products)
      :product  (Product id)
      (NotFound))))

[:a {:href (r/href :product {:id 3})} "详情"]   ; SSR 输出真实链接，hydration 后变成客户端导航
(r/navigate! :products)
```

- 浏览器前进后退、SSR、客户端导航共用同一个响应式值；页面切换只替换子图。
- **放在 URL 里的状态天然在重连和部署后保留**，这是对快照（§8.3）的补充，也是 LiveView 的经验。
- 程序表按路由分块（§4.4），首屏只下载当前页面的子图。

### 3.9 外部 JS 组件挂载点

```clojure
(r/foreign chart-lib/mount {:data rows :on-select handler})
```

Curve 给外部组件一个 DOM 节点和响应式 props，props 变化调用其 update，作用域结束调用其 unmount。图表、富文本编辑器、地图都走这里。对应 LiveView 的 Hooks，但 props 是响应式的，不用手动同步。

---

## 4. 编译器与程序表

### 4.1 阶段

1. **Expand**：展开到内建形式；用 `cljs.analyzer` 的公开 API 解析 cljs 宏，**只在编译期用**，产物不含它。保留行列元数据生成 source map。
2. **Analyze**：构建数据流图，多遍处理：站点推断、副作用排序、DCE、别名重路由、局部内联、纯函数折叠、静态模板提取。
3. **Check**：污点分析与边界报告（§11.2、§11.3）。
4. **Split + Emit**：输出两份程序表，按路由分块。

增量编译：以 `r/defn` 为单位缓存分析结果，只重分析改动的函数及其依赖者；与热替换共用稳定 ID。

### 4.2 程序表：产物是数据，不是闭包

Electric 每个节点发射成闭包，产物随节点数线性膨胀（已核实）。Curve 的产物是一张表：

```clojure
{:nodes [[:lookup 0] [:call fmt-price [3]] [:site :server 4] [:key 0 :price] [:tpl 7 [1 ...]] ...]
 :ctors [...] :tpls ["<tr><td class=id><!></td>…"] :sites {...}}
```

运行时是一个小的**表驱动求值器**。只有用户函数（`fmt-price`、事件处理）保留为 JS 函数引用。代价是解释开销，阶段 0 用基准验证（待核实）。

编译期对表做两步分区（借鉴 React Compiler 的成本模型，§17.4）：依赖集相同的节点合并为一个 memo 单元；依赖每次都是新值或结果不逃逸的节点不做 memo。分析失败只把单个节点标为"不 memo"，不影响其余节点。

### 4.3 稳定节点 ID

节点 ID 由"命名空间 + 函数名 + 源码结构路径"的哈希派生。改一个函数，其他函数的 ID 不变，线上会话可局部热替换；两端源码一致即得到相同 ID。

### 4.4 代码分割

程序表按路由（§3.8）分块，每块附带它引用的模板和用户函数；跨块共享的节点放公共块。首屏只加载当前路由。

### 4.5 编译期产出的工具信息

编译器额外输出一个 `curve-info.edn`（不进 bundle）：每个表达式的站点、每个跨站点值的位置与类型、路由分块。编辑器插件和 clj-kondo 用它做站点着色和提示；安全审查用它做边界报告（§11.3）。

### 4.6 动态程序表：编译器作为运行时 API

程序表是数据，客户端是解释器，这意味着**代码可以在运行时产生**。这是编译成闭包的框架（包括 Electric）做不到的，也是笔记本（§16.4）、插件系统、低代码表单、用户自定义仪表盘这类"UI 在运行时生成"的应用的基础。

```clojure
;; 服务端，运行时
(curve.compiler/compile-form '(r/defn Viewer [x] [:pre (pr-str x)]) opts)   ; => 程序表片段
(curve.session/load! session table-fragment)                                  ; 推给该会话的客户端热加载
```

规则：
- 走 §10 局部热替换的同一条路：片段带稳定 ID，可以替换或追加节点，不影响已挂载的其他部分。
- 片段里的**叶子函数**（普通 `fn`）需要在客户端执行代码。两种方式：内建 viewer 函数白名单（零成本，默认）；或可选的 `curve.runtime.sci` 模块用 SCI 解释（约 300 KB gzip，只有选择它的构建才承担，待测）。
- 安全：动态片段经过与静态编译相同的站点推断、污点分析和 slot 授权；来自不可信来源的片段只能引用白名单函数，不能启用 SCI。
- 编译器本身只在服务端存在，客户端体积不变。

---

## 5. 增量集合：incseq v2

### 5.1 保留 Electric 的代数

有序序列 diff = `{grow, degree, shrink, permutation, change}`，置换用循环表示，diff 是半群可压缩。独立实现同样的代数，并用性质测试保证结合律与单位元（§14.3）。

### 5.2 三种增量类型

| 类型 | 语义 | diff 形态 | 用途 |
|---|---|---|---|
| `incseq` | 有序序列 | 同上 | 列表、表格行 |
| `incmap` | 键值映射 | `{:assoc {k v} :dissoc #{k}}`，值可嵌套 incmap | 一行记录、表单、协作文档的字段 |
| `incset` | 无序集合 | `{:add #{} :remove #{}}` | 不关心顺序时更便宜 |

一行记录作为 `incmap` 传给客户端后，解构 `{:keys [name]}` 就是字段级订阅：一次传输、字段级更新、不用逐字段标注站点。

### 5.3 小的增量关系算子子集

`filter`、`map`、`sort-by`（有序索引）、`take / drop`（窗口）、`group-by`（到 incmap of incseq）、可逆聚合（`count`、`sum`、`min`、`max`）、**时间分桶聚合**（`bucket-by` 时间字段 + 桶宽 → incmap of 聚合，用于可视化降采样，§16.3）。目的：服务端对一次写入的工作量是 O(变化量)。不做任意 join、递归和空间索引，交给数据库 + CDC 或 `Source` 实现。

### 5.4 非驻留流（借鉴 LiveView streams）

`(r/stream xs)`：服务端不保留已发送元素，只转发增量；客户端负责截断。用于日志、消息流等只追加的大集合，直接降低每连接内存。

---

## 6. 协议与通信

### 6.1 消息结构

沿用"四元组皆为 monoid"：`[acks requests changes freezes]`。编码层的变化：

| | Electric（已核实） | Curve |
|---|---|---|
| 编码 | transit 文本 | 二进制：varint slot id + 类型字节 + 值（transit-msgpack 或 CBOR，待测体积） |
| 帧 | 文本 websocket | 二进制 websocket，可选 permessage-deflate |
| slot 引用 | `[frame id]` 结构 | 单个 varint |
| 批处理 | socket 就绪即发 | 客户端按动画帧合并，服务端按 tick（默认 8 ms）合并 |
| 同构记录 | 每行重复键名 | **列式编码**：同形状记录首次发键表（shape id），之后只发位置值 |
| 数值序列 | 逐个数字编码 | **typed array**：`Float64Array` / `Int32Array` 作为一个二进制块传输，客户端零拷贝 |
| 高频值 | 无 | **每 slot 速率提示** `^{:rate 20}`：发送端按该频率取最新值，中间值丢弃（monoid 合并保证正确） |

### 6.2 乐观传输与背压

保留"一端预判另一端需求提前发送"和基于 ack 的会话拆除共识。写方向背压映射到 Missionary flow 背压；每会话发送队列有上限，超限时合并而不是丢弃。

### 6.3 更新优先级

求值器有三条通道：**用户输入 > 可见区域更新 > 离屏更新**。大列表的 diff 应用可以让位给正在输入的框。借鉴 React transitions / Solid `startTransition`，但因为求值器是自己的，不需要用户显式标注，可见性由挂载器提供。

### 6.4 传输可插拔

接口抽象为"有序可靠双向字节流"。默认 websocket，留出 WebTransport 与 SSE+POST 回退（阶段 3）。

---

## 7. SSR、静态构建与 Hydration

### 7.1 服务端渲染

程序表是数据，服务端可同时扮演两端：用**无头 DOM**（只实现挂载器需要的子集）运行客户端程序，与服务端程序进程内直连。输出 HTML + **状态快照**（客户端程序表每个节点的值与三态）。

### 7.2 流式输出

HTML 按 `suspense` 边界分块：边界外先 flush，边界内数据到达后再 flush 并带定位信息（React 18 方式）。

### 7.3 Hydration 与续航

客户端：加载程序表与快照 → 挂载器**认领**已有 DOM（key 由模板路径派生，不注入属性）→ 从快照恢复节点值，**不重跑任何服务端查询** → 建立 websocket，服务端从同一快照续航 → 之后只传 diff。

### 7.4 静态构建与懒连接

**静态构建**：构建时对每个路由做 SSR。编译器能判断一个页面渲染后是否仍有"活的"服务端节点（依赖数据源或会话的节点）：
- 没有 → 输出纯静态 HTML + 该路由的客户端子图，**不需要服务端**；客户端逻辑（折叠、搜索、主题切换）照常 hydrate。
- 有 → 输出 HTML + 快照，运行时需要服务端。

`(r/static expr)` 显式把一个服务端表达式标为构建期常量（如从 Markdown 读文档）。

**懒连接**：有服务端节点的页面，SSR 后也不立刻建 websocket，直到页面里首次出现需要服务端的交互或订阅。文档站、营销页、博客因此可以用 Curve，服务端连接数只来自真正交互的用户。

### 7.5 无 JS 可用的子集

SSR 输出里表单是真实 `<form>`、链接是真实 `<a>`，`r/mutation` 无 JS 时退化为 POST。

---

## 8. 服务端：共享、隔离、快照与数据源

### 8.1 共享层（对应 Meteor 的 ObserveMultiplexer）

```clojure
(r/shared [::products] (src/rows products-source))         ; 跨会话共享的响应式值
(r/shared [::products-by-user user-id] ...)                 ; 带参数自动分组
```

相同 key 在进程内只实例化一次，所有会话订阅；最后一个订阅者离开后延迟释放。每会话仍各自维护 diff 发送状态，但**计算和数据源订阅只有一份**。

### 8.2 会话隔离与预算

JVM 没有 BEAM 的进程隔离，Curve 用以下机制逼近：

| 机制 | 做法 |
|---|---|
| 执行隔离 | 每会话的求值在独立虚拟线程上，带超时与取消（Missionary 取消语义） |
| 预算 | 每会话节点数、发送字节/秒、累计 CPU 时间上限；超限先降级（合并、延迟），再熔断断开该会话 |
| 共享查询隔离 | `r/shared` 在单独执行器上跑，带超时；一个坏查询不会阻塞所有会话 |
| 内存估算 | JVM 难以按会话计量内存，用节点数 + 缓存 payload 字节数近似 |
| 指标 | 会话数、每会话节点数、diff 字节/秒、共享命中率、预算触发次数 |

Meteor 社区"300 连接/机"的经验说明必须从第一天就能量化。

### 8.3 会话快照与续航

会话状态 = 程序表版本 + 每节点值 + 各 slot 的 ack 位置，是数据，所以可以：断线后在内存保留（宽限期）或序列化到外部存储；重连时从最后 ack 续传，不重建、不重查。SSR 续航（§7.3）用同一机制。

### 8.4 滚动部署与版本

- 握手携带程序表哈希。相同 → 直接续航。
- 不同 → **部分续航**：稳定 ID（§4.3）相同的节点保留值，其余重新求值；路由状态在 URL 里不受影响。无法续航时优雅降级为整页重载。
- **排空**：节点下线进入 drain 模式，停止接受新连接，通知已连接客户端携快照迁移到其他节点。
- 粘性会话通过 cookie；多实例的变更源每实例只订阅一次，或经 Redis 等广播。

### 8.5 数据源协议与适配器

```clojure
(defprotocol Source (rows [this query] "返回 incseq / incmap，变化来自底层变更通知"))
```

| 适配器 | 机制 | 每次写入成本 |
|---|---|---|
| 轮询 + 版本号 | 写后重查、服务端 diff；经 §8.1 共享后只算一次 | O(N)，默认兜底 |
| SQLite update hook | `sqlite-jdbc` 更新监听，按表/行触发局部重查（接口待核实） | O(受影响行) |
| Datomic tx-report | 事务报告队列 | O(变化 datom) |
| Postgres 逻辑复制 | CDC（wal2json / pgoutput），多实例单消费者再广播 | O(变化行) |
| 外部事件（Redis / Kafka） | 应用自己发变更 | 由应用决定 |

### 8.6 每会话成本优化

有状态服务端每连接有三类开销：**求值**（数据变了重算节点）、**发送状态**（记住该客户端上次收到了什么，才能算 diff；Meteor MergeBox 的内存黑洞就在这里）、**编码**（每连接各序列化一次）。§8.1 的共享层只共享了求值。把后两类也共享掉，每连接成本才能接近 LiveView 的水平。以下八项按收益排序。

| # | 优化 | 做法 | 效果 | 阶段 |
|---|---|---|---|---|
| 1 | **共享值用 diff 日志 + 每会话游标** | `r/shared` 的值维护一条有界 diff 日志（环形缓冲，按各会话 ack 回收），每会话只存一个游标整数。游标落后超过缓冲长度的慢客户端发一次全量快照后重置 | 1000 个会话看同一张 1000 行表：1000 份副本 → 1 份 + 1000 个整数。断线续传就是从游标继续，复用 §8.3 | 0 |
| 2 | **编码一次，多路分发** | 共享值的 diff 在日志里就以线上格式（§6.1 二进制帧）存放，发送只是 `ByteBuffer` 切片写入各 socket。会话私有 slot 才逐会话编码 | 编码成本从 O(会话数) 降到 O(1)，效果等同 LiveView PubSub broadcast 但粒度到 slot | 0 |
| 3 | **脏位图 + 拓扑序求值器** | 程序表给节点稠密整数 ID，会话状态就是几个按 ID 索引的数组：值、脏位、入边。一次变化 = 置脏位 → 按拓扑序扫脏位重算 → 清位。不为每个节点分配 flow 对象和续体；Missionary 只用在边界（数据源、IO、取消） | GC 分配和缓存失效降一个量级；§8.2 的内存估算从近似变为精确（数组长度） | 0 |
| 4 | **每核一个工作线程，会话是任务** | 会话是可运行对象，变化到达入队；工作线程按 tick（§6.1）批处理一个会话的全部脏节点，一次编码一次发送。空闲会话零 CPU。阻塞调用（`r/offload`）才进虚拟线程池 | 避免每会话一线程的开销，tick 合并变得自然 | 0 |
| 5 | **跨会话查询合并** | 一个 tick 内多个会话触发同一 `Source` 的查询（参数可不同），按 DataLoader 方式合并为一次批量查询再分发。只在适配器层实现 | 每用户参数化查询的数据库往返 N → 1 | 2 |
| 6 | **空闲会话休眠** | 借鉴 Erlang `hibernate` 与 Blazor circuit 回收：无活动 N 秒后序列化状态数组（§8.3 快照）并释放，只留游标和程序表哈希；回来时从快照恢复 | 后台标签页占多数的应用，常驻内存减半以上 | 2 |
| 7 | **关闭或缩小 permessage-deflate** | 默认 deflate 上下文每连接约 300 KB，比会话状态本身还大。默认关闭，或窗口设为 8 KB；二进制 + 列式编码已足够紧凑 | 一行配置，但不写进文档就会被默认值吃掉以上收益 | 0 |
| 8 | **需求驱动 + 可见性降频** | 客户端未订阅的服务端节点不求值（保留 Electric 的需求驱动）；配合 §6.3 的可见性信息，离屏 `r/for` 行在服务端降频或暂停 | 大列表的服务端成本随可见行数而非总行数 | 2 |

1 和 3 决定会话的内存布局，必须在阶段 0 定下，事后改动代价很高；2、4、7 是它们的直接延伸。5、6、8 彼此独立，不影响应用代码，按需加入。

验证指标（进 §8.2 的指标集）：每会话常驻字节、每次变化的分配字节、共享 slot 的编码次数 / 发送次数之比（目标接近 1 / 会话数）。

---

## 9. 乐观更新（借鉴 Meteor、Replicache）

客户端维护一个**投影层**：对服务端下发状态的一组待确认纯函数变换。`r/mutation` 带 `:optimistic f` 时先应用 `f`，UI 立即响应；token 释放后变换移除，服务端 diff 成为权威；失败时撤销并走错误边界。多个未确认变换顺序叠加，没有 Meteor 式"回滚再重放"的闪动。默认关闭，可选模块。

---

## 10. 实时协作与 local-first

### 10.1 协作：共享层的直接应用

实时协作需要三样东西，Curve 都从共享层派生，不新增机制：

```clojure
;; 1. 共享可变状态：服务端权威，所有会话看到同一个响应式值
(r/shared-atom [::doc doc-id] initial)        ; 读：响应式；写：r/mutation 经服务端

;; 2. 在线状态：会话注册表派生的 incmap，断开自动移除（生命周期 = 作用域）
(r/presence [::doc doc-id] {:user user :cursor pos})   ; 返回 incmap: session-id -> data

;; 3. 广播：共享值的变化就是广播，不需要单独的 PubSub API
```

- 粒度：`incmap` 让多人同时编辑一条记录的不同字段互不覆盖；同一字段的冲突按"服务端最后写入"处理，这对表单、看板、仪表盘足够。
- 文本级协作（多人同时编辑一段文字）需要 CRDT。**Curve 不实现 CRDT**，但 `shared-atom` 的值可以是 CRDT 文档，更新作为二进制值传输，客户端用 Yjs / Automerge（经 `r/foreign`）合并。Curve 只负责传输和生命周期。

### 10.2 Local-first 友好度：能承载，不实现

**决定：local-first 是 Curve 之上的一层，Curve 不实现它，只保证不设障碍。** Curve 是服务端权威模型；程序表设计让 local-first 可以作为外部层叠加：

- **本地服务端站点**：程序表的"server 站点"只是"由哪个求值器执行"。应用可以选择把 server 站点编译进客户端，在 Web Worker 里对本地数据源（SQLite-wasm、IndexedDB）求值，两端进程内直连。此时网络消失，Curve 变成一个纯客户端的响应式框架。
- **数据同步交给外部引擎**：本地数据源与远端的同步（ElectricSQL、PowerSync、Replicache 等）不是 Curve 的工作；它们只需实现 `Source` 协议，变化就流进 UI。
- **边界清楚**：投影层（§9）只覆盖短暂的未确认窗口，不会长成同步引擎。

这是对复杂度预算的遵守：local-first 的难点（冲突、离线队列、schema 迁移）留给专门的工具，Curve 提供的是"本地站点"这一个开关。只有选择它的应用才承担 server 站点进入 bundle 的体积。

---

## 11. 安全模型

表达式级边界带来新的攻击面：编译器决定什么跨网络，应用作者不一定看得见。Curve 的回答是**让边界可见、可审计，并让服务端对请求有最终裁决权**。

### 11.1 威胁模型

| 威胁 | 说明 |
|---|---|
| 越权订阅 | 客户端伪造请求，订阅一个 UI 上没有、但服务端能算的 slot |
| 伪造参数 | `r/mutation` 的参数被篡改 |
| 敏感泄漏 | 一个服务端值无意中被站到客户端 |
| 反序列化 | 恶意 payload 触发任意类型构造 |
| 资源耗尽 | 请求代价高的节点，或高频发送 |
| 传统 Web | CSRF、origin、cookie |

### 11.2 机制

1. **服务端权威的 slot 授权**：服务端运行同一张程序表，它知道当前会话**合法挂载**了哪些节点。对未挂载节点的请求一律拒绝。这是协议层的规则，不是应用层的检查，而且零额外成本，因为服务端本来就在维护这张图。
2. **污点分析**：敏感值在编译期沿图传播；任何把它站到 client 的路径都是**编译错误**，附源码位置。图是显式的，所以这个分析很便宜。粒度规则见 §11.4。
3. **边界 schema**：跨站点的值和 `r/mutation` 的参数可以用 malli 声明；服务端在入口校验，不合法直接拒绝。可选但推荐，`curve-info.edn` 列出未声明 schema 的边界。
4. **反序列化白名单**：只接受协议定义的基础类型和 incseq/incmap diff；不接受任意 record、类、符号求值；payload 有大小上限。
5. **预算与限流**：复用 §8.2 的每会话预算；请求节点的代价由其子图大小估算，超预算拒绝。
6. **传统防护**：websocket 握手检查 Origin，携带一次性 token；cookie `HttpOnly` + `SameSite`；无 JS 表单 POST 带 CSRF token。
7. **授权上下文**：`r/binding` 的当前用户在服务端从会话绑定，客户端不可写；文档和模板统一用这一种方式传递身份。

### 11.3 边界报告

编译器输出"每一个跨网络的值"的清单：源码位置、方向、类型、是否有 schema、是否经过 `^:secret` 附近。它是 `curve-info.edn` 的一部分，可以进 PR 审查、可以 diff。安全审查从"读全部代码"变成"读这份清单"。零运行时成本。

### 11.4 污点分析的粒度

**决定：以值级污染为底线，对字面量键的 map 做键级追踪；追踪不了时退回整体污染。** 方向是宁可误报，不可漏报。

污点来源（三种，可混用）：

```clojure
(def ^:secret api-key ...)                                   ; 1. 变量标注
(let [^:secret token (sign session)] ...)                    ; 2. 绑定标注
(def User [:map [:name :string] [:password_hash {:secret true} :string]])   ; 3. schema 标注（推荐）
```

第三种最重要：数据源的行 schema 一次声明，所有从该 `Source` 读出的 incmap 自动带键级污点，应用代码里不需要再标注。

传播规则：

| 表达式 | 结果 |
|---|---|
| `(:password_hash user)`、`(get user :password_hash)`（字面量键） | 取出的值污染 |
| `(:name user)`（字面量键，非敏感） | 干净 |
| `(select-keys user [:name :id])`、`(dissoc user :password_hash)`（字面量键） | 得到的 map 按剩余键重新计算污点 |
| `(assoc m :k secret-val)` | map 在键 `:k` 上污染 |
| `(get user k)`、`(vals user)`、`(into {} ...)`、经过任何未知函数 | **整体污染**（保守） |
| 污染值参与的任何运算（字符串拼接、比较结果、集合元素） | 污染 |
| 纯函数折叠（§4）的常量 | 按输入计算 |

解除：`(r/declassify expr "原因")` 是唯一的解除方式，必须写原因字符串，并出现在边界报告（§11.3）里供审查。不提供静默解除。

不做的事：不跨 `Source` 协议追踪数据库查询本身（SQL 字符串里的列名不分析），所以 schema 标注是数据源一侧的唯一入口；不追踪嵌套超过两层的 map 键（第三层起整体污染），避免分析代价失控。

---

## 12. 运行时体积与模块

### 12.1 已核实的问题与规则

| Electric 现状 | Curve 规则 |
|---|---|
| 核心命名空间内联 `rcf` 的 `tests`，连带 `cljs.analyzer` | 运行时命名空间**禁止**引用测试库；测试全部在 `test/` |
| `runtime3` 直接 `require` `clojure.pprint` | 运行时不引用 pprint；调试输出在 `curve.dev`，受 `goog.DEBUG` 控制，生产构建 DCE |
| 编译器与运行时同一 jar | 分成 `curve.compiler`（clj）与 `curve.runtime`（cljc）两个 artifact |

### 12.2 模块与预算（gzip）

| 模块 | 内容 | 预算 | 性质 |
|---|---|---|---|
| `curve.runtime.core` | 求值器、incseq/incmap、协议、三态 | 25 KB | 核心 |
| `curve.runtime.dom` | `Mount` 协议 + DOM 实现、模板克隆、事件、hydration | 12 KB | 核心 |
| `curve.runtime.router` | 路由 | 3 KB | 核心 |
| `curve.runtime.optimistic` | 投影层 | 3 KB | 可选 |
| `curve.runtime.local` | 本地服务端站点（Worker 桥接） | 4 KB | 可选 |
| `curve.runtime.foreign` | 外部组件挂载（含 diff 感知） | 1.5 KB | 可选 |
| `curve.runtime.virtual` | `r/window` 超量预取、`r/for :recycle` 行复用 | 3 KB | 可选 |
| `curve.runtime.canvas` | `Mount` 协议的 canvas 场景图实现 | 3 KB | 可选 |
| `curve.runtime.dynamic` | 动态程序表片段加载 | 1 KB | 可选 |
| `curve.runtime.sci` | SCI 解释叶子函数（笔记本、插件） | ~300 KB（待测） | 可选 |
| `curve.dev` | 检视、回放、追踪 | 0（生产 DCE） | dev |
| `curve.test` | 无头两端、虚拟时钟、断言 | 0（不进 bundle） | test |

协作（§10.1）、共享层、快照、SSR 都在服务端，不占客户端体积。CI 对 Hello World 和 TodoMVC 做生产构建并记录体积，超预算即失败。

---

## 13. 可调试性

全部在 `curve.dev`，生产构建零成本；大多数能力是程序表和消息日志的直接读取。

| 能力 | 做法 | 回答的问题 |
|---|---|---|
| 实时图检视 | `(curve.dev/inspect session)` 返回图的数据视图，Portal / Reveal 可浏览；每节点显示值、三态、更新时间、最近 diff 大小 | 现在的状态是什么 |
| "为什么更新" | 每次传播记录触发链，`(curve.dev/why node)` 给出上游变化路径 | 这个值为什么变了 |
| 时间旅行 | 协议消息是 monoid 日志，开发模式记录后可回放到任意位置 | 刚才发生了什么 |
| 跨站点栈 | 客户端错误携带触发它的服务端 slot，用两端 source map 拼成一条栈 | 错在哪一端的哪一行 |
| 线路检视 | 每 slot 的字节数和频率统计 | 什么在占带宽 |
| 卡住检测 | pending 超过阈值且没有 `suspense` 边界时警告，指出节点位置 | 为什么一直在加载 |
| 站点着色 | 编辑器读 `curve-info.edn`，服务端表达式与客户端表达式不同底色 | 这段代码在哪跑 |
| 编译期诊断 | 站点冲突、不可序列化值跨站点、普通 `fn` 内误用响应式形式、`^:secret` 泄漏，都带源码位置和修复建议 | 为什么编译不过 |

---

## 14. 可测试性

全部在 `curve.test`，不进 bundle；核心是复用 SSR 的无头 DOM 和进程内直连。

### 14.1 单 JVM 双端测试

```clojure
(deftest edit-cell
  (with-curve [app (curve.test/mount App {:source (curve.test/mem-source products)})]
    (is (= "苹果" (curve.test/text app "tr:first-child td:nth-child(2) input")))
    (curve.test/input! app "tr:first-child input" "红苹果")
    (curve.test/flush! app)                                    ; 虚拟时钟推进到稳定
    (is (= "红苹果" (-> products deref first :name)))))
```

- 两端在同一进程直连，不走网络，但经过完整的协议编码/解码路径。
- `mem-source`：内存数据源，实现 `Source` 协议。
- 虚拟时钟：`sleep`、防抖、超时都确定性可测。

### 14.2 网络断言：Curve 独有

```clojure
(curve.test/with-wire [w app]
  (curve.test/input! app "..." "x")
  (is (<= (curve.test/bytes-sent w) 40))                     ; 这次交互传了多少
  (is (= #{::name} (curve.test/slots-changed w))))           ; 传了哪些 slot
```

因为边界由编译器决定，**"这个改动是否意外增加了传输"**是真实的回归风险。把它变成可断言的指标。

### 14.3 代数与模拟

- incseq / incmap / incset 的 diff 代数用 test.check 做性质测试：结合律、单位元、`combine` 与顺序应用等价。
- 网络模拟：延迟、断线重连、乱序不会发生（有序流）但可测丢连接后的续航。
- 确定性调度：求值器的调度用种子控制，失败可重现。

### 14.4 从日志生成测试

开发模式的消息日志（§13）可导出为测试夹具：回放一段真实会话并断言最终状态。生产问题可以变成回归测试。

---

## 15. 与各框架的对照

| 能力 | React 19 | Svelte 5 | Solid | Meteor | LiveView | Electric v3 | Curve |
|---|---|---|---|---|---|---|---|
| 边界划分 | 模块级显式 | 文件级显式 | 无 | 文件级显式 | 全服务端 | 表达式级推断 | 表达式级推断 + 元数据糖 |
| 客户端响应式 | 重渲染 + 编译器 memo | 细粒度 | 细粒度 | Tracker | 无 | 语言级 | 语言级 + 程序表 |
| 模板静态提取 | 无 | 有 | 有 | 无 | 有 | 无 | 有 |
| 跨网络 diff | 无 | 无 | 无 | 文档级 | 模板级 | 数据流级 | 数据流级 + 字段级 |
| SSR / 流式 / 静态 | 有 / 有 / 有 | 有 / 有 / 有 | 有 / 有 / 有 | 部分 | 原生 / — / 无 | 无 | 有 / 有 / 有 + 懒连接 |
| 富交互无逃生舱 | 是 | 是 | 是 | 是 | 否（Hooks） | 是 | 是 |
| 乐观更新 | useOptimistic | 手动 | 手动 | 内建 | 手动 | 无 | 可选内建 |
| 会话恢复 | 无状态 | 无状态 | 无状态 | 2026 加入 | 重 mount | 重建 | 快照续航 + 部分续航 |
| 跨会话共享 / 协作 | — | — | — | Multiplexer | PubSub + Presence | 无 | 共享层 + presence |
| 边界安全审计 | 无 | 无 | — | 无 | — | 无 | 污点分析 + 边界报告 |
| 网络断言测试 | — | — | — | 无 | 无 | 无 | 有 |
| 许可证 | MIT | MIT | MIT | MIT | MIT | BSL | MIT |

---

## 16. 高难度场景评估与针对性优化

用三个对框架压力最大的场景和一个"UI 在运行时生成"的场景检验设计。每个场景先列已有机制如何覆盖，再列缺口和补充，最后与其他框架对比"优化的难易"。补充项汇总在 §16.5，体积影响已计入 §12.2。

### 16.1 超大表格（10 万到百万行，可编辑）

**已有机制的覆盖**

| 需求 | 机制 |
|---|---|
| 只传可见行 | 服务端 `sort-by` 索引 + `take / drop` 窗口（§5.3），O(log N) 维护 |
| 多人同一排序 | `r/shared [::products-sorted :price]`，索引跨会话一份 |
| 编辑单元格 | `r/mutation` + `incmap` 字段级 diff，≤ 20 字节 |
| 行创建开销 | 模板克隆，只给洞布线 |
| 滚动时输入不卡 | 优先级通道（§6.3） |

**缺口与补充**

1. **滚动的往返延迟**：窗口在服务端切，每次滚动要等一个往返。补充 `r/window`（可选模块）：

   ```clojure
   (r/window rows {:size 50 :margin 100 :row-height 32})
   ;; 服务端发 [窗口 + 上下各 100 行余量]；余量内的滚动完全本地；
   ;; 接近余量边缘时提前移动服务端窗口（预取），用户感知不到往返
   ```

   窗口移动 k 行的 diff 是 shrink k + grow k，置换代数处理成本 O(k)。

2. **行编码**：1000 行同构记录用 map 编码每行重复键名。补充协议的**列式编码**（§6.1）：同形状记录首次发键表，之后只发位置值；数值列作为 typed array。预期 1000 行 × 5 列的窗口从约 60 KB 降到约 15 KB（待测）。

3. **DOM 行复用**：`(r/for [p rows :by :id :recycle true] ...)`，窗口移动时回收被卸载的行 DOM 改内容，而不是销毁重建。

**与其他框架对比**

| | 做法 | 难点 |
|---|---|---|
| React + TanStack Table/Virtual | 全客户端虚拟化；服务端分页 API 手写；编辑后缓存失效手写 | 服务端推送、多人同步、排序索引都要自己搭 |
| AG Grid 服务端行模型 | 成熟，但是闭源商业组件，数据层 API 手写 | 框架外解决 |
| LiveView streams | 服务端拥有滚动状态，每次滚动一个往返且无余量 | 延迟明显；编辑需要 `stream_insert` 手动维护 |
| Electric v3 | 有 `VirtualScroll`（源码已核实），窗口在服务端，思路相同 | 无列式编码、无余量预取、无行复用 |
| **Curve** | 窗口 + 余量 + 列式编码 + 共享索引 + 字段级编辑，全部声明式 | 需要验证百万行时服务端排序索引的内存 |

**Curve 更容易优化的原因**：数据路径从索引到窗口到 DOM 全在一个响应式图里，每一段都可以独立换更快的实现（索引结构、编码、挂载策略）而不改应用代码。React 方案里这几段分属不同库，优化要跨库协调。

### 16.2 Canvas 实时协作（白板、设计工具）

**已有机制的覆盖**

| 需求 | 机制 |
|---|---|
| 拖拽零延迟 | 拖拽状态 `^:client`，指针移动完全本地，只有释放时 commit 走服务端 |
| 多人光标与在线 | `r/presence`（§10.1），断开自动移除 |
| 对象并发修改 | `shared-atom` of `incmap`（每对象一个 map），**按属性 last-writer-wins**。这正是 Figma 公开描述的多人模型（属性级 LWW，非 CRDT；待核实），对白板和设计工具足够 |
| 提交等待期 | 乐观投影（§9），本地立即生效 |
| 形状内文本 | 外部 CRDT（Yjs / Automerge）经 `r/foreign`，Curve 只传二进制更新 |

**缺口与补充**

1. **渲染目标不是 DOM**。目前挂载器有 DOM 和无头 DOM 两个实现，说明它已经是一个隐含协议。补充：把 `Mount` 协议**显式化**（`insert` / `remove` / `move` / `set-prop` / `set-text`），核心体积不变；`curve.runtime.canvas` 作为可选模块提供保留模式场景图实现，同一份 `r/for` 和 `incmap` diff 直接驱动 canvas 重绘。应用用 hiccup 写 `[:rect {:x x :y y}]`，切换 DOM/SVG/canvas 只换挂载器。

2. **高频广播**：N 个用户 60 Hz 光标 = N² 消息/秒。tick 合并已让每 8 ms 只发最新值；补充**每 slot 速率提示**（§6.1）：`^{:rate 20} cursor`，发送端按 20 Hz 取最新值。带宽从每用户每秒 60 × N 降到 20 × N，且不影响正确性。

3. **大量对象的视口裁剪**：万级对象，客户端按视口 `filter` 即可（对象元数据小，一次传完后只收 diff）。十万级以上需要服务端空间索引，由 `Source` 的 bbox 查询提供，不进核心。

4. **撤销/重做**：应用层用 `shared-atom` 的历史栈实现；Curve 的消息日志（§13）可作为开发期的回放工具，不作为撤销机制。

**与其他框架对比**

| | 做法 | 难点 |
|---|---|---|
| React + Yjs + Konva/PixiJS | 三套状态（React、Yjs 文档、场景图）手动同步 | 同步代码占项目大头；presence、乐观、冲突都手写 |
| LiveView | 不适合：每次指针移动一个往返 | 必须把整个 canvas 交给 JS Hook，等于放弃 LiveView |
| Meteor | Minimongo 文档级 LWW + 方法桩，思路相近 | 文档级而非属性级；无 presence 内建；Mongo 绑定 |
| Electric v3 | 站点模型相同，拖拽本地化同样可行 | 无 presence、无共享层、无 canvas 挂载 |
| **Curve** | 一套状态（共享 incmap），拖拽本地，LWW 内建，canvas 是挂载器的一个实现 | 文本协作仍需外部 CRDT；速率与裁剪需要应用标注 |

**Curve 更容易优化的原因**：表达式级站点让"哪些计算留在本地、哪些同步"是逐表达式的决定，拖拽、吸附、对齐线这类高频计算全部留在客户端不需要任何架构改动；而共享层让同步部分只剩"声明这个值是共享的"。

### 16.3 数据可视化（仪表盘、流式图表、大序列）

**已有机制的覆盖**

| 需求 | 机制 |
|---|---|
| 时间序列流 | `r/stream` 非驻留（§5.4），服务端不保留已发点 |
| 多个仪表盘同一查询 | 共享层，一次计算全员订阅 |
| 交互（hover、刷选、缩放） | 客户端站点，无往返；缩放级别作为服务端查询的响应式参数 |
| SVG 图表 | hiccup 写 SVG，静态提取同样有效；几千个元素内 `r/for :by` 完全响应式 |
| 多图表页面不卡 | 优先级通道，离屏图表让位 |

**缺口与补充**

1. **图表库要数组不要 diff**。`r/foreign` 给 ECharts 整个数组会导致每个 tick 全量 `setOption`。补充**diff 感知的 foreign**：

   ```clojure
   (r/foreign echarts/mount {:series rows}
     {:on-diff (fn [chart diff] (.appendData chart ...))})   ; 有增量 API 的库走增量
   ```

   没有增量 API 的库，`r/as-vec` 物化时保持**结构共享**：未变化的数组保持同一引用，图表库的浅比较直接跳过。

2. **降采样**：10 万点不该全传。补充 §5.3 的**时间分桶聚合** `bucket-by`：缩放级别 → 桶宽 → 每桶 count/sum/min/max，增量维护（新点只更新一个桶），跨会话共享。客户端收到的点数与像素宽度同量级。

3. **数值编码**：与表格共用 typed array 编码。10 万个 double 作为一个 800 KB 二进制块，不经过 JSON/transit。

4. **大规模渲染**：超过几千个点用 canvas/WebGL，经 `curve.runtime.canvas` 或 `r/foreign`（uPlot、regl）。文档给出阈值建议。

**与其他框架对比**

| | 做法 | 难点 |
|---|---|---|
| React + Recharts | SVG + 重渲染，几千点就卡；流式数据靠轮询或手写 WS | 数据管道和降采样全在框架外 |
| Svelte + d3 / Observable Plot | 客户端渲染很好 | 服务端推送、共享聚合、降采样仍手写 |
| LiveView + Hooks | `push_event` 把数据推给 JS 图表 | 每个图表一个 Hook，数据形状手动约定；无增量 |
| Grafana 类 | 成熟但是产品不是框架 | — |
| **Curve** | 从聚合到编码到图表增量 API 一条响应式管道 | 图表库适配器要逐个写（社区工作） |

**Curve 更容易优化的原因**：降采样、共享、编码三件事都发生在数据流图里，改一个桶宽就改变了整个管道的成本，而且对所有会话生效；其他框架里这是三个不同层的三次改动。

### 16.4 计算笔记本（Clerk 类）

**参照**：Clerk（clerk.vision，Nextjournal）。笔记本是普通 `.clj` 文件，在 JVM 上求值，按表单哈希与依赖缓存、只重算改动部分；求值结果转成 presentation 树发给浏览器，大数据结构做省略、展开时按需拉取；浏览器用 React 渲染，自定义 viewer 的 `:render-fn` 由 SCI 解释执行；`::clerk/sync` atom 双向同步；可构建为静态 HTML。（基于截至 2026 年中的了解。）

这个场景和前三个不同：它不是性能压力，而是**UI 在运行时生成**。用它检验 Curve 是否只能服务提前编译好的应用。

**已有机制的覆盖**

| 需求 | 机制 |
|---|---|
| 服务端求值、结果流到浏览器 | 服务端站点 + 协议，Curve 的主路径 |
| 只重算改动的单元格 | 稳定节点 ID + 局部热替换（§4.3、§10），同一个机制 |
| 大结果的省略与按需展开 | `r/window`、incseq 按需订阅；展开 = 挂载子图并请求 slot，比手写 fetch 更自然 |
| `::clerk/sync` | `shared-atom` |
| 多人看同一个笔记本 | 共享层 + presence，内建 |
| 静态发布 | 静态构建（§7.4），客户端交互照常 hydrate |
| 图表、表格 viewer | `r/foreign`、`virtual`、canvas 模块 |
| 单 JVM、REPL 驱动 | Curve 的开发模式本就如此；笔记本是开发场景，约束变成优势 |

**缺口与补充**

1. **运行时生成的 viewer**：用户在单元格里写的 hiccup 和 render 函数不是提前编译的。补充 §4.6 动态程序表：服务端在运行时把单元格编译成程序表片段推给客户端热加载；叶子函数走白名单或可选的 SCI 模块。这是本场景唯一需要的新能力，且是通用的。
2. **边界**：Curve 不是求值层。单元格求值仍是普通 Clojure `eval`，表单依赖分析和结果缓存由笔记本自己做（Clerk 用 tools.analyzer）；Curve 只负责呈现与传输。不把笔记本逻辑塞进响应式图。

**与其他方案对比**

| | 做法 | 难点 |
|---|---|---|
| Clerk（React + SCI） | 成熟；presentation 树 + 手写省略与 fetch | 客户端带 React 与 SCI，体积大；协作与增量传输基础 |
| Jupyter（JSON 消息 + 前端 widgets） | 输出是 MIME bundle，整块替换 | 无增量、无细粒度更新；widgets 另一套状态同步 |
| Observable（浏览器内响应式） | 求值在浏览器，天然响应式 | 服务端计算要另搭；不是 JVM |
| Electric v3 | 站点模型相同 | 闭包产物无法运行时生成，只能同样塞 SCI |
| **Curve** | 程序表运行时生成 + 增量传输 + 共享层 + 静态构建 | 叶子函数仍需 SCI 或白名单；Curve 尚不存在，今天做笔记本应选 Clerk |

**Curve 更适合的原因**：笔记本命中了几乎每一项已有机制，唯一的补充（动态程序表）来自程序表是数据这一基本设计，不是为笔记本单独加的。

### 16.5 补充项汇总

| 补充项 | 位置 | 服务的场景 | 体积 |
|---|---|---|---|
| 动态程序表（编译器作为运行时 API） | §4.6，服务端 + 客户端加载 | 笔记本、插件、低代码 | 客户端 +1 KB；可选 SCI 模块另计 |
| 列式编码 + typed array | §6.1，核心协议 | 表格、可视化 | 核心 +2 KB |
| 每 slot 速率提示 | §6.1，核心协议 | 协作、可视化 | 核心 +0.3 KB |
| `Mount` 协议显式化；canvas 实现 | §12.2 | 协作、可视化 | 核心 0；可选 +3 KB |
| `r/window` 余量预取、`r/for :recycle` | §12.2 `curve.runtime.virtual` | 表格 | 可选 +3 KB |
| diff 感知 foreign、结构共享 `as-vec` | §12.2 `curve.runtime.foreign` | 可视化 | 可选 +0.5 KB |
| `bucket-by` 时间分桶聚合 | §5.3，服务端 | 可视化 | 客户端 0 |

核心运行时预算从 40 KB 调整为 43.3 KB；三个性能场景全开时客户端最多再加约 7 KB；笔记本场景若启用 SCI 另加约 300 KB（待测）。

**共同的结论**：前三个场景的瓶颈分别在传输（表格）、频率（协作）、数据量（可视化），Curve 的应对都落在数据流图里的某一段，可以单独替换而不改应用代码。第四个场景（笔记本）检验的是"UI 在运行时生成"，程序表是数据这一设计让它几乎免费。这是"一条响应式管道"相对"多个库拼接"在可优化性上的实际差别。代价是每个场景都需要一两处显式标注（`:rate`、`:recycle`、`bucket-by`），Curve 不替用户猜。

---

## 17. 编译器与运行时优化：调研 Svelte 5、React Compiler、Octane 后的补充

> 2026-10-03 调研。Svelte 部分来自 sveltejs/svelte 源码（`compiler/phases/3-transform`、`internal/client`）；React Compiler 部分来自 facebook/react `compiler/` 目录的设计文档与 Pipeline 源码；Octane 部分来自 octanejs.dev 文档与仓库源码。来源列表见文末。
>
> Octane 说明：不是 Ember Octane。它是 Dominic Gannaway（Inferno 作者、前 React 核心、Svelte 5 贡献者）于 2026 年 6 月开源的新框架，定位"React 的编程模型，编译掉 VDOM"，0.7 beta，MIT。其基准全部为自报，未进入 krausest js-framework-benchmark；本节只借鉴其设计，不引用其性能数字。

### 17.1 调研确认 Curve 已有的方向

| 技术 | 谁在用 | Curve 对应 |
|---|---|---|
| 静态 HTML 作为数据字符串，`cloneNode` 一次 | Svelte 5 `from_html`、Octane template IR（源码注释："静态模板是运行时数据，不是 JS 语法"） | §3.4 |
| 编译到运行时求值，不追求"编译掉一切" | Rich Harris："我们从编译期响应式稍微退回运行时风格……静态分析能做的有限" | §4.2 程序表 |
| 微任务批处理、渲染到底、不做时间切片 | Svelte 5、Octane；React 的 lanes 切片是少数派 | §6.1；§6.3 的优先级只在 tick 之间生效 |
| 延迟 hydration 边界同时是代码分割点 | Octane `<Hydrate when={visible|idle|interaction}>` | §4.4 + §7.4 合并为同一个边界原语 |
| 真实 DOM 事件委托 | Svelte 5（根监听 + `node[sym][type]`）、Octane（指出 React 合成事件占 13 KB） | 补入 §12.2 dom 模块 |

### 17.2 模板与挂载（来自 Svelte 5 源码）

替换 §3.4 中"用路径定位洞"的笼统写法，程序表的模板条目定义为：

```clojure
{:tpl   "<tr><td class=id> </td><td><input></td><td> </td></tr>"   ; 按 hash 去重；单根 / 多根分开标记
 :holes [[:child]            ; 相对上一个洞的步骤序列：child | sibling n
         [:sibling 1 :child] ; 不存节点引用，运行时用缓存的 firstChild/nextSibling getter 走一遍
         [:sibling 1]]
 :text  [[0 "用户：" 1]]}    ; 相邻文本与表达式合并成一个文本节点、一次更新
```

规则：
1. **洞 = 步骤序列**。运行时对每个克隆只做一次线性遍历，无每节点变量。
2. **块实例只记 `{start end}`**。`if` / `for` 的每个分支或项记录自己 DOM 范围的首尾两个节点，移动与移除是范围遍历。
3. **唯一动态子块用父元素做锚点**，不插注释节点；其他动态块在模板里放空注释 `<!>` 做锚点。
4. **合并文本**：`"用户：" user` 编译为一个文本节点和一条"拼整个字符串"的表行；纯静态文本元素直接 `textContent`，不下钻。
5. **SSR 块标记**：服务端在每个块两侧输出 `<!--[-->` / `<!--]-->`（else 分支 `[!`）。hydration 时分支或长度不匹配，只跳过该范围重渲染，不是整页失败。
6. **事件委托**：每种事件类型在根上一个监听器，handler 存在节点的 symbol 属性下，由程序表的 handler 索引查找；不可委托的事件（`focus`、`scroll` 等）单独 `addEventListener`。
7. **keyed `for` 的 reconcile 用贪心单遍**：匹配项和错位项分两组，移动较小的那组；不用 LIS。新项在离屏 fragment 中创建后一次插入。

### 17.3 调度（来自 Svelte 5 运行时）

叠加在 §3.7 三态传播之上：

- **版本号脏检查**：每个 source 带写版本 `wv`，每个节点记录上次求值时的版本。上游变化先标 `maybe-dirty`，求值前比较 `dep.wv > node.wv`，没有更新的依赖就直接标 `clean`，不重算也不比较值。
- **依赖按读取顺序去重**：节点重算时若依赖读取顺序与上次相同，只递增计数不重新分配数组。
- **派生值惰性 + 断连**：没有订阅者的派生节点断开上游连接，再次被读时重连。与结构化并发一致。

### 17.4 编译分析（来自 React Compiler）

**不可变数据让最复杂的部分整套消失。** React Compiler 的 mutable range、aliasing effects（`Assign` / `Alias` / `Capture` / `CreateFrom` / `Mutate*`）、Freeze-on-escape、"作用域延伸到最后一次 mutation"都只因为值创建后还能变。Curve 的节点值创建即最终，memo 边界可以落在任意节点，作用域范围平凡。

**直接借鉴的：**

1. **memo 成本模型**（`mergeReactiveScopesThatInvalidateTogether`、`pruneAlwaysInvalidatingScopes`）：memo 检查不免费。编译期把依赖集相同的节点合并成一个 memo 单元；依赖本身每次都是新值（如 map 字面量）的节点不做 memo；结果不逃逸（不参与渲染、不跨站点）的节点整个剪掉。这是程序表的一个分区步骤。
2. **按节点 bail out**：React 一个函数里有违规就整个放弃优化。程序表可以只把那一个节点标为"不 memo，照常解释"，其余保持优化。严格更好的降级方式。
3. **效果签名表**：core 库函数（`map`、`assoc`、`swap!`、`reset!`）有签名；本地 `fn` 从函数体推断；未知调用按"不纯、可能修改参数"处理；`js/` 对象、DOM 节点、外部库返回值一律视为可变可别名，只在 interop 边界用悲观假设，其余地方分析保持便宜。
4. **捕获了可变引用的闭包**：一个捕获了 atom 或 JS 对象的 `fn` 身份稳定但行为不稳定，不能按闭包身份 memo；为每个闭包记录捕获的可变引用。
5. **依赖比较的选择**：React 用 `Object.is`；Curve 对持久化结构默认用身份比较（结构共享下安全且最便宜），只对已知会重建相等值的生产者用值相等。按边配置，不全局选一种。
6. **分析结果复用成诊断**：React 1.0 后最大的收益是把编译分析变成 `eslint-plugin-react-hooks` 的规则。Curve 的站点推断、污点分析、效果签名都应作为编辑器诊断输出（经 `curve-info.edn`），而不只是编译错误。
7. **Flight 风格 wire format**：RSC 的 payload 是 id 索引的行流，`$` 前缀引用（`$n` 引用行、`$@` promise、`$L` 懒加载），同一机制解决去重、循环引用、乱序流式。Curve 的 slot id 天然是行 id；§6.1 的列式编码采用这个形式：shape 表作为一行，记录引用它。

**仍然存在的：** `swap!` / `reset!` / I/O 在节点体内是 `Impure` 效果，必须在事件或 effect 语义下执行，不能在纯节点里。编译器对此报错，与 React 的 `set-state-in-render` 规则同类。

**校准预期：** React Compiler 的实测收益是初始加载最多 12%、多数应用 3–5%、少数交互 2.5 倍。自动 memo 的上限就这么高；真正的收益来自细粒度响应式本身。Curve 不应把编译期 memo 当作主要性能来源。

### 17.5 Octane 的两个可借鉴点

1. **`for` 作为独立 opcode，每项 slot 自有状态**：选中态变化只触碰两行（Octane 称"编译器可证明的 O(1) 失效"）。Curve 的 `r/for :by` 本来就是 incseq 驱动，把"每项的局部状态"作为程序表中 `for` 节点的子槽位明确下来，可以保证同样的性质，并让 §16.1 的 `:recycle` 有落点。
2. **行为根**（`attachBehaviorRoot`）：给服务端 HTML 只挂事件、不接管 DOM。Curve 的静态站点（§7.4）可以多一个档位：纯静态页面 → 行为根（只有事件和少量客户端状态）→ 完整 hydration → 懒连接。每档的客户端成本递增。

### 17.6 性能基线进 CI（Octane、Svelte 的实践）

- 性能回归用**比值**（相对 vanilla JS 或上一版本）而不是绝对毫秒做 CI 断言，避免机器差异。
- 体积基线已有（§12.2）；增加 create-1k / update-10th / select / swap / clear 的 js-framework-benchmark 标准操作，加上 Curve 特有的"1000 行窗口滚动字节数"和"单字段更新字节数"。
- Svelte 4 → 5 的真实应用数据（应用代码 154 KB → 74 KB）说明模板与运行时的设计改动能带来一半的体积差；Curve 阶段 0 的体积基准应以这个量级为目标，而不是百分之几。

### 17.7 对现有章节的修订

| 章节 | 修订 |
|---|---|
| §3.4 | 模板条目定义改为 §17.2 的形式 |
| §4.2 | 增加 memo 成本模型分区与按节点 bail out |
| §4.4 + §7.4 | 延迟 hydration 边界与代码分割合并为一个原语 `r/defer {:when :visible}`；静态档位增加"行为根" |
| §6.1 | 列式编码采用 Flight 式行引用 |
| §7.3 | hydration 增加块标记与范围跳过 |
| §12.2 | dom 模块含事件委托；预算不变 |
| §13 | 编辑器诊断的来源增加效果签名 |

### 来源

- Svelte：`packages/svelte/src/compiler/phases/3-transform/client/{transform-client.js, transform-template/index.js, visitors/shared/fragment.js, visitors/RegularElement.js}`；`packages/svelte/src/internal/client/{dom/template.js, dom/operations.js, dom/hydration.js, dom/blocks/each.js, dom/elements/events.js, reactivity/sources.js, reactivity/deriveds.js, reactivity/effects.js, runtime.js}`；https://svelte.dev/blog/runes ；Rich Harris 访谈 https://codetv.dev/series/learn-with-jason/s6/going-deep-on-svelte-5 ；体积数据 https://khromov.se/svelte-5-brings-up-to-50-bundle-size-decrease-for-existing-svelte-4-apps/
- React Compiler：https://github.com/facebook/react/blob/main/compiler/docs/DESIGN_GOALS.md ；`compiler/packages/babel-plugin-react-compiler/src/Entrypoint/Pipeline.ts` ；`src/Inference/MUTABILITY_ALIASING_MODEL.md` ；`src/Inference/InferReactivePlaces.ts` ；https://react.dev/blog/2025/10/07/react-compiler-1 ；https://react.dev/blog/2024/10/21/react-compiler-beta-release ；Flight 格式 `packages/react-client/src/ReactFlightClient.js` ；实测 https://calendar.perfplanet.com/2024/how-does-the-react-compiler-perform-on-real-code/
- Octane：https://octanejs.dev/docs/ ；https://github.com/octanejs/octane （`packages/octane/src/compiler/{template-ir, dom-binding-program, deferred-snapshots}.js`）；https://news.ycombinator.com/item?id=49152640

---

## 18. 实施阶段

### 阶段 0：可行性原型（约 6 周）

验证两个最大假设：程序表解释开销可接受；静态模板提取与数据流图配合。

- `r/defn`、块级站点推断、hiccup 静态提取、表驱动求值器
- §17.2 的模板规则（步骤序列定位、`{start end}` 范围、文本合并、事件委托）与 §17.3 的版本号脏检查，从第一版求值器就采用
- incseq v1、二进制协议最小子集
- **会话内存布局**按 §8.6 定下：脏位图 + 拓扑序求值器、共享值 diff 日志 + 游标、编码一次多路分发、每核工作线程；deflate 默认关闭
- **`curve.test` 最小集**（无头双端 + 虚拟时钟）：从第一行代码开始用它测
- **slot 授权规则**进入协议设计，不后补
- 跑通 TodoMVC 和 SQLite 表格应用；产出体积、字节数、1000 行更新延迟、1000 会话共享一张表的常驻内存的基准

**退出标准**：Hello World gzip ≤ 80 KB；解释开销相对闭包产物 ≤ 20%。达不到则退回"热点子图发射闭包"的混合方案。

### 阶段 1：核心（约 3 个月）

- incmap / incset、三态与边界、`r/mutation` + token、`r/for :by`、`r/binding`、`r/effect`
- 路由与代码分割、**表单库**（提前到此阶段）、`r/foreign`
- 完整协议（含 Flight 式行引用的列式编码、typed array、速率提示）、稳定 ID、局部热替换、增量编译
- memo 成本模型分区、按节点 bail out、效果签名表（§17.4）；`r/defer` 边界（延迟 hydration = 代码分割）
- `Mount` 协议显式化；`curve.runtime.virtual`（`r/window`、`:recycle`）
- 会话隔离与预算、共享层、数据源协议 + 轮询 + SQLite hook 适配器
- 污点分析、边界报告、边界 schema、反序列化白名单
- `curve.dev`：检视、"为什么更新"、编译期诊断；clj-kondo 配置；`curve-info.edn`

### 阶段 2：SSR 与生产特性（约 3 个月）

- 无头 DOM、SSR、流式、hydration（块标记 + 范围跳过）与续航、静态构建（含行为根档位）、懒连接
- js-framework-benchmark 标准操作的比值基线进 CI（§17.6）
- 快照、重连、部分续航、排空、版本协商
- 乐观投影、协作（`shared-atom`、`presence`）、本地服务端站点
- Datomic 与 Postgres CDC 适配器、指标、优先级通道；跨会话查询合并、空闲会话休眠、可见性降频（§8.6 第 5、6、8 项）
- `curve.runtime.canvas`、diff 感知 foreign、`bucket-by` 聚合；动态程序表与可选 SCI 模块（§4.6）；四个场景（§16）的示例应用与基准
- `curve.dev`：时间旅行、跨站点栈、线路检视；`curve.test`：网络断言、日志生成测试

### 阶段 3：生态（持续）

- 虚拟滚动、上传、WebTransport、更多数据源、部署指南、编辑器插件

---

## 19. 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 程序表解释器比闭包慢 | 核心假设失败 | 阶段 0 基准；备选：热点子图发射闭包 |
| 站点推断在复杂闭包场景出错 | 正确性、安全 | 保守推断 + 编译错误；污点分析作为第二道防线；对照用例库 |
| JVM 隔离不如 BEAM | 一个坏会话影响节点 | §8.2 的预算与熔断；§8.6 的数组式会话状态让内存按会话精确计量；指标先行 |
| 每连接内存随会话数线性增长（Meteor 教训） | 扩展上限低 | §8.6 第 1、2、6 项；阶段 0 以"1000 会话共享一张表"为基准 |
| 有状态服务端的运维复杂度 | 采用门槛 | 快照、部分续航、排空、文档 |
| 功能蔓延 | 体积与复杂度 | §0.2 复杂度预算；§12.2 体积 CI |
| Clean-room 执行不严 | 许可证风险 | 贡献指南；核心模块审查关注相似度 |
| Clojure 社区规模 | 生态 | 复用 Ring、next.jdbc、Datomic 等现有生态 |

---

## 20. 待决问题

1. 名字：Curve 为暂定，需确认无冲突。
2. 值编码：transit-msgpack 还是 CBOR，取决于 cljs 侧实现体积（待测）。
3. 是否支持 Babashka / nbb 作为服务端。
4. `^:server` / `^:secret` 元数据在 Cursive / clj-kondo 中的提示效果需试验。
5. CDC 适配器由核心仓库维护还是独立仓库。
6. ~~污点分析的粒度~~ 已决定，见 §11.4。
