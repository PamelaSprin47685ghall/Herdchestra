# HACK.md — 外部依赖、平台限制与复检账本

> 权威顺序：根 `AGENTS.md` > 本文件。
> 本文件记录外部库、平台与运行时的实测限制、绕行方式、测量数据与复检命令。协议语义不在这里定义；冲突以根 `AGENTS.md` 为准。
> 证据标签：`[MEASURED]` 本机实际运行；`[SPEC]` 根规格写明但本仓库未复测；`[INFERENCE]` 由依赖元数据或代码推断；`[NOT RUN]` 尚未执行。
> 最后更新：2026-11-05。

## 1 当前环境

| 项 | 值 | 证据 |
|---|---|---|
| 主机 | `Linux 7.3.0-rc6-1-MANJARO`，x86_64 | `[MEASURED]` `uname -sr`（2026-11-05） |
| 宿主 JDK | OpenJDK `27`（2026-09-15） | `[MEASURED]` `java -version`（2026-11-05） |
| 宿主 Clojure CLI | `1.12.5.1664` | `[MEASURED]` `clojure --version` |
| 宿主 Node | `v26.10.0` | `[MEASURED]` `node --version`（2026-11-05） |
| Pixi | 宿主 `PATH` 无 `pixi`；用 `/tmp/pixi`（`0.83.0`）生成锁并验证 | `[MEASURED]` `which pixi`、`/tmp/pixi --version` |
| 仓库基线 | `015229e` | `[MEASURED]` `git rev-parse --short HEAD`（2026-11-05） |

宿主与 Pixi 的 JDK 不同：

| 环境 | JDK | Clojure | 证据 |
|---|---|---|---|
| 宿主 | `27` | CLI `1.12.5.1664`，项目依赖 `1.12.6` | `[MEASURED]` |
| Pixi `default` | `21.0.10-internal`，`java.home = .pixi/envs/default/lib/jvm` | `1.12.6.1673` | `[MEASURED]` `/tmp/pixi run clojure -e '(println (System/getProperty "java.version")) ...'` |

解释边界：宿主 JDK 27 只用于快速验证；Pixi 声明的目标运行时是 JDK 21 LTS。两者都通过了 `test.check` 冒烟。JDK 27 不是本项目承诺的运行时。

## 2 依赖 pin

版本在 2026-11-03 通过 Maven Central / Clojars 元数据核对，并用 Clojure CLI 实际解析。

| 依赖 | Pin | 用途 | 状态 |
|---|---|---|---|
| `org.clojure/clojure` | `1.12.6` | 基础运行时 | `[MEASURED]` 解析成功 |
| `org.clojure/test.check` | `1.1.3` | 属性测试 | `[MEASURED]` quick-check 通过 |
| `io.github.cognitect-labs/test-runner` | git tag `v0.5.1`，sha `5e91ee0989d93115cfb5e15b6f242f5ec7ab762e` | `clojure.test` 运行器 | `[MEASURED]` `-M` 与 `-X` 均可运行 |
| `missionary/missionary` | `b.47` | 组合层异步与监督 | `[MEASURED]` 解析成功；API 使用未验证 |
| `com.taoensso/sente` | `1.22.0` | 实时通道 | `[MEASURED]` 解析成功；API 使用未验证 |
| `com.cognitect/transit-clj` | `1.1.367` | 跨语言序列化 | `[MEASURED]` 解析成功 |
| `dev.langchain4j/langchain4j` | `1.23.0` | 低层模型端口 | `[MEASURED]` 解析成功；API 使用未验证 |
| `metosin/malli` | `0.20.3` | schema 校验 | `[MEASURED]` 解析成功 |
| `datalevin/datalevin` | `1.2.10` | 嵌入式当前态存储（KV/LMDB） | `[MEASURED]` 解析、打开、提交、重开与跨进程重开均通过；见 §5.1 |
| `com.fulcrologic/fulcro` | `3.10.1` | 客户端状态库 | `[MEASURED]` 解析成功；CLJS 编译未验证 |
| `com.fulcrologic/fulcro-websockets` | `3.4.0` | Sente 客户端扩展 | `[MEASURED]` 解析成功；CLJS 编译未验证 |
| `org.clojure/clojurescript` | `1.12.145` | 客户端编译 | `[MEASURED]` 解析成功；编译未验证 |

Pin 分类：

1. **可复现输入**：Maven/Clojars 坐标由仓库内容寻址；同一版本应得到相同字节。test-runner 由 git sha 固定。
2. **工件 pin**：`pixi.lock` 记录 conda 构建与哈希，证明“本次解析使用了这些工件”；不宣称跨渠道重建得到位级相同字节。

存储引擎已选定为 **Datalevin 1.2.10 KV**，放在 `deps.edn` 的 `:server` 别名（恰好一个候选）。选择理由：耐久 LMDB、单次 `transact-kv` 多操作原子提交、同步提交后立即可读、无需 Datalog schema、可直接删除当前态。实测边界见 §5.1。

## 3 已验证的构建与测试

### 3.1 依赖解析

```bash
clojure -P -M:dev:test:compose:server:client:cljs
```

`[MEASURED]` 成功。首次运行下载 Transit、Malli、Sente 等依赖，耗时约 8.5 s（网络相关，不作为固定预算）。该命令只证明 classpath 可解析，不证明各库 API 兼容。

### 3.2 `test.check`

宿主 JDK 27。`:test` 现在带 test-runner 的 `-main`，临时求值改用 `:dev`（同样包含 `test.check`）：

```bash
clojure -M:dev -e "(require '[clojure.test.check :as tc] '[clojure.test.check.generators :as gen] '[clojure.test.check.properties :as prop]) (let [r (tc/quick-check 200 (prop/for-all [s gen/string] (= s (str s))))] (prn r) (System/exit (if (:pass? r) 0 1)))"
```

`[MEASURED]` 结果：`:result true, :pass? true, :num-tests 200, :time-elapsed-ms 23`。

Pixi JDK 21：

```bash
/tmp/pixi run clojure -M:dev -e "(require '[clojure.test.check ...]) ..."
```

`[MEASURED]` 结果：`:result true, :pass? true, :num-tests 100, :time-elapsed-ms 11`。

结论：`test.check` 在宿主与 Pixi 两个运行时都可用。属性测试写成普通 `clojure.test` 命名空间即可被 test-runner 发现。

### 3.3 两种测试入口

```bash
clojure -X:test
clojure -M:test -n hc.core.pair-hint-test
```

`[MEASURED]` 2026-11-03：`-M:test -n hc.core.pair-hint-test` 运行 26 个测试、116 个断言，0 failures / 0 errors。`-X:test :nses '[hc.core.pair-hint-test]'` 同样通过。`-X:test` 与 `-M:test` 共用同一个 `:test` 别名；`-M` 走 `cognitect.test-runner` 的 `-main`，`-X` 走 `cognitect.test-runner.api/test`。

### 3.4 资源 classpath

`resources/` 必须进入 `:paths`，否则双语提示资源加载失败：

```text
pair-hint: both language resources are required
{:error :PAIR_HINT_RESOURCE_MISSING, :missing [:en :zh-CN], ...}
```

`[MEASURED]` 初次 `pixi run test` 在 `:paths` 只有 `src` 时出现上述错误。将 `:paths` 改为 `["src" "resources"]` 后，`pair-hint` 测试通过。该修复属于构建配置，不是业务代码绕过。

### 3.5 全量测试状态

`[MEASURED]` 2026-11-05 09:18–09:24，`/tmp/pixi run test`
（`clojure -X:test:compose:server:client`）在基线树
`015229e28bd0fdddc14060a28f1348185c6e765b` 上退出 0：
**3406 tests / 22121 assertions，0 failures / 0 errors**，343 s；运行窗口内
`src/`、`test/` 无文件改动。日志 `/tmp/final-full-test3.log`，汇总见
`plans/release-0.0.1-baseline.md` §2 G5。

`[LIMITATION]` 此后工作树仍被并行修改；该结果只绑定上述树。发布提交前必须
以最终合并树重跑，不能把 3406/22121 直接当最终放行证据。`test/` 在
2026-11-05 文档核对时约 350 个 `.clj/.cljs/.cljc` 文件（静态计数，含 runner
与 support）；计数随施工变化，不作为测试数。

## 4 Pixi 平台与 Java 供应

### 4.1 锁文件

```bash
/tmp/pixi lock
```

`[MEASURED]` 成功，生成 `pixi.lock`（lock version 7），平台为 `linux-64`、`osx-64`、`win-64`。解析出的关键工件：

| 平台 | Clojure | OpenJDK | Node |
|---|---|---|---|
| `linux-64` | `1.12.6.1673 ha770c72_0` | `21.0.10 h1602c4f_22` | `22.23.4 h645b291_0` |
| `osx-64` | `1.12.6.1673 h694c41f_0` | `21.0.10 h3ebe2c2_22` | `22.23.4 h89f919c_0` |
| `win-64` | `1.12.6.1673 h57928b3_0` | `21.0.10 h56f2b75_22` | `22.23.4 h80d1838_0` |

### 4.2 `osx-arm64` 缺口

`[MEASURED]` conda-forge 的 `clojure` 包截至 2026-11-03 只提供 `linux-64`、`osx-64`、`win-64`，没有 `osx-arm64`。因此 `pixi.toml` 不声明 `osx-arm64`；Apple Silicon 使用 `osx-64` 经 Rosetta，或使用宿主 Clojure CLI。

复检：

```bash
curl -fsSL 'https://api.anaconda.org/package/conda-forge/clojure' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(sorted({f["attrs"]["subdir"] for f in d["files"] if f["version"]=="1.12.6.1673"}))'
```

预期：`['linux-64', 'osx-64', 'win-64']`。

### 4.3 Pixi 任务

```bash
/tmp/pixi run test
/tmp/pixi run clojure -X:test :nses '[hc.core.pair-hint-test]'
/tmp/pixi run clojure -M:test -n hc.core.pair-hint-test
```

`[MEASURED]` Pixi 实际供应 `java.version = 21.0.10-internal`，`java.home` 位于 `.pixi/envs/default/lib/jvm`，不是宿主 JDK 27。`pixi run clojure -X:test :nses '[hc.core.pair-hint-test]'` 与 `pixi run clojure -M:test -n hc.core.pair-hint-test` 都在该 JDK 下通过（测试数随并行施工增长）。`pixi run test` 会运行全量测试；全量状态见 §3.5。

## 5 外部库与平台限制

### 5.1 Datalevin 1.2.10 KV（已选定，已实测）

`[MEASURED]` 2026-11-03，宿主 JDK 26 与 Pixi JDK 21，Linux x86_64：

| 观察 | 结果 | 处理 |
|---|---|---|
| 打开缺失目录 | `open-kv` 自动创建目录 | 适配器在原生打开前自行分类路径；只有缺失/空目录/可证明为空的未初始化环境才允许 `:create? true` |
| 打开外来非空目录 | `open-kv` 接受并向其中写入 `data.mdb`、`lock.mdb`、`VERSION`、`write-protocol.lock` | 分类为 `:foreign-dir` 并拒绝；不写入、不删除 |
| 同进程二次打开 | 抛出“multiple LMDB connections”异常 | 适配器另有 OS 文件锁；第二次打开返回 `:STORAGE_LOCKED` |
| 多操作事务 | `transact-kv` 一次调用原子应用 `[:put ..]`/`[:del ..]`，`with-transaction-kv` 可让多次调用加入同一事务 | 每次领域迁移只用一次调用；文档、义务、Blob 引用、元数据与回执同一事务 |
| 提交可见性 | `transact-kv` 同步返回；随后读取立即可见 | 提交后再构造视图；视图另做全局版本前后一致性检查 |
| 耐久性 | `d/sync kv 1` 强制落盘；跨进程重开可读到已提交值 | `:sync` 为默认；`d/sync` 与 `transact-kv` 期间清除并恢复线程中断位 |
| 只读环境 | `:flags #{:rdonly-env}` 在打开元数据 DBI 时返回 `Permission denied` | 不宣称文件系统只读；`:read-only?` 是适配器逻辑模式，目录仍需可写 |
| 键长 | LMDB 键上限 511 字节 | 适配器拒绝超过 500 字节的键 |
| 原生库 | 由 bytedeco/javacpp 携带，Linux x86_64 实测可加载 | 其他平台尚未实测；选定引擎的三平台打包仍按 §5 表格复核 |
| 完整性扫描 | `verify-integrity` 线性扫描全部当前态（文档、义务、Blob 引用、元数据、回执与索引）；`recovery-entry` 默认开启 | 大当前态有启动成本；直接打开可用 `:verify-integrity? false` 显式跳过 |

实测命令（宿主）：

```bash
clojure -M:test:server -n hc.server.storage-test -n hc.server.recovery-test
clojure -M:test:server -n hc.server.storage.disk-smoke-test
clojure -M:test:server -n hc.server.app.restart-smoke-test
```

`hc.server.app.restart-smoke-test` 启动三个独立 JVM（seed/verify/blocked），每个都运行完整应用；seed 写入后停止，verify 重新启动并检查启动恢复，blocked 在损坏一个信封后确认应用返回 blocked 且没有 gateway/URL。

跨进程冒烟在独立 JVM 中写入、关闭、重开、再写入，并由父进程复核；另有一个子进程在父进程持锁时尝试打开，期望 `:STORAGE_LOCKED`。

以下条目来自根 `AGENTS.md`，除 §5.1 外仍按规格保留，尚未逐条复测。

| 条目 | 限制 | 处理 |
|---|---|---|
| Sente 单条消息 | 官方边界约 1 MiB | 长日志、大附件、导出包走独立 HTTP/Blob 通道 |
| Datomic Local | 单值字符串硬上限 4096 字符；共享 `FileChannel` 不能被 `Thread/interrupt` 打断 | 长正文走 Blob 或分块；提交线程与取消域隔离 |
| Datalevin | 含原生存储部件，需按 OS/CPU 核实打包；关闭顺序严格 | 已选定；Linux x86_64 实测见 §5.1。三平台打包仍未实测；关闭顺序由 `open-close-store!` 固定 |
| Asami | `local` 磁盘存储仅 JVM；JVM 事务异步返回 | 适配器 deref 提交完成后的 db 值再交 Owner |
| XTDB v2 | 事务日志与索引可见性之间存在异步间隙 | 适配器等待索引读屏障后再发布新状态 |
| LangChain4j | 高层 `AiServices` / `ChatMemory` 会接管工具循环与历史 | 只用低层 `ChatModel` / `StreamingChatModel` / `ToolSpecification` |
| ClojureScript / Framework7 | npm 依赖与 CLJS 编译链独立于 `deps.edn` | 客户端包自带 `package.json`；`deps.edn` 只声明 CLJS 与 Fulcro |
| 宿主服务 | Ollama、浏览器、桌面应用不属于本进程 | 只按显式地址连接，不擅自关闭 |

## 6 绕行记录

1. **宿主没有 Pixi**：用 `/tmp/pixi` 的独立二进制生成锁并验证任务；不修改用户全局环境。产品运行仍以 `pixi.toml` / `pixi.lock` 为准。
2. **`osx-arm64` 无 Clojure 包**：不声明该平台；文档写明 Rosetta 或宿主 CLI 路径。若未来 conda-forge 补齐，重新生成锁并恢复平台声明。
3. **`:test` 同时支持 `-M` 与 `-X`**：同一别名同时提供 `:main-opts` 与 `:exec-fn`。已验证两者都能运行；若 Clojure CLI 未来改变别名合并规则，拆成 `:test` 与 `:test-x` 两个别名。
4. **资源未进 classpath**：`resources/` 加入 `:paths`；不把资源复制到 `src/` 规避。
5. **宿主 JDK 27 与 Pixi JDK 21 并存**：快速验证可在宿主运行；交付验证以 Pixi JDK 21 为准。差异只在运行时，不改变源码依赖。
6. **`cljs-repl` 不重复 `-m cljs.main`**：`:cljs` 别名已提供 `:main-opts ["-m" "cljs.main"]`。Pixi 任务写 `clojure -M:cljs:client -re node`；再加 `-m cljs.main` 会重复主类入口。

## 7 复检清单

```bash
# 1. 依赖解析
clojure -P -M:dev:test:compose:server:client:cljs

# 2. test.check 冒烟
clojure -M:dev -e "(require '[clojure.test.check :as tc] '[clojure.test.check.generators :as gen] '[clojure.test.check.properties :as prop]) (prn (tc/quick-check 100 (prop/for-all [s gen/string] (= s (str s)))))"

# 3. 单命名空间测试
clojure -M:test -n hc.core.pair-hint-test
clojure -X:test :nses '[hc.core.pair-hint-test]'

# 4. 全量测试（当前见 §3.5）
clojure -X:test

# 5. Pixi 锁与运行时
/tmp/pixi lock
/tmp/pixi run clojure -e '(println (System/getProperty "java.version")) (println (clojure-version))'
```

每次平台、JDK、Clojure 或关键依赖升级后，重跑本清单并把新数据写回本文件；不要用旧测量为新技术栈背书。

## 8 聚焦集成命令与运行时基线（2026-11-03 16:40 CST）

> 本节记录骨架并行施工期间的实测。模块源文件随后仍会变化；每条结果只绑定该次命令与环境，不替代合并后的单 JVM 全量复检。

### 8.1 运行时快照

| 项 | 值 | 证据 |
|---|---|---|
| 主机 | 12 核，62 GiB，`/tmp` 可用 27 GiB | `[MEASURED]` `nproc` / `free -h` / `df -h /tmp` |
| 宿主 JDK | `26.0.2.1` | `[MEASURED]` `java -version` |
| 宿主 Clojure CLI | `1.12.5.1664` | `[MEASURED]` `clojure --version` |
| 宿主 Node | `v26.8.1` | `[MEASURED]` `node --version` |
| Git | `2.55.0` | `[MEASURED]` `git --version` |
| Pixi | `/tmp/pixi` `0.83.0`；宿主 `PATH` 无 `pixi` | `[MEASURED]` |
| Pixi 运行时 | JDK `21.0.10-internal`，Clojure `1.12.6` | `[MEASURED]` `/tmp/pixi run clojure -e ...` |
| 全别名解析 | 成功，缓存命中约 2 s | `[MEASURED]` `/tmp/pixi run clojure -P -M:dev:test:compose:server:client:cljs` |

本机没有 `.Herdchestra/` 或 `/tmp/hc-devops/`；测试产物只写入本回合自建的 `/tmp/hc-*` 路径。

### 8.2 聚焦集成命令（已测）

所有命令在仓库根目录运行；`clojure` 指宿主 CLI，Pixi 复跑时在命令前加 `/tmp/pixi run`。

| 目的 | 命令 | 本次结果 |
|---|---|---|
| 控制内核 + 文件入口 | `clojure -X:test :nses '[hc.core.swarm-stm-test hc.core.swarm-stm-property-test hc.core.swarm-stm-smoke-test hc.core.swarm-stm-workspace-test hc.core.workspace-gate-test hc.core.workspace-gate.prepare-test]'` | 94 tests / 849 assertions；0 failures，0 errors |
| 真实 Git worktree fork/join | `clojure -X:test :nses '[hc.compose.fleet.slice-test hc.compose.fleet.state-test hc.compose.fleet.git-test hc.compose.fleet.control-test hc.compose.fleet.smoke-test]'` | 53 / 308；0/0 |
| 服务端适配层 | `clojure -X:test :nses '[hc.server.commands-test hc.server.blob-store-test hc.server.model-port.call-identity-test hc.server.model-port.contract-test hc.server.model-port.receiver-test hc.server.model-port.retry-test hc.server.model-port.stream-test hc.server.model-port.usage-test]'` | 90 / 429；0/0 |
| 客户端同步 | `clojure -X:test :nses '[hc.client.sync-test]'` | 34 / 280；0/0 |
| Scope | `clojure -M:dev -m hc.core.scope.run-tests` | 53 / 234；0/0 |
| 文本定位 + Steward Sphinx | `clojure -X:test :nses '[hc.core.text-location-test hc.core.steward-sphinx-test hc.core.steward-sphinx.evidence-test hc.core.steward-sphinx.cognition-test hc.core.steward-sphinx.frontier-test hc.core.steward-sphinx.docket-test]'` | 87 / 367；0/0 |
| 组合层其余包 | `clojure -X:test:compose :nses '[hc.compose.cancel-test hc.compose.guards.scheduler-test hc.compose.retry.policy-test hc.compose.retry.error-test hc.compose.retry.planner-test hc.compose.reports.report-test hc.compose.reports.delivery-test hc.compose.reports.acceptance-test hc.compose.reports.protocol-test]'` | 125 / 595；0/0 |
| Relay + Nudge 咬合 | `clojure -X:test :nses '[hc.core.relay-test hc.compose.nudge.policy-test]'` | 34 / 1024；0/0 |
| UFSS 双射 + pair_hint | `clojure -X:test :nses '[hc.core.ufss-test hc.core.pair-hint-test hc.core.pair-hint-ufss-integration-test]'` | 49 / 328；0/0 |
| 单 JVM 跨层冒烟（推荐 CI 入口） | `clojure -X:test :nses '[hc.core.pair-hint-ufss-integration-test hc.core.swarm-stm-workspace-test hc.compose.fleet.smoke-test hc.server.commands-test hc.client.sync-test]'` | 60 / 453；0/0 |

前九行合计（不含单 JVM 冒烟）：619 tests / 4414 assertions，0 failures / 0 errors，全部退出码 0。`[MEASURED]`（2026-11-03）。这些命令是聚焦抽样，不覆盖 `test/` 下全部命名空间：2026-11-05 静态计数约 350 个测试/支持命名空间文件；`hc.core.scope.run-tests` 是专用 runner，不是独立测试命名空间。全量状态见 §8.4。

同一单 JVM 冒烟在 Pixi 下复跑（JDK 21、Clojure 1.12.6）：`/tmp/pixi run clojure -X:test :nses '[hc.core.pair-hint-ufss-integration-test hc.core.swarm-stm-workspace-test hc.compose.fleet.smoke-test hc.server.commands-test hc.client.sync-test]'`，60 tests / 453 assertions，0 failures / 0 errors，退出 0。`[MEASURED]`

### 8.3 客户端编译冒烟

```bash
clojure -M:cljs -co '{:target :nodejs
                      :output-to "/tmp/hc-devops-cljs-smoke.js"
                      :output-dir "/tmp/hc-devops-cljs-smoke-out"}' \
  -c hc.client.sync
env -C / node /tmp/hc-devops-cljs-smoke.js
```

`[MEASURED]` 编译退出 0，产物 901 B，Node 退出 0。`env -C /` 不是装饰：生成的入口按当前工作目录解析 `tmp/.../goog/bootstrap/nodejs.js`；在仓库根直接运行会报 `MODULE_NOT_FOUND`。输出路径应放在仓库外；不要把 `out/` 或 `node_modules/` 写进工作树。

同一流程在 Pixi 下复跑（Node `22.23.4`）：编译与 `env -C / node` 均退出 0。`[MEASURED]`

### 8.4 全量与真实应用冒烟

1. `clojure -X:test`：`[MEASURED]` 2026-11-05 已在基线树完成单 JVM 全量
   （3406 tests / 22121 assertions，0/0），见 §3.5 与 §13。合并后仍须重跑，
   因为跨命名空间的资源与全局状态只有在该形态下才暴露。
2. 真实应用冒烟：`[MEASURED]` 2026-11-05 已在基线树完成：临时端口
   39697，`GET /healthz` 200 且 `:ok true`/`:version "0.0.1"`，`GET /` 200
   且返回客户端 shell；SIGTERM 后同一数据目录可再次启动。证据见
   `plans/release-0.0.1-baseline.md` §2 G6 与
   `plans/release-notes-0.0.1.md` §2。不得把模块测试当作应用冒烟；最终树
   仍需重跑。
3. E2E 01–22：`[MEASURED]` 2026-11-05 已实跑/取证；最新统计是
   **8 PASS（01/02/14/17/18/19/20/22）、10 PARTIAL、4 BLOCKED**，以
   `plans/e2e-master-status-0.0.1.md` 为准；多数 PASS 仍不等于完整 UI 通过。
   根 `AGENTS.md` §8.5 的自动化验收（mock 模型/OAuth/WebDAV/S3）以 W10
   报告为准；本节的手工/真实端点结果不能替代自动化 PASS。
   模型端点边界见 §13.1。F01–F15、V01–V08、T01–T13 仍为 `[NOT RUN]`；
   不把模块测试冒充应用冒烟。
4. 放行前的最小真实冒烟（入口已存在，执行前仍按下列约束）：
   - 用 `mktemp -d` 建临时数据根，只监听 loopback，不连接 Ollama、浏览器等宿主服务；
   - 启动、读取基线快照、关闭、重开，确认恢复路径不初始化空库；
   - 全程无 GPU、无外部网络模型调用；结束后只删除本次自建临时目录。

### 8.5 执行纪律

1. 测试只写本回合自建临时路径；`/tmp/hc-devops/`、`.Herdchestra/`、用户项目与既有 `/tmp/hc-sync-smoke*` 一律只读。
2. 清理是单独一次具名调用，不在复合命令中夹带 `rm -rf`。
3. 记录命令、cwd、宿主/Pixi、测试数、断言数、failures/errors、退出码与 test.check seed；跳过与未运行分别标注，不合并成“通过”。
4. 并行施工期间，失败先看源文件 mtime 与兄弟写入；不要用一次瞬时失败推断模块结论。

## 9 Sente 1.22 与 JDK HttpServer 网关实测（2026-11-03）

`[MEASURED]` 以下事实来自 `hc.server.gateway` 的真实 HTTP 冒烟（JDK 自带
`com.sun.net.httpserver`，Ajax transport；不使用模拟 socket）：

1. `make-channel-socket!` 的 `:user-id-fn` 只接收一个参数（已附带
   `:client-id` 的 Ring request），不是 `(fn [ring-req client-id])`。写成两参
   会在首次握手时报 `Wrong number of args (1)`。
2. Ajax POST 的回复由 Sente 直接打包为 `[reply nil]`，**不回显**请求里的
   callback UUID；客户端按请求关联回调，不按 wire 字段。测试不要断言第二项。
3. 服务端接收循环不能用 `clojure.core.async/<!`：它只能在 `go` 块内使用，
   在 `future` 中会抛错并静默杀死循环。用阻塞的 `<!!`，或改用 `go` 块。
4. Ajax 的 server channel 是“一问一答”：`sch-send!` 交付响应后立即关闭并
   触发 `on-close`。长轮询没有事件时由 Sente 的 `:lp-timeout-ms` 触发
   `:chsk/timeout`；适配器自身的等待时限应略长于该值。
5. `HttpServer.sendResponseHeaders(status, 0)` 使用 chunked 编码。如果 Ring
   响应头已经带 `content-length`，必须把该长度传给 `sendResponseHeaders`，
   否则 `java.net.http.HttpClient` 会把 chunk 头当作正文（例如
   `"a\r\nhello b"`）。
6. Sente 的 1 MiB 边界是项目纪律，不是库强制值。网关在 `/chsk` POST 表单
   解析层截断入站 body，在事件分发前按实际字符串/byte[]/集合大小再次检查，
   出站 packer 也包装了 1 MiB 上限；超限事件不推进 epoch/seq。`/blobs` 走
   独立通道且默认上限 100 MiB。
7. `[MEASURED]` 同一网关用生产适配器
   `taoensso.sente.server-adapters.http-kit/get-sch-adapter` 与
   `hc.server.app.http/start!` 启动真实 http-kit。完整
   `hc.server.app/system/start!`（临时数据根、临时 workspace、空模型配置）
   实际启动后：`GET /healthz` 返回 200 且 `:gateway` 计数存在，`GET /`
   返回静态 `index.html`，`GET /chsk?client-id=smoke&handshake%3F=true`
   返回合法 `[:chsk/handshake ...]`；另用假 Owner/store 的同一网关验证缺失
   blob 返回 404。JDK 宿主与 http-kit 宿主共用同一 Ring handler，网关本身
   不绑定服务器。
8. `[MEASURED]` devops-current 在 2026-11-04T00:23 的当前树复跑网关六个
   命名空间（`feed`、`patch`、门面、`http`、`client-contract`、`security`）
   加 `commands-gateway-test`：38 tests / 265 assertions，0 failures /
   0 errors，exit 0。更早的 Pixi 子集为 26 tests / 177 assertions，0/0。
   `/tmp/pixi run test` 全量：2112 tests / 14186 assertions，0 failures /
   0 errors，wall 278.15 s，exit 0。
   `http-test`
   的真实 JDK HTTP 冒烟包含 Sente 握手、订阅基线、`STATE_DELTA`、命令回执、
   stale 订阅拒绝、blob 上传/下载/HEAD/Range/304/404/413，以及 evil Origin
   403 与同源 200。
9. `[MEASURED]` 认证边界：默认 loopback + 同源 Origin/Host。真实 JDK HTTP
   验证 `Origin: http://evil.example` 对 `/chsk` 与 `/blobs` 返回 403，同源
   Origin 与无 Origin 的 loopback 请求返回 200。`Origin: null`、畸形 Origin、
   DNS rebinding（Host/Origin 同为 evil.example）均拒绝。应用已有
   `/attachments`、workspace files、`/speech` 的 `gateway-auth/wrap`；网关
   还默认拒绝浏览器提交 `:review/submit`、`:board/command`、`:report/ack`、
   `:report/register`、`:session/issue-epoch`。`:subscription-id` 在
   command/receipt/ack/resync 上可选；提供时必须匹配当前订阅，省略时使用
   当前订阅，无当前订阅才返回 `:STALE_SUBSCRIPTION`。devops-current 在
   2026-11-04T00:25 以真实应用（127.0.0.1:17781）复核：`/healthz` 无
   Origin 200、evil Origin 403；`/` 无 Origin 200；`/chsk` evil 403、同源
   200；`/attachments` evil 403。
10. `[MEASURED]` 2026-11-04 全量 2227/14797 的单失败
   `gateway_test.clj` `command-domain-refusal-becomes-a-custom-error` 根因：
   gateway `command-receipt` 传 OwnerPort query map，测试 fake owner 直接把
   map 交给 `hc.server.commands/receipt-for`，而后者只接受 string/vector，
   因此查不到以 string 存储的 receipt。修复：`receipt-for` 接受 query map，
   依次尝试 `:command-key`、`[scope|session-id command-id]`、bare
   command-id；字符串/向量行为不变。命令接受与持久化本身没有失败。
11. `[INFERENCE]` Sente 1.22.0 的 `make-channel-socket!` 用
    `(truss/have [:or set? #{:all}] allowed-origins)` 校验该选项：传 vector
    会在启动时抛断言，不是静默忽略。`allow-origin?` 在给定 set 时还会拒绝
    既无 `Origin` 也无 `Referer` 的请求，因此不能把应用配置的允许 origin
    集合直接交给 Sente 的重复检查。网关保留 `:sente-allowed-origins :all`，
    由 `:authorized?-fn` 执行 `auth/authorize`；`hc.server.app.config` 在
    启动前把配置的 `:allowed-origins` 规范成 set 或 `:all`。
12. `[INFERENCE]` 安全审查发现 `:allow-remote? true` 的默认策略把 Host/Origin
    当成了远程认证：远程客户端可伪造 `Host: 127.0.0.1` 且不带 Origin 通过。
    修复为 fail-closed：`auth/authorize` 默认只接受 loopback；`:allow-remote?
    true` 必须同时提供 `:authorized?-fn` 或 `:authorize-request`，否则
    `sente/start!` 抛 `:REMOTE_REQUIRES_AUTH`；应用 EDN 配置直接拒绝
    `:allow-remote? true`。Host/Origin 只承担浏览器 CSRF/DNS rebinding 检查，
    不是认证。
13. `[INFERENCE]` http-kit 2.8.0 的 `server-status` 返回
    `:stopped`/`:running`/`:stopping`；`server-stop!` 在已停止或正在停止时
    返回 nil，在开始停止时返回 promise。`hc.server.app.http/stop!` 等待
    promise 或超时后读取 status，只有 `:stopped` 才返回 `:stopped? true`；
    `:running`/`:stopping` 视为失败，`system/stop!` 因此不关闭 store。
14. `[MEASURED]` 2026-11-05 E2E02：Sente Ajax 把队列中的多个出站事件合并为
    一个 pack；1 MiB 边界作用于整个 pack，而不是单个事件。app 日志中的失败
    批量为 1,194,776 bytes，其中包含多个低于边界的 `STATE_SNAPSHOT` 与
    command receipt。应用发布器每 500 ms 重发完整快照时，积压批量反复超过
    1 MiB，`BoundedPacker` 拒绝整批，快照无法送达。修复方向是发布增量并
    跳过无变化 tick，不是提高边界或丢弃消息正文。

### 8.6 Swarm dashboard CLJS 渲染验证（2026-11-03）

`[MEASURED]` `test/js/verify_swarm_ui.sh` 用 `cljs.main` 编译
`hc.client.swarm.ui-test`，以 `react-dom/server` 渲染真实
React/Framework7 元素树，再用 Node 运行。结果：6 tests / 55 assertions，
0 failures / 0 errors，退出码 0；渲染 HTML 约 18.6 KB，包含任务树、等待链、
槽位、holder/W、完整 join 报告、ARIA 标签与响应式 CSS。

`[LIMITATION]` `cljs.main` 在 `:target :nodejs` 下会把 CommonJS npm 模块包装
成文件内局部 `var module$...`，跨文件引用时变成 `undefined`；直接
`:foreign-libs` 处理 `framework7-react` 的 ESM 相对导入也会按仓库根而非包
目录解析。验证脚本因此使用 `test/js/*_shim.js` 注入**同一个** React /
ReactDOM / Framework7 实例，并在编译后用
`test/js/run_swarm_ui_tests.cjs` 把生成的局部 namespace 提升到
`goog.global`。这是验证专用适配，不是生产构建路径；生产浏览器构建走
`shadow-cljs :app`，由 shadow 直接解析 npm 模块。不要把这些 shim 引入
`src/hc/client/swarm/ui.cljs`。

`[INFERENCE]` 同一机制也解释了隔离尝试中的 `ERR_INVALID_PACKAGE_CONFIG`：
若 `:foreign-libs` 的 `:file` 指向 `node_modules/...`，Closure 会把包内
`package.json` 当作 JS 模块写入输出树（如
`out/node_modules/react-dom/package.json`），Node 读取该文件时得到非 JSON
内容并拒绝加载。验证 harness 因此只使用 `test/js/*_shim.js`，由 runner 注入
真实 npm 模块；`react-dom/client` 同样走
`test/js/react_dom_client_shim.js`。

## 10 受管浏览器 CDP 实测（2026-11-03）

`[MEASURED]` 环境：宿主 Clojure 1.12.5.1664、OpenJDK 26.0.2.1、Google Chrome
153.0.8010.36；测试命令为标准 `clojure -M:test:server -n <namespace>`。

1. `[MEASURED]` `hc.server.browser-test`：18 tests / 195 assertions，0
   failures / 0 errors。覆盖纯状态、敏感字段、页面卡上限、权限范围、假端口
   导航/观察/动作、操作员输入暂停、恢复重读、重复过期目标交回操作员、登录
   页暂停、恢复重读失败保持暂停、未知结果不重试、关页保留卡片、停止不关页、
   当前标签页权限、真实页面输入自动暂停与执行器状态映射。
2. `[MEASURED]` `hc.server.browser-cdp-smoke-test`：1 test / 53 assertions，
   0 failures / 0 errors。真实启动隔离 headless Chrome，经 CDP 打开本地
   HTTP 页面，观察标题/正文/节点，输入普通文本，拒绝密码输入并暂停，恢复后
   重读，点击按钮观察同文档变化，显式接管后动作被拒、恢复后页面仍保留已输入
   文本与 URL，检测 404 与拒绝连接，保存历史截图，停止后关页仍保留卡片。无
   Chrome 可执行文件时该测试不定义，不以假端口替代。
3. `[MEASURED]` JDK `java.net.http.WebSocket` 的 `WebSocket.Listener` 每收到
   一条文本或二进制消息后必须再次调用 `request(1)`；只在 `onOpen` 请求一次会
   导致第二条 CDP 响应永远不投递，表现为 `CDP request timed out`。
4. `[MEASURED]` Chrome 的 `/json/new` 必须用 `PUT`；`DevToolsActivePort` 第一
   行是调试端口，第二行是浏览器 WebSocket 路径。启动参数需要
   `--remote-debugging-port=0`、临时 `--user-data-dir` 与
   `--remote-allow-origins=*`。
5. `[MEASURED]` `Page.captureScreenshot` 返回 base64 PNG；主文档 HTTP 状态从
   `Network.responseReceived` 的 `type == "Document"` 事件取得，401/403/404
   分别映射为 `:login-required`、`:access-denied`、`:not-found`。端口 1 这类
   地址返回 `net::ERR_UNSAFE_PORT`，应归为 `:navigation-failed`；真正的拒绝
   连接返回 `ERR_CONNECTION_REFUSED`，归为 `:connection-failed`。
6. `[MEASURED]` 动作通过观察时写入的 `data-hc-ref` 在当前 DOM 中重新解析；
   点击使用当前元素矩形上的 `Input.dispatchMouseEvent`，输入使用
   `Runtime.evaluate` 聚焦后 `Input.insertText`。导航、操作员输入、接管和恢复
   都会递增页面代次并清空最近观察，旧目标只能得到 `:stale-target`。
7. `[MEASURED]` `hc.server.browser-app-integration-test`：1 test / 24
   assertions，0 failures / 0 errors。真实启动 `hc.server.app.system`，配置
   `[:browser :endpoint]` 指向隔离 Chrome；验证应用为会话创建浏览器服务、
   `:browser/*` 命令适配器可打开/观察/输入、显式接管阻止动作、恢复保留输入
   文本与页面状态，模型工具执行器 `browser_observe`/`browser_type` 经
   `routing-executor` 命中同一服务，并暴露 snapshot/cards/health。该测试只读
   应用装配，不改 `app/system.clj`。
8. `[LIMITATION]` 观察只覆盖顶层可见文档；跨域 iframe 不读取，报告保留
   `:iframes-not-read`。敏感字段识别是 best-effort；页面卡截图标记为历史，
   不宣称是当前页面。权限是会话内临时状态，重启、分支或迁移不继承。
9. `[MEASURED]` `hc.server.browser` 提供两种组合缝：把 `:browser` 加入 Owner
   执行器白名单后直接用 `executor`；或保持 `:tool` 种类，用
   `routing-executor` 把 `:tool-name "browser_*"`/`:executor :browser` 的
   载荷转给浏览器，其余转给程序运行时。`tool-specs` 返回 14 个模型工具定义；`permission`、`takeover`、`resume`、`operator-input` 属于操作员/工作台，模型执行器默认以 `:operator-only` 拒绝。
   应用装配已采用 `:tool` + `routing-executor`，并用 `browser/command!`
   处理 `:browser/*` 工作台命令。工作台命令适配器保留 `:browser/act` 的具体
   `:action`；模型执行器仍拒绝 operator-only 动作。`browser/health` 已并入
   `/healthz` 的 `:browser` 与 `:browser-errors`。
10. `[MEASURED]` 客户端：`hc.client.browser-model-test` 9 tests / 41
    assertions，0 failures / 0 errors。覆盖 snapshot 投影、同观察选择保留、
    outbox/回执终态、单飞与 session-id 门、敏感字段、接管门、表单 `:value`
    事件和视图树。`test/js/verify_browser_ui.cjs` 在真实 Chrome 中启动
    工作台、本地 HTTP 页与应用（动态端口）：启用权限、打开/观察、选择邮箱并
    输入 `ui@example.test`、点击 Advance 后观察 `UI Done`、显式接管后动作控件
    消失/禁用、恢复重读后仍见输入文本、页面卡 8 条；结果 `SMOKE OK`，退出码
    0。2026-11-04T08:59:31 的 release 退出 0，`main.js` sha1
    `f781ee1763292bea8a86782a935df594c386e233`；live 无
    `explicit :session-id` 错误。
11. `[MEASURED]` 浏览器 bridge 曾用原始 `:ui/selected-session-id`，在材料
    路由上为 nil，导致 `:browser/*` 命令缺 `:session-id` 并被服务器拒绝，
    形成轮询刷屏。修复为 `workbench.model/selected-session-id` 并在模型门中
    要求非空 session-id；快照请求单飞，失败后 `fetch-blocked?` 终止自动重试，
    只有显式刷新或会话/连接重置才重试。只读 `:browser/snapshot`/
    `:browser/cards` 不再触发服务器 revision，避免 fetch/notify 循环。
12. `[MEASURED]` 真实人工输入：CDP 在受控文档安装 `Runtime.addBinding`
    `__hcOperatorInput` 与 `pointerdown/keydown/input/click/wheel` 监听；
    `Runtime.bindingCalled` 投递 `{:tab-id :kind}`，服务只在
    `:agent-active`/`:read-only` 时自动暂停。代理自身 `act!` 期间设置
    `window.__hcAgentInput`，避免把自己的 CDP 输入误判为人工。live 脚本
    直接对受管标签点击邮箱并输入 `human@example.test`：工作台显示
    `检测到手动操作`，type 控件消失（计数 0），恢复后观察仍见
    `human@example.test`；随后代理 type/click、显式接管/恢复继续通过，
    `SMOKE OK`。

## 11 文档提取与本地 OCR 实测（2026-11-03）

`[MEASURED]` 环境：宿主 Clojure 1.12.6、OpenJDK 26.0.2.1、Linux x86_64；
命令为 `clojure -M:test:server -d test/hc/server/documents`。

1. `[MEASURED]` 59 tests / 257 assertions，0 failures / 0 errors。覆盖真实
   UTF-8/UTF-16 文本、真实 PDFBox 三页混合 PDF（文本页 + 图片页 + 文本页）、
   全扫描 PDF、加密 PDF（无密码/错密码/正确密码/权限禁止）、损坏 PDF、
   EXIF orientation 6、透明 PNG、JPEG 透明压平、真实 Tesseract 图片与 PDF
   扫描页识别、OCR 空结果/缺语言/非法 PSM/超时、`:native` 不读文件、MIME
   伪装与页码身份。
2. `[MEASURED]` `org.apache.pdfbox/pdfbox` 3.0.5：`PDRectangle` 位于
   `org.apache.pdfbox.pdmodel.common`，不是 `org.apache.pdfbox.pdmodel`；
   `Loader/loadPDF(byte[] [, password])` 是入口；加密且无密码抛
   `InvalidPasswordException`。`PDFTextStripper` 逐页设置 `startPage`/
   `endPage` 可取单页文本；扫描页返回空字符串，必须显式标为 `:no-text`。
3. `[MEASURED]` PDFBox 3 的 `PDPageContentStream/drawImage` 坐标参数必须显式
   为 `float`，测试夹具里传 Clojure Long 会触发
   `No matching method drawImage ... taking 5 args`。这不是产品路径，但复检
   夹具时先按此处理。
4. `[MEASURED]` JDK 的 `javax.imageio.ImageWriter` 没有
   `write(IIOImage, ImageWriteParam)` 两参重载；JPEG 质量必须调用
   `write(nil, IIOImage, param)` 三参形式。两参调用会在运行时抛
   `No matching method write ... JPEGImageWriter`，并被误分类为图像解码失败；
   已修正为三参并增加 `:IMAGE_ENCODE_FAILED` 边界。
5. `[MEASURED]` JavaCPP Tesseract 需要“无 classifier 的 API jar + 对应平台
   classifier jar”同时存在；只加 classifier 只有原生库、没有
   `TessBaseAPI` 类。平台 jar 每个约数 MiB，五个平台合计可接受；不使用
   `-platform` 聚合包以免带入 Android 等无关产物。
6. `[MEASURED]` Tesseract 5.5.3 + Leptonica 1.87.0 在 Linux x86_64 上通过
   `Loader/load` 装载，无需系统 `tesseract`，无需 GPU。`pixReadMem` 读 PNG
   后先 `pixSetResolution(pix, 300, 300)` 再 `SetImage`，可避免
   “Estimating resolution”日志；`SetSourceResolution` 必须在 `SetImage` 之后，
   否则打印 “Please call SetImage before SetSourceResolution”。
7. `[MEASURED]` `tessdata_fast` 的 `eng.traineddata` 约 4.0 MiB、
   `chi_sim.traineddata` 约 2.4 MiB；首次调用复制到
   `${java.io.tmpdir}/herdchestra-tessdata-5.5.3`。生成图
   “Herdchestra OCR 2048” 与 PDF 扫描页 “Scanned Herdchestra 2048” 均被
   正确识别，空白色图片得到空文本并显式判为 `:OCR_EMPTY`。
8. `[LIMITATION]` JDK 26 对 JavaCPP 的原生装载打印 restricted-method 警告
   （`System::load`）；功能正常。需要消除警告时由启动参数
   `--enable-native-access=ALL-UNNAMED` 处理，不由本包修改全局 JVM 参数。
9. `[LIMITATION]` JDK 内置 ImageIO 没有 WebP 解码器；WebP 会得到
   `:IMAGE_UNREADABLE`，不按扩展名假装支持。DOCX/PPTX/EPUB 同样明确不在
   当前提取范围。
10. `[MEASURED]` 额外用 Pixi 环境自带的真实 CUPS 样例做冒烟：
    `.pixi/envs/default/share/cups/ipptool/` 下的 `onepage-a4.pdf`（1 页）、
    `document-a4.pdf`（4 页）与 `testfile.jpg` 实际是 gzip 压缩；解压后
    PDFBox 提取出正常 Lorem ipsum 文本，Tesseract 从 835×1280 JPEG 中识别出
    “Test Images / Grayscales / 50% / 100% Red + 50% Black …”。该外部样例
    只作手工冒烟，不作为测试前提，避免依赖 Pixi 环境内容。

## 12 应用启动与维护入口

`[MEASURED]` 2026-11-03：

发布版本 `0.0.1` 的唯一运行时源是 `src/hc/version.cljc`；`--help` 与
`/healthz` 读取它。用户可见的启动、Pixi 包装与发布条件见根目录
`RUNNING.md`。`pixi.toml`、`package.json` 必须与 `hc.version` 保持同值。

```bash
# 启动集成应用（所有已装配组件）
clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 7575 \
  --data-dir ~/.local/share/herdchestra \
  --workspace-root /path/to/workspace \
  --static-root resources/public

# 启动自检：打开真实存储、创建默认会话、绑定 HTTP/Sente、打印健康后停止
clojure -M:server -m hc.server.app --check --port 0

# 离线备份/恢复（必须先停止应用；live Sente restore 一律拒绝）
HC_BACKUP_PASSWORD=... clojure -M:server -m hc.server.backup.cli restore \
  --db-path <data-dir>/db --data-dir <data-dir> --backup-id <id> --mode merge

# 集成系统测试
clojure -M:test:server -n hc.server.app.system-test
```

已装配：Datalevin 存储、会话持久化、每会话 Owner、Mission 端口、模型端口（LangChain4j OpenAI/Ollama）、workspace/program/shell 执行器、Sente/AG-UI 网关、静态工作台、设置、materials、diagnostics、scheduler、backup（live 只读/写，restore 离线）、extensions、speech、search、documents、attachments、artifacts、files。

已知边界：客户端 composer 草稿刷新属于 workbench；模型默认流式 SSE 已接线但本轮真实模型烟测使用 `:model-stream? false` 的非流式 OpenAI 兼容路径；live restore 只提供离线 CLI；浏览器真实 UI 端到端需 devops 复跑。

### 12.1 受监督恢复入口（2026-11-04，已复跑）

`hc.server.app` 现在把 `{:handle! ... :status ...}` 维护处理器传给
`system/start!`，由 `hc.server.app.restore-supervisor` 负责：

1. `:backup/maintenance-restore` 先在活应用内预览并要求 `:confirm? true`；
   若客户端带上 `:preview-fingerprint`，与服务端新预览不一致时在停机前
   拒绝 `:BACKUP_PREVIEW_STALE`。accepted 回执先返回，预览/暂存在 worker
   内完成；预检失败只写 `:failed` journal 并重新开门，不停机。
2. 选定备份从真实 target 暂存为本地 verified target；journal 写入
   `<data-dir>/maintenance/state.edn`（原子 rename、SHA-256、owner-only）。
3. 关闭命令门，排空在途 handler，`system/stop!` 必须返回
   `:stopped? true` 且 `:store-closed? true`，否则不恢复。
4. `hc.server.backup.offline/restore!` 以 `:quiescent? true` 和预览
   fingerprint 恢复；恢复点创建失败或计划变化时在写入前失败。
5. `system/start!` 重建服务并检查 health；客户端重连后以
   `:backup/maintenance-status` 的 `:complete` / `:failed` /
   `:restart-failed` / `:stop-failed` 为唯一终态。

复跑命令：

```bash
clojure -M:test:server -n hc.server.app.restore-supervisor-test
clojure -M:test:server -n hc.server.backup.fingerprint-test
clojure -M:test:server -n hc.server.app.restore-supervisor-integration-test
```

集成测试使用临时 data/workspace/static 根和 `:port 0`；浏览器往返需固定
空闲端口。

`[MEASURED]` 2026-11-04（devops 复跑，async receipt 树）：focused pair
`hc.server.app.restore-supervisor-test` + `hc.server.backup.fingerprint-test`
与 `hc.server.app.restore-supervisor-integration-test` 合计
= 16 tests / 77 assertions，0 failures / 0 errors；`hc.server.app.system`
重新可编译。预览/暂存已移入 worker，accepted 回执先返回；错误密码用例为
accepted → failed。客户端已在本地 maintenance request 补 `:maintenance-id`
与 `:preview-fingerprint`；router 已改为回执先发送、in-flight 后递减，敏感
命令 dedup 只存摘要并脱敏回执。

`[MEASURED]` 2026-11-04 supervised full E2E（devops-final）：服务端成功；
drop 后客户端状态已有 `:maintenance-request {:maintenance-id "..."
:phase :done :verification {:ok true ...} :restart {:ok? true :url ...}}`，
journal 在 `<data-dir>/maintenance/state.edn`。脚本最初报 “maintenance id
null” 是因为它读了 `:ui/maintenance-request`，而实体键是未限定
`:maintenance-request`（状态仍嵌套在 `{:ui {...}}`）；这是测试查找错误，
不是产品超时。脚本已修正为 `(:ui backup)` → `:maintenance-request`
两级读取，待下一次 devops 复跑确认全绿。

`[MEASURED]` 2026-11-04（devops-current 全量）：2112 tests / 14186
assertions，0 failures / 0 errors；`--check` 返回 `{:ok true :store {:open?
true} :gateway {:epoch 1 :seq 1} :publish-error nil}`；restore focused pair +
integration 16/77 0/0。

`[MEASURED]` 2026-11-04（敏感 dedup/restore）：`hc.server.commands-sensitive-test`
= 2 tests / 24 assertions，0/0；sensitive+restore = 5 tests / 60 assertions，
0/0。嵌套 `{:token ...}` 命中 secret 删除；旧 `different` 是测试密码与错误
文案撞词，不是脱敏遗漏；supervisor 状态键保留。

Fleet supervisor quiescence：当前 `system.clj` 已包含 `:fleet-supervisor` 与
`:fleet-attempts`，并在 `:fleet`、scheduler、Owners、model ports/store 之前
调用 `fleet-supervisor/stop!`；stop 返回 `:stopped? true :attempts n
:cancelled n`，per-attempt cancel 异常计数不隐藏，`handle-event!` 在 stopped
后拒绝 late event（`:SUPERVISOR_STOPPED`）。据此接受为 writer quiescence：
若 `cancelled < attempts`，模型计算可能继续但已无法写入。devops-current
已在当前工作树（system.clj mtime 01:16:02）复核并撤回旧 gap 报告：
`:fleet-supervisor` 在 system map，stop 顺序在 `:fleet`/scheduler/Owners/
model ports/store 之前，`:stopped? true/:attempts/:cancelled` 接受为 writer
quiescence。

离线 roundtrip 测试回归：新 app-command fence 在目标 store 没有 prior gate
时要求显式 `:no-prior-app-commands? true`；`storage_adapter_test` 的
`offline-entry-point-roundtrips-real-store` 已补该参数（其余 restore 用例
本就传），产品 CLI 已有 `--no-prior-app-commands true`。

`[MEASURED]` 2026-11-04（devops-last 复跑）：`storage-adapter-test` +
`command-epoch-test` + `crypto-test` = 19 tests / 165 assertions，0 failures /
0 errors；`storage-adapter-test` 单独 3 tests / 88 assertions 0/0。显式
`:no-prior-app-commands? true` 解决了离线 roundtrip，无剩余失败。

`restart-recovery` smoke 回归：`recover!` 的结果总是包含 `:pending-retries`
键（空向量表示没有 owner-owned retry）；旧断言用 `contains?` 会误报。已改为
`empty?`，legacy `:retry-wait` 仍按合同留在 `:blocked-retries`。待 devops
复跑 `hc.server.recovery.restart-smoke-test`。

## 13 0.0.1 发布验证实测（2026-11-05）

本节只记录本轮实际运行过的命令与结果。E2E 的逐条结论见
`plans/e2e-0.0.1-results.md`，发布清单见
`plans/release-notes-0.0.1.md`。

根 `AGENTS.md` §8.5 现要求 22 条 E2E 全部自动化，并配套 mock 模型服务与
OAuth/WebDAV/S3 mock 外部服务；手工验收只用于 mock 无法覆盖的宿主平台差异。
验收按 §7.4 八段用户旅程（首次启动、日常对话、长任务、中断与恢复、
历史与知识、扩展能力、导出/备份/迁移、设置与诊断）组织，旧八组 UI 规范
不再是验收单位。
本节记录的真实端点/浏览器跑法属于手工探索与回归证据，不是新验收口径；
W10 的自动化结果出来前，不得据此判 PASS。

### 13.1 首轮 E2E 模型端点：stub；01/02 后续已用真实 Ollama 复跑

`[MEASURED]` 2026-11-05 08:11 首轮批次的环境：

- 本机 Ollama `curl --max-time 3 http://127.0.0.1:11434/api/tags` 不可达。
- 环境变量只发现 `OLLAMA_API_KEY`；没有 `OPENAI_*`、`ANTHROPIC_*`、
  `MODEL_*`、`BASE_URL`、`CDP_URL`、`PLAYWRIGHT*` 名称。
- 模型端点使用本地 OpenAI-compatible stub：`http://127.0.0.1:18081/v1`，
  模型名 `stub-model`。`GET /v1/models` 返回该模型；`POST
  /v1/chat/completions` 返回固定文本 “这是本地 stub 的固定回复。Hello from
  stub.”。

证据：`/tmp/hc-e2e/20261105-0811-exec/env/probe.log`、
`/tmp/hc-e2e/stub/server.js`、`plans/e2e-0.0.1-results.md` §0 与 §7。

`[LIMITATION]` 本节只描述首轮批次：该批次 01/02 的模型端点是 stub，不能把
stub 的固定回复当作真实模型验收。01/02 后续已用真实本地 Ollama
（`127.0.0.1:11434/v1`，`qwen3.6:27b`）复跑，证据见
`/tmp/hc-e2e-real/artifacts/e2e01/e2e01-result.json`、
`/tmp/hc-e2e-real/artifacts/e2e02/e2e02-result.json` 与
`plans/e2e-master-status-0.0.1.md`。真实云供应商的质量、费用、流式/错误行为
仍未验证。

### 13.2 Chrome 与 Playwright 路径

`[MEASURED]`：

- Chrome：`/usr/bin/google-chrome` 与 `/usr/bin/google-chrome-stable`，
  版本 `Google Chrome 153.0.8010.36`。
- 仓库内 `require('playwright')` 不可解析；Bun 缓存有
  `playwright@1.64.1@@@1` 与 `playwright-core@1.64.1@@@1`。
- E2E 用 `/tmp/hc-pw/node_modules` 软链 + `NODE_PATH` 加载：

```bash
NODE_PATH=/tmp/hc-pw/node_modules ./test/js/verify_conversation_selection_browser.sh
NODE_PATH=/tmp/hc-pw/node_modules node test/js/verify_browser_ui.cjs
```

`[LIMITATION]` `test/js/verify_browser_ui.cjs` 硬编码
`/usr/bin/google-chrome`，不读 `HC_CHROME`；换机需临时副本。证据：
`/tmp/hc-e2e/20261105-0811-exec/env/playwright.log`、
`plans/e2e-0.0.1-results.md` D10。

### 13.3 ENOSPC 注入：已执行

`[MEASURED]` E2E 18 已执行真实 ENOSPC 注入：用 `unshare -Ur -m` 建立用户+
挂载命名空间，把 `tmpfs size=64M` 挂到 data-dir，无需 root；`dd` 填满后
附件上传返回 HTTP 500 `ATTACHMENT_STORE_FAILED`、会话提交失败不冒充成功、
已保存历史仍可读、释放空间后同一保存/提交恢复。证据：
`enospc/evidence/e2e18-pass.json`
（`/tmp/hc-e2e-harness/enospc/evidence/e2e18-pass.json`）；复检命令见 §14.3。

`[LIMITATION]` 首轮 08:11 批次没有执行真实 ENOSPC，只有草稿保存失败、损坏
库阻断启动、维护恢复失败不冒充成功等相邻取证；后续补测已覆盖真实 ENOSPC，
不要把首轮状态当最终状态。无法使用 `unshare`/tmpfs 的环境仍可能 NOT RUN。

### 13.4 备份 CLI：早期缺陷与当前树复检

早期实跑（`plans/offline-restore-0.0.1.md`，隔离根
`/tmp/hc-offline-vzwJBd3u`）记录：

- D1：CLI 默认备份 `:encryption :none`；根因是 `offline/backup!` 无条件传
  `:encrypt? nil`，`core` 把“键存在”当成显式 `false`。
- D2：`--encrypt true` / `--include-secrets true` 报
  `expected true or false {:value nil}`；CLI 的 `select-keys` 与 `cond->`
  键不一致。
- D3：全新目标恢复报 `BACKUP_COMMAND_EPOCH_WINDOW_MISSING`；
  `--no-prior-app-commands` 与 `:no-prior-app-commands?` 键名不一致。

`[MEASURED]` 2026-11-05 当前树复检（隔离根 `/tmp/hc-docs-verify`）：

```bash
# 默认备份：现在默认加密
HC_BACKUP_PASSWORD='docs-verify-pass-123' \
  /tmp/pixi run clojure -M:server -m hc.server.backup.cli backup \
  --db-path /tmp/hc-docs-verify/db --data-dir /tmp/hc-docs-verify \
  --target-root /tmp/hc-docs-verify/backups \
  --recovery-root /tmp/hc-docs-verify/recovery \
  --extensions-root /tmp/hc-docs-verify/extensions \
  --blob-root /tmp/hc-docs-verify/blobs \
  --workspace-store-root /tmp/hc-docs-verify/workspaces \
  --now 2026-11-05T00:00:00Z
# => exit 0, :encrypted? true, :encryption :aes-gcm

# 显式加密：沿用上面的 --db-path/--data-dir/... 参数，另给
# --target-root /tmp/hc-docs-verify/backups-enc
# --recovery-root /tmp/hc-docs-verify/recovery-enc
# 并追加 --encrypt true
# => exit 0, :encrypted? true, :encryption :aes-gcm

# 恢复到新目录：可直接给完全空目录；CLI 会创建 store
# 也可先用 (storage/open-store {:path "..." :create? true}) 预建 store
# 再沿用上面的路径参数（改指向 target4），追加
# --backup-id <id> --mode replace
# => exit 0
```

结果：D1/D2/D3 的早期现象在当前树已不再复现；CLI 现在把 `:encrypt` 映射为
`:encrypt?`，把 `:no-prior-app-commands` 映射为
`:no-prior-app-commands?`，并让缺省 `:encrypt?` 走“未指定→默认加密”。
restore 对没有 app gate 的新 store 默认按 `:no-prior-app-commands? true`
处理，因此不再报 `BACKUP_COMMAND_EPOCH_WINDOW_MISSING`。
`:include-secrets` 也已映射为 `:include-secrets?`，但本轮未单独复检该组合。

`[MEASURED]` 2026-11-05 最终复检（隔离根 `/tmp/hc-docs-verify-final`）：

- 全新目录 restore 直接退出 0；CLI 默认带 `:no-prior-app-commands? true`，
  不再需要手工预置 app gate。
- 同一备份再次 `--mode replace` 返回 `:already-imported`，恢复点不新增。
- 没有 `HC_BACKUP_PASSWORD` 且未显式 `--encrypt false` 时退出 1：
  `:code :BACKUP_PASSWORD_REQUIRED`。
- `:include-secrets` 的键映射仍存在，但未单独复检该组合。
- 2026-11-05 追加复测（隔离根 `/tmp/hc-prep-restore-0.0.1`）：完全空目录
  restore 退出 0，CLI 生成 `db/` 下的 store 文件，回执
  `:store-observed? true`、`:status :no-changes`（该备份无类别可导入）；
  证据 `/tmp/hc-prep-restore-0.0.1/restore-empty.log`。不需要预先
  `storage/open-store`。

证据：`/tmp/hc-docs-final-restore.log`、`/tmp/hc-docs-final-restore-repeat.log`、
`/tmp/hc-docs-final-nopassword.log`、`/tmp/hc-prep-restore-0.0.1/restore-empty.log`；
早期日志保留在 `/tmp/hc-docs-verify-*.log`。当前行为汇总见 §14.4。

### 13.5 Pixi 0.83.0 校验和

`[MEASURED]` 宿主 `PATH` 没有 `pixi`；本轮使用 `/tmp/pixi`：

| 项 | 值 |
|---|---|
| 版本 | `pixi 0.83.0` |
| 大小 | 71,427,392 bytes |
| SHA-256 | `50571451e96f4a82cf675cd83baaf66fff9b718f0aaf153900b9faf4c29bd401` |
| 来源 | GitHub Releases `v0.83.0` 的 `pixi-x86_64-unknown-linux-musl` |

复检：

```bash
/tmp/pixi --version
sha256sum /tmp/pixi
stat -c '%s' /tmp/pixi
```

`[LIMITATION]` `/tmp` 被清理后该二进制会消失；不要在不核对校验和的情况下
从任意镜像重新取得。发布门禁依赖一个临时路径，这是当前引导的脆弱点。

## 14 平台与运行时实测补充（2026-11-05）

本节只记录本轮实际测量到的平台/运行事实。除特别标注外，均为本机
linux-64 的开发/验证结果，不是 0.0.1 的发布运行时依赖。

### 14.1 本地 Ollama 真实端点

`[MEASURED]` 2026-11-05：

| 项 | 实测 |
|---|---|
| OpenAI-compatible 端点 | `http://127.0.0.1:11434/v1` |
| 模型 | `qwen3.6:27b`，27.8B Q4_K_M |
| 声明能力 | vision、completion、tools、thinking |
| `OLLAMA_CONTEXT_LENGTH` 默认 | `4096` |
| 默认上下文下的 Plan 请求 | 带 Plan 系统提示 + 工具定义的请求返回 HTTP 500 |
| 设为 `16384` 后 | 同一请求正常完成 |
| thinking 关闭 | `:options {:reasoning-effort "none"}` |
| 未关闭 thinking | 每轮约 20–95 s；`max_tokens` 只有 8 时会被 reasoning 吃光，正文为空 |
| 并发 | 3 路并发请求安全；未验证更高并发 |

复检命令：

```bash
curl -s http://127.0.0.1:11434/api/tags
curl -s http://127.0.0.1:11434/v1/models

# 需要重启 Ollama 服务时，显式设置上下文长度
OLLAMA_CONTEXT_LENGTH=16384 ollama serve

# 原生 chat 接口关闭 thinking，避免短 max_tokens 被 reasoning 吃光
curl -s http://127.0.0.1:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.6:27b","stream":false,
       "messages":[{"role":"user","content":"ping"}],
       "options":{"reasoning-effort":"none","num_predict":8}}'
```

适用范围：本机开发/验证。发布不要求 Ollama，也不把该端点写入默认配置。

### 14.2 受管浏览器工装

`[MEASURED]` 2026-11-05：

| 项 | 实测 |
|---|---|
| Chrome | `Google Chrome 153.0.8010.36` |
| Playwright | `1.64.1` |
| 加载方式 | `NODE_PATH=/tmp/hc-pw/node_modules` |
| CDP 启动 | `--remote-debugging-port=0`，从 `<user-data-dir>/DevToolsActivePort` 第一行读取端口 |
| 进程存活 | 必须用 `setsid` 启动 Chrome，避免父 shell/测试进程退出时带走浏览器 |
| headless 麦克风 | `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream --use-file-for-fake-audio-capture=<wav>` |

复检命令：

```bash
/usr/bin/google-chrome --version
NODE_PATH=/tmp/hc-pw/node_modules node -e 'console.log(require("playwright/package.json").version)'

setsid /usr/bin/google-chrome \
  --headless=new --remote-debugging-port=0 \
  --user-data-dir=/tmp/hc-chrome-profile \
  --no-first-run --no-default-browser-check \
  --use-fake-device-for-media-stream \
  --use-fake-ui-for-media-stream \
  --use-file-for-fake-audio-capture=/tmp/hc-fake-mic.wav \
  about:blank >/tmp/hc-chrome.log 2>&1 &
head -1 /tmp/hc-chrome-profile/DevToolsActivePort
```

`test/js/verify_browser_ui.cjs` 仍硬编码 `/usr/bin/google-chrome`，不读
`HC_CHROME`；换机需要临时副本或改脚本。适用范围：受管浏览器/E2E 工装，
不是产品运行时依赖。

### 14.3 ENOSPC 注入（无需 root）

`[MEASURED]` 2026-11-05：

- 用 `unshare -Ur -m` 建立用户+挂载命名空间，把 `tmpfs size=64M` 挂到
  data-dir；无需 root。
- `dd` 填满到 100% 后：
  - 附件上传返回 HTTP 500，错误码 `ATTACHMENT_STORE_FAILED`；
  - 会话提交失败，不冒充成功；
  - 已保存历史仍可读；
  - 释放空间后同一保存/提交成功；
  - 浏览器 localStorage 配额触发时显示“草稿暂未保存”。

复检命令：

```bash
mkdir -p /tmp/hc-enospc-data
unshare -Ur -m sh -c '
  mount -t tmpfs -o size=64M tmpfs /tmp/hc-enospc-data
  # 在同一命名空间内启动应用/测试，data-dir 指向 /tmp/hc-enospc-data
  HC_DATA_DIR=/tmp/hc-enospc-data /tmp/pixi run clojure -M:server \
    -m hc.server.app --host 127.0.0.1 --port 0 \
    --workspace-root /tmp/hc-enospc-work \
    --static-root resources/public
'
# 另一个终端或同一脚本中填满文件系统
dd if=/dev/zero of=/tmp/hc-enospc-data/fill bs=1M status=progress
```

证据：`enospc/evidence/e2e18-pass.json`
（`/tmp/hc-e2e-harness/enospc/evidence/e2e18-pass.json`）；该回执记录
`kernel_enospc`、`attachment_store_enospc`、会话提交失败、历史仍可读与释放
空间后恢复。

适用范围：发布前故障注入验证。不要把首轮“草稿保存失败注入”写成最终 ENOSPC
结论；本节是后续真实 ENOSPC 实测。

### 14.4 备份 CLI 当前行为（修复后）

`[MEASURED]` 2026-11-05，隔离根 `/tmp/hc-docs-verify-final`：

| 行为 | 实测 |
|---|---|
| 默认备份加密 | `:encrypted? true`、`:encryption :aes-gcm` |
| `--encrypt true` | 解析成功，退出 0，AES-GCM |
| 无密码且未显式 `--encrypt false` | 退出 1，`:code :BACKUP_PASSWORD_REQUIRED` |
| 全新目录 restore | 退出 0；CLI 默认带 `:no-prior-app-commands? true` |
| 完全空目录 restore | 退出 0；CLI 创建 store；空备份回执 `:store-observed? true`、`:status :no-changes`（`/tmp/hc-prep-restore-0.0.1/restore-empty.log`） |
| 重复 `--mode replace` | 返回 `:already-imported`，恢复点不新增 |
| `:include-secrets` | 键映射存在，未单独复检 |

复检命令：

```bash
BID=$(grep -o ':backup-id "[^"]*"' /tmp/hc-docs-verify-enc.log | head -1 | cut -d'"' -f2)

# 默认加密
HC_BACKUP_PASSWORD='docs-verify-pass-123' \
  /tmp/pixi run clojure -M:server -m hc.server.backup.cli backup \
  --db-path /tmp/hc-docs-verify-final/target/db \
  --data-dir /tmp/hc-docs-verify-final/target \
  --target-root /tmp/hc-docs-verify-final/backups \
  --recovery-root /tmp/hc-docs-verify-final/target/recovery \
  --extensions-root /tmp/hc-docs-verify-final/target/extensions \
  --blob-root /tmp/hc-docs-verify-final/target/blobs \
  --workspace-store-root /tmp/hc-docs-verify-final/target/workspaces \
  --now 2026-11-05T00:00:00Z

# 全新目录恢复
HC_BACKUP_PASSWORD='docs-verify-pass-123' \
  /tmp/pixi run clojure -M:server -m hc.server.backup.cli restore \
  --db-path /tmp/hc-docs-verify-final/fresh/db \
  --data-dir /tmp/hc-docs-verify-final/fresh \
  --target-root /tmp/hc-docs-verify-final/backups \
  --recovery-root /tmp/hc-docs-verify-final/fresh/recovery \
  --extensions-root /tmp/hc-docs-verify-final/fresh/extensions \
  --blob-root /tmp/hc-docs-verify-final/fresh/blobs \
  --workspace-store-root /tmp/hc-docs-verify-final/fresh/workspaces \
  --backup-id "$BID" --mode replace

# 显式加密：在默认 backup 命令上追加 --encrypt true
# => exit 0, :encrypted? true, :encryption :aes-gcm

# 重复 replace：再次执行上面的 restore 命令
# => :status :already-imported，恢复点不新增

# 无密码：同一 backup 命令不设 HC_BACKUP_PASSWORD，也不传 --encrypt false
env -u HC_BACKUP_PASSWORD /tmp/pixi run clojure -M:server \
  -m hc.server.backup.cli backup ... 
# => exit 1, :code :BACKUP_PASSWORD_REQUIRED
```

证据：`/tmp/hc-docs-final-restore.log`、
`/tmp/hc-docs-final-restore-repeat.log`、`/tmp/hc-docs-final-nopassword.log`。
适用范围：离线 CLI；活服务在线整库恢复仍拒绝，必须停机/维护窗口。

### 14.5 属性测试规模

`[MEASURED]` 2026-11-05（`plans/property-test-coverage-0.0.1.md`）：

| 指标 | 值 |
|---|---:|
| 有属性测试引用的模块 | 85 / 104 |
| 属性测试文件 | 60 |
| `defspec` | 134 |
| `quick-check` 型 `deftest` | 36 |
| 属性检查总数 | 170 |

组合验证 `137 tests / 1566 assertions`，0 failures / 0 errors，退出 0。
仍有 8 个确定性模块与 11 个无直接测试模块；不得写成“全部覆盖”。

### 14.6 适用范围

以上 Ollama、Chrome/Playwright、ENOSPC 注入均为本机开发/验证工装；
0.0.1 的发布运行时仍以 Pixi 的 JDK 21/Clojure 1.12.6/Node 22 为准。
备份 CLI 行为属于发布产品路径，按 §14.4 的复检命令核对。

## 15 小应用生成、存储隔离与客户端 bundle 实测（2026-11-05）

### 15.1 小应用生成路由与超时

`[MEASURED]`：

| 项 | 值 |
|---|---|
| 路由 | `POST /api/workspace/miniapp-generate`（JSON 请求，EDN 回执） |
| 默认模型截止 | 300 s |
| 上限 | 600 s |
| 请求覆盖 | `:timeout-ms`（1..600000；超过 600000 截到 600 s） |
| 三次真实生成 | 218.82 s、292.80 s、79.06 s |
| 旧 180 s 截止 | 会误杀 218.82 s / 292.80 s 两次 |
| 超时回执 | `:MODEL_TIMEOUT`，HTTP 504，`:error {:kind :timeout :remedy :retry}` |

请求示例：

```json
{"prompt":"做一个记录读书条目的小应用，含输入框与列表，数据存本地","filename":"reading-log"}
```

复检：

```bash
curl -sS -D /tmp/hc-miniapp-timeout/curl.meta \
  -H 'Content-Type: application/json' \
  -d @/tmp/hc-miniapp-timeout/request.json \
  http://127.0.0.1:<port>/api/workspace/miniapp-generate
cat /tmp/hc-miniapp-timeout/response.edn
```

证据：`/tmp/hc-miniapp-timeout/request.json`、`response.edn`、`curl.meta`、
`app.log`、`work/miniapps/reading-log.html`。连接自身的
`:timeout-ms` 也必须至少等于期望截止；LangChain4j 默认 60 s 会在服务截止前
先断开。

### 15.2 小应用存储隔离

`[MEASURED]`：

- 生成时注入 `data-hc-miniapp-storage` 前缀脚本，键前缀为
  `miniapp:<app-id>:`；同时提供 `window.miniappStorage`。
- 两个真实生成的小应用 `reading-a`、`reading-b` 各自带自己的 app id 前缀，
  互不可见；`clear` 只清本应用命名空间。
- 重复注入幂等；普通 HTML 没有 marker，也没有 `window.miniappAppId`。
- **手动添加**的小应用没有服务端注入，只有客户端 IndexedDB 隔离；不能把
  两种隔离混为一谈。

证据：`/tmp/hc-miniapp-storage/evidence/injection-check.edn`：

```clojure
{:a-marker true :a-prefix true :a-has-api true :a-idempotent true
 :b-marker true :b-prefix true :b-has-api true :b-idempotent true
 :normal-marker false :normal-app-id false}
```

### 15.3 客户端 bundle 重建后必须重启 app 并使用新页面

`[MEASURED]`：重新 `client-release` 后，旧 app 进程仍持有旧内存 bundle；
旧标签页继续使用旧 bundle。此时新页面可能停在“已连接，等待原子基线快照”，
或在扩展/搜索面出现 `react is not defined`。这些是**版本不一致假故障**，
不是服务端崩溃。复检顺序：

1. 重新 `pixi run client-release`；
2. 重启 app；
3. 用新标签页/新 Chrome profile 打开；
4. 确认 `main.js` 的 mtime/sha256 与新构建一致，再复现业务问题。

证据：`/tmp/hc-e2e-harness/e2e11ui/phaseC.log`、
`/tmp/hc-e2e-real/extensions-probe.png`；客户端源码缺口见
`src/hc/client/search/view.cljs:139` 与 `src/hc/client/swarm/ui.cljs:285`
（使用 `react/` 但未 require `["react" :as react]`）。

### 15.4 Ollama 设置与串行 E2E

`[MEASURED]`：

```bash
OLLAMA_CONTEXT_LENGTH=16384 OLLAMA_NUM_PARALLEL=1 ollama serve
```

- 16384 是 Plan 系统提示 + 工具定义可用的最小实测上下文；默认 4096 会
  HTTP 500。
- `OLLAMA_NUM_PARALLEL=1` + E2E 串行，避免多个 27B 请求争用导致超时。
- 观测到的 HTTP 500 根因是客户端中途断开/取消，不是模型崩溃；服务端应
  记录取消，不得把断连当成模型失败或重放。
