# Herdchestra 实施路线图

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 本底稿。
> 本文件是用户可见的实施底稿，不是验收报告。未运行的检查一律标 `NOT RUN`。
> 起始日期：2026-11-03。当前状态：骨架与核心支持库并行施工中。

## 1 范围

按根 §8.1 的扁平布局实现独立系统：

```text
src/hc/core/*       独立核心支持库
src/hc/compose/*    Owner、守卫命令、Missionary、Nudge、取消域
src/hc/server/*     存储、模型端口、沙箱、Sente/AG-UI 网关
src/hc/client/*     Fulcro + Framework7 React 瘦客户端
```

根 `AGENTS.md` 不修改。包级文档、`HACK.md` 与源码随根规格同步。

## 2 五阶段顺序（L29-01）

| 阶段 | 目标 | 最小验收 | 证据标签 |
|---|---|---|---|
| 一：控制内核与脚本化个体 | 任务、个体、认领、依赖、责任的短 STM 转换；文件动作先用无副作用替身 | 2–3 个脚本化个体跑通同时拆分、同时认领、父项 `:waiting` → `:ready`（绝不自动 `:done`）、休眠/唤醒竞态 | V01–V08、T01–T13 的适用子集；未跑标 `NOT RUN` |
| 二：受保护读取与整批写入 | 小临时工作区跑通 `stamp` 读取、`$W$`、四种基础操作的整批 dry-run 与 `apply` | F01–F10；拒绝时真实文件零修改；`apply` 次数严格为 1 | F01–F10；未跑标 `NOT RUN` |
| 三：生命管理与令牌竞争 | 加入 `take-or-die`、请求去重、逻辑死亡、槽位补新；停止按 `agent-id` 寻址 | T01–T13；V01–V05；失败竞争与移交同事务 | T01–T13；未跑标 `NOT RUN` |
| 四：Shell 会话适配 | 接入 `.call`、`$W$` 串行、会话边界 | F11 相互等待；F12 子活动边界；F13 硬失败现场；F14 外部写入；F15 唤醒覆盖 | F11–F15；未跑标 `NOT RUN` |
| 五：真实模型接入 | 少量并发个体与独立模块任务观察模型行为 | 模型不在安全工作未做完时急抢令牌；不把父项当计数容器；不把无响应当未执行 | 观察记录与修正；未跑标 `NOT RUN` |

阶段之间不跳步。前一阶段的最小验收未通过，不把后一阶段说成“已完成”。

## 3 目标交付物（按阶段落地）

1. `HACK.md`：外部库与平台限制、测量数据、复检命令。
2. `pixi.toml` / `pixi.lock`：JVM 21、Clojure 1.12.6、Node 22 的声明与锁。
3. `deps.edn`：基础 Clojure、`:test` 的 `clojure.test.check` 与 test-runner、compose/server/client/cljs 分层别名。
4. `src/AGENTS.md` 与各包 `AGENTS.md`：模块边界、装配方式、测试要求。
5. `src/hc/core/*`：独立核心支持库；各模块自带局部 `AGENTS.md` 与属性测试。
6. `src/hc/compose`、`src/hc/server`、`src/hc/client`：按阶段逐步实现；当前先保留边界文档。

## 4 验证命令

```bash
# 解析全部依赖别名
clojure -P -M:dev:test:compose:server:client:cljs

# 运行 clojure.test + test.check 属性测试
clojure -X:test
clojure -M:test -n hc.core.pair-hint-test

# 组合层与服务端测试
clojure -X:test:compose
clojure -X:test:server

# Pixi 环境
pixi run test
```

## 5 边界与纪律

1. 不修改根 `AGENTS.md`；发现根规格需要变更时，由维护者显式修改。
2. 本地提交可以；未获指示不 push。
3. 不使用 GPU；模型接入只通过显式端点。
4. 测试只用自建临时工作区，完成后清理；不触碰用户项目。
5. 失败与未运行分开报告。普通拒绝、已应用、仍在等待、硬失败后的不确定现场不得压成一个布尔值。
6. 发现随机反例后先缩小并沉淀为固定确定性场景，再修复。
