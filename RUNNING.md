# Herdchestra 0.0.1 — 运行与发布检查

版本：**0.0.1**。唯一运行时版本源是 `src/hc/version.cljc`；`pixi.toml` 与
`package.json` 的版本必须与它一致。`--help` 与 `GET /healthz` 都会显示该版本。

这是源码级本地发布，不是签名安装包。Pixi 负责 JVM、Clojure、Node 与锁文件；
浏览器客户端仍由 shadow-cljs 构建。

## 前置条件

- Pixi 0.83 或兼容版本；仓库已带 `pixi.lock`。
- 首次安装与客户端构建需要网络，用于 Pixi/conda、Maven/Clojars 与 npm。
- 不需要 GPU。启动本身不需要模型服务；聊天需要显式配置模型连接。
- 默认只监听 `127.0.0.1`，不是远程多用户部署。

## 快速启动

```bash
pixi install --locked
pixi run client-install
pixi run client-release
pixi run app-check
pixi run app
```

`pixi run app` 使用默认数据目录（Linux 上为 `~/.local/share/herdchestra`；
macOS/Windows 由 `hc.server.app.config/default-data-dir` 决定）、当前目录作为
workspace，并从 `resources/public` 提供浏览器客户端。启动后打开：

```text
http://127.0.0.1:7575/
```

健康检查：

```bash
curl -s http://127.0.0.1:7575/healthz
```

健康 EDN 应包含 `:ok true`、`:version "0.0.1"`、`:store {:open? true}` 与
`:gateway` 计数。停止应用使用 Ctrl-C 或 SIGTERM；进程会先停止接收新工作，
再关闭网关、Owner、模型端口与存储。

### 隔离冒烟

`--check` 默认使用临时 data-dir 与临时 workspace，不打开用户默认数据目录，
也不参与运行中实例的单例锁。默认隔离出的 workspace 不是 Git 仓库，因此
check 模式下 fleet 以 `:preflight? false` 启动（不执行真实 fork），fleet-gc
也不立即扫描；显式传入 workspace 时保持真实预检。health 会区分
`:configured?`/`:status`，不会用 `:started? false` 冒充健康。

需要显式路径时（例如复现真实仓库的 fleet 预检）：

```bash
pixi run clojure -M:server -m hc.server.app --check --port 0 \
  --data-dir /tmp/hc-0.0.1-data \
  --workspace-root "$PWD" \
  --static-root resources/public
```

显式传入 `--data-dir`/`--workspace-root`（或 `HC_DATA_DIR`/`HC_WORKSPACE_ROOT`）
会关闭对应隔离。`--check` 启动完整系统、打印健康、关闭并退出；health `:ok`
或 stop 未确认时退出非零。它不会打开浏览器。

### 自定义启动

```bash
pixi run clojure -M:server -m hc.server.app \
  --host 127.0.0.1 --port 7575 \
  --data-dir /path/to/data \
  --workspace-root /path/to/workspace \
  --static-root resources/public
```

`--help` 会打印版本与全部选项：

```bash
pixi run clojure -M:server -m hc.server.app --help
```

## Pixi 包装检查

| 项 | 期望 |
|---|---|
| `pixi.toml` `version` | `0.0.1` |
| `package.json` `version` | `0.0.1` |
| Pixi 运行时 | JDK 21、Clojure 1.12.6、Node 22 |
| 锁文件 | `pixi.lock` 存在；安装使用 `--locked` |
| 客户端依赖 | `pixi run client-install`（`npm ci`） |
| 客户端产物 | `pixi run client-release` 生成 `resources/public/js/main.js` 与 CSS |
| 启动自检 | `pixi run app-check` 退出 0，健康为 `:ok true` |
| 全量测试 | `pixi run test` 通过，记录测试/断言数与 test.check seed；以最终合并树重跑为准 |

`osx-arm64` 目前没有 conda-forge Clojure 构建；Apple Silicon 走 `osx-64`
（Rosetta）或宿主 Clojure CLI。该限制见 `HACK.md` §4.2。

客户端构建的 shadow-cljs classpath 由 `shadow-cljs.edn` 的
`:deps {:aliases [:shadow :client :browser-test]}` 组成。`:browser-test` 只
把 `test` 路径加入 classpath，供聚焦浏览器 harness 使用；生产 `:app` 构建只
编译入口实际引用的命名空间。`:shadow` 只含 `thheller/shadow-cljs` 且没有
`:main-opts`；`:cljs` 只用于 cljs.main/REPL。不要把 `:cljs` 加回 shadow 的
aliases，否则 `-m cljs.main` 会覆盖 shadow CLI。

## 0.0.1 发布条件

以下全部满足才可标记 0.0.1：

1. `pixi install --locked` 在干净环境成功。
2. `pixi run client-install` 与 `pixi run client-release` 退出 0；静态产物存在。
3. `pixi run app-check` 退出 0，健康包含 `:version "0.0.1"`、存储打开与网关计数。
4. 真实启动后 `GET /healthz` 返回 200，`GET /` 返回客户端 shell。
5. `pixi run test` 全量通过；记录命令、测试数、断言数、退出码与 seed。
6. 发布提交干净；没有未提交/未跟踪的产品文件。版本在 `hc.version`、
   `pixi.toml`、`package.json` 与 `--help`/health 中一致。
7. 没有模型配置时启动仍健康，但聊天明确提示需要选择模型；不得用假模型或
   静默回退冒充可用。
8. `plans/release-notes-0.0.1.md` 如实列出已验证项、`NOT RUN`/`BLOCKED` 项与
   已知边界：E2E 尚未全部 PASS；逐条状态与证据以
   `plans/e2e-master-status-0.0.1.md` 为准；修复中的条目不得预写 PASS。
   根 `AGENTS.md` §8.5 要求 22 条 E2E 全部自动化，配套 mock 模型服务与
   OAuth/WebDAV/S3 mock 外部服务；手工验收只用于 mock 无法覆盖的宿主平台
   差异。自动化结果以 W10 的报告为准；W10 完成前条件 8 不能算 PASS。
   验收按 §7.4 八段用户旅程（首次启动、日常对话、长任务、中断与恢复、
   历史与知识、扩展能力、导出/备份/迁移、设置与诊断）组织；旧八组 UI
   规范不再作为验收单位。
   无签名安装包；默认 loopback-only；离线恢复走 `hc.server.backup.cli`
   且有已知边界；没有 GPU 路径。

**资源纪律**：所有 app/test JVM 必须带显式堆上限（`deps.edn` 默认
`-Xmx1g -XX:MaxDirectMemorySize=512m`；测试子 JVM 512m/256m；E2E app
768m/512m）；同一时刻最多 1 个重资源 app JVM + 1 个 Chrome；全量测试与
E2E 串行；结束立即停止 app/Chrome。不得用无 caps 的裸 `java`/无别名
`clojure` 绕过 `deps.edn`。

0.0.1 的逐项证据与未覆盖清单以 `plans/release-notes-0.0.1.md` 为准；本文件
只保留运行入口、发布条件与操作边界。

## 已知边界

- 默认安全边界是 loopback + 同源检查；远程部署必须提供认证与 TLS，不能只改
  `--host`。
- 客户端实时通道与静态资源同源；大对象走独立 HTTP/Blob 通道。
- 备份恢复需要停机窗口，活服务拒绝在线整库恢复。
- `pixi run client-release` 需要先执行 `pixi run client-install`；Pixi 不自动
  管理 npm 依赖。重建 bundle 后必须重启 app 并使用新页面；旧标签页仍持有
  旧内存 bundle，会把“等待基线/`react is not defined`”误报成服务端故障。
- 备份恢复后，模型连接配置需要重新投影到运行时；旧进程内连接句柄不会继承。
  恢复后的连接状态以新的 health/连接列表为准。
- 备份恢复后定时任务默认暂停，不会自动补跑；需要用户显式恢复或手动运行。
- 小应用生成是同步 HTTP `POST /api/workspace/miniapp-generate`，默认截止
  300 s、上限 600 s，真实生成可能耗时数分钟；UI 不得在等待期间重复提交。
- 本地 Ollama 端点（`127.0.0.1:11434/v1`、`qwen3.6:27b`）只用于开发/验证，
  不是 0.0.1 的发布依赖；发布不要求本机运行 Ollama。验证时使用
  `OLLAMA_CONTEXT_LENGTH=16384`、`OLLAMA_NUM_PARALLEL=1`，E2E 串行。
