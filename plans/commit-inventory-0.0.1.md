# 0.0.1 提交清单与忽略规则核对

- 日期：2026-11-05
- 方法：`git status --porcelain=v1 -z -uall` 取全部未跟踪路径；`os.path.getsize`
  统计字节；`git check-ignore -v` 逐项核对。只读 + 文档，未执行 Git 写操作。
- 快照：15 条顶层未跟踪条目、**1027 个未跟踪文件、22,369,303 bytes**（含本报告）。
  已跟踪文件仅 `AGENTS.md`。

## 1. 未跟踪路径聚合

| 类别 | 文件数 | 字节数 | 说明 |
|---|---:|---:|---|
| `src/` | 548 | 9,598,007 | 产品源码（core/compose/server/client） |
| `test/` | 427 | 4,949,620 | 单元/属性/集成测试与 `test/js` harness |
| `resources/` | 6 | 6,627,286 | 客户端入口、workbench CSS、pair-hint 资源、Tesseract 数据 |
| `plans/` | 35 | 985,145 | 计划、审计、E2E 结果、证据（本报告另计） |
| 构建清单 | 7 | 106,734 | `.gitignore`、deps/package/pixi/shadow 清单与锁 |
| 根文档 | 4 | 102,511 | `RUNNING.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md` |
| 其他 | 0 | 0 | 没有未分类路径 |
| **合计** | **1027** | **22,369,303** | |

### 1.1 目录树

```text
src/
  hc/core/             UFSS、scope、pair-hint、relay、swarm、workspace-gate、text-location、steward-sphinx
  hc/compose/          owner、mission、fleet、guards、cancel、nudge、reports、retry、scheduler、workspace
  hc/server/           app、storage、gateway、history、model、program、search、documents、backup、scheduler…
  hc/client/           workbench、conversation、copy、settings、backup、materials、extensions、diagnostics…
test/
  hc/core/ hc/compose/ hc/server/ hc/client/
  js/                  浏览器/jsdom harness、Playwright 驱动、CLI 验证脚本
resources/
  hc/core/pair_hint/   en.md、zh-CN.md
  public/              index.html、workbench.css
  tessdata/            chi_sim/eng traineddata
plans/
  *.md                 计划、审计、E2E 结果、发布说明
  evidence/            截图/JSON 与 scale-dom 日志
```

## 2. 顶层条目的忽略判定

`git check-ignore -v` 对以下 15 条全部退出 1（**不被忽略**），即都可提交：

```text
.gitignore  HACK.md  HISTORY.md  PROMPT.md  RUNNING.md
deps.edn  package-lock.json  package.json  pixi.lock  pixi.toml
plans  resources  shadow-cljs.edn  src  test
```

被忽略的 34 条顶层条目及规则：

| 条目 | 规则 | 判定 |
|---|---|---|
| `.cpcache/` | `.cpcache/` | 该忽略（Clojure 缓存） |
| `.pi/` | `.pi/` | 该忽略（本地工具） |
| `.pixi/` | `.pixi/` | 该忽略（环境） |
| `.shadow-cljs/` | `.shadow-cljs/` | 该忽略（编译缓存） |
| `node_modules/` | `node_modules/` | 该忽略（npm 依赖） |
| `target/` | `target/` | 该忽略（构建输出） |
| 19 个 `out-*/` | `out-*/` | 该忽略（测试 harness 输出） |
| `resources/public/js/` | `resources/public/js/` | 该忽略（client-release 产物） |
| `resources/public/css/framework7-bundle.css` | 同名规则 | 该忽略（client-release 产物） |
| `plans/evidence/no-model/chrome-profile/` | `**/chrome-profile/` | 该忽略（浏览器 profile） |
| `plans/evidence/scale-draft/chrome-profile/` | `**/chrome-profile/` | 该忽略（浏览器 profile） |
| 5 个 `hs_err_pid*.log` | `hs_err_pid*.log` | 该忽略（JVM 崩溃日志） |

`out-*` 的 19 个目录：artifacts-ui、backup-password-dom、check、check2、
check3、conversation-selection-dom.R14enn、conversation-selection-dom.ljxjx4、doc、files-ui、generated-card-dom、
generated-card-ui、schedule-ui、swarm-ui、workbench-draft-storage.NK4I6V、
workbench-scale-dom.cmV2Ks、workbench-scale-dom.xNNXBw、
workbench-session-commands.sPVn6N、workbench-shell-dom.A0z1qK、
workbench-shell-dom.coG8l8。

## 3. 不该提交但当前未被忽略

扫描未跟踪路径中的 `.log`、`.tmp`、`.class`、`.hprof`、`.pid`、`.bak`、
`.swp`、`~`、`.out`、`.DS_Store`、`core`、`replay_pid*`：

| 路径 | 判定 | 建议 |
|---|---|---|
| `plans/evidence/scale-dom/list.log` | **应提交**（发布证据） | 已有否定规则 `!plans/evidence/**/*.log` |
| `plans/evidence/scale-dom/messages.log` | **应提交**（发布证据） | 同上 |
| `plans/evidence/scale-dom/pure-bench.log` | **应提交**（发布证据） | 同上 |

没有发现 `.DS_Store`、`*.class`、`*.hprof`、`core.*`、`replay_pid*`、
`out-` 前缀文件、临时 profile 或其它未忽略的残留。**无需新增忽略规则。**

## 4. 该提交但可能被忽略

以下路径已用 `git check-ignore` 确认**不被忽略**：

| 路径 | 说明 |
|---|---|
| `plans/evidence/scale-dom/*.log` | `!plans/evidence/**/*.log` 否定规则生效 |
| `resources/public/index.html` | 客户端入口 |
| `resources/public/css/workbench.css` | 工作台样式（不是 release bundle） |
| `resources/hc/core/pair_hint/en.md`、`zh-CN.md` | 双语提示资源 |
| `resources/tessdata/chi_sim.traineddata`、`eng.traineddata` | OCR 语言数据 |
| `test/js/**` | 浏览器/jsdom/Playwright 验证脚本 |
| `pixi.lock`、`package-lock.json` | 锁文件 |
| `.gitignore` | 忽略规则本身 |

`resources/public/js/` 与 `framework7-bundle.css` 是构建产物，仍应忽略；
`plans/evidence/*/chrome-profile/` 是浏览器缓存，仍应忽略。

## 5. 建议提交分组

| # | 分组 | 路径前缀 | 建议 message |
|---:|---|---|---|
| 1 | 构建清单 | `.gitignore`、`deps.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`shadow-cljs.edn` | `build: pin Pixi, Clojure and client toolchain` |
| 2 | 核心库 | `src/hc/core/`、`test/hc/core/` | `core: add UFSS, scope, relay, swarm and workspace gate` |
| 3 | 组合层 | `src/hc/compose/`、`test/hc/compose/` | `compose: add Owner, mission, fleet and scheduler orchestration` |
| 4 | 服务端 | `src/hc/server/`、`test/hc/server/` | `server: add storage, gateway, model and backup adapters` |
| 5 | 客户端 | `src/hc/client/`、`resources/public/index.html`、`resources/public/css/workbench.css`、`resources/hc/core/pair_hint/`、`resources/tessdata/` | `client: add workbench, conversation and failure copy` |
| 6 | 测试工装 | `test/js/` | `test: add browser, jsdom and CLI harnesses` |
| 7 | 根文档 | `RUNNING.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md` | `docs: add run, hack and history notes` |
| 8 | 计划与证据 | `plans/**`（含 `plans/evidence/**`） | `docs: add release plans, audits and E2E evidence` |

一次性提交可用：

```text
release: prepare Herdchestra 0.0.1 source distribution
```

分组提交时按 1→8 顺序，确保构建清单先于源码、证据最后；不要提交被忽略的
`resources/public/js/`、CSS bundle、`out-*`、`target`、`node_modules`、
`.pixi`、`.shadow-cljs`、`.cpcache`、`.pi`、`hs_err_pid*.log`、
`chrome-profile`。

## 6. 最终计数

本报告加入后：顶层未跟踪条目仍为 15，未跟踪文件为 **1027**，字节数为
**22,369,303**；`plans/` 文件数为 35。忽略条目为 34。建议在提交前
用以下命令重新取一次快照：

```bash
git status --porcelain -uall | wc -l
git status --porcelain --ignored | grep -c '^!!'
git check-ignore -v plans/evidence/scale-dom/list.log \
  hs_err_pid2905205.log plans/evidence/no-model/chrome-profile/x
```
