# E2E 自动化工装运行报告（0.0.1）

- 日期：2026-11-06
- 工装：`test/e2e/`；入口 `npm run e2e` / `e2e:core` / `e2e:mock` / `e2e:memory`。
- 浏览器：系统 Chrome `/usr/bin/google-chrome` 153.0.8010.36；Playwright 1.64.1；Node v26.10.0。
- 资源限制：app JVM `-Xmx768m -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError`；E2E09 driver `-Xmx384m`；Chrome `--disable-dev-shm-usage` + `--js-flags=--max-old-space-size=512`；`workers: 1`；整段 E2E 用 `flock /tmp/hc-app-single.lock` 包裹。
- 客户端 bundle：最终 `072587a4690b6953bae9cae3a06eaaa815a38b13eee9b98dc5ce98e72a54824f`（13:51:04，3,011,162 B；CSS `332f9fd3…`；源码树 `a97c68f7…`），W33 已冻结；`0c05b7d1`/`ea9e5732` 及更旧作废。E2E 结果以运行时 JSON 记录的 hash 为准。

## 结果

| 项目 | 命令 | 结果 | 退出码 | 耗时 | 证据 |
|---|---|---|---:|---:|---|
| mock 外部服务 | `npm run e2e:mock` | PASS | 0 | 1.2s | `plans/evidence/e2e-auto/mock-services/mock-services-result.json` |
| 内存基准 | `npm run e2e:memory` | PASS | 0 | 0.86s | `plans/evidence/e2e-auto/memory/memory-result.json` |
| E2E 03 流式阅读与下一条草稿 | `npx playwright test ... e2e-03-streaming-draft.spec.mjs` | PASS（19/19，`ea9e5732`） | 0 | 36.3s | `plans/evidence/e2e-auto/e2e03/e2e03-result.json` |
| E2E 09 工作区受保护提交 | `npx playwright test ... e2e-09-protected-commit.spec.mjs` | PASS（15/15，`ea9e5732`） | 0 | 17.6s | `plans/evidence/e2e-auto/e2e09/e2e09-result.json` |
| E2E 01 空安装到首条回复 | `npx playwright test ... e2e-01-empty-install.spec.mjs` | FAIL（D-AUTO-02：UI 声明 tools 后 Plan 仍报 `MODEL_TOOLS_UNAVAILABLE`；无用户消息） | 1 | 33.8s | `plans/evidence/e2e-auto/e2e01/e2e01-result.json` |

## E2E 01 说明

- 引导不要求注册、草稿完整、服务身份可见、连接测试、角色保存均已通过。
- 新建本地模型连接后，模型行显示“此次列表未返回”，`用于下一轮` 按钮 disabled；返回会话时模型标签为“尚未选择模型”，无法绑定，更无法发送。记录为 D-12，不使用预配置连接绕过。
- 后续复跑在启动阶段遇到 `src/hc/server/app/snapshot.clj:596:55` 的嵌套 `#()` 语法错误（其他工人编辑中）；W1/W11 已修复，`require` 与 `app-check` 通过。
- 11:07 复跑：`sessionModelVisible:true`、`bind.switchStatus:clicked`，页面停在会话页；点击发送后 240s 内没有产生用户消息/回复，Playwright test timeout 关闭页面，错误为 `page.waitForTimeout: Target page, context or browser has been closed`。截图停在 `e2e01-06-draft-restored`。这不是 OOM；已加 `page.on('crash'/'close'/'pageerror')` 诊断、发送后 15s 内必须出现用户消息的 fail-fast、assistant 等待缩短到 60s、test timeout 420s。因锁让给视觉工人，修复后尚未复跑。

## 当前阻塞

- final7 单树全量 PASS（3749/24310/0F/0E，380s，树 a97c68f7，bundle 072587a4，14:00:12）；E2E03/09/01 正在锁队列中，结果绑 072587a4。`ea9e5732`/`0c05b7d1` 上的结果标前冻结。

## 剩余缺口

- E2E 01 待发送路径稳定后复跑（D-12 已关闭；当前证据显示模型已绑定，卡在发送后无用户消息）。
- 其余 19 条（02、04、05、06、07、08、10、11、12、13、14、15、16、17、18、19、20、21、22）仍无 spec；mock 外部服务尚未接入这些剧本。
- 手工验收仅保留给 mock 无法覆盖的平台差异（真实麦克风、触屏、屏幕阅读器等）。
