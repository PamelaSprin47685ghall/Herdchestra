# hc.client.speech — 语音输入、朗读与翻译界面

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > 本文件。
> 本文件说明客户端语音模块的界面合同与装配点，不新增服务器协议。

## 1 责任

`hc.client.speech.*` 把服务器语音能力接到真实工作台：

- **录音与转写**：麦克风录音、时长/电平反馈、停止/取消、上传、可编辑
  转写、插入草稿、失败保留录音、换服务重试、下载与删除。
- **朗读**：消息菜单“朗读”、TTS 请求、播放器暂停/继续/停止/速度、
  下载音频、部分音频提示、版本切换停止旧朗读。
- **服务设置**：语音输入、朗读、翻译三套服务列表；添加/编辑/测试/
  设默认/启用禁用/排序/删除；密钥只显示是否已配置。
- **翻译**：消息派生翻译面板；原文保留；目标语言可切换；结果可复制
  或插入草稿；服务与语言显示在结果详情中。

本模块不直接发送用户消息。转写只能插入草稿；翻译结果只能复制或插入
草稿，不能覆盖原文或历史消息。

## 2 文件

| 文件 | 责任 |
|---|---|
| `api.cljs` | `/speech/*` HTTP、EDN 解析、Sente 设置命令与回执关联。 |
| `playback.cljs` | 单实例朗读播放器、停止旧音频、速度与下载。 |
| `ui.cljs` | 录音输入、朗读按钮、设置页、消息派生翻译面板。 |
| `translation.cljs` | 独立翻译页：源/目标语言、服务、原文/译文分离、选区安全替换与清空撤销。 |
| `input_model.cljc` | 录音面板的纯决策：插入载荷、服务位置、保存状态、取消/中断文案、语言选择。 |
| `playback_model.cljc` | 朗读请求代次判定；过期响应不得发布状态。 |
| `settings_model.cljc` | 设置命令未发送时的通知文案。 |

## 3 装配点

- `hc.client.conversation.view/composer-view` 在输入区上方挂载
  `speech-ui/input-element`，传入当前 `session-id`、草稿与
  `:insert-transcript!`。
- `hc.client.conversation.view/message-item` 对助手消息挂载
  `read-aloud-button` 与 `translation-controls`。
- `hc.client.conversation.view/conversation-surface` 挂载
  `speech-playback/player-element`，并在消息版本变化时调用
  `stop-if-stale!`。
- `hc.client.workbench.views.session` 提供 `:insert-transcript!`，按录音
  开始时的光标位置插入文字，不覆盖录音期间写入的内容。
- `hc.client.workbench.views.settings` 增加“语音与朗读”标签页，挂载
  `speech-ui/settings-element`。
- 同一设置页增加“独立翻译”标签页，挂载
  `speech-translation/page-element`。

## 4 行为边界

1. 首次使用麦克风先说明用途，再请求系统授权；拒绝后文字输入仍可用。
2. 录音中显示真实时长与来自 `AnalyserNode` 的电平；没有输入时显示
   “暂未听到声音”，不模拟波形。
3. 单次录音上限 5 分钟；首次说明与接近上限时都显示该限制。到上限
   自动停止并完成已有录音的上传。录音中显示当前识别服务与处理位置
   （设备内、云端或系统服务）；麦克风报错或音轨结束时显示中断原因，
   并保留已确认文字。
4. 停止后先上传并显示可编辑转写；确认页显示录音时间、服务与识别
   语言。语言可选自动识别、原语言或指定语言；重试只重做这次录音，
   不修改全局设置。用户点“插入草稿”才写入草稿。默认不自动发送。
5. 上传失败显示录音时长与保存状态；保留本地 Blob 与录音 ID；可预听、
   重试、下载或删除。换服务前显示“将把这段录音发送给另一服务”，
   操作员确认后才允许重试；没有录音 ID 时“重新上传”再次发送同一
   Blob。取消只保证本产品不再继续提交，不声称远端撤回。
6. 录音阶段取消也进入取消状态，显示“本次语音输入已取消；原草稿保持
   原样”，不直接清空面板；只有关闭或删除才清空。录音归属会话与当前
   会话不一致时，“插入草稿”禁用，避免把转写放进错误会话。
7. 朗读只发送所选消息版本的可见正文；服务器跳过代码、网址、大表格
   和公式。播放器一次只播放一条音频；切换消息版本会停止旧朗读。
   每次播放或停止都推进请求代次；过期响应不改变播放器状态，并释放
   其 Blob URL；停止后在途响应不得播放。
8. 朗读请求失败显示错误；部分音频仍可播放并明确标记为部分结果。
   服务端响应读取超时或提前结束时，客户端只播放已收到的部分，并在
   播放器标记 `x-hc-partial`/错误，不把 HTTP 200 当作完整音频。
9. 设置页只在服务器回执后更新状态；命令未发送（同步未完成或实时
   通道关闭）时清除 pending、显示原因并保留表单；测试使用真实服务
   请求，没有服务时显示未配置，不伪造成功。
10. 翻译面板保留原文与已生成译文；结果按目标语言分别保存，迟到的
    响应不得覆盖其他语言的结果；失败保留语言选择和原文，重试只重做
    翻译，不把原文作为新聊天发送。
11. 独立翻译页默认不自动翻译；只有显式开启才在停止输入后调用服务。
    源语言默认自动识别，目标语言默认跟随界面语言；交换按钮只在两端
    都确定时可用。
12. 选区替换只在原文未修改且选区起止、选中文字都与请求时一致时执行。
    任一条件变化就只提供复制或插入到光标，绝不替换旧位置。
13. 没有可用语音输入服务时，麦克风入口显示原因并提供“打开语音设置”，
    不先录音再失败；服务列表加载完成前按钮显示读取状态并禁用，加载
    完成或失败后恢复可点击，由下一次点击给出真实结果。
14. 消息翻译面板在没有可用翻译服务时立即显示设置指引并禁用翻译；
    朗读缺少服务时播放器显示设置指引，不伪装成已开始。
15. 独立翻译页只列出已启用服务；配置的默认服务被禁用时，自动选择
    第一个已启用服务，不把禁用服务当作可用。
16. 转写确认页的“插入草稿”载荷带录音开始时的 `:session-id`，不依赖
    当前界面会话；没有文字时不修改草稿。

消息翻译的选区消费：

- `translation-controls` 读取 `:draftText`（草稿全文）、`:selection`
  （`{:start :end :text}`，调用方捕获的草稿选区快照）与 `:onReplace`。
  有选区时翻译发送草稿片段，并记录 `:fragment?`、`:source-at-request`、
  `:request-start`、`:request-end`、`:request-text`；无选区时翻译消息
  正文，结果不可替换。
- 结果块只在 `translation-model/replace-plan` 返回 `:replace` 且
  `:onReplace` 是函数时显示“替换当前选区”。点击调用
  `onReplace {:start :end :expected-text :text}`；`expected-text` 是
  请求时的选中文字。
- 原文、选区起止或选中文字任一变化时，`replace-plan` 返回 `:blocked`；
  面板显示原因，只保留复制与插入草稿，不覆盖已变化范围。
- 没有 `:onReplace` 时不显示替换按钮，不伪造替换成功。

当前明确未做的部分：

- 客户端语音输入路径没有专用的全局默认语言设置命令；确认页只改这次
  录音，不显示无实现的“保存为默认”按钮。选区替换在独立翻译页的原文
  编辑区执行，并带原文/选区一致性检查。
- STT 服务测试需要部署时配置中性测试音频；未配置时测试按钮诚实显示
  `:not-run`，不伪造识别成功。
- 自动化验证覆盖服务器真实回环 HTTP、客户端编译、设置页与独立翻译页
  渲染；真实麦克风采集与扬声器播放需要浏览器权限和音频设备，未在
  无头环境中冒充已通过。

## 5 验证

语音输入、播放代次、设置提交、选区安全替换与消息翻译面板渲染：

```bash
clojure -M:test:server \
  -n hc.client.speech.input-model-test \
  -n hc.client.speech.playback-model-test \
  -n hc.client.speech.settings-model-test \
  -n hc.client.speech.translation-model-test
```

消息翻译面板的真实 React 渲染（Node，无需浏览器）：

```bash
rm -rf target/speech-ui
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/speech-ui/main.cjs"
        :output-dir "target/speech-ui"
        :main hc.client.speech.ui-test/-main
        :node-modules-dirs ["node_modules"]}' \
  -c hc.client.speech.ui-test
printf '{"type":"commonjs"}\n' > target/speech-ui/package.json
node target/speech-ui/main.cjs
```

浏览器构建与真实路径验证由 workbench/devops 运行：

```bash
clojure -Sdeps '{:deps {thheller/shadow-cljs {:mvn/version "3.5.11"}}}' \
  -M:client -m shadow.cljs.devtools.cli compile app
```

真实录音需要浏览器麦克风权限；TTS 与转写需要显式配置的服务端点。
未配置或外部服务不可达时，界面显示真实错误，不把按钮伪装成可用。
