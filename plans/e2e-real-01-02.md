# E2E 01/02 回归复跑（真实 Ollama）

日期：2026-11-05 13:45–14:05
运行目录：`/tmp/hc-e2e-real`（脚本与证据不写入仓库；本文件是唯一新增的仓库文档）
构建：**release**（`resources/public/js/main.js`，2026-11-05 13:41:39；本轮未出现 `react is not defined`，未使用 dev build）
模型端点：本机 Ollama `http://127.0.0.1:11434/v1`，`qwen3.6:27b`，`OLLAMA_CONTEXT_LENGTH=16384`、`OLLAMA_NUM_PARALLEL=1`，串行发送
模型选项：`:options {:reasoning-effort "none"}`、`max-output-tokens 128`

## E2E 01 — PASS（13/13）

命令：

```bash
cd /tmp/hc-e2e-real
MAX_TOKENS=128 ./run_e2e01.sh
```

| 断言 | 结果 |
|---|---|
| 连接卡片可见 / 指向 `127.0.0.1:11434` | true / true |
| 新会话默认模型绑定（非“尚未选择”） | true，`模型：Qwen3.6 27B (real)` |
| 回复非空 / 非 stub / 语义有效 | true / true / true（回复 `是的`） |
| 用户消息进入会话 | true |
| 重载后消息 / 回复 / 模型归属 | true / true / true |
| 新浏览器上下文后消息 / 回复 / 模型归属 | true / true / true |

耗时：发送到回复 29.7 s；整轮（含启动、重载、新上下文）66 s。
证据：`/tmp/hc-e2e-real/artifacts/e2e01/{e2e01-result.json,e2e01-settings.png,e2e01-reply.png,e2e01-reload.png,e2e01-reopen.png}`

## E2E 02 — PASS（8/8）

命令：

```bash
cd /tmp/hc-e2e-real
./run_e2e02.sh
```

| 断言 | 结果 |
|---|---|
| 会话 A 默认绑定 Alpha（未手工切换） | true，`模型：Alpha 模型` |
| 会话 B 切到 Beta | true，`模型：Beta 模型` |
| 两会话回复互不串投 | true（`这是一个` / `你好`；A 无 B 标记，B 无 A 标记） |
| 两会话模型归属保持 | true |
| 停用 Alpha 后发送被拒 | true，客户端未发请求 |
| 停用提示原文 | `所选连接已停用；请启用连接或选择其他模型，草稿已保留` |
| 模型标签 | `模型：Alpha 模型（连接已停用）` |

耗时：5 min 0 s（含两次真实回复与停用流程）。
证据：`/tmp/hc-e2e-real/artifacts/e2e02/{e2e02-result.json,e2e02-two-sessions.png,e2e02-after-disable.png}`

## 备注

- 本轮树变化较大（binding、epoch、流式、取消、错误码、诊断），01 的 13 项断言全部通过，说明默认绑定、真实发送、重载/新上下文持久化未被回归破坏。
- 串行发送，避免 Ollama 过载；`OLLAMA_NUM_PARALLEL=1`。
- 如 release 出现 `react is not defined`，改用 `npx shadow-cljs compile :app` 的 dev build 并在本节标注；本轮未触发。
