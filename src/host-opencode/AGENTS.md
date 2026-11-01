# host-opencode：OpenCode v2 适配器（总览）

包级规格在 `spec/AGENTS.md`（§8.1：`src/host-opencode/<包名>/AGENTS.md`）。
本文件只说明目录形状，不重复规格。

- `spec/` —— 适配器的全部代码，也是包根（`name: @herdchestra/host-opencode`）。
  `spec/effect-entry.mjs` 是权威入口（`{ id, effect }`，v2.1.4 唯一拿到完整上下文的形状）；
  `spec/package.json` 的 `main`/`exports` 都指向它。
- `index.js` —— 目录装载入口。宿主把本地插件目录解析成 `index.js`/`server.js`，
  不读 `main`/`exports`；缺这个文件时目录会被静默忽略。它再导出 `spec/effect-entry.mjs`。
- `plugin.mjs` —— **仅测试入口，不是装载路径**。宿主从不导入它（目录目标只找
  `index.js`/`server.js`，不读 `main`/`exports`，也不按文件名找 `plugin.mjs`）；
  它只把两种形状放在同一个 specifier 下，供测试使用。真实装载只走 `index.js`。

改动适配器只改 `spec/`。
