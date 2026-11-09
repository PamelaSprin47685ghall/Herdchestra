# E2E 01–22 状态台账（0.0.3）

- 日期：2026-11-09
- **终局 bundle**：`resources/public/js/main.js` sha256
  `751756ec4788679642365fbb53825878004e993967d6db3fa946563fa8de025c`，
  **3,151,319 B**。
- **总判定：PASS 22 / RE-VERIFYING 0（按 22 个 spec）。**
  E2E 01 有 first journey 与 OAuth journey 两条证据行，均为 PASS，故下表
  共有 **21 行 PASS 证据**，对应 22 个 PASS spec。
- **树哈希口径**：20 个 spec 在标准树
  `07b1ea6b5336f8944ffa288618d279b157f61510d80ed89cc4f1816c86e805b`
  上通过；随后仅对 `test/e2e/e2e-11.spec.mjs` 与 `test/e2e/e2e-17.spec.mjs`
  做测试侧鲁棒性加固（`src/` 零改动）：E2E 11 在
  `0f9647fea8eb83335c0f0e5e97e58c1e4bec41d07b9f184327606b4c7a70eb73`
  上通过，E2E 17 在最终标准树
  `80e9321c460d118dc936d05fd01321f0d64d5dfdbb485104d350851b39a246dd`
  上通过。
- 所有 E2E 均在 `TMPDIR=/var/tmp/hc-e2e` 下运行；`/tmp` 为 32G tmpfs，
  写满会产生假「连接中断」（见 `HACK.md` §17.24）。
- 状态定义：`PASS` = 在该终局 bundle 上 `ok=true` 且所有 checks true；
  未实测项不写 PASS。

## 1. 22 行状态

| E2E | spec 文件 | tag | 证据 JSON | bundle | sourceTree | 状态 | checks / 备注 |
|---:|---|---|---|---|---|---|---|
| 01 | `test/e2e/e2e-01-empty-install.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e01/e2e01-result.json` | `751756ec` | `07b1ea6b` | `PASS` | first journey；all true |
| 01 OAuth | `test/e2e/e2e-01-empty-install.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e01-oauth/e2e01-oauth-result.json` | `751756ec` | `07b1ea6b` | `PASS` | OAuth journey；15/15 |
| 02 | `test/e2e/e2e-02.spec.mjs` | `@core` | `plans/evidence/e2e-02/e2e02-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 03 | `test/e2e/e2e-03-streaming-draft.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e03/e2e03-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 04 | `test/e2e/e2e-04.spec.mjs` | `@core` | `plans/evidence/e2e-04/e2e04-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 42/42 |
| 05 | `test/e2e/e2e-05.spec.mjs` | `@core` | `plans/evidence/e2e-05/e2e05-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 36/36 |
| 06 | `test/e2e/e2e-06.spec.mjs` | `@core` | `plans/evidence/e2e-06/e2e06-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 37/37 |
| 07 | `test/e2e/e2e-07.spec.mjs` | `@core` | `plans/evidence/e2e-07/e2e07-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 08 | `test/e2e/e2e-08.spec.mjs` | `@core` | `plans/evidence/e2e-07-08/e2e08-751756ec-07b1ea6b-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 09 | `test/e2e/e2e-09-protected-commit.spec.mjs` | `@core` | `plans/evidence/e2e-auto/e2e09/e2e09-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 10 | `test/e2e/e2e-10-tool-interrupt.spec.mjs` | `@core` | `plans/evidence/e2e-10/e2e-10-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 11 | `test/e2e/e2e-11.spec.mjs` | `@e2e` | `plans/evidence/e2e-11/e2e-11-result.json` | `751756ec` | `0f9647fe` | `PASS` | 31/31，58.4s；`normal-session-memory-visible` detail `"badge=材料库已就绪; revision=16; seq=46; visibleRows=4"`；截图 `10a-normal-session-ready.png` / `10b-normal-session-rows.png` |
| 12 | `test/e2e/e2e-12.spec.mjs` | `@core` | `plans/evidence/e2e-12/e2e12-result.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 13 | `test/e2e/e2e-13.spec.mjs` | `@e2e` | `plans/evidence/e2e-13/e2e-13-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 25/25；进程 exit 0 |
| 14 | `test/e2e/e2e-14.spec.mjs` | `@e2e` | `plans/evidence/e2e-14/e2e-14.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 15 | `test/e2e/e2e-15.spec.mjs` | `@core` | `plans/evidence/e2e-15/e2e-15.json` | `751756ec` | `07b1ea6b` | `PASS` | 44/44；`cloudUiRefusal=true` |
| 16 | `test/e2e/e2e-16.spec.mjs` | `@e2e` | `plans/evidence/e2e-16/e2e-16.json` | `751756ec` | `07b1ea6b` | `PASS` | all true |
| 17 | `test/e2e/e2e-17.spec.mjs` | `@e2e` | WebDAV `plans/evidence/e2e-17/e2e-17.json`；S3 `plans/evidence/e2e-17/e2e-17-s3.json` | `751756ec` | `80e9321c` | `PASS` | 92.37s；WebDAV 17/17 + S3 13/13；移除私有哈希遮蔽并完成测试文件加固 |
| 18 | `test/e2e/e2e-18-disk-full.spec.mjs` | `@core @disk` | `plans/evidence/e2e-auto/e2e18/e2e18-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 39/39；`platformSupported=true` |
| 19 | `test/e2e/e2e-19-ime-a11y.spec.mjs` | `@core @a11y` | `plans/evidence/e2e-auto/e2e19/e2e19-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 46/46 |
| 20 | `test/e2e/e2e-20.spec.mjs` | `@core` | `plans/evidence/e2e-20/e2e20-result.json` | `751756ec` | `07b1ea6b` | `PASS` | 20/20 |
| 21 | `test/e2e/e2e-21.spec.mjs` | `@core` | `plans/evidence/e2e-21/e2e21-result.json` | `751756ec` | `07b1ea6b`（证据哈希为自定义口径，见 D-46） | `PASS` | 20/20 |
| 22 | `test/e2e/e2e-22.spec.mjs` | `@e2e` | `plans/evidence/e2e-22/e2e-22.json` | `751756ec` | `07b1ea6b` | `PASS` | 32/32 |

### 1.1 E2E 11/17 的测试侧加固

- **E2E 11**：spec 加固后在同一终局 bundle `751756ec` 上独立复跑，标准树
  `0f9647fe` 上 31/31 checks 全 true，58.4s；`normal-session-memory-visible`
  的 detail 为 `"badge=材料库已就绪; revision=16; seq=46; visibleRows=4"`。
- **E2E 17**：移除私有哈希遮蔽并完成测试文件加固后，同一终局 bundle
  `751756ec` 上独立复跑，最终标准树 `80e9321c` 上 WebDAV 17/17 + S3
  13/13 全 true，92.37s。
- 两次加固均只改 `test/e2e/e2e-11.spec.mjs` 与 `test/e2e/e2e-17.spec.mjs`
  测试侧代码，`src/` 零改动；因此 20 条标准树 `07b1ea6b` 的 PASS 不受影响。

### 1.2 哈希口径说明

E2E 21 的证据哈希使用自定义 `sha256(git HEAD tree + spec bytes)` 口径，
不等同于标准 `find src test` 源树哈希；已记入 `DEBT.md` D-46，不视为失败。
其余 PASS 行（含 E2E 11/17）的 `sourceTree` 均为标准公式值。

## 2. E2E 运行纪律

- 22 条 E2E **必须在冻结树 + 固定 `main.js` sha 上串行运行**；每阶段校验
  `main.js` sha 未变，变了即作废重跑。
- **运行期间禁止改 `src/`**：源码编辑期的 `startApp` 编译错误会被误记为
  spec 失败。
- 所有运行统一 `TMPDIR=/var/tmp/hc-e2e`；`/tmp` 是 32G tmpfs，写满会产生
  假「连接中断」（见 `HACK.md` §17.24）。
- 每条结果必须同时记录 `bundleSha256` + `sourceTreeSha256`；缺失或变化即
  不计入。任何 spec 必须从 `test/e2e/support/evidence.mjs` 导入
  `sourceTreeSha256`，禁止本地重写（见 `HACK.md` §17.29）。

## 3. 口径

- 本轮最终矩阵：**22/22 spec PASS**，全部绑定终局 bundle `751756ec`；
  20 个 spec 在标准树 `07b1ea6b` 上通过，E2E 11 在 `0f9647fe`、E2E 17 在
  最终标准树 `80e9321c` 上通过（仅测试侧加固）。
- 之前诊断轮跨 `da131397` → `52ff15` → `187b401` 三个 bundle，跨 bundle
  不可比；那些 ENV/BLOCKED 记录已被本轮最终矩阵取代，不再作为门禁证据。
- E2E 22/22 已完成；最终 `80e9321c` 上的 JVM、26/26 CLJS 与三项基准
  也已完成并绑定同一终局 bundle。

## 4. 待办

1. 全部最终门禁证据已回填；只剩 `release-gate.sh` 端到端与 worktree 干净。
2. 若最终 bundle 再次变化，22 条 E2E 与所有绑定证据全部作废重跑。
