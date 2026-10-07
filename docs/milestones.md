# Hypercurve 开发里程碑计划

> 2026-10-03 起草。对应 [design.md](design.md) §18 的阶段划分，把每个阶段拆成可独立验收、可独立提交的里程碑。
> 版本管理用 jj（colocated git）。每个里程碑内按"一个可测的小步"提交，提交信息前缀为里程碑编号（如 `M1: incseq diff 代数与性质测试`）。

## 原则

- **先核心后外围**：先把程序表、求值器、incseq、协议这四个基础机制（§0.2）做出来并测通，再做 DOM、网络、SSR。
- **每一步有测试**：`hypercurve.test` 的最小集在 M0 就建立，后续每个里程碑的验收标准都是可运行的测试。
- **退出标准可量化**：阶段 0 结束时跑基准，不达标就改方案（§18 退出标准）。
- **clean-room**：不读 Electric 源码，只引用其公开文档与本仓库的 design.md。
- **体积预算从第一天记录**：每次 release 构建输出 gzip 体积，写入 `docs/benchmarks.md`。

## 仓库布局

```
hypercurve/
  deps.edn               ; :test :dev :cljs 别名
  shadow-cljs.edn        ; 客户端构建（M4 起）
  src/hypercurve/
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
  test/hypercurve/            ; 与 src 一一对应
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
| **M0 脚手架** | deps.edn、目录、`hypercurve.test` 最小集（虚拟时钟、`flush!`）、CI 脚本、本文件 | `clj -M:test` 通过一个空测试 |
| **M1 增量集合** | `incseq`：diff 代数 `{grow degree shrink permutation change}`、`combine`、`patch`、`diff-by`；算子 `map` `filter` `sort-by` `take` `drop`；`incmap` 的 `{:assoc :dissoc}` 代数 | test.check 性质测试：结合律、单位元、`combine` 与顺序 `patch` 等价；`diff-by` 后 `patch` 回到目标序列 |
| **M2 程序表与求值器** | 程序表格式（节点向量、稠密 ID、入边、站点、模板）；求值器：脏位图、拓扑序扫描、版本号脏检查（§17.3）、三态 `value/pending/error`、条件子图挂载/卸载、`for` 子槽位；结构化取消 | 手写程序表驱动测试：改一个源只重算下游；分支切换释放子图；1 万节点传播基准 |
| **M3 编译器 v1** | `r/defn` 宏 → expand（cljs.analyzer 公开 API）→ 数据流图 → 块级站点推断（`r/server`/`r/client`/`^:server`）→ 静态模板提取（§17.2 步骤序列、文本合并）→ split 为两份程序表 → 稳定节点 ID（§4.3） | 编译示例函数得到预期程序表；站点冲突报编译错误并带位置；同一源码两次编译 ID 相同；改一个函数其他 ID 不变 |
| **M4 DOM 挂载** | `Mount` 协议；无头 DOM（`headless.cljc`，供测试与后续 SSR）；浏览器 DOM 实现：模板 `cloneNode`、洞定位、`{start end}` 块范围、事件委托、keyed reconcile 贪心单遍 | 无头 DOM 上 render/update/unmount 测试；shadow-cljs 浏览器 smoke 测试 |
| **M5 协议与会话** | 二进制帧：varint slot、类型字节、四元组 `[acks requests changes freezes]` monoid；会话状态数组；共享值 diff 日志 + 游标；编码一次多路分发；每核工作线程 + 8 ms tick；slot 授权（未挂载节点拒绝）；deflate 默认关 | 编解码往返与 monoid 性质测试；进程内直连双端跑通；`bytes-sent` / `slots-changed` 网络断言；越权 slot 请求被拒 |
| **M6 端到端** | websocket 传输（Ring + Jetty/http-kit）、客户端入口、`examples/hello`；`hypercurve.test/mount` 走完整协议路径 | 浏览器打开 Hello World 看到服务端值并随之更新；release 构建 gzip 体积记录 |
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
| M14 安全 | 污点分析（值级 + 字面量键）、`r/declassify`、边界报告 `hypercurve-info.edn`、malli 边界 schema、反序列化白名单 |
| M15 服务端 | 会话预算与熔断、指标、SQLite update hook 适配器 |
| M16 表单与 foreign | 表单库、`r/foreign`、`hypercurve.runtime.virtual` |
| M17 开发工具 | `hypercurve.dev` 检视、"为什么更新"、clj-kondo 配置 |

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

## 阶段 4：协作应用（面向 hypercanvas，2026-10-07 增补）

hypercanvas（多人实时画布）是 Hypercurve 的第一个真实应用。对照它的架构（hypercanvas `docs/architecture.md`）核对后，共享层和协作原语还停在"有原语"，缺少生产应用需要的生命周期、持久化、变更元数据和频率控制。以下里程碑都做成通用能力，不写死画布语义。

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M26 持久共享原子 | `shared/atom-family`：按 key 惰性创建；引用计数（`r/watch` 自动 acquire/release），最后一个观察者离开 `:idle-ms` 后卸载；`:load`（首次创建时从存储加载）、`:flush`（批量写回，`:flush-ms` 防抖 + `:max-wait-ms` 上限，卸载前必刷）；`:validate`（写入前校验，拒绝即抛错） | 打开/关闭会话驱动加载卸载；崩溃前最多丢一个刷新窗口；校验失败不改值 |
| M27 变更元数据 | `swap-meta!`：写入带 `{:op-id :user ...}`；版本日志记录每次变更的 delta 与元数据；`op-id` 去重（有界窗口）；`listen!` 订阅变更流（持久化、历史、审计用） | 重复 op-id 不生效；监听者按版本顺序收到 delta |
| M28 共享原子走共享发送路径 | `r/watch` 一个共享原子时，像 `r/shared` 一样用"版本游标 + 编码一次"的 blob 下发：一次变更只做一次 diff（复用写入时已算好的 delta），N 个会话共享同一份字节 | 1 次写入 × 100 会话 = 1 次编码；线路字节只含变化字段 |
| M29 频率控制 | ① 发送提示 `^{:debounce ms}`（安静 ms 后才发，与 `^{:rate}` 节流并列）；② `hypercurve.timing`：`debounce`、`throttle`（leading/trailing、`flush!`、`cancel!`，走 Clock，虚拟时钟可测）；③ `r/mutation` 选项 `{:debounce ms}`（合并为最后一次调用，乐观投影立即生效）；④ `presence/update!` 支持 `{:rate hz}` 合并 | 虚拟时钟下的确定性测试：N 次输入 → 1 次发送/调用 |
| M30 二维视口窗口 | `hypercurve.virtual`：`quantize`（视口量化到格子，平移不越格不重算）、`in-rect`（按包围盒过滤）、`grid-index`（均匀网格空间索引，按 delta 增量维护，查询 O(格子数)） | 1 万元素平移只增删边缘元素；量化后小幅平移零重算 |
| M31 delta 感知的 `r/for` | 客户端收到 map 的 `[:m {:patch ...}]` 时，把被触及的 key 传给下游 `r/for`，只调和这些子项，不再 O(n) 遍历；`r/for ... :keyed true` 直接接受 map（按 key 迭代） | 1000 个元素中改 1 个：调和工作量 O(1)（计数断言） |
| M32 版本历史模块 | `hypercurve.history`：基于 M27 变更流，按"同一用户 + 时间窗口 + 字段冲突即封存"合并相邻变更为版本（字段只存首个 before 与最新 after，抵消净零变更）；快照 + 重放计算任意版本；`restore!` 作为一条新变更写回 | 合并、抵消、并发封存、恢复后再恢复的性质测试 |
| M33 协作撤销模块 | `hypercurve.undo`：每用户的撤销栈，只记录自己的字段变更；撤销时若该字段已被他人改过则跳过；500 ms 内同对象合并 | 两用户交错编辑的撤销语义测试 |
| M34 客户端缓存续传 | 客户端保留最近 N 个共享值及其版本；重新观察时把版本带给服务端，服务端从该版本发 delta（日志中已无则发全量） | 返回已看过的画布只传增量 |

顺序依据：M26–M29 是 hypercanvas 的硬前提（房间、乐观、实时频率）；M30–M31 决定大画布性能；M32–M33 是历史与撤销的通用实现；M34 是 nice to have。每个里程碑独立提交，提交信息前缀为里程碑编号。

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
| M26 | 完成 | 2026-10-07 | `SharedAtom` + `atom-family`：加载、防抖批量写回、按观察者引用计数、空闲卸载（卸载时最后一次写回，过期引用的写入转到新实例）、`:validate` |
| M27 | 完成 | 2026-10-07 | `swap-meta!` / `reset-meta!`、op-id 去重（最近 4096 个）、`listen!` 与 `:on-change` 按版本顺序收到 `{:version :delta :meta ...}` |
| M28 | 完成 | 2026-10-07 | `rt/Shareable` 协议：`r/watch` 共享原子走版本游标 + 一次编码；相邻版本直接复用写入时的 delta；`shared-atom` 也改为 SharedAtom |
| M29 | 完成 | 2026-10-07 | `hypercurve.timing`（debounce / throttle，`:max-wait` `:leading` `:trailing`，`flush!` `cancel!`）；发送提示 `^{:debounce ms}`；`r/mutation {:debounce ms}`；`presence/publisher`（合并 + 限频）、`join!` 可指定 id；JVM 主机时钟改为单个调度线程 |
| M30 | 完成 | 2026-10-07 | `virtual/quantize` `in-rect` `grid-index` `index-sync` `index-patch` |
| M31 | 完成 | 2026-10-07 | `r/for [x m :keyed true]`：按 map 的键迭代；值经线路或共享原子以 delta 到达时只访问被触及的条目（客户端与服务端两侧），1000 项改 1 项访问 1 次；`rt/counters` 计数 |
| M32 | 完成 | 2026-10-07 | `hypercurve.history`：`recorder` 把变更折叠为按用户的版本（字段首个 before + 最新 after，净零抵消），空闲 / 字段冲突 / 恢复时封存；`replay`、`apply-version`、`restore!` |
| M33 | 完成 | 2026-10-07 | `hypercurve.undo`：每用户每键的撤销/重做栈，500 ms 内同实体合并；只回退仍是自己所留值的路径，他人改过的跳过 |
| M34 | 完成 | 2026-10-07 | 客户端缓存槽：服务端精确镜像客户端缓存（分配、LRU 淘汰、版本），重新观察时只发 `[:cache 槽 delta]`，无变化只发槽号；客户端缺槽时 `:resync` 取全量。顺带修正：共享值切换实例时重置游标 |

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

