# Herdchestra 0.0.1 最终提交计划

- 日期：2026-11-06；作者：发布提交准备工人（只读检查 + 按用户裁决更新 `.gitignore`；未执行任何 Git 写操作）。
- 基线文档：`plans/final-commit-manifest-0.0.1.md`（2026-11-05 快照，已过期）、`plans/release-gate-checklist-0.0.1.md`、`plans/release-notes-0.0.1.md`、`RUNNING.md`。
- 冻结目标：bundle `72de4f9a`（3,014,943 B，2026-11-06 17:45:52）+ 源码树 `36fddd6d`（full `36fddd6d77ebd21791e0081be15f93cb9b6470e286457af2a1cbdf6e4524fd9c`，含 W63 D-22 + W35 D-23 `:send-message` 修复）；final13 单树全量 PASS 3795 tests / 24596 assertions / 0F / 0E / 356 s（PRE=POST 同树，峰值 1.95 GiB）；`app-check` exit 0 / 14 s / `:ok true` / `0.0.1`。
- **当前 E2E 状态：18 PASS / 3 PARTIAL（05/12/15）/ 1 BLOCKED（11）/ 0 NOT RUN。未全 PASS → 先提交冻结树，不打 tag。**

## 0. 用户裁决（2026-11-06，已落地）

| # | 事项 | 裁决 | 落地状态 |
|---:|---|---|---|
| 1 | `机制提炼/` | 不提交；`.gitignore` 加 `/机制提炼/`；保留文件不删除 | **已写入 `.gitignore:34`**；`git check-ignore` 确认命中；`git status` 已不再列出 |
| 2 | `PROMPT.md` | 已跟踪，保留提交（不 `rm --cached`、不 ignore） | 无需动作；随已跟踪文件提交 |
| 3 | 8 份内部施工报告 | 随 `plans/` 一起提交（避免未跟踪） | manifest §4.2 排除建议**作废**；全部 `git add plans` |
| 4 | `plans/evidence/e2e-auto/playwright-report/index.html` | 加入 `.gitignore`；其余 e2e-auto 证据提交 | **已写入 `.gitignore:35`**（`plans/evidence/**/playwright-report/`）；`git check-ignore` 确认命中 |
| 5 | 9 个 `hs_err_pid*.log` | 已忽略，保留文件不提交 | 已由 `.gitignore:30` 覆盖，无需动作 |
| 6 | E2E 未全 PASS | **先提交冻结树，不打 tag**；E2E 收口（或明确接受 PARTIAL/BLOCKED 并写入 release notes）后再打 annotated tag；commit message 写明“E2E 18/3/1，tag 待收口” | 见 §4 commit message 与 §5 门禁 |

## 1. 当前工作树快照（2026-11-07 final13 后复核，`.gitignore` 更新后）

`git status --porcelain -uall`：

| 类别 | 数量 | 说明 |
|---|---:|---|
| 已跟踪修改 ` M` | 3 | `DEBT.md`、`HACK.md`、`RUNNING.md` |
| 已跟踪未修改 | 3 | `AGENTS.md`、`HISTORY.md`、`PROMPT.md` |
| 未跟踪 `??` | **2017** | 含本计划文件；`机制提炼/` 与 playwright-report 已被忽略不再计入；plans/evidence 较 11-06 新增 39 个文件 |

未跟踪分布（按裁决后口径）：

| 路径 | 数量 | 判定 |
|---|---:|---|
| `src/` | 550 | 必须提交（含各级 `AGENTS.md` 规格） |
| `test/` | 459 | 必须提交（含 `test/e2e/` Playwright 工装） |
| `plans/` | 984 | 910 个 `plans/evidence/` 文件 + 74 个 `plans/*.md` 报告（含本计划文件；playwright-report/index.html 已忽略） |
| `journeys/` | 8 | 必须提交（产品旅程文档） |
| `resources/` | 6 | 必须提交（index.html、workbench.css、pair_hint×2、tessdata×2） |
| `scripts/` | 3 | 必须提交（release-gate.sh、store-shutdown-sigterm.sh、store-shutdown-stress.sh） |
| 根配置/文档 | 7 | `.gitignore`、`deps.edn`、`package.json`、`package-lock.json`、`pixi.toml`、`pixi.lock`、`shadow-cljs.edn` |

- `git ls-files | wc -l` = **6**（`AGENTS.md`、`DEBT.md`、`HACK.md`、`HISTORY.md`、`PROMPT.md`、`RUNNING.md`）。
- **>5 MB 未跟踪文件：0 个。** 最大：`resources/tessdata/eng.traineddata` 4,113,088 B、`chi_sim.traineddata` 2,469,156 B（产品 OCR 资源，提交）；其余最大 `plans/evidence/agents-md-unauthorized-diff-3.patch` 295,043 B。
- `hs_err_pid*.log`：**9 个**（2905205、3003787、3597664、455536、1928617、3566148、3871452、4054882、4063480），全部被 `.gitignore:30` 忽略；保留在工作区，不提交。
- 已忽略构建产物：`resources/public/js/`（main.js + cljs-runtime）、`resources/public/css/framework7-bundle.css`、`target/`、`node_modules/`、`.pixi/`、`.shadow-cljs/`、`.cpcache/`、20+ 个 `out-*/`、`plans/evidence/*/chrome-profile/`、新增的 `/机制提炼/` 与 `plans/evidence/**/playwright-report/`。
- **bundle/CSS hash 复核（2026-11-06）**：`main.js` SHA-256 = `72de4f9a3d452e697e4d8a0311af6954621d373124d378964eee1f24b7ac0726`，3,014,943 B，mtime 17:45:52 —— **与冻结值一致，未变**。`framework7-bundle.css` 570,383 B，SHA-256 `77da0dd4…`（release-notes 记载 CSS 指纹 `332f9fd3` 为内容口径；字节数与冻结一致）。
- **版本五处复核**：`src/hc/version.cljc` `"0.0.1"`、`pixi.toml` `version = "0.0.1"`、`package.json` `"version": "0.0.1"`、`package-lock.json` `"version": "0.0.1"` —— 静态四处一致；`--help`/`/healthz` 的 0.0.1 由 app-check 证据覆盖（final13 PASS）。未变。

## 2. 必须提交清单（按裁决后口径：全部未跟踪产品/证据/文档）

### 2.1 产品与工程文件（1033 个未跟踪文件 + 6 个已跟踪，未变）

- `src/` 全部 550 个：Clojure/ClojureScript 源码 + 各级 `AGENTS.md` 规格。
- `test/` 全部 459 个：单元/属性测试、jsdom/DOM harness、`test/js/*.{cjs,sh}`、`test/e2e/`（playwright.config.mjs、spec、support、e2e09_workspace.clj）。
- `resources/` 6 个：`public/index.html`、`public/css/workbench.css`、`hc/core/pair_hint/{en,zh-CN}.md`、`tessdata/{eng,chi_sim}.traineddata`。
- `scripts/` 3 个：`release-gate.sh`、`store-shutdown-sigterm.sh`、`store-shutdown-stress.sh`。
- `journeys/` 8 个：journey-1..8.md。
- 根构建/配置 7 个：`.gitignore`、`deps.edn`、`pixi.toml`、`pixi.lock`、`package.json`、`package-lock.json`、`shadow-cljs.edn`。
- 根文档：`AGENTS.md`（用户版本为准）、`DEBT.md`、`HACK.md`、`HISTORY.md`、`RUNNING.md`、`PROMPT.md`（保留提交）。

### 2.2 plans/（984 个未跟踪路径 = 74 报告 + 910 evidence 文件）

- **74 份 `plans/*.md` 全部提交**（裁决 3：8 份内部施工报告不再排除）。含 manifest 之外新增的 `d12-fix-report`、`d15-fix-report`、`agents-md-*`、`debt-status`、`final-gate-inventory`、`full-test-baseline-current`、`hs-err-datalevin`、`memory-*`、`oom-root-cause`、`spec-delta-scan`、`store-shutdown-fix`、`take-or-die-retire-diagnosis`、`e2e-auto-{defects,memory,run-report}`、`e2e22-regression-diagnosis`、`visual-audit-*`（6 份）等。
- **`plans/evidence/` 910 个文件全部提交**（截图/JSON/log/patch/trace；`.last-run.json` 等小文件保留；`playwright-report/` 已忽略）。

### 2.3 数量汇总

- 必须提交未跟踪文件：**2017**（全部 `??`；`机制提炼/` 与 playwright-report 已被 `.gitignore` 移出未跟踪集）。
- 加已跟踪 6 个（含 3 个 ` M` 修改 + `PROMPT.md`）→ 提交后 `git ls-files` 期望 **2023**。

## 3. 不提交 / 已忽略清单（裁决后）

| 项 | 处置 | 依据 |
|---|---|---|
| `机制提炼/AGENTS.md` | 不提交；`.gitignore:34 /机制提炼/`；文件保留 | 裁决 1 |
| `plans/evidence/**/playwright-report/` | 不提交；`.gitignore:35` | 裁决 4 |
| `resources/public/js/`、`resources/public/css/framework7-bundle.css` | 已忽略，永不提交 | 构建产物 |
| `hs_err_pid*.log`（9）、`out-*/`、`chrome-profile/`、`target/`、`node_modules/`、`.pixi/`、`.shadow-cljs/`、`.cpcache/`、`.pi/`、根级 `*.log` | 已忽略，留工作区 | `.gitignore` |
| `PROMPT.md` | **提交**（已跟踪） | 裁决 2 |
| 8 份内部施工报告 | **提交** | 裁决 3 |

## 4. 建议命令（不执行；等最终门禁与用户指令）

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra

# 冻结确认：所有工人停止写入后重取快照
git status --porcelain -uall | wc -l   # 当前 2020（3 M + 2017 ??）

# 全量暂存（.gitignore 已排除 机制提炼/ 与 playwright-report/）
git add -A
# 或显式等价：
git add .gitignore deps.edn pixi.toml pixi.lock package.json package-lock.json \
        shadow-cljs.edn AGENTS.md DEBT.md HACK.md HISTORY.md RUNNING.md PROMPT.md \
        src test resources scripts journeys plans

# 校验（期望全部通过，无输出）
git diff --cached --name-only | grep -E '机制提炼|playwright-report|resources/public/js/|framework7-bundle.css|hs_err|out-|chrome-profile|node_modules|\.pixi|target/' \
  && echo 'ERROR: excluded file staged'
git status --porcelain -uall            # 应只剩 ?? plans/evidence/**/playwright-report/（已忽略则不显示）与 M 无关项
git diff --cached --name-only | wc -l   # 期望 2023
```

### Commit message 草稿（含裁决 6 要求的 E2E 口径）

```
release: Herdchestra 0.0.1 source release (freeze tree 36fddd6d)

Freeze bundle 72de4f9a (main.js 3,014,943 B, sha256 72de4f9a…0726)
on source tree 36fddd6d (incl. D-22, D-23 fixes). final13 full suite PASS:
3795 tests / 24596 assertions / 0F / 0E / 356s; app-check :ok true /
version 0.0.1. Version sources aligned: hc.version, pixi.toml,
package.json, package-lock.json.

Includes src/, test/ (incl. test/e2e Playwright harness), resources/,
scripts/, journeys/ 1-8, plans/ reports + evidence, root docs
(AGENTS/DEBT/HACK/HISTORY/RUNNING/PROMPT). Excluded via .gitignore:
机制提炼/, playwright-report/, build artifacts, hs_err/out-*/profiles.

E2E 18 PASS / 3 PARTIAL (05,12,15) / 1 BLOCKED (11); §8.5 automated
acceptance (D-04) still open. Tag v0.0.1 deferred until E2E closes
or PARTIAL/BLOCKED is explicitly accepted in release notes.
See plans/e2e-master-status-0.0.1.md and plans/release-notes-0.0.1.md.
```

### Annotated tag（E2E 收口后才执行；当前禁止）

```bash
git tag -a v0.0.1 -m "Herdchestra 0.0.1 — bundle 72de4f9a, tree 36fddd6d"
git show v0.0.1 --stat | head
```

## 5. 发布前仍需重跑/确认的门禁

| # | 门禁 | 状态 | 剩余动作 |
|---:|---|---|---|
| 1 | Pixi 锁定安装 `pixi install --locked` | 旧证据 PASS（热缓存） | 最终树重跑；记录冷/热缓存口径 |
| 2 | `client-install` + `client-release` | 旧证据 PASS；bundle 已冻结 `72de4f9a` | 不再重建则仅需确认 hash 未变（已复核一致）；重建会使全部 E2E 证据失效 |
| 3 | `app-check` | final13 PASS（exit 0/14 s/`:ok true`/`:component-errors {}`） | 提交前可复跑一次确认 |
| 4 | 真实启动冒烟（healthz/`/`/SIGTERM/重启） | 旧证据 PASS | 最终树重跑 |
| 5 | `pixi run test` 全量 | **final13 PASS 3795/24596/0F/0E/356s（PRE=POST 同树 `36fddd6d`，峰值 1.95 GiB，exit 0）** | 提交前若 `src/test` 再有写入必须重跑；当前以 final13 为准；`7dfcf0d7`（final12）/`ab801450`（final11）降为历史；树含 W63 D-22 + W35 D-23 `:send-message` 修复 |
| 6 | 版本五处 | 静态四处已复核 0.0.1；`--help`/health 由 app-check 覆盖 | 提交前复核 |
| 7 | 无模型门槛 `no_model_probe.cjs` | 旧证据 PASS | bundle 未变则沿用；重建后重跑 |
| 8 | E2E 全 PASS | **FAIL：18 PASS / 3 PARTIAL（05/12/15）/ 1 BLOCKED（11）** | 补齐 05/12/15/11；或按裁决 6 明确接受并写入 release notes |
| 9 | §8.5 自动化验收（D-04 缺口） | **未完成**：mock-services/memory PASS、e2e03/e2e09 PASS、e2e01 FAIL（D-AUTO-02 `MODEL_TOOLS_UNAVAILABLE`） | 22 条全自动化 + mock OAuth/WebDAV/S3 未达标；W10/D-04 结果未齐 |
| 10 | 工作树干净 | 提交后 `git status --porcelain -uall` 必须为空 | 提交后复核；`git ls-files` 应 ≈2023 |
| 11 | `hs_err` 9 个 | 已忽略，final13 期间 9（git 2020） | 确认无新增 |

**结论：按裁决先提交冻结树 36fddd6d（commit message 已含 E2E 18/3/1 与 tag 待收口）；E2E 收口或明确接受 PARTIAL/BLOCKED 前不打 annotated tag v0.0.1。**
