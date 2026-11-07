# hc.server.speech — 语音输入与朗读适配

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本文件只说明本包的接口、状态与验证边界，不新增协议义务。

## 1 责任

本包把显式配置的语音服务接到服务器：

- **语音输入**：把一段原始录音发往一个 OpenAI 兼容的
  `POST <base-url><transcriptions-path>`，返回可编辑文字。
- **朗读**：把所选消息版本的可见正文发往一个 OpenAI 兼容的
  `POST <base-url><speech-path>`，返回音频字节。
- **录音保留**：在调用任何服务之前先原子写入录音与元数据。识别失败、
  认证失败、超时或服务不可达都不删除原始音频。
- **草稿优先**：转写结果只以 `:draft` 指令返回，`auto-send?` 恒为
  `false`。本包不创建用户消息，不调用聊天模型，也不替客户端发送。
- **二进制边界**：录音上传、录音读取、朗读音频读取和朗读请求走独立
  Ring 路由，不占用 Sente 通道的 1 MiB 边界。

本包不提供合成/假供应商。`kind` 只接受 `:openai-compatible`；没有
`base-url`、`model` 或真实端点时，配置直接失败。系统语音属于客户端；
服务器不伪装成 `:system` 供应商。

## 2 配置

根配置的 `:speech` 段：

```edn
{:speech
 {:stt {:services [{:id "local-whisper"
                    :label "Local Whisper"
                    :kind :openai-compatible
                    :base-url "http://127.0.0.1:8080"
                    :transcriptions-path "/v1/audio/transcriptions"
                    :model "whisper-1"
                    :api-key-env "WHISPER_API_KEY"
                    :processing :device
                    :enabled? true
                    :order 0
                    :languages ["en" "zh-CN"]
                    :connect-timeout-ms 5000
                    :request-timeout-ms 60000
                    :test-audio-path "/opt/herdchestra/test-phrase.wav"}]
        :default-service-id "local-whisper"
        :max-duration-ms 300000
        :max-audio-bytes 26214400
        :language "auto"
        :response-format "json"}
  :tts {:services [{:id "local-tts"
                    :label "Local TTS"
                    :kind :openai-compatible
                    :base-url "http://127.0.0.1:8080"
                    :speech-path "/v1/audio/speech"
                    :model "tts-1"
                    :voice "alloy"
                    :format "wav"
                    :speed 1.0
                    :processing :device
                    :enabled? true
                    :order 0}]
        :default-service-id "local-tts"
        :max-text-chars 20000
        :max-audio-bytes 52428800
        :format "wav"
        :speed 1.0
        :skip-code? true
        :skip-urls? true
        :skip-tables? true
        :skip-formulas? true
        :table-min-rows 4
        :notices {:en {:code-block "There is a code block."
                       :table "There is a table."
                       :url "There is a link."
                       :formula "There is a formula."
                       :image "Image."}
                  :zh-CN {:code-block "这里有代码块。"
                          :table "这里有表格。"
                          :url "这里有一个网址。"
                          :formula "这里有一个公式。"
                          :image "图片。"}}
        :test-text "Herdchestra speech test."}
  :translation {:services [{:id "local-translator"
                            :label "Local Translator"
                            :kind :openai-compatible
                            :base-url "http://127.0.0.1:8080"
                            :chat-path "/v1/chat/completions"
                            :model "translate-model"
                            :api-key-env "TRANSLATION_API_KEY"
                            :processing :device
                            :enabled? true
                            :order 0}]
                :default-service-id "local-translator"
                :max-text-chars 20000}}}
```

规则：

1. 语音输入、朗读与翻译是三套独立服务；输入默认服务不决定朗读或
   翻译默认服务。
2. `:processing` 只接受 `:device` 或 `:cloud`。省略时按保守值
   `:cloud` 处理并标记 `:processing-source :inferred`；回环地址不再自动
   声称“设备内”。声明 `:device` 但主机不是回环地址会被拒绝。
3. `base-url` 只接受 `http`/`https`，不得含用户信息、查询串或片段。
4. `:api-key` 只保留在当前进程内存；配置文件与快照都不写它。持久化
   形式是 `:api-key-env`。快照只报告 `:has-key?` 与 `:key-source`。启动
   时若发现手工写入的内联 key，会把配置文件原子重写为无 key 版本。
5. `:max-duration-ms` 不得超过 `300000`。单次输入超过五分钟时，录音
   仍会保留，但不会发往供应商。服务端从 WAV 容器计算时长，并取“声明
   时长/容器时长”的较大值；云端服务遇到无法验证的容器时拒绝发送。
6. 配置文件位于 `<root>/config.edn`，使用原子替换写入并带
   `:revision` 冲突检查。文件存在时优先于启动时的 `:speech` 段；首次
   保存后以文件为准。
7. 翻译结果只作为派生文本返回，`source-text` 与历史消息不被覆盖。
8. 非 token 用量只接受供应商明确返回的字段：STT 的
   `usage.seconds`/`usage.duration`、TTS 的显式 `x-usage-*` 响应头、
   翻译的 `usage.seconds`/`usage.characters`/`usage.calls`。本地录音
   时长、字节数、文本长度与请求耗时绝不当作计费数量；缺失时统计保持
   `:unknown`，不写 `0`。

本包是一次请求/响应适配：实时临时片段、麦克风权限、音频焦点和播放器
控件属于客户端。服务器接收最终录音，返回可编辑草稿或已保存音频。

## 3 命令

`(handle! component payload)` 处理以下 `:kind`：

| 命令 | 作用 |
|---|---|
| `:speech/snapshot` | 返回无密钥的服务、默认项、限制与瞬时状态。 |
| `:speech/save-service` | 新增或更新一个 `:stt`/`:tts`/`:translation` 服务；支持 `:expected-revision`、`:clear-api-key?`。 |
| `:speech/delete-service` | 删除服务配置；录音与转写不受影响。 |
| `:speech/set-default` | 设置某个区域的默认服务。 |
| `:speech/reorder-services` | 按 `:service-ids` 重排；必须覆盖现有全部服务。 |
| `:speech/test-service` | 发一次真实测试请求。输入测试只使用组件/服务配置注入的中性音频，拒绝请求自带的音频；没有配置时返回 `:not-run`，不伪造成功。翻译区域同样可用。 |
| `:speech/transcribe` | 接收 `:audio-base64`（小段音频）或直接音频字节，返回转写草稿指令。 |
| `:speech/retry-transcription` | 用已保存的 `:recording-id` 重试；不要求重新录音，不自动遍历服务。 |
| `:speech/recordings` / `:speech/recording` | 列出或读取录音元数据；不含绝对路径。 |
| `:speech/delete-recording` | 显式删除原始音频；已进入草稿/消息的转写不被连带删除。 |
| `:speech/synthesize` | 准备可朗读文本并生成音频；返回 `:audio-id`，客户端再走二进制路由取回。 |
| `:speech/audio` / `:speech/audios` | 读取朗读音频元数据；带 `:session-id` 时按会话隔离。 |
| `:speech/delete-audio` | 显式删除朗读音频。 |
| `:speech/translate` | 把 `:text` 从 `:source-language`（默认 `auto`）翻译到 `:target-language`；返回派生 `:translated-text`，不创建消息。 |

成功回执带 `:status`、`:receipt` 与相关 ID；可预期失败返回
`{:ok? false :error {:code ... :message ... :details ...}}`。

转写成功回执中的草稿指令：

```edn
{:action :insert-into-draft
 :text "..."
 :session-id "..."
 :insert-at 12
 :expected-revision 3
 :auto-send? false}
```

空转写返回 `:status :empty`，`:draft` 为 `nil`；不创建消息，也不触发
聊天费用。

## 4 二进制 HTTP 路由

`(handler component)` 只处理 `/speech/*`，其他路径返回 `nil`，由应用
路由继续交给网关。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/speech`、`/speech/snapshot` | 无密钥快照。 |
| `POST` | `/speech/recordings` | 请求体为音频字节；`x-hc-duration-ms`、`x-hc-language`、`x-hc-service-id`、`x-hc-session-id`、`x-hc-client-id`、`x-hc-filename` 可选；返回 EDN 转写回执。 |
| `GET` | `/speech/recordings` | 列出录音；`x-hc-session-id`、`x-hc-limit` 可选。 |
| `GET` | `/speech/recordings/<id>` | 原始音频字节。 |
| `GET` | `/speech/recordings/<id>/metadata` | 录音元数据。 |
| `POST` | `/speech/recordings/<id>/retry` | 用已保存音频重试；`x-hc-service-id` 等头同上传。 |
| `DELETE` | `/speech/recordings/<id>` | 删除录音。 |
| `POST` | `/speech/tts` | JSON `{text, voice, format, speed, serviceId, messageId, versionId}`；成功返回音频字节；`x-hc-partial: true` 表示已保留部分音频。头还包括 `x-hc-audio-id`、`x-hc-format`、`x-hc-service-id`、`x-hc-voice`。 |
| `GET` | `/speech/audio/<id>` | 朗读音频字节。 |
| `GET` | `/speech/audio/<id>/metadata` | 朗读音频元数据。 |
| `DELETE` | `/speech/audio/<id>` | 删除朗读音频。 |
| `POST` | `/speech/translate` | JSON `{text, sourceLanguage, targetLanguage, serviceId, messageId, versionId}`；返回 EDN 派生翻译结果。 |

EDN 路由使用 `application/edn; charset=utf-8`。错误码到 HTTP 状态的
映射在 `error-status`：参数错误 `400`，不存在 `404`，服务选择/修订冲突
`409`，上游不可达/超时 `504`，上游失败 `502`，其余 `422`。

## 5 保留与失败语义

1. 录音先写入 `<root>/recordings/<recording-id>/`，目录由临时目录一次
   rename 发布；音频与 `recording.edn` 同时可见。
2. 每次尝试记录在 `:attempts`；失败时 `:status :failed`，音频不动。
3. 重试只替换 `:service-id` 与本次尝试；不重新上传，不删除原音频。
4. 朗读音频写入 `<root>/audio/<audio-id>/`，元数据保留 `:session-id`。
   上游流提前结束或达到本地
   上限时，已收到的字节仍保存，`meta.edn` 标记 `:partial? true`，HTTP
   响应头 `x-hc-partial: true`。部分音频可播放，但不声称完整。
5. 删除只由显式命令执行；没有后台自动清理。禁用服务不删除历史转写与
   已保存音频。
6. 快照、错误详情与日志都不包含 `Authorization` 头或密钥值。
7. 供应商返回非 token 用量时，回执带 `:usage {:unit ... :quantity ...
   :quantity-source :provider-reported}`；若配置了 `:usage-port`，服务在
   成功响应后调用一次，供 diagnostics 记录。供应商未返回时不构造记录。
8. 响应体读取受 `:request-timeout-ms` 约束：超时后关闭响应流，已收到的
   字节按部分结果返回；不会无限等待供应商补足 `Content-Length`。

## 6 测试

```bash
clojure -M:test:server -n hc.server.speech.config-test
clojure -M:test:server -n hc.server.speech.text-test
clojure -M:test:server -n hc.server.speech.loopback-test
```

`loopback-test` 使用 JDK `com.sun.net.httpserver` 启动真实回环 HTTP 服务，
覆盖 multipart、Bearer 认证、失败保留、换服务重试、部分朗读流、二进制
上传/下载、翻译、非 token 用量与草稿优先。它们不是适配器边界的假端口：
请求真的经过 `java.net.http.HttpClient`。完整应用外壳的 `/speech/*`
路由由 `hc.server.app.system-test` 与浏览器工作台 smoke 另行验证，避免
在同一个测试 JVM 内启动两个完整应用生命周期。

未运行的项目不得标为通过；本文件不替代根 `AGENTS.md` §8.5 的 E2E 记录。
