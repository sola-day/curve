# Curve 开发里程碑计划

> 2026-10-03 起草。对应 [design.md](design.md) §18 的阶段划分，把每个阶段拆成可独立验收、可独立提交的里程碑。
> 版本管理用 jj（colocated git）。每个里程碑内按"一个可测的小步"提交，提交信息前缀为里程碑编号（如 `M1: incseq diff 代数与性质测试`）。

## 原则

- **先核心后外围**：先把程序表、求值器、incseq、协议这四个基础机制（§0.2）做出来并测通，再做 DOM、网络、SSR。
- **每一步有测试**：`curve.test` 的最小集在 M0 就建立，后续每个里程碑的验收标准都是可运行的测试。
- **退出标准可量化**：阶段 0 结束时跑基准，不达标就改方案（§18 退出标准）。
- **clean-room**：不读 Electric 源码，只引用其公开文档与本仓库的 design.md。
- **体积预算从第一天记录**：每次 release 构建输出 gzip 体积，写入 `docs/benchmarks.md`。

## 仓库布局

```
curve/
  deps.edn               ; :test :dev :cljs 别名
  shadow-cljs.edn        ; 客户端构建（M4 起）
  src/curve/
    incseq.cljc          ; M1  diff 代数与算子
    incmap.cljc          ; M1  键值 diff
    table.cljc           ; M2  程序表数据结构与校验
    eval.cljc            ; M2  表驱动求值器（脏位图 + 拓扑序 + 版本号）
    compiler/            ; M3  expand / analyze / split / emit（仅 clj）
    core.cljc            ; M3  r/defn r/server r/client 等用户宏
    protocol.cljc        ; M5  二进制帧编码/解码
    session.clj          ; M5  服务端会话、diff 日志 + 游标、工作线程
    shared.clj           ; M7  共享层
    source.clj           ; M7  Source 协议与轮询/内存适配器
    dom.cljs             ; M4  Mount 协议 DOM 实现、模板克隆、事件委托
    headless.cljc        ; M4  无头 DOM（测试 + SSR 共用）
    client.cljs          ; M6  客户端运行时入口
    server.clj           ; M6  Ring/websocket 服务端入口
    test.cljc            ; M0  无头双端测试工具与虚拟时钟
  test/curve/            ; 与 src 一一对应
  examples/
    hello/               ; M6
    todomvc/             ; M8
    sqlite-table/        ; M8
  docs/
    design.md milestones.md benchmarks.md
```

## 阶段 0：可行性原型

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M0 脚手架** | deps.edn、目录、`curve.test` 最小集（虚拟时钟、`flush!`）、CI 脚本、本文件 | `clj -M:test` 通过一个空测试 |
| **M1 增量集合** | `incseq`：diff 代数 `{grow degree shrink permutation change}`、`combine`、`patch`、`diff-by`；算子 `map` `filter` `sort-by` `take` `drop`；`incmap` 的 `{:assoc :dissoc}` 代数 | test.check 性质测试：结合律、单位元、`combine` 与顺序 `patch` 等价；`diff-by` 后 `patch` 回到目标序列 |
| **M2 程序表与求值器** | 程序表格式（节点向量、稠密 ID、入边、站点、模板）；求值器：脏位图、拓扑序扫描、版本号脏检查（§17.3）、三态 `value/pending/error`、条件子图挂载/卸载、`for` 子槽位；结构化取消 | 手写程序表驱动测试：改一个源只重算下游；分支切换释放子图；1 万节点传播基准 |
| **M3 编译器 v1** | `r/defn` 宏 → expand（cljs.analyzer 公开 API）→ 数据流图 → 块级站点推断（`r/server`/`r/client`/`^:server`）→ 静态模板提取（§17.2 步骤序列、文本合并）→ split 为两份程序表 → 稳定节点 ID（§4.3） | 编译示例函数得到预期程序表；站点冲突报编译错误并带位置；同一源码两次编译 ID 相同；改一个函数其他 ID 不变 |
| **M4 DOM 挂载** | `Mount` 协议；无头 DOM（`headless.cljc`，供测试与后续 SSR）；浏览器 DOM 实现：模板 `cloneNode`、洞定位、`{start end}` 块范围、事件委托、keyed reconcile 贪心单遍 | 无头 DOM 上 render/update/unmount 测试；shadow-cljs 浏览器 smoke 测试 |
| **M5 协议与会话** | 二进制帧：varint slot、类型字节、四元组 `[acks requests changes freezes]` monoid；会话状态数组；共享值 diff 日志 + 游标；编码一次多路分发；每核工作线程 + 8 ms tick；slot 授权（未挂载节点拒绝）；deflate 默认关 | 编解码往返与 monoid 性质测试；进程内直连双端跑通；`bytes-sent` / `slots-changed` 网络断言；越权 slot 请求被拒 |
| **M6 端到端** | websocket 传输（Ring + Jetty/http-kit）、客户端入口、`examples/hello`；`curve.test/mount` 走完整协议路径 | 浏览器打开 Hello World 看到服务端值并随之更新；release 构建 gzip 体积记录 |
| **M7 共享层与数据源** | `r/shared`、`Source` 协议、`mem-source`、轮询 + 版本号适配器、SQLite 适配器（轮询版） | 1000 个会话订阅同一表：求值与编码次数为 1；常驻内存基准 |
| **M8 示例与基准** | TodoMVC、SQLite 表格（登录 + 编辑，对标 electric-sqlite-app）；基准：Hello gzip、单字段更新字节数、1000 行更新延迟、解释开销对比闭包、1000 会话内存 | **阶段 0 退出标准**：Hello gzip ≤ 80 KB；解释开销 ≤ 闭包 20%；结果写入 benchmarks.md |

M1–M2 不依赖浏览器，可以完全在 JVM 上测；M3 依赖 cljs.analyzer 但只在编译期；M4 起引入 shadow-cljs。

## 阶段 1：核心

| 里程碑 | 内容 |
|---|---|
| M9 语言完整 | `incset`、`r/for :by`、`r/binding`、`r/effect`、`r/flow`、`r/suspense`、`r/boundary`、`r/mutation` + token |
| M10 路由与分块 | `r/route` `r/href` `navigate!`、程序表按路由分块、`r/defer` 边界 |
| M11 协议完整 | 列式编码（Flight 式行引用）、typed array、速率提示、背压、ack 拆除共识 |
| M12 热替换与增量编译 | 稳定 ID 局部热替换、按 `r/defn` 缓存分析结果 |
| M13 memo 与分析 | memo 成本模型分区、按节点 bail out、效果签名表、纯节点内 `swap!` 报错 |
| M14 安全 | 污点分析（值级 + 字面量键）、`r/declassify`、边界报告 `curve-info.edn`、malli 边界 schema、反序列化白名单 |
| M15 服务端 | 会话预算与熔断、指标、SQLite update hook 适配器 |
| M16 表单与 foreign | 表单库、`r/foreign`、`curve.runtime.virtual` |
| M17 开发工具 | `curve.dev` 检视、"为什么更新"、clj-kondo 配置 |

## 阶段 2：SSR 与生产

| 里程碑 | 内容 |
|---|---|
| M18 SSR | 无头 DOM 渲染 HTML + 快照、流式输出、块标记 |
| M19 Hydration 与续航 | 认领 DOM、范围跳过、从快照恢复、懒连接 |
| M20 静态构建 | 路由静态化、行为根档位、`r/static` |
| M21 会话运维 | 快照、重连、部分续航、排空、版本协商、空闲休眠 |
| M22 协作与乐观 | `shared-atom`、`presence`、乐观投影 |
| M23 数据源 | Datomic tx-report、Postgres CDC、跨会话查询合并 |
| M24 场景模块 | canvas、diff 感知 foreign、`bucket-by`、动态程序表与 SCI 模块 |
| M25 工具完整 | 时间旅行、跨站点栈、线路检视、日志生成测试、js-framework-benchmark 比值进 CI |

## 阶段 3：生态

虚拟滚动、上传、WebTransport、更多数据源、部署指南、编辑器插件。按需排期。

## 进度记录

| 里程碑 | 状态 | 完成日期 | 说明 |
|---|---|---|---|
| M0 | 完成 | 2026-10-03 | 脚手架、虚拟时钟 |
| M1 | 完成 | 2026-10-03 | incseq 代数与结构化 delta，性质测试 |
| M2 | 完成 | 2026-10-03 | 程序表、求值器、跨站点导出 |
| M3 | 完成 | 2026-10-03 | r/defn 编译器 |
| M4 | 完成 | 2026-10-03 | 模板提取、挂载、无头与浏览器 DOM |
| M5 | 完成 | 2026-10-03 | 二进制协议、会话 |
| M6 | 完成 | 2026-10-03 | websocket 端到端，真实浏览器验证 |
| M7 | 完成 | 2026-10-03 | r/shared、diff 日志游标、编码一次；Source 适配器 |
| M8 | 完成 | 2026-10-03 | TodoMVC、SQLite 示例；阶段 0 退出标准达成（见 benchmarks.md） |
| M9 | 完成 | 2026-10-03 | r/binding、r/effect、r/boundary、r/suspense、r/flow、r/offload、r/mutation |
| M10 | 完成 | 2026-10-03 | 路由；响应式函数按名字过线路（可配合懒加载模块）；r/defer |
| M11 | 完成 | 2026-10-03 | 列式记录、typed array、速率提示、确认与窗口背压 |
| M12 | 完成 | 2026-10-03 | 稳定节点 ID、热替换保留本地状态、增量编译 |
| M13 | 完成 | 2026-10-03 | 效果签名、死代码消除、身份比较、值内修改报错 |
| M14 | 完成 | 2026-10-03 | 污点分析、r/declassify、边界报告、服务端入口校验 |
| M15 | 完成 | 2026-10-03 | 会话预算与熔断、指标、SQLite update hook 适配器 |
| M16 | 完成 | 2026-10-03 | 展开属性、r/foreign、r/for :recycle、表单库、虚拟滚动 |
| M17 | 完成 | 2026-10-03 | inspect、why、诊断、clj-kondo 配置 |
| M18 | 完成 | 2026-10-03 | SSR、流式输出（与 M19 合并实现） |
| M19 | 完成 | 2026-10-03 | 快照续航：浏览器接回 SSR 会话，不重查 |
| M20 | 完成 | 2026-10-03 | 静态构建与页面分档、懒连接、无 JS 表单 |
| M21 | 完成 | 2026-10-03 | 可靠传输、断线重连、休眠唤醒、版本协商、迁移 |
| M22 | 完成 | 2026-10-03 | shared-atom、presence、乐观投影、Worker 本地服务端站点 |
| M23 | 完成 | 2026-10-03 | 按表通知数据源、Datomic 与 Postgres CDC 适配器、跨会话批量查询 |
| M24 | 完成 | 2026-10-03 | canvas 场景图、分桶聚合、diff 感知 foreign、动态程序表、场景基准 |
| M25 | 完成 | 2026-10-03 | 线路统计、回放、生成测试、跨站点错误位置、比值基线与 CI |

阶段 3（生态）按计划为"按需排期"，未纳入本轮；其中部署指南已写（docs/deploy.md）。

后续补充（2026-10-03）：`r/defer {:when :visible}` 已实现（占位元素上的 IntersectionObserver，`:margin` 提前量）；虚拟滚动支持可变行高（渲染后测量，Fenwick 树存高度，按偏移找行 O(log n)，滚动锚定）。examples/showcase 在真实浏览器中验证两者。

## 实现与设计的出入

| 设计 | 实现 | 原因 |
|---|---|---|
| §4.2 纯解释的程序表 | 程序表仍是数据；运行时首次使用时按（函数、站点）生成专门化的步骤函数并缓存 | 纯解释比闭包慢约 6 倍，退出标准要求 ≤ 20%（§18 的备选方案） |
| §7.3 hydration 认领已有 DOM | 用快照在同一个 JS 任务内重新渲染并替换 SSR DOM | 不闪烁、不重查、实现小；代价是 JS 加载前已获得焦点的输入会失焦 |
| §4.6 可选 SCI 模块 | 白名单解释器（无 eval），`:eval` 选项可接入 SCI，未打包 SCI | 默认安全、零体积 |
| §8.5 Datomic / Postgres 适配器 | 已实现；Datomic 用替身 API 测试，Postgres 只测了 wal2json 解析 | 本机无这两个数据库 |
| 版本协商 | 版本是程序表结构（节点、站点、读者、洞）的 FNV-1a 哈希 | 平台条件代码（`#?`）会让源码文本在两端不同，但协议相同 |

