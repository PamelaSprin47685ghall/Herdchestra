# hc.core AGENTS.md — 独立核心支持库

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 本文件 > 各模块 `AGENTS.md`。
> 本文件只定义 `hc.core/*` 的共同纪律；每个模块的局部规格写在自己的 `AGENTS.md`。

## 1 边界

1. `hc.core/*` 模块彼此独立。一个模块不得 require 另一个 core 模块。
2. core 模块不得 require `hc.compose`、`hc.server` 或 `hc.client`。
3. core 模块不启动线程、不打开网络连接、不读取用户工作区。纯函数与显式传入的端口对象是默认形态；需要副作用时由调用者注入。
4. 数据用不可变 map、vector、set 与 keyword 表达。状态关键字集合保持封闭；非法组合应在构造或转换入口拒绝，而不是在业务流里用隐式计数器补救。
5. 预期拒绝返回结构化结果或 `ex-info` 数据，不用异常控制正常业务分支。结果必须能区分“尚未生效的拒绝”“已应用”“仍在等待”“硬失败后的不确定现场”。

## 2 模块清单

| 模块 | 命名空间 | 局部规格 |
|---|---|---|
| `ufss/` | `hc.core.ufss.*` | `ufss/AGENTS.md`；EDN + UFSS 双射与 Pretty Printer |
| `scope/` | `hc.core.scope.*` | `scope/AGENTS.md`；准入、回合结算、出站裁剪 |
| `pair_hint/` | `hc.core.pair-hint.*` | `pair_hint/AGENTS.md`；双语资源、前缀链、提示注入 |
| `relay/` | `hc.core.relay.*` | `relay/AGENTS.md`；台账、任期、接力屏障 |
| `swarm_stm/` | `hc.core.swarm-stm.*` | `swarm_stm/AGENTS.md`；看板、命令、责任、令牌 |
| `workspace_gate/` | `hc.core.workspace-gate.*` | `workspace_gate/AGENTS.md`；`stamp`、预演、应用、`$W$` |
| `steward_sphinx/` | `hc.core.steward-sphinx.*` | `steward_sphinx/AGENTS.md`；认识状态与决策前沿 |
| `text_location/` | `hc.core.text-location.*` | `text_location/AGENTS.md`；UTF-16 精确切片与 `where` / `whereAll` |

模块文档至少写明：输入与输出 map 形状、状态转换前置条件、拒绝码、与其他模块的装配点、属性测试清单。

## 3 共同不变量

1. **看板父项**：子项完成只解除父项等待；父项保留 `:remaining-work` 与 `:owner-id`。
2. **令牌与责任**：同一时刻至多一个有效 `holder`；`:dead` 身份不能提交；失败竞争与责任移交在同一事务完成。
3. **文件判据**：只用 `mtime` 秒/纳秒与 `size`；不使用 `atime`；不把 `stamp` 当内容哈希。
4. **写入口**：普通受保护提交与 Shell 共用 `$W$`；读取不申请 `$W$`；预演在 `$W$` 内完成，应用不重做可拒绝判断。
5. **通知**：任何使全局可推进集合从空变非空的事务，以及任何用户新输入，都在同一事务产生 `wake-all`。
6. **无副作用重放**：可重试的记账闭包不得包含文件写入、Shell 或外部调用。

## 4 测试

每个模块必须提供 `clojure.test.check` 属性测试，至少覆盖根 `AGENTS.md` §8.2 列出的对应不变量。测试命名空间放在 `test/hc/core/<module>_test.clj`，用普通 `clojure.test` 包装属性，使 `clojure -X:test` 可以发现。

随机测试发现反例后，必须缩小并沉淀为固定确定性场景；不能只保留随机种子。文件相关测试只用测试自建临时工作区，完成后清理。

## 5 构建

core 模块只依赖基础 Clojure。运行：

```bash
clojure -X:test
```

需要 Missionary 的组合层测试使用 `clojure -X:test:compose`。资源文件放在 `resources/hc/core/<module>/`，已随 `:paths` 进入 classpath。
