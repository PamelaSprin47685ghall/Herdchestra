(ns hc.client.conversation.view
  "Framework7-styled React surface for conversation display and input.

  The component is intentionally thin: it reads the pure view model from
  `hc.client.conversation.surface` and turns it into elements.  It never
  constructs commands, command IDs, or domain terminal states.  Workbench
  callbacks own transport and command deduplication.

  The textarea is uncontrolled and keyed by session id, so server snapshots
  and sibling renders cannot replace the operator's text, cursor, selection,
  or IME composition.  An external draft is adopted only when the field is
  pristine, unfocused, or explicitly cleared; composition is never
  interrupted.  The message scroller separately records and restores a
  reader's DOM Range after a streamed character-data update, so arriving
  tokens never clear a live selection.  Enter is delegated to
  `composer/keydown`; Escape is the shell's responsibility and is not handled
  here."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.attachments :as attachments]
            [hc.client.conversation.composer :as composer]
            [hc.client.conversation.export :as export]
            [hc.client.conversation.history-edit :as history-edit]
            [hc.client.conversation.stream :as stream]
            [hc.client.copy.failures :as failures]
            [hc.client.conversation.surface :as surface]
            [hc.client.generated-card.view :as generated-card]
            [hc.client.preview.view :as preview]
            [hc.client.render :as render]
            [hc.client.search.model :as search-model]
            [hc.client.search.view :as search-view]
            [hc.client.sessions.labels :as session-labels]
            [hc.client.speech.playback :as speech-playback]
            [hc.client.speech.ui :as speech-ui]))

;; ---------------------------------------------------------------------------
;; Small DOM helpers

(def ^:private css
  ".hc-conversation{display:flex;flex-direction:column;min-height:0;height:100%;color:var(--f7-text-color,#111);background:var(--f7-page-bg-color,#fff)}
.hc-conversation *{box-sizing:border-box}
.hc-conversation-header{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px 16px;border-bottom:1px solid var(--f7-list-item-border-color,#d8d8dc)}
.hc-conversation-title{margin:0;font-size:1.1rem;line-height:1.3}
.hc-conversation-meta{display:flex;gap:10px;flex-wrap:wrap;font-size:.82rem;color:var(--f7-label-color,#555)}
.hc-stream-status{margin:6px 16px;padding:6px 10px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:8px;background:var(--f7-card-bg-color,#fafafa);font-weight:600;font-size:.86rem}
.hc-blocked{margin:6px 16px;padding:8px 10px;border:1px solid #a4000f;border-left-width:6px;border-radius:8px;background:#fff4f4;color:#5a0008;font-size:.86rem}
.hc-conversation-messages{flex:1;min-height:0;overflow-y:auto;padding:12px 16px;scroll-behavior:auto}
.hc-find{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:6px 16px;border-bottom:1px solid var(--f7-list-item-border-color,#d8d8dc);background:var(--f7-card-bg-color,#fafafa)}
.hc-find-input{flex:1;min-width:160px;min-height:32px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:8px;padding:4px 8px;font:inherit}
.hc-find-count{font-size:.85rem;color:var(--f7-label-color,#555);white-space:nowrap}
.hc-message{display:flex;flex-direction:column;margin:0 0 14px;max-width:min(760px,100%)}
.hc-message-out{align-self:flex-end;align-items:flex-end}
.hc-message-in{align-self:flex-start;align-items:flex-start}
.hc-message-body{border-radius:14px;padding:10px 13px;background:var(--f7-card-bg-color,#f7f7f9);white-space:pre-wrap;overflow-wrap:anywhere}
.hc-message-out .hc-message-body{background:var(--f7-color-primary,#0a84ff);color:#fff}
.hc-message-meta{font-size:.78rem;color:var(--f7-label-color,#555);margin:0 0 4px}
.hc-message-status{font-size:.78rem;color:var(--f7-label-color,#555);margin-top:4px}
.hc-process{margin:6px 0;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px;padding:6px 10px;background:var(--f7-card-bg-color,#fafafa)}
.hc-process summary{cursor:pointer;font-size:.86rem;font-weight:600;min-height:32px;display:flex;align-items:center}
.hc-process-item{margin:6px 0;font-size:.86rem}
.hc-process-item .hc-process-status{color:var(--f7-label-color,#555)}
.hc-code,.hc-table,.hc-math{margin:8px 0;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px;overflow-x:auto;background:var(--f7-card-bg-color,#fafafa)}
.hc-code pre,.hc-math pre{margin:0;padding:10px 12px;overflow-x:auto;white-space:pre}
.hc-code-head,.hc-math-head{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:4px 8px;border-bottom:1px solid var(--f7-list-item-border-color,#d8d8dc);font-size:.78rem;color:var(--f7-label-color,#555)}
.hc-table table{border-collapse:collapse;min-width:100%}
.hc-table th,.hc-table td{border:1px solid var(--f7-list-item-border-color,#d8d8dc);padding:6px 8px;text-align:left;vertical-align:top}
.hc-composer{position:sticky;bottom:0;z-index:20;display:flex;flex-direction:column;gap:8px;padding:10px 16px calc(10px + env(safe-area-inset-bottom,0px));border-top:1px solid var(--f7-list-item-border-color,#d8d8dc);background:var(--f7-page-bg-color,#fff)}
.hc-composer-row{display:flex;align-items:flex-end;gap:8px}
.hc-composer textarea{flex:1;min-height:44px;max-height:180px;resize:vertical;padding:10px 12px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);border-radius:12px;font:inherit;line-height:1.45;background:var(--f7-card-bg-color,#fff);color:inherit}
.hc-composer textarea:focus{outline:3px solid var(--f7-color-primary,#0a84ff);outline-offset:1px}
.hc-button{min-height:44px;min-width:44px;padding:8px 14px;border-radius:10px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);background:var(--f7-card-bg-color,#fff);color:inherit;font:inherit;cursor:pointer}
.hc-button-primary{background:var(--f7-color-primary,#0a84ff);border-color:var(--f7-color-primary,#0a84ff);color:#fff}
.hc-button[disabled],.hc-button[aria-disabled=true]{opacity:.55;cursor:not-allowed}
.hc-composer-hint,.hc-composer-save{font-size:.78rem;color:var(--f7-label-color,#555)}
.hc-reason{font-size:.8rem;font-weight:600;color:var(--f7-label-color,#555)}
.hc-attachments,.hc-quotes,.hc-ask-answers{display:flex;flex-direction:column;gap:6px;margin:0;padding:0;list-style:none}
.hc-chip{display:flex;align-items:center;gap:8px;justify-content:space-between;padding:6px 8px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:8px;font-size:.82rem}
.hc-banner{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:8px 16px;padding:8px 10px;border-radius:10px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);font-size:.88rem}
.hc-banner-error{border-color:#c62828;background:#ffebee;color:#7f0000}
.hc-banner-warning{border-color:#ef6c00;background:#fff3e0;color:#6d3b00}
.hc-banner-info{border-color:#1565c0;background:#e3f2fd;color:#0d3c6e}
.hc-jump{position:sticky;bottom:8px;align-self:center;z-index:6}
.hc-ask{margin:10px 16px;border:1px solid var(--f7-color-primary,#0a84ff);border-radius:12px;padding:12px;background:var(--f7-card-bg-color,#fff)}
.hc-ask h3{margin:0 0 6px;font-size:1rem}
.hc-ask-context{font-size:.86rem;color:var(--f7-label-color,#555);white-space:pre-wrap;margin:0 0 8px}
.hc-ask-options{display:flex;flex-direction:column;gap:8px;margin:8px 0}
.hc-ask-option{display:flex;align-items:center;gap:8px;min-height:44px;padding:6px 8px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:8px}
.hc-ask textarea{width:100%;min-height:80px;padding:10px 12px;border:1px solid var(--f7-list-item-border-color,#c8c8cc);border-radius:10px;font:inherit;background:inherit;color:inherit}
.hc-ask-status{font-size:.82rem;color:var(--f7-label-color,#555);margin:6px 0}
.hc-late-result{margin:6px 0;padding:6px 8px;border-left:4px solid #ef6c00;background:#fff3e0;color:#6d3b00;font-size:.84rem}
.hc-message-versions{display:flex;gap:4px;flex-wrap:wrap;margin:2px 0 6px}
.hc-version-button{min-height:32px;min-width:32px;padding:2px 8px;font-size:.78rem}
.hc-version-current{border-color:var(--f7-color-primary,#0a84ff);font-weight:600}
.hc-context-panel{margin:8px 16px;padding:10px 12px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:12px;background:var(--f7-card-bg-color,#fafafa);display:flex;flex-direction:column;gap:6px;font-size:.84rem}
.hc-context-header{display:flex;justify-content:space-between;gap:8px;align-items:center}
.hc-context-boundary,.hc-context-rounds,.hc-context-pins{display:flex;gap:8px;align-items:center;flex-wrap:wrap;color:var(--f7-label-color,#555)}
.hc-context-compression{display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:4px 0;border-top:1px solid var(--f7-list-item-border-color,#e4e4e8)}
.hc-context-warnings{color:#6d3b00;background:#fff3e0;border-radius:8px;padding:4px 8px}
.hc-visually-hidden{position:absolute!important;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media (prefers-reduced-motion:reduce){.hc-conversation *{scroll-behavior:auto!important;transition:none!important;animation:none!important}}
@media (max-width:599px){.hc-conversation-header,.hc-conversation-messages,.hc-composer{padding-left:10px;padding-right:10px}.hc-message{max-width:100%}}
/* Workbench theme bridge.  The surface may be mounted standalone, so every
   rule keeps the Framework7 fallback; inside the workbench the --wb-* palette
   wins and the conversation follows light/dark and high-contrast settings. */
.hc-conversation{color:var(--wb-ink,var(--f7-text-color,#111));background:var(--wb-bg,var(--f7-page-bg-color,#fff))}
.hc-conversation-header,.hc-find,.hc-composer{border-color:var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-conversation-meta,.hc-message-meta,.hc-message-status,.hc-composer-hint,.hc-composer-save,.hc-find-count,.hc-process-item .hc-process-status,.hc-context-boundary,.hc-context-rounds,.hc-context-pins,.hc-ask-status{color:var(--wb-ink-muted,var(--f7-label-color,#555))}
.hc-stream-status,.hc-process,.hc-code,.hc-table,.hc-math,.hc-context-panel,.hc-ask{background:var(--wb-surface,var(--f7-card-bg-color,#fafafa));border-color:var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-message-body{background:var(--wb-surface-2,var(--f7-card-bg-color,#f7f7f9));color:var(--wb-ink,inherit)}
.hc-message-out .hc-message-body{background:var(--wb-accent,var(--f7-color-primary,#0a84ff));color:var(--wb-accent-ink,#fff)}
.hc-code-head,.hc-math-head,.hc-table th,.hc-table td{border-color:var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-composer{background:var(--wb-bg,var(--f7-page-bg-color,#fff))}
.hc-composer textarea,.hc-find-input,.hc-button{background:var(--wb-surface,var(--f7-card-bg-color,#fff));border-color:var(--wb-line,var(--f7-list-item-border-color,#c8c8cc));color:var(--wb-ink,inherit)}
.hc-button-primary{background:var(--wb-accent,var(--f7-color-primary,#0a84ff));border-color:var(--wb-accent,var(--f7-color-primary,#0a84ff));color:var(--wb-accent-ink,#fff)}
.hc-find{background:var(--wb-surface,var(--f7-card-bg-color,#fafafa))}
.hc-message-actions{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-top:6px}
.hc-message-actions .hc-button{min-height:32px}
.hc-message-more{display:flex;justify-content:center;padding:8px 0}
.hc-message-stream{display:flex;flex-direction:column;width:100%;max-width:820px;margin:0 auto;min-height:0}
/* The materials surface also owns a global `.hc-message` rule.  Reset its
   border/padding/background/font-size inside the conversation so a message
   is a bubble, not a nested card, and the body inherits the page size. */
.hc-conversation .hc-message{border:0;border-radius:0;padding:0;margin:0 0 14px;background:transparent;font-size:inherit;min-width:0}
.hc-conversation .hc-table{font-size:inherit;width:auto;max-width:100%;min-width:0}
.hc-conversation-messages{overflow-x:hidden}
.hc-message-body{max-width:100%;min-width:0}
.hc-conversation .hc-chip{margin:0}
.hc-export-controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:8px;padding-top:8px;border-top:1px solid var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-export-selection{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:8px 16px;padding:8px 10px;border:1px solid var(--wb-line,var(--f7-list-item-border-color,#d8d8dc));border-radius:10px;background:var(--wb-surface,var(--f7-card-bg-color,#fafafa));font-size:.86rem}
.hc-inline-edit{display:flex;flex-direction:column;gap:6px;margin-top:6px}
.hc-inline-edit-panel{display:flex;flex-direction:column;gap:8px;padding:8px;border:1px solid var(--wb-line,var(--f7-list-item-border-color,#d8d8dc));border-radius:10px;background:var(--wb-surface,var(--f7-card-bg-color,#fafafa))}
.hc-round-context{margin-top:6px;border-top:1px solid var(--wb-line,var(--f7-list-item-border-color,#e4e4e8));padding-top:6px}
.hc-round-section{margin:6px 0;padding:6px 8px;border-left:3px solid var(--wb-accent,var(--f7-color-primary,#0a84ff));border-radius:6px;background:var(--wb-surface-2,var(--f7-card-bg-color,#f7f7f9))}
.hc-round-section h4{margin:0 0 4px;font-size:.86rem}
.hc-round-fact{display:flex;gap:6px;flex-wrap:wrap;font-size:.82rem}
.hc-round-item{font-size:.82rem;margin:2px 0;overflow-wrap:anywhere}
.hc-round-warn{color:var(--wb-warn,#8a5300)}
.hc-compression-preparer{margin-top:6px}
.hc-context-panel>summary{cursor:pointer;min-height:32px;display:flex;align-items:center;justify-content:space-between;gap:8px;list-style:none}
.hc-context-panel>summary::-webkit-details-marker{display:none}
.hc-context-panel>summary::after{content:\"展开\";font-size:.78rem;color:var(--wb-ink-muted,var(--f7-label-color,#555));white-space:nowrap}
.hc-context-panel[open]>summary::after{content:\"收起\"}
@media (max-width:599px){
  .hc-context-panel[open]{position:fixed;left:8px;right:8px;top:64px;z-index:80;max-height:70vh;overflow:auto;margin:8px;box-shadow:var(--wb-shadow,0 12px 40px rgba(16,30,45,.16));background:var(--wb-surface,var(--f7-card-bg-color,#fafafa))}
}
.hc-attachment-thumb{display:block;max-width:100%;max-height:160px;width:auto;border-radius:8px;border:1px solid var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-attachments .hc-chip{justify-content:flex-start;flex-wrap:wrap}
.hc-table table{width:max-content;min-width:100%}
.hc-table th,.hc-table td{max-width:36rem}
.hc-inline-code{padding:1px 4px;border-radius:4px;background:var(--wb-surface-2,var(--f7-card-bg-color,#f0f0f3));font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.92em}
.hc-heading{margin:10px 0 6px;line-height:1.3}
.hc-quote{margin:6px 0;padding:4px 10px;border-left:3px solid var(--wb-accent,var(--f7-color-primary,#0a84ff));color:var(--wb-ink-muted,var(--f7-label-color,#555));background:var(--wb-surface-2,var(--f7-card-bg-color,#f7f7f9));border-radius:0 6px 6px 0}
.hc-list{margin:4px 0 8px;padding-left:22px}
.hc-list li{margin:2px 0}
.hc-link{color:var(--wb-accent,var(--f7-color-primary,#0a84ff));text-decoration:underline}
.hc-inline-image{max-width:100%;height:auto;border-radius:8px}
.hc-rule{border:0;border-top:1px solid var(--wb-line,var(--f7-list-item-border-color,#d8d8dc));margin:10px 0}
.hc-banner{border-color:var(--wb-line,var(--f7-list-item-border-color,#d8d8dc))}
.hc-banner-error{border-color:var(--wb-danger,#c62828);background:#ffebee;background:color-mix(in srgb,var(--wb-danger,#c62828) 12%,var(--wb-surface,#fff));color:var(--wb-danger,#7f0000)}
.hc-banner-warning{border-color:var(--wb-warn,#ef6c00);background:#fff3e0;background:color-mix(in srgb,var(--wb-warn,#ef6c00) 14%,var(--wb-surface,#fff));color:var(--wb-warn,#6d3b00)}
.hc-banner-info{border-color:var(--wb-accent,#1565c0);background:#e3f2fd;background:color-mix(in srgb,var(--wb-accent,#1565c0) 12%,var(--wb-surface,#fff));color:var(--wb-accent,#0d3c6e)}
.hc-blocked{border-color:var(--wb-danger,#a4000f);border-left-color:var(--wb-danger,#a4000f);background:#fff4f4;background:color-mix(in srgb,var(--wb-danger,#a4000f) 12%,var(--wb-surface,#fff));color:var(--wb-danger,#5a0008)}
.hc-late-result{border-left-color:var(--wb-warn,#ef6c00);background:#fff3e0;background:color-mix(in srgb,var(--wb-warn,#ef6c00) 14%,var(--wb-surface,#fff));color:var(--wb-warn,#6d3b00)}
.hc-context-warnings{background:#fff3e0;background:color-mix(in srgb,var(--wb-warn,#ef6c00) 14%,var(--wb-surface,#fff));color:var(--wb-warn,#6d3b00)}
@media (max-width:599px){.hc-conversation .hc-button{min-height:44px!important;min-width:44px!important}}")

(defn- style [m]
  (clj->js m))

(defn- class-names [& values]
  (str/join " " (remove nil? values)))

(defn presence-control
  "Presence notice for the workbench conversation header.

  Only an observer sees it: a controller needs no notice, and a free session
  is attached automatically by `presence-bridge`.  The takeover control is a
  real button with a stable id so the workbench and browser tests can find
  it; rendering never sends a command."
  [{:keys [presence on-takeover! disabled?]}]
  (when (and (map? presence) (:takeover? presence))
    (dom/div {:id "hc-presence-notice"
              :className "hc-presence-notice"
              :role "status"
              :aria-live "polite"
              :style (style {:display "flex"
                             :alignItems "center"
                             :gap "8px"
                             :margin "8px 16px"})}
             (dom/span {:style (style {:fontWeight "600"})}
                       (:label presence))
             (dom/button {:id "hc-takeover-control"
                          :type "button"
                          :className "hc-button"
                          :aria-label "接管会话控制"
                          :title (when disabled?
                                   "连接建立并同步后才能接管")
                          :disabled (boolean disabled?)
                          :onClick (fn [_]
                                     (when (fn? on-takeover!)
                                       (on-takeover!)))}
                         "接管控制"))))

(defn- command-map
  "Accept both documented command shapes: one command map, or a map keyed by
  command id.  The workbench passes the keyed map; the single-command form is
  what AGENTS.md §3.2 describes."
  [command]
  (cond
    (and (map? command) (:command-id command)) {(:command-id command) command}
    (map? command) command
    :else {}))

(defn- pinned-attachment-ids
  [session]
  (let [context (:context session)]
    (set (or (:pinned-attachment-ids context)
             (map :attachment-id (:pinned-attachments context))
             []))))

(defn- warning-text
  [warning]
  (let [code (some-> (:code warning) keyword)]
    (case code
      :CONTEXT_BOUNDARY_MISSING
      "上下文起点已失效；发送范围从当前可见历史重新计算"
      :PINNED_ATTACHMENT_LIMIT_EXCEEDED
      (str "固定附件 " (or (:count warning) "?")
           " 个，超过模型上限 " (or (:limit warning) "?")
           "；未自动取消固定")
      :PINNED_ATTACHMENT_MISSING
      "有固定附件已缺失；请检查附件列表"
      (or (:message warning)
          "上下文警告"))))

(defn- clip-views
  "Tolerant projection of `:clips` (Pair Hint / Scope boundaries).  The server
  owns the shape; render only readable label/summary fields and never a whole
  arbitrary map."
  [clips]
  (let [entries (cond
                  (map? clips) (seq clips)
                  (sequential? clips) (map-indexed vector clips)
                  :else nil)]
    (mapv (fn [[key value]]
            (let [label (if (map? value)
                          (or (:label value) (:kind value) (:name value) (str key))
                          (str key))
                  summary (cond
                            (map? value)
                            (or (:summary value) (:reason value) (:message value))
                            (some? value) (str value)
                            :else nil)]
              {:key (str key)
               :text (str label (when summary (str "：" summary)))}))
          entries)))

;; ---------------------------------------------------------------------------
;; Token labels
;;
;; Internal keywords (`:reported`, `:read-write`, `:file`, tool statuses)
;; must never reach the DOM as English words.  Each helper maps the server
;; vocabulary to Chinese and falls back to "未知" for unrecognized values.

(defn- usage-source-label
  "Provenance of a usage figure.  Unknown values never leak an English
  token into the message meta line."
  [source]
  (case (some-> source keyword)
    :reported "实测"
    :estimated "估算"
    :unknown "未知"
    :model "模型"
    :manual "手动"
    :regenerated "重新生成"
    :branch "分支"
    :imported "导入"
    "未知"))

(defn- estimate-kind-label
  [value]
  (case (some-> value keyword)
    :estimated "估算"
    :tokens "token 估算"
    :chars "字符估算"
    :unknown "未知"
    "未知"))

(defn- late-result-status-label
  "A late tool result keeps its honest status; the status word is localized
  so `failed` never reaches the message list as an English token."
  [status]
  (case (some-> status keyword)
    :succeeded "已完成"
    :success "已完成"
    :completed "已完成"
    :complete "已完成"
    :done "已完成"
    :failed "失败"
    :error "失败"
    :cancelled "已取消"
    :canceled "已取消"
    :outcome-unknown "结果未确认"
    :unknown "结果未确认"
    :running "执行中"
    :pending "执行中"
    "未知状态"))

(defn- usage-label
  "Display only what the server actually reported.  An absent token count is
  `未知`, never `0`."
  [usage]
  (when (map? usage)
    (let [input (:input-tokens usage)
          output (:output-tokens usage)
          source (:source usage)]
      (when (or (some? input) (some? output))
        (str "用量 " (or input "未知") " / " (or output "未知")
             (when source
               (str "（" (usage-source-label source) "）")))))))

(defn- session-status-label
  "User-visible session state.  `active` and `idle` are the normal working
  state and add no information; internal keywords never reach the header."
  [status]
  (case (some-> status keyword)
    :active nil
    :idle nil
    :archived "已归档"
    :deleted "已删除"
    :temporary "临时"
    :paused "已暂停"
    :error "错误"
    nil))

(defn- preview-component
  "Stable adapter for the generated-HTML preview component, which expects a
  CLJS props map.  React renders this wrapper so the preview's hooks stay in
  the preview component."
  [js-props]
  (preview/generated-html-preview (.-props js-props)))

(defn- html-preview
  [props]
  (react/createElement preview-component
                       #js {:key (:key props)
                            :props (dissoc props :key)}))

(defn- component-props
  "Unwrap the `#js {:props cljs-map}` form used by internal React elements.
  Calling a hook-using component directly would merge its hooks into the
  caller, so internal controls are real elements with this wrapper."
  [props]
  (if (map? props)
    props
    (let [inner (some-> props .-props)]
      (if (map? inner) inner (js->clj props :keywordize-keys true)))))

(defn- copy-text!
  [text]
  (when (and text (pos? (count text)))
    (try
      (if-let [clipboard (.-clipboard js/navigator)]
        (.writeText clipboard text)
        (let [node (.createElement js/document "textarea")]
          (set! (.-value node) text)
          (set! (.-position (.-style node)) "fixed")
          (set! (.-opacity (.-style node)) "0")
          (.appendChild js/document.body node)
          (.select node)
          (.execCommand js/document "copy")
          (.removeChild js/document.body node)))
      (catch :default _ nil))))

(defn- download-text!
  "Offer TEXT as a real local file through the browser's download path.

  Returns true only when the anchor click was dispatched.  The browser owns
  the save; a dispatched download is not a completed save.

  The workbench mounts Framework7, whose clicks module installs a
  document-level capture listener and calls `preventDefault()` for every
  anchor.  That suppresses a programmatic download even though the handler
  ran and the anchor is connected.  A one-shot window capture listener stops
  propagation for this anchor only; stopping propagation does not cancel the
  browser's default download action.  Other clicks keep their normal path.

  The object URL and the empty anchor also stay alive for one second, so the
  browser can resolve the blob before cleanup."
  [text filename mime]
  (if (and (some? text)
           (exists? js/document) (exists? js/Blob) (exists? js/URL))
    (try
      (let [blob (js/Blob. #js [(str text)]
                           #js {:type (or mime "text/markdown;charset=utf-8")})
            url (.createObjectURL js/URL blob)
            anchor (.createElement js/document "a")]
        (set! (.-href anchor) url)
        (set! (.-download anchor) (or filename "herdchestra-export.md"))
        (set! (.-rel anchor) "noopener")
        (.appendChild js/document.body anchor)
        (let [stop-global-click!
              (fn [event]
                (when (identical? anchor (.-target event))
                  (.stopPropagation event)))]
          (.addEventListener js/window "click" stop-global-click! true)
          (try
            (.click anchor)
            (finally
              (.removeEventListener js/window "click" stop-global-click! true))))
        (js/setTimeout
         (fn []
           (try
             (.removeChild js/document.body anchor)
             (catch :default _ nil))
           (try
             (.revokeObjectURL js/URL url)
             (catch :default _ nil)))
         1000)
        true)
      (catch :default error
        (when (exists? js/console)
          (.error js/console "Herdchestra export download failed" error))
        false))
    false))

(defn- offer-download!
  "Offer TEXT as a real browser download and surface a visible failure.

  `on-error` receives nil after a successful dispatch or a short message when
  the browser refused to create the download.  A dispatched download is not a
  completed save; the browser owns the save location and confirmation."
  [text filename mime on-error]
  (if (download-text! text filename mime)
    (do
      (when (fn? on-error) (on-error nil))
      true)
    (do
      (when (fn? on-error)
        (on-error "浏览器未能开始下载；导出内容仍在，可重试。"))
      false)))

;; ---------------------------------------------------------------------------
;; Message rendering

(defn- split-table-row
  "Split one table row on unescaped pipes.  `\\|` is a literal cell character,
  not a separator; the backslash itself is not display content."
  [line]
  (loop [chars (seq line) escaped? false cell "" cells []]
    (if-let [ch (first chars)]
      (cond
        escaped? (recur (next chars) false (str cell ch) cells)
        (= ch \\) (recur (next chars) true cell cells)
        (= ch \|) (recur (next chars) false "" (conj cells cell))
        :else (recur (next chars) false (str cell ch) cells))
      (conj cells cell))))

(defn- table-rows
  [content]
  (let [lines (str/split-lines (or content ""))
        rows (mapv (fn [line]
                     (->> (split-table-row line)
                          (map str/trim)
                          (drop 1)
                          (drop-last)
                          vec))
                   lines)]
    (filterv (fn [row]
               (not (every? #(re-matches #":?-{2,}:?" %) row)))
             rows)))

(defn- render-table
  [segment]
  (let [rows (table-rows (:content segment))]
    (if (empty? rows)
      (dom/pre {:className "hc-table-fallback"} (:content segment))
      (let [[header & body] rows]
        (dom/div {:className "hc-table"}
                 (dom/table nil
                            (dom/thead nil
                                       (dom/tr nil
                                               (for [cell header]
                                                 (dom/th {:key (str "h-" cell)} cell))))
                            (dom/tbody nil
                                       (for [[index row] (map-indexed vector body)]
                                         (dom/tr {:key (str "r-" index)}
                                                 (for [[cell-index cell] (map-indexed vector row)]
                                                   (dom/td {:key (str "c-" index "-" cell-index)} cell)))))))))))

(def ^:private inline-markdown-pattern
  ;; Group 1 is inline math `$…$`; the rest follow the original order so
  ;; code spans keep winning over formula delimiters inside backticks.
  ;; The `$…$` alternative mirrors `hc.client.render/inline-math-pattern`:
  ;; digit guards skip currency, dollar guards skip `$$…$$` display math.
  #"(?<!\d)(?<!\$)\$([^\s$](?:[^$]*[^\s$])?)\$(?!\d)(?!\$)|`([^`]+)`|!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]*)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|_([^_]+)_")

(defn- inline-nodes
  "Render conservative inline Markdown.  Anything the pattern does not
  recognize stays a literal string, so a malformed marker never removes the
  reader's text."
  [text key-prefix]
  (if-not (and (string? text) (re-find inline-markdown-pattern text))
    [text]
    (let [total (count text)]
      (loop [matches (seq (re-seq inline-markdown-pattern text))
             end 0 index 0 nodes []]
        (if-let [match (first matches)]
          (let [full (get match 0)
                start (or (str/index-of text full end) end)
                stop (+ start (count full))
                group (fn [n] (get match n))
                key (str key-prefix "-i" index)
                node (cond
                       ;; Groups: 1 math, 2 code, 3-4 image, 5-6 link,
                       ;; 7-8 strong, 9-10 emphasis.
                       (some? (group 1))
                       (dom/code {:className "hc-inline-code hc-inline-math" :key key}
                                 (group 1))

                       (some? (group 2))
                       (dom/code {:className "hc-inline-code" :key key} (group 2))

                       (some? (group 4))
                       (dom/img {:className "hc-inline-image" :key key
                                 :src (group 4) :alt (or (group 3) "")
                                 :loading "lazy"})

                       (some? (group 6))
                       (dom/a {:className "hc-link" :key key
                               :href (group 6) :target "_blank" :rel "noreferrer"}
                              (group 5))

                       (some? (group 7))
                       (dom/strong {:key key} (group 7))

                       (some? (group 8))
                       (dom/strong {:key key} (group 8))

                       (some? (group 9))
                       (dom/em {:key key} (group 9))

                       (some? (group 10))
                       (dom/em {:key key} (group 10))

                       :else nil)
                nodes (cond-> nodes
                        (< end start) (conj (subs text end start))
                        node (conj node))]
            (recur (next matches) stop (inc index) nodes))
          (cond-> nodes
            (< end total) (conj (subs text end total))))))))

(defn- markdown-block-start?
  [line]
  (boolean
   (or (re-find #"^\s{0,3}#{1,6}\s" line)
       (re-find #"^\s{0,3}[-*+]\s" line)
       (re-find #"^\s{0,3}\d+[.)]\s" line)
       (re-find #"^\s{0,3}>\s?" line)
       (re-find #"^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$" line))))

(defn- block-markdown?
  [text]
  (boolean (some markdown-block-start? (str/split-lines (or text "")))))

(defn- markdown-blocks
  "Split text into display blocks without dropping unrecognized lines.

  This is deliberately smaller than a general Markdown parser: headings,
  lists, quotes, rules, and paragraphs.  Fenced code, display math, and
  tables already arrive as their own segments from the timeline."
  [text]
  (let [lines (str/split-lines (or text ""))]
    (loop [lines lines blocks []]
      (if (empty? lines)
        blocks
        (let [line (first lines)]
          (cond
            (str/blank? line)
            (recur (rest lines) blocks)

            (re-matches #"\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*" line)
            (recur (rest lines) (conj blocks {:type :hr}))

            (re-matches #"\s{0,3}#{1,6}\s+.*" line)
            (let [[_ hashes content]
                  (re-matches #"\s{0,3}(#{1,6})\s+(.*)" line)]
              (recur (rest lines)
                     (conj blocks {:type :heading
                                   :level (count hashes)
                                   :content content})))

            (re-matches #"\s{0,3}>\s?.*" line)
            (let [[quoted remaining]
                  (split-with #(re-matches #"\s{0,3}>\s?.*" %) lines)]
              (recur remaining
                     (conj blocks
                           {:type :quote
                            :content (str/join
                                      "\n"
                                      (map #(str/replace-first
                                             % #"^\s{0,3}>\s?" "")
                                           quoted))})))

            (re-matches #"\s{0,3}[-*+]\s+.*" line)
            (let [[items remaining]
                  (split-with #(re-matches #"\s{0,3}[-*+]\s+.*" %) lines)]
              (recur remaining
                     (conj blocks
                           {:type :ul
                            :items (mapv #(str/replace-first
                                           % #"^\s{0,3}[-*+]\s+" "")
                                         items)})))

            (re-matches #"\s{0,3}\d+[.)]\s+.*" line)
            (let [[items remaining]
                  (split-with #(re-matches #"\s{0,3}\d+[.)]\s+.*" %) lines)]
              (recur remaining
                     (conj blocks
                           {:type :ol
                            :items (mapv #(str/replace-first
                                           % #"^\s{0,3}\d+[.)]\s+" "")
                                         items)})))

            :else
            (let [[paragraph remaining]
                  (split-with #(and (not (str/blank? %))
                                    (not (markdown-block-start? %)))
                              lines)]
              (recur remaining
                     (conj blocks {:type :paragraph
                                   :content (str/join "\n" paragraph)})))))))))

(defn- block-nodes
  [block key-prefix]
  (let [nodes (inline-nodes (:content block) key-prefix)
        props {:className "hc-text"
               :key key-prefix
               :style (style {:whiteSpace "pre-wrap" :margin "0 0 8px"})}]
    (case (:type block)
      :heading
      (let [heading-props {:className "hc-heading" :key key-prefix}]
        (case (:level block)
          1 (apply dom/h1 heading-props nodes)
          2 (apply dom/h2 heading-props nodes)
          3 (apply dom/h3 heading-props nodes)
          4 (apply dom/h4 heading-props nodes)
          5 (apply dom/h5 heading-props nodes)
          (apply dom/h6 heading-props nodes)))

      :hr
      (dom/hr {:className "hc-rule" :key key-prefix})

      :quote
      (apply dom/blockquote {:className "hc-quote" :key key-prefix} nodes)

      :ul
      (dom/ul {:className "hc-list" :key key-prefix}
              (for [[index item] (map-indexed vector (:items block))]
                (apply dom/li {:key (str key-prefix "-li-" index)}
                       (inline-nodes item (str key-prefix "-li-" index)))))

      :ol
      (dom/ol {:className "hc-list" :key key-prefix}
              (for [[index item] (map-indexed vector (:items block))]
                (apply dom/li {:key (str key-prefix "-li-" index)}
                       (inline-nodes item (str key-prefix "-li-" index)))))

      (apply dom/p props nodes))))

(defn- segment-block-plain
  [segment index target kind]
  (case (:type segment)
    :code
    (dom/div {:className "hc-code" :key (str "code-" index)}
             (dom/div {:className "hc-code-head"}
                      (dom/span nil (or (not-empty (:lang segment)) "代码"))
                      (dom/button {:className "hc-button"
                                   :type "button"
                                   :style (style {:minHeight target :minWidth target})
                                   :onClick #(copy-text! (:content segment))}
                                  "复制代码"))
             (dom/pre nil (dom/code nil (:content segment)))
             (when (= "html" (some-> (:lang segment) str/lower-case))
               (html-preview {:html (:content segment)
                              :source-label "生成的 HTML"
                              :streaming? (not (:closed? segment))})))

    :math
    (dom/div {:className "hc-math" :key (str "math-" index)}
             (dom/pre nil (:content segment)))

    :table
    (if (:closed? segment)
      (dom/div {:key (str "table-" index)} (render-table segment))
      (dom/pre {:className "hc-table hc-table-fallback"
                :key (str "table-" index)
                :style (style {:overflowX "auto" :padding "10px 12px"})}
               (:content segment)))

    (if (block-markdown? (:content segment))
      (dom/div {:className "hc-markdown"
                :key (str "text-" index)}
               (for [[block-index block]
                     (map-indexed vector (markdown-blocks (:content segment)))]
                 (block-nodes block (str "text-" index "-" block-index))))
      (let [nodes (inline-nodes (:content segment) (str "text-" index))
            props {:className "hc-text"
                   :key (str "text-" index)
                   :style (style {:whiteSpace "pre-wrap" :margin "0 0 8px"})}]
        (apply dom/p props nodes)))))

(defn- segment-block
  [segment index target]
  (let [kind (render/block-kind segment)]
    (if (#{:mermaid :math} kind)
      (let [label (render/block-label kind)
            class-name (if (= :math kind) "hc-math" "hc-code")]
        (dom/div {:className class-name :key (str "special-" kind "-" index)}
                 (dom/div {:className (str class-name "-head")}
                          (dom/span nil label)
                          (dom/button {:className "hc-button"
                                       :type "button"
                                       :style (style {:minHeight target
                                                      :minWidth target})
                                       :onClick #(copy-text! (:content segment))}
                                    (render/copy-label kind)))
                 (dom/pre nil (dom/code nil (:content segment)))))
      (segment-block-plain segment index target kind))))

(defn- tool-access-label
  "Tool read/write access in the outbound round snapshot."
  [value]
  (case (some-> value keyword)
    :read "只读"
    :read-write "读写"
    :write "写入"
    :unknown "未知"
    "未知"))

(defn- attachment-kind-label
  "Attachment kind in the outbound round snapshot.  An unknown server kind
  falls back to a noun, never its English keyword."
  [value]
  (case (some-> value keyword)
    :image "图片"
    :pdf "PDF"
    :text "文本"
    :audio "音频"
    :video "视频"
    :archive "压缩包"
    :file "文件"
    :unknown "未知"
    "文件"))

(defn- session-title-label
  "The server's default title is English; the UI is Chinese.  A manual title
  passes through unchanged."
  [title]
  (session-labels/title-label title))

(defn- banner-text-label
  "Localize the browser failure the server still returns in English.

  The browser panel normalizes the same strings in
  `hc.client.browser.model/error-text`; a conversation banner can carry them
  too, and an English duplicate is not acceptable on the empty-install
  screen."
  [text]
  (case text
    "managed browser is not connected for this session"
    "本会话尚未连接受管浏览器。"
    "The operator has not enabled the managed browser for this session."
    "操作员尚未为本会话启用受管浏览器。"
    "The operator has not enabled the managed browser."
    "操作员尚未启用受管浏览器。"
    text))

(defn- process-block
  [process target]
  (when (seq process)
    (dom/details {:className "hc-process"}
                 (dom/summary nil (str "过程 · " (count process) " 项"))
                 (for [item process]
                   (dom/div {:className "hc-process-item" :key (:id item)}
                            (dom/strong nil (:title item))
                            (when-let [summary (:summary item)]
                              (dom/div {:style (style {:whiteSpace "pre-wrap"})} summary))
                            (when-let [status (:status-label item)]
                              (dom/div {:className "hc-process-status"}
                                       (if (contains? #{:failed :error :refused}
                                                      (:status item))
                                         (let [copy (failures/failure-copy
                                                     {:code :tool-failed
                                                      :context {:saved? false}})]
                                           (str (:title copy) "：" (:body copy)))
                                         status)))
                            (when (= :outcome-unknown (:status item))
                              (dom/div {:className "hc-process-ack"}
                                       (dom/span nil
                                                 "先核对目标状态；确认后只结清记录，不自动重放。")
                                       (dom/button {:className "hc-button"
                                                    :type "button"
                                                   :style (style {:minHeight target
                                                                  :minWidth target})
                                                    :onClick (fn [_]
                                                               (when (exists? js/window)
                                                                 (set! (.-hash js/window.location)
                                                                       "#/board")))}
                                                   "去看板核对并结清"))))))))

(defn- attachment-list
  [attachments {:keys [callbacks session-id pinned-ids target]}]
  (when (seq attachments)
    (dom/div {:className "hc-attachment-stack"}
             (dom/ul {:className "hc-attachments"}
                     (for [attachment attachments
                           :let [facts (attachments/attachment-facts attachment)]]
                       (dom/li {:className "hc-chip" :key (:id attachment)}
                               (when (and (:url attachment)
                                          (str/starts-with?
                                           (str/lower-case (str (:mime attachment)))
                                           "image/"))
                                 (dom/img {:className "hc-attachment-thumb"
                                           :src (:url attachment)
                                           :alt (str (:name attachment) " 缩略图")
                                           :loading "lazy"}))
                               (dom/span nil (:name attachment))
                               (when-let [size (:size-label attachment)]
                                 (dom/span nil size))
                               (when-let [page-label (:page-label facts)]
                                 (dom/span nil page-label))
                               (when-let [unreadable-label (:unreadable-label facts)]
                                 (dom/span {:className "hc-attachment-unreadable"}
                                           unreadable-label))
                               (when-let [copy-label (:copy-label facts)]
                                 (dom/span nil copy-label))
                               (when-let [workspace-label (:workspace-label facts)]
                                 (dom/span {:className "hc-attachment-workspace"}
                                           workspace-label))
                               (when-let [url (:url attachment)]
                                 (dom/a {:href url :target "_blank" :rel "noreferrer"} "打开"))
                               (when-let [attachment-id (:id attachment)]
                                 (let [pinned? (contains? (or pinned-ids #{}) attachment-id)
                                       callback (if pinned?
                                                  (:history/unpin-attachment! callbacks)
                                                  (:history/pin-attachment! callbacks))]
                                   (when (fn? callback)
                                     (dom/button
                                      {:className "hc-button"
                                       :type "button"
                                       :style (style {:minHeight target :minWidth target})
                                       :aria-pressed (if pinned? "true" "false")
                                       :aria-label (str (if pinned? "取消固定 " "固定 ")
                                                        (:name attachment))
                                       :onClick (fn [_]
                                                  (callback {:session-id session-id
                                                             :attachment-id attachment-id}))}
                                      (if pinned? "取消固定" "固定"))))))))
             (for [attachment attachments
                   :when (or (re-find #"(?i)text/html" (str (:mime attachment)))
                             (str/ends-with? (str/lower-case (str (:name attachment))) ".html"))]
               (html-preview {:key (str "preview-" (:id attachment))
                              :url (:url attachment)
                              :source-label (:name attachment)})))))

(defn- version-selector
  [message callbacks session-id target]
  (let [versions (vec (or (:versions message) []))]
    (when (and (> (count versions) 1)
               (fn? (:history/select-version! callbacks)))
      (dom/div {:className "hc-message-versions"
                :role "group"
                :aria-label "消息版本"}
               (for [[index version] (map-indexed vector versions)]
                 (dom/button
                  {:className (class-names "hc-button hc-version-button"
                                           (when (= index (:current-version message))
                                             "hc-version-current"))
                   :type "button"
                   :key (str (:id message) "-version-" index)
                   :style (style {:minHeight target :minWidth target})
                   :aria-pressed (if (= index (:current-version message))
                                   "true" "false")
                   :onClick #(do
                              ((:history/select-version! callbacks)
                               {:session-id session-id
                                :message-id (:id message)
                                :version-id (:version-id version)})
                              ;; A version switch changes the visible
                              ;; transcript.  Read-aloud belongs to the old
                              ;; version, so stop it before the new version
                              ;; renders; the operator can start a new read
                              ;; on the selected version.
                              (speech-playback/stop!))}
                  (str "v" (inc index))))))))

(defn- compression-row
  "Draft compression summary is editable before apply.  Applied rows only
  offer undo; they never silently rewrite the frozen summary."
  [props]
  (let [{:keys [compression callbacks session-id]} (component-props props)
        state (:state compression)
        id (:compression-id compression)
        [summary set-summary!] (react/useState (or (:summary compression) ""))]
    (dom/div {:className "hc-context-compression" :key id}
             (dom/span nil (str "压缩 "
                                (case state
                                  :draft "草稿"
                                  :applied "已采用"
                                  :reverted "已撤销"
                                  :undone "已撤销"
                                  (or (some-> state name) "未知"))
                                "："))
             (if (= :draft state)
               (dom/div {:className "hc-inline-edit-panel"
                         :style (style {:display "flex" :flexDirection "column" :gap 6})}
                        (dom/textarea {:value summary
                                       :aria-label "压缩摘要"
                                       :onChange (fn [event]
                                                   (set-summary! (.-value (.-target event))))
                                       :style (style {:width "100%" :minHeight 64})})
                        (dom/button {:className "hc-button" :type "button"
                                     :disabled (or (empty? summary)
                                                   (not (fn? (:history/apply-compression! callbacks))))
                                     :onClick (fn [_]
                                                ((:history/apply-compression! callbacks)
                                                 {:session-id session-id
                                                  :compression-id id
                                                  :summary summary}))}
                                    "使用摘要"))
               (dom/span nil (or summary "")))
             (when (and (= :applied state)
                        (fn? (:history/undo-compression! callbacks)))
               (dom/button {:className "hc-button" :type "button"
                            :onClick (fn [_]
                                       ((:history/undo-compression! callbacks)
                                        {:session-id session-id
                                         :compression-id id}))}
                           "撤销压缩"))
             (when (and (= :applied state)
                        (fn? (:history/create-from-summary! callbacks)))
               (dom/button {:className "hc-button" :type "button"
                            :onClick (fn [_]
                                       ((:history/create-from-summary! callbacks)
                                        {:session-id session-id
                                         :compression-id id}))}
                           "用摘要建新会话")))))

(defn- compression-preparer
  "Local editable summary and message range.  Only “准备压缩” calls the server;
  checkbox changes and summary edits stay local."
  [props]
  (let [{:keys [session-id callbacks outbound]} (component-props props)
        ids (vec (or (:message-ids outbound) []))
        [editing? set-editing!] (react/useState false)
        [summary set-summary!] (react/useState "")
        [selected set-selected!] (react/useState (set ids))
        [error set-error!] (react/useState nil)]
    (when (fn? (:history/prepare-compression! callbacks))
      (dom/div {:className "hc-compression-preparer"}
               (when-not editing?
                 (dom/button {:className "hc-button" :type "button"
                              :onClick (fn [_]
                                         (set-summary! "")
                                         (set-selected! (set ids))
                                         (set-error! nil)
                                         (set-editing! true))}
                             "压缩当前发送范围"))
               (when editing?
                 (dom/div {:className "hc-inline-edit-panel"
                           :role "group"
                           :aria-label "准备压缩"}
                          (dom/div {:className "hc-context-rounds"} "选择范围")
                          (for [id ids]
                            (dom/label {:className "hc-ask-option" :key id}
                                       (dom/input {:type "checkbox"
                                                   :checked (contains? selected id)
                                                   :onChange (fn [event]
                                                               (let [checked? (.-checked (.-target event))]
                                                                 (set-selected!
                                                                  (if checked?
                                                                    (conj selected id)
                                                                    (disj selected id)))))}
                                                  )
                                       id))
                          (dom/textarea {:value summary
                                         :aria-label "压缩摘要"
                                         :placeholder "可编辑摘要"
                                         :onChange (fn [event]
                                                     (set-summary! (.-value (.-target event))))
                                         :style (style {:width "100%" :minHeight 80})})
                          (when error
                            (dom/div {:className "hc-banner hc-banner-error" :role "alert"}
                                     error))
                          (dom/div {:className "hc-message-actions"}
                                   (dom/button
                                    {:className "hc-button hc-button-primary" :type "button"
                                     :disabled (or (empty? selected) (empty? summary))
                                     :onClick (fn [_]
                                                (let [result ((:history/prepare-compression! callbacks)
                                                              {:session-id session-id
                                                               :message-ids (vec selected)
                                                               :summary summary})]
                                                  (if (:ok? result)
                                                    (set-editing! false)
                                                    (set-error! "准备压缩未送达"))))}
                                    "准备压缩")
                                   (dom/button {:className "hc-button" :type "button"
                                                :onClick (fn [_] (set-editing! false))}
                                               "取消"))))))))

(defn- export-controls
  "Branch export with the operator choices the spec requires: format
  (Markdown or plain text), whether sources are included, and whether tool
  and process detail is included.  Tool detail is off by default because a
  serialized tool payload is operational data, not conversation."
  [props]
  (let [{:keys [messages offer! fallback-sources]} (component-props props)
        [include-sources? set-include-sources!] (react/useState false)
        [include-tools? set-include-tools!] (react/useState false)
        export-opts {:include-sources? include-sources?
                     :include-tools? include-tools?
                     :fallback-sources fallback-sources}
        disabled? (empty? messages)]
    (dom/div {:className "hc-export-controls"
              :role "group"
              :aria-label "导出当前分支"}
             (dom/label {:className "hc-context-rounds"}
                        (dom/input {:type "checkbox"
                                    :checked include-sources?
                                    :onChange (fn [event]
                                                (set-include-sources!
                                                 (.-checked (.-target event))))})
                        "含来源")
             (dom/label {:className "hc-context-rounds"}
                        (dom/input {:type "checkbox"
                                    :checked include-tools?
                                    :onChange (fn [event]
                                                (set-include-tools!
                                                 (.-checked (.-target event))))})
                        "包含工具详情")
             (dom/button {:className "hc-button"
                          :type "button"
                          :disabled disabled?
                          :onClick #(offer!
                                     (export/markdown
                                      messages
                                      export-opts)
                                     "herdchestra-branch.md"
                                     "text/markdown;charset=utf-8")}
                         "导出当前分支")
             (dom/button {:className "hc-button"
                          :type "button"
                          :disabled disabled?
                          :onClick #(offer!
                                     (export/plain-text
                                      messages
                                      export-opts)
                                     "herdchestra-branch.txt"
                                     "text/plain;charset=utf-8")}
                         "导出纯文本"))))

(defn- round-fact
  [label value]
  (dom/div {:className "hc-round-fact"}
           (dom/strong nil (str label "："))
           (dom/span nil (str (or value "未知")))))

(defn- round-label
  "A user-readable label for a keyword reason; strings pass through."
  [value fallback]
  (cond
    (keyword? value)
    ;; Memory categories (server `materials/memory.clj`), scopes, and
    ;; epistemic kinds.  An unrecognized keyword must not leak its English
    ;; name; the caller's fallback is the honest label.
    (or (case value
          :identity-background "身份与背景"
          :working-style "工作方式"
          :expression-preference "表达偏好"
          :long-term-requirement "长期要求"
          :global "全局"
          :assistant "助手"
          :project "项目"
          :turn "本轮"
          :session "本会话"
          :fact "事实"
          :preference "偏好"
          :requirement "要求"
          :assumption "假设"
          :unknown "未知"
          fallback)
        fallback)
    (string? value) value
    :else fallback))

(defn- round-scope-label
  [scope]
  (cond
    (keyword? scope)
    (case scope
      :global "全局"
      :assistant "助手"
      :project "项目"
      :turn "本轮"
      :session "本会话"
      "范围未知")
    (map? scope) (case (:kind scope)
                   :global "全局"
                   :assistant (str "助手 " (or (:assistant-id scope) "未知"))
                   :project (str "项目 " (or (:project-id scope) "未知"))
                   "范围未知")
    :else "范围未知"))

(defn- round-reason-label
  [reason]
  (case reason
    :ok "正常"
    :memory-read-disabled "读取已关闭"
    :temporary-session "临时会话不读记忆"
    :materials-not-initialized "材料库尚未初始化"
    :budget "超出本轮预算"
    :cooldown "冷却中"
    :sticky "持续提供中"
    :keyword "关键词命中"
    :constant "始终提供"
    :always "始终提供"
    :delay "尚未到延迟轮次"
    :disabled "已停用"
    :unbound "未绑定当前助手"
    (round-label reason "未知")))

(defn- round-context-section
  "Read-only summary of the actual outbound request snapshot.  Missing
  sections are shown as not reported; unknown estimates are never zero."
  [outbound]
  (let [system (:system outbound)
        assistant (:assistant outbound)
        instructions (:instructions outbound)
        memory (:memory outbound)
        worldbook (:worldbook outbound)
        cards (:instruction-cards outbound)
        tools (:tools outbound)
        skills (:skills outbound)
        history (:history outbound)
        attachments (:attachments outbound)
        estimates (:estimates outbound)
        present? (some #(contains? outbound %)
                       [:system :assistant :instructions :memory :worldbook
                        :instruction-cards :tools :skills :history :attachments
                        :estimates])]
    (when present?
      (dom/details {:className "hc-round-context"}
                   (dom/summary nil
                                (if (= :last-run (:source outbound))
                                  "本轮上下文 · 上次发送"
                                  "本轮上下文 · 预览"))
                   (when-not present?
                     (dom/p {:className "wb-muted"} "本轮尚未发送。"))
                   (when system
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "系统")
                                  (round-fact "提示字节"
                                              (if (= :unknown (:prompt-bytes system))
                                                "未知" (:prompt-bytes system)))
                                  (when (seq (:modules system))
                                    (dom/div nil (str "模块：" (str/join "、" (:modules system)))))))
                   (when assistant
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "助手")
                                  (round-fact "身份" (round-label (:id assistant) "未知"))
                                  (round-fact "名称" (:name assistant))
                                  (round-fact "版本" (if (= :unknown (:revision assistant)) "未知" (:revision assistant)))))
                   (when instructions
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "指令")
                                  (round-fact "会话覆盖" (if (:session-override? instructions) "是" "否"))
                                  (when (:text-preview instructions)
                                    (dom/p {:className "wb-muted"} (:text-preview instructions)))))
                   (when memory
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "记忆")
                                  (round-fact "启用" (if (:enabled? memory) "是" "否"))
                                  (round-fact "状态" (round-reason-label (:reason memory)))
                                  (round-fact "归档排除" (if (= :unknown (:archived-excluded memory)) "未知" (:archived-excluded memory)))
                                  (for [item (:items memory)]
                                    (dom/div {:className "hc-round-item" :key (:id item)}
                                             (dom/span nil (str (:summary item)
                                                                " · " (round-label (:category item) "未分类")
                                                                " · " (round-scope-label (:scope item))
                                                                " · 版本 " (if (= :unknown (:version item)) "未知" (:version item))))))
                                  (for [item (:omitted memory)]
                                    (dom/div {:className "hc-round-item hc-round-warn" :key (str "memory-missed-" (:id item))}
                                             (str "命中但未提供 " (or (:summary item) (:id item))
                                                  " · " (round-reason-label (:reason item))
                                                  (when (integer? (:chars item))
                                                    (str " · " (:chars item) " 字")))))))
                   (when worldbook
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "世界书")
                                  (dom/div nil (str "启用书籍：" (str/join "、" (or (:enabled-books worldbook) []))))
                                  (for [item (:provided worldbook)]
                                    (dom/div {:className "hc-round-item" :key (str "provided-" (:entry item))}
                                             (str "已提供 " (:entry item) " · " (round-reason-label (:reason item)))))
                                  (for [item (:hit-not-provided worldbook)]
                                    (dom/div {:className "hc-round-item hc-round-warn" :key (str "missed-" (:entry item))}
                                             (str "命中但未提供 " (:entry item) " · " (round-reason-label (:reason item)))))))
                   (when (some? cards)
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "指令卡")
                                  (if (seq cards)
                                    (for [card cards]
                                      (dom/div {:className "hc-round-item" :key (str (:name card) "-" (:scope card))}
                                               (str (:name card) " · "
                                                    (round-label (or (:scope card) :turn)
                                                                 "范围未知")
                                                    " · 版本 " (if (= :unknown (:version card)) "未知" (:version card)))))
                                    (dom/div {:className "hc-round-item wb-muted"} "本轮未使用指令卡"))))
                   (when (seq tools)
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "工具")
                                  (for [tool tools]
                                    (dom/div {:className "hc-round-item" :key (:name tool)}
                                             (dom/div nil
                                                      (str (:name tool) " · "
                                                           (tool-access-label (:read-write tool))
                                                           (when-not (:enabled? tool) " · 未启用")))
                                             (when (:description tool)
                                               (dom/div {:className "wb-muted"}
                                                        (:description tool)))))))
                   (when (some? skills)
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "技能")
                                  (if (seq skills)
                                    (for [skill skills]
                                      (dom/div {:className "hc-round-item" :key (:id skill)}
                                               (str (:name skill) " · " (:id skill)
                                                    (when (:version skill)
                                                      (str " · 版本 " (:version skill))))))
                                    (dom/div {:className "hc-round-item wb-muted"}
                                             "本轮无可用技能"))))
                   (when history
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "历史")
                                  (round-fact "发送消息" (count (get-in history [:range :message-ids])))
                                  (round-fact "摘要" (count (get-in history [:range :summary-ids])))
                                  (round-fact "轮次" (get-in history [:range :rounds]))
                                  (round-fact "可见" (:visible-count history))
                                  (round-fact "排除" (:excluded-count history))
                                  (when (:compressed? history)
                                    (dom/div nil "含压缩摘要"))))
                   (when (seq attachments)
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "附件")
                                  (for [attachment attachments]
                                    (dom/div {:className "hc-round-item" :key (:attachment-id attachment)}
                                             (str (:name attachment) " · "
                                                  (attachment-kind-label (:kind attachment))
                                                  " · 页 " (if (= :unknown (:pages attachment))
                                                             "未知"
                                                             (str/join "," (:pages attachment))))))))
                   (when estimates
                     (dom/section {:className "hc-round-section"}
                                  (dom/h4 nil "估算")
                                  (round-fact "Token"
                                              (if (= :unknown (:tokens estimates))
                                                "未知" (:tokens estimates)))
                                  (round-fact "来源" (estimate-kind-label (:kind estimates)))))))))

(defn- context-panel
  [session session-id callbacks messages search-state secondary-target offer!]
  (let [context (or (:context session) {})
        outbound (:outbound context)
        compressions (let [value (:compressions context)]
                       (if (map? value)
                         (mapv (fn [id]
                                 (assoc (get value id) :compression-id id))
                               (or (:compression-order context) (keys value)))
                         (vec (or value []))))
        boundary (:boundary context)
        warnings (vec (or (:warnings outbound) []))
        clips (clip-views (or (:clips outbound) (:clips context)))
        reading (:reading context)
        round-context? (some #(contains? outbound %)
                             [:system :assistant :instructions :memory :worldbook
                              :instruction-cards :tools :history :attachments
                              :estimates])]
    ;; Export controls live inside this panel, and the operator must be able
    ;; to export a non-empty branch even when its context has no boundary,
    ;; rounds, compressions, warnings or outbound snapshot yet.  Keep the
    ;; panel for every non-empty conversation; each child below still has its
    ;; own `when`/empty state, so widening this gate does not add blank rows.
    (when (or (seq messages)
              (:boundary context) (:recent-rounds context)
              (seq compressions) (seq warnings) (map? outbound))
      ;; The round context is its own top-level disclosure.  Nested inside
      ;; the collapsed "上下文与压缩" panel its summary could never be
      ;; opened, so the panel read as a permanently blank preview.
      (react/createElement
       react/Fragment
       nil
       (round-context-section outbound)
       (dom/details {:className "hc-context-panel" :aria-label "上下文与压缩"}
                   (dom/summary {:className "hc-context-header"}
                                (dom/strong nil "上下文")
                                (dom/span nil
                                          (str "发送 " (or (:message-count outbound) 0)
                                               " 条 · 摘要 "
                                               (or (:summary-count outbound) 0)
                                               (when (seq warnings)
                                                 (str " · 警告 " (count warnings))))))
                   (when-not round-context?
                     (dom/div {:className "hc-context-rounds"}
                              "本轮尚未发送"))
                   (when reading
                     (dom/div {:className "hc-context-rounds hc-context-reading"}
                              (dom/div nil
                                       (str "阅读 " (or (:message-count reading) 0)
                                            " 条 · 已删除 "
                                            (or (:deleted-count reading) 0)))
                              (dom/div {:className "hc-context-reading-note"}
                                       (str "当前可见 " (count messages)
                                            " 条；差额来自系统提示、工具结果与过程记录，"
                                            "它们参与上下文但不显示为消息气泡。"))))
                   (when boundary
                     (dom/div {:className "hc-context-boundary"}
                              (dom/span nil (str "起点：" (:message-id boundary)))
                              (when (fn? (:history/clear-boundary! callbacks))
                                (dom/button {:className "hc-button" :type "button"
                                             :onClick #((:history/clear-boundary! callbacks)
                                                        {:session-id session-id})}
                                            "清除边界"))))
                   (when-let [rounds (:recent-rounds context)]
                     (dom/div {:className "hc-context-rounds"}
                              (str "最近 " rounds " 轮")))
                   (when (seq (:pinned-attachments context))
                     (dom/div {:className "hc-context-pins"}
                              (str "固定附件：" (count (:pinned-attachments context)))))
                   (when (seq (:missing-pinned-attachments context))
                     (dom/div {:className "hc-context-warnings" :role "status"}
                              (str "固定附件缺失："
                                   (count (:missing-pinned-attachments context))
                                   " 个")))
                   (when (seq clips)
                     (dom/div {:className "hc-context-rounds"}
                              (dom/span nil "裁剪边界：")
                              (for [clip clips]
                                (dom/span {:key (:key clip)} (:text clip)))))
                   (when (seq (:message-ids outbound))
                     (react/createElement
                      compression-preparer
                      #js {:key (str "compress-" session-id)
                           :props {:session-id session-id :callbacks callbacks
                                   :outbound outbound}}))
                   (for [compression compressions]
                     (react/createElement
                      compression-row
                      #js {:key (:compression-id compression)
                           :props {:compression compression
                                   :callbacks callbacks
                                   :session-id session-id}}))
                   (when (seq warnings)
                     (dom/div {:className "hc-context-warnings" :role "status"}
                              (for [warning warnings]
                                (dom/div {:key (str (:code warning) "-" (hash warning))}
                                         (warning-text warning)))))
                   (when (seq messages)
                     (react/createElement
                      export-controls
                      #js {:key "branch-export"
                           :props {:messages messages :offer! offer!
                                   :fallback-sources
                                   (search-model/source-cards search-state)}})))))))

(defn- inline-edit-control
  "Independent per-message editor.  It never touches the composer draft and
  closes only after the tracked edit command reaches :accepted.

  The attachment list is this version's draft, not the composer attachment
  queue.  Removing an item drops only its reference from the new version;
  the original version and every stored file remain intact."
  [props]
  (let [{:keys [message callbacks session-id commands
                secondary-target]} (component-props props)
        secondary-target (or secondary-target 44)
        [editing? set-editing!] (react/useState false)
        [draft set-draft!] (react/useState (or (:body message) ""))
        [attachments set-attachments!] (react/useState [])
        [pending set-pending!] (react/useState nil)
        [error set-error!] (react/useState nil)
        pending-command (when pending (get commands pending))
        status (when pending (get-in commands [pending :status]))
        accepted? (= :accepted status)
        rejected? (contains? #{:rejected :failed :refused} status)
        rejection-label (or (:message pending-command)
                            (get-in pending-command [:error :message])
                            (when-let [code (get-in pending-command [:error :code])]
                              ;; Contract codes get their shared copy; an
                              ;; unrecognized code falls through to the
                              ;; honest generic label below.
                              (let [copy (failures/failure-copy
                                          {:code code :context {:saved? false}})]
                                (when (and copy
                                           (not= "无法确定具体原因" (:title copy)))
                                  (str (:title copy)
                                       (when-let [body (:body copy)]
                                         (str "：" body))))))
                            "编辑未送达，编辑内容仍在")
        attachment-rows (history-edit/attachment-rows attachments)
        edit-state {:content draft :attachments attachments}]
    (react/useEffect
     (fn []
       (when accepted?
         (set-editing! false)
         (set-pending! nil)
         (set-error! nil))
       (when rejected?
         ;; Release the lock but keep the independent draft and editor open so
         ;; the operator can retry or cancel.
         (set-pending! nil)
         (set-error! rejection-label))
       js/undefined)
     (clj->js [accepted? rejected?]))
    (dom/div {:className "hc-inline-edit"}
             (when-not editing?
               (dom/button {:className "hc-button"
                            :type "button"
                           :style (style {:minHeight secondary-target
                                          :minWidth secondary-target})
                            :onClick (fn [_]
                                       (let [initial (history-edit/initial-state message)]
                                         (set-draft! (:content initial))
                                         (set-attachments! (:attachments initial)))
                                       (set-error! nil)
                                       (set-editing! true))}
                           "编辑"))
             (when editing?
               (dom/div {:className "hc-inline-edit-panel"
                         :role "group"
                         :aria-label "编辑消息"}
                        (dom/textarea
                         {:value draft
                          :aria-label "编辑消息内容"
                          :onChange (fn [event] (set-draft! (.-value (.-target event))))
                          :style (style {:width "100%" :minHeight 80
                                         :whiteSpace "pre-wrap"})})
                        (when (seq attachment-rows)
                          (dom/div {:className "hc-inline-edit-attachments"
                                    :role "group"
                                    :aria-label "编辑消息附件"
                                    :style (style {:display "flex"
                                                   :flexDirection "column"
                                                   :gap 6})}
                                   (dom/div {:className "hc-composer-hint"}
                                            "移除只影响这个新版本；原版本和原始文件仍保留。")
                                   (dom/ul {:className "hc-attachments"}
                                           (for [[index row] (map-indexed vector attachment-rows)]
                                             (dom/li {:className "hc-chip"
                                                      :key (str "edit-attachment-"
                                                                (or (:id row) index))}
                                                     (dom/span nil (:name row))
                                                     (dom/button
                                                      {:className "hc-button"
                                                       :type "button"
                                                      :style (style {:minHeight secondary-target
                                                                     :minWidth secondary-target})
                                                       :disabled (boolean pending)
                                                       :aria-label (:remove-label row)
                                                       :onClick (fn [_]
                                                                  (set-attachments!
                                                                   (history-edit/remove-attachment
                                                                    attachments
                                                                    (:id row))))}
                                                      "移除附件"))))))
                        (when error
                          (dom/div {:className "hc-banner hc-banner-error" :role "alert"}
                                   error))
                        (dom/div {:className "hc-message-actions"}
                                 (dom/button
                                  {:className "hc-button hc-button-primary"
                                   :type "button"
                                   :disabled (or (boolean pending)
                                                 (not (fn? (:history/edit-message! callbacks)))
                                                 (not (history-edit/saveable? edit-state)))
                                   :aria-label "保存消息编辑"
                                   :onClick (fn [_]
                                              (set-error! nil)
                                              (let [result ((:history/edit-message! callbacks)
                                                            (assoc (history-edit/payload edit-state)
                                                                   :session-id session-id
                                                                   :message-id (:id message)))]
                                                (if (:ok? result)
                                                  (set-pending! (:command-id result))
                                                  (set-error! "编辑未发送，请检查连接"))))}
                                  (if pending "正在保存编辑" "保存编辑"))
                                 (dom/button {:className "hc-button"
                                              :type "button"
                                              :disabled (boolean pending)
                                              :onClick (fn [_]
                                                         (set-editing! false)
                                                         (set-pending! nil)
                                                         (set-error! nil))}
                                             "取消")))))))

(defn- regenerate-control
  "Explicit regeneration.  Disabled while the tracked command is pending; the
  server appends a real version, never client-generated content."
  [props]
  (let [{:keys [message callbacks session-id commands
                secondary-target]} (component-props props)
        secondary-target (or secondary-target 44)
        [pending set-pending!] (react/useState nil)
        status (when pending (get-in commands [pending :status]))
        done? (contains? #{:accepted :rejected} status)]
    (react/useEffect
     (fn []
       (when done? (set-pending! nil))
       js/undefined)
     (clj->js [done?]))
    (when (and (= :assistant (:role message))
               (fn? (or (:history/regenerate! callbacks)
                        (:regenerate! callbacks))))
      (dom/button {:className "hc-button"
                   :type "button"
                   :disabled (boolean pending)
                   :aria-label (if pending "重新生成中" "重新生成")
                   :style (style {:minHeight secondary-target
                                  :minWidth secondary-target})
                   :onClick (fn [_]
                              (let [result ((or (:history/regenerate! callbacks)
                                                (:regenerate! callbacks))
                                            {:session-id session-id
                                             :message-id (:id message)})]
                                (when (:ok? result)
                                  (set-pending! (:command-id result)))))}
                  (if pending "重新生成中" "重新生成")))))

(defn- message-item
  [message {:keys [callbacks session-id commands selected? on-select!
                   pinned-ids draft-selection draft-text on-replace
                   search-state last-assistant?
                   secondary-target]}]
  (let [role (:role message)
        out? (= :user role)
        callbacks (or callbacks {})
        copy! (or (:copy! callbacks) copy-text!)
        secondary-target (or secondary-target 44)
        replace-capable? (or (fn? (:replace-transcript! callbacks))
                             (fn? (:edit-draft! callbacks)))]
    (dom/div {:className (class-names "hc-message"
                                      (if out? "hc-message-out" "hc-message-in")
                                      (when (contains? #{:tool :activity :reasoning} role)
                                        "hc-message-process"))
              :data-message-id (:id message)
              :key (:id message)}
             (dom/div {:className "hc-message-meta"}
                      (str (:author message))
                      (when (:manual-edit? message) " · 手动编辑")
                      (when-let [version (:current-version message)]
                        (str " · 版本 "
                             (if (number? version) (inc version) version)))
                      (when-let [usage (usage-label (:usage message))]
                        (str " · " usage)))
             (version-selector message callbacks session-id secondary-target)
             (when-let [status (:status-label message)]
               (dom/div {:className "hc-message-status"} status))
             (process-block (:process message) secondary-target)
             (dom/div {:className "hc-message-body"}
                      (for [[index segment] (map-indexed vector (:segments message))]
                        (segment-block segment index secondary-target)))
             ;; The display projection drops identity and provenance fields.
             ;; The raw references keep the saved-copy marker, page results and
             ;; workspace provenance the history row must show.
             (attachment-list (or (seq (:attachment-refs message))
                                  (:attachments message))
                              {:callbacks callbacks
                               :session-id session-id
                               :pinned-ids pinned-ids
                               :target secondary-target})
             (search-view/source-cards
              (or (seq (search-model/message-sources message))
                  (when last-assistant?
                    (search-model/source-cards search-state))))
             (when-let [card (:generated-card message)]
               (react/createElement
                generated-card/generated-card
                #js {:key (str "generated-" (or (:card-id card) (:id message)))
                     :props {:card card
                             :on-submit! (:submit-generated-card! callbacks)}}))
             (when (seq (:late-tool-results message))
               (for [late (:late-tool-results message)]
                 (dom/div {:className "hc-late-result" :key (str "late-" (:tool-call-id late))}
                          (str (:label late) " · " (or (:tool-call-id late) "工具")
                               (when-let [status (:status late)]
                                 (str " · " (late-result-status-label status)))))))
             (dom/div {:className "hc-message-actions"}
                      (dom/label {:className "hc-message-select"
                                  :style (style {:display "flex" :alignItems "center"
                                                 :gap 6 :minHeight secondary-target})}
                                 (dom/input {:type "checkbox"
                                             :checked (boolean selected?)
                                             :aria-label (str "选择消息 " (:id message))
                                             :onChange (fn [event]
                                                         (when (fn? on-select!)
                                                           (on-select! (:id message)
                                                                       (.-checked (.-target event)))))}
                                            )
                                 "选择")
                      (dom/button {:className "hc-button"
                                   :type "button"
                                   :style (style {:minHeight secondary-target
                                                  :minWidth secondary-target})
                                   :onClick #(copy! (:copy-text message))}
                                  "复制")
                      (when (= :assistant role)
                        (react/createElement
                         speech-ui/read-aloud-button
                         #js {:messageId (:id message)
                              :versionId (:current-version message)
                              :text (:copy-text message)}))
                      (when (and (= :assistant role)
                                 (or (fn? (:insert-transcript! callbacks))
                                     replace-capable?))
                        (react/createElement
                         speech-ui/translation-controls
                         #js {:messageId (:id message)
                              :versionId (:current-version message)
                              :text (:copy-text message)
                              :draftText (or draft-text "")
                              :draft-text (or draft-text "")
                              :selection (when replace-capable?
                                           (clj->js
                                            (or draft-selection
                                                {:start 0 :end 0
                                                 :text "" :draft-version nil})))
                              :onReplace (when (and replace-capable?
                                                    (fn? on-replace))
                                           (fn [payload]
                                             (on-replace
                                              (if (map? payload)
                                                payload
                                                (js->clj payload :keywordize-keys true)))))
                              :onInsert (when (fn? (:insert-transcript! callbacks))
                                          (fn [text]
                                            ((:insert-transcript! callbacks)
                                             {:session-id session-id
                                              :text text})))}))
                      (react/createElement
                       regenerate-control
                       #js {:key (str "regen-" (:id message))
                            :props {:message message :callbacks callbacks
                                    :session-id session-id :commands commands
                                    :secondary-target secondary-target}})
                      (let [branch! (:history/branch! callbacks)
                            legacy-branch! (:branch! callbacks)]
                        ;; A branch is a readable conversation path, so it
                        ;; starts at a user or assistant message.  Branching
                        ;; from a tool/process row would name a message the
                        ;; persisted history does not contain, and the server
                        ;; would refuse it as `:MESSAGE_NOT_FOUND`.
                        ;;
                        ;; `:before-message-id` nil copies the whole path:
                        ;; the branch keeps every readable message of the
                        ;; source session, including the clicked one and
                        ;; every later turn, then continues independently.
                        ;; The clicked position is still recorded through
                        ;; the legacy message-id path when that callback is
                        ;; the only one wired.
                        (when (and (contains? #{:user :assistant} role)
                                   (or (fn? branch!) (fn? legacy-branch!)))
                          (dom/button {:className "hc-button"
                                       :type "button"
                                       :style (style {:minHeight secondary-target
                                                      :minWidth secondary-target})
                                       :onClick (fn [_]
                                                  (if (fn? branch!)
                                                    (branch! {:session-id session-id
                                                              :before-message-id nil})
                                                    (legacy-branch! {:session-id session-id
                                                                     :message-id (:id message)})))}
                                      "从此处分支")))
                      (when (fn? (:history/set-boundary! callbacks))
                        (dom/button {:className "hc-button"
                                     :type "button"
                                     :style (style {:minHeight secondary-target
                                                    :minWidth secondary-target})
                                     :onClick #((:history/set-boundary! callbacks)
                                                {:session-id session-id
                                                 :message-id (:id message)})}
                                    "从此处开始"))
                      (when (and (contains? #{:user :assistant} role)
                                 (fn? (:history/edit-message! callbacks)))
                        (react/createElement
                         inline-edit-control
                         #js {:key (str "edit-" (:id message)
                                        "-" (:current-version message))
                              :props {:message message :callbacks callbacks
                                      :session-id session-id :commands commands
                                      :secondary-target secondary-target}}))))))

;; ---------------------------------------------------------------------------
;; Composer

(defn- event-map
  [event]
  (let [native (.-nativeEvent event)]
    {:key (.-key event)
     :shift? (boolean (.-shiftKey event))
     :ctrl? (boolean (.-ctrlKey event))
     :meta? (boolean (.-metaKey event))
     :alt? (boolean (.-altKey event))
     :repeat? (boolean (.-repeat event))
     :key-code (.-keyCode event)
     :is-composing? (boolean (and native (.-isComposing native)))}))

(defn- draft-snapshot
  [draft]
  (select-keys (or draft {}) [:text :cursor :selection :attachments :quotes
                              :version :turn-card-ids :instruction-values
                              :temporary-read? :turn-selection-version]))

(defn- composer-snapshot
  "Snapshot the composer the operator can actually see.

  The textarea is uncontrolled.  Input reaches the reducer synchronously,
  but a click that arrives before the next render still reads the previous
  `props`, whose draft text can be empty while the field already shows the
  typed body.  The field value is the authority for what the operator is
  looking at, so it overwrites the snapshot text.  Without this, a fast
  type-then-send (or paste-then-send) with a ready attachment submits an
  attachment-only message."
  [props textarea-ref]
  (let [snapshot (draft-snapshot (:draft props))
        node (when textarea-ref (.-current textarea-ref))
        value (when node (.-value node))]
    (if (string? value)
      (assoc snapshot :text value)
      snapshot)))

(defn- call!
  [callbacks key value]
  (when-let [callback (get callbacks key)]
    (when (ifn? callback) (callback value))))

(defn- request-send!
  [props view-model local-decision set-local-decision! textarea-ref]
  (let [callbacks (or (:callbacks props) {})
        session-id (:session-id view-model)
        intent (get-in view-model [:composer :send :intent])
        fix-intent (get-in view-model [:composer :send :fix-intent])]
    (cond
      (= :needs-decision intent)
      (set-local-decision! (get-in view-model [:composer :send :options]))

      (and fix-intent (fn? (:fix! callbacks)))
      ((:fix! callbacks) {:session-id session-id :intent fix-intent})

      fix-intent
      nil

      ;; `intent` comes from the last render, but the textarea is
      ;; uncontrolled: a `fill`/paste followed by Enter in the same frame
      ;; still reads the previous render's `:disabled`/`:empty`.  Dropping
      ;; the key silently there is a data-loss bug the operator cannot see —
      ;; the draft stays in the box and nothing explains why.  So when the
      ;; visible field holds real text, hand the DOM snapshot to the bridge,
      ;; which decides from the state at submit time.  `:duplicate` and
      ;; `:wait` still short-circuit: those describe an in-flight command the
      ;; operator must not silently double-submit.
      (and (contains? #{:disabled :select-model} intent)
           (let [value (some-> textarea-ref .-current .-value)]
             (string? value)
             (not (str/blank? value))))
      (let [result ((:send! callbacks)
                    {:session-id session-id
                     :snapshot (composer-snapshot props textarea-ref)})]
        (when (true? (:ok? result))
          (when-let [node (.-current textarea-ref)]
            (set! (.-value node) "")
            (when (fn? (.-setSelectionRange node))
              (.setSelectionRange node 0 0))))
        result)

      (contains? #{:disabled :duplicate :wait :select-model} intent)
      nil

      (fn? (:send! callbacks))
      (let [result ((:send! callbacks)
                    {:session-id session-id
                     :snapshot (composer-snapshot props textarea-ref)})]
        (when (true? (:ok? result))
          ;; The command is queued under the reducer's authority: clear the
          ;; visible composer now, so an accepted send never leaves the old
          ;; text in the box.  A blocked or transport-failed send keeps it.
          ;; The reducer draft remains authoritative; clearing the DOM here
          ;; and `draft/clear-submitted` in the reducer are idempotent.
          (when-let [node (.-current textarea-ref)]
            (set! (.-value node) "")
            (when (fn? (.-setSelectionRange node))
              (.setSelectionRange node 0 0))))
        result)

      :else nil)))

;; ---------------------------------------------------------------------------
;; DOM selection preservation
;;
;; Blink resets a Range's offsets when character data is replaced through
;; `nodeValue` (the same rule as `character-data-mutation`); React updates a
;; growing streamed paragraph that way, so a reader's selection collapses even
;; though the selected nodes stay mounted.  The surface therefore records the
;; selection as character offsets inside the message scroller and restores it
;; after each commit.  This keeps the stream flowing: it never freezes or drops
;; an arriving chunk to protect the selection.

(defn- point-char-offset
  "Absolute character offset of a DOM point inside ROOT, or nil when the point
  is outside ROOT."
  [root node offset]
  (when (and root node (exists? js/document) (.contains root node))
    (try
      (let [range (.createRange js/document)]
        (.setStart range root 0)
        (.setEnd range node offset)
        (.-length (str range)))
      (catch :default _ nil))))

(defn- text-point-at
  "Return `[text-node offset]` for absolute character offset N under ROOT."
  [root n]
  (when (and root (exists? js/document) (exists? js/window)
             (some? (.-NodeFilter js/window)))
    (let [walker (.createTreeWalker js/document root
                                    (.-SHOW_TEXT (.-NodeFilter js/window)))]
      (loop [remaining n]
        (when-let [node (.nextNode walker)]
          (let [length (.-length node)]
            (if (<= remaining length)
              [node remaining]
              (recur (- remaining length)))))))))

(defn- point-valid?
  [node offset]
  (and node
       (.-isConnected node)
       (integer? offset)
       (let [limit (if (= 3 (.-nodeType node))
                     (.-length node)
                     (.-length (.-childNodes node)))]
         (<= 0 offset limit))))

(defn- capture-selection!
  "Record a non-collapsed selection inside ROOT.

  Both the original DOM points and absolute character offsets are kept.  The
  points make the common append-only case cheap; the offsets still locate the
  text when React replaces the selected node (for example when a streamed
  paragraph splits into a table or code segment)."
  [root]
  (when (and root (exists? js/window))
    (let [selection (.getSelection js/window)]
      (when (and selection
                 (not (.-isCollapsed selection))
                 (pos? (.-rangeCount selection)))
        (try
          (let [range (.getRangeAt selection 0)
                start-node (.-startContainer range)
                end-node (.-endContainer range)
                start-offset (.-startOffset range)
                end-offset (.-endOffset range)
                start-char (point-char-offset root start-node start-offset)
                end-char (point-char-offset root end-node end-offset)
                text (str selection)]
            (when (and (some? start-char) (some? end-char)
                       (<= start-char end-char) (seq text))
              {:start-node start-node
               :start-offset start-offset
               :end-node end-node
               :end-offset end-offset
               :start-char start-char
               :end-char end-char
               :text text}))
          (catch :default _ nil))))))

(defn- restore-selection!
  "Restore SAVED inside ROOT when the same text still exists.

  The stored DOM points are tried first.  When those nodes were replaced, the
  absolute character offsets locate the same range in the new tree.  A range
  whose text no longer matches the recorded selection is not installed, so a
  message edit or version switch cannot resurrect a range over different text."
  [root saved]
  (when (and root saved (exists? js/window))
    (let [selection (.getSelection js/window)]
      (when selection
        (let [range
              (or (when (and (point-valid? (:start-node saved) (:start-offset saved))
                             (point-valid? (:end-node saved) (:end-offset saved)))
                    (try
                      (let [range (.createRange js/document)]
                        (.setStart range (:start-node saved) (:start-offset saved))
                        (.setEnd range (:end-node saved) (:end-offset saved))
                        (when (= (:text saved) (str range)) range))
                      (catch :default _ nil)))
                  (when-let [[start-node start-offset]
                             (text-point-at root (:start-char saved))]
                    (when-let [[end-node end-offset]
                               (text-point-at root (:end-char saved))]
                      (try
                        (let [range (.createRange js/document)]
                          (.setStart range start-node start-offset)
                          (.setEnd range end-node end-offset)
                          (when (= (:text saved) (str range)) range))
                        (catch :default _ nil)))))]
          (when range
            (try
              (.removeAllRanges selection)
              (.addRange selection range)
              true
              (catch :default _ nil))))))))

(defn- decision-block
  [options on-stop-and-send on-continue can-stop?]
  (when (seq options)
    (dom/div {:className "hc-banner hc-banner-info"
              :role "group"
              :aria-label "当前轮仍在生成"}
             (dom/span nil "当前轮仍在生成。")
             (for [option options]
               (dom/button {:className "hc-button"
                            :type "button"
                            :key (:id option)
                            :disabled (and (= :stop-and-send (:id option))
                                           (not can-stop?))
                            :onClick (case (:id option)
                                       :stop-and-send on-stop-and-send
                                       :continue-editing on-continue
                                       nil)}
                           (:label option))))))

(defn- composer-view
  [{:keys [props view-model textarea-ref composing-ref local-decision set-local-decision!
           pending-send set-pending-send! selection-locked? set-selection-locked!] :as _ctx}]
  (let [composer (:composer view-model)
        callbacks (or (:callbacks props) {})
        session-id (:session-id view-model)
        send (:send composer)
        stop (:stop composer)
        secondary-target (or (get-in view-model [:a11y :secondary-target]) 44)
        decision (or local-decision
                     (when (get-in composer [:decision :visible?])
                       (get-in composer [:decision :options])))
        send-disabled? (or (:disabled? send)
                           (and (:fix-intent send)
                                (not (fn? (:fix! callbacks)))))
        on-send (fn [event]
                  (when event (.preventDefault event))
                  (request-send! props view-model local-decision
                                 set-local-decision! textarea-ref))
        on-stop (fn [event]
                  (when event (.preventDefault event))
                  (let [sid (or session-id (:session-id props))]
                    (if (ifn? (:stop! callbacks))
                      ((:stop! callbacks) {:session-id sid
                                           :origin :operator-stop})
                      (call! callbacks :stop-run! {:session-id sid
                                                   :origin :operator-stop}))))
        on-stop-and-send (fn [event]
                           (when event (.preventDefault event))
                           (when (fn? (:stop! callbacks))
                             (set-pending-send! {:session-id session-id
                                                 :snapshot (assoc (composer-snapshot
                                                                   props textarea-ref)
                                                                  :deferred? true)})
                             ((:stop! callbacks) {:session-id session-id})
                             (set-local-decision! nil)))
        on-continue (fn [event]
                      (when event (.preventDefault event))
                      (set-local-decision! nil)
                      (when-let [node (.-current textarea-ref)]
                        (.focus node)))]
    (dom/div {:className "hc-composer"
              :role "group"
              :aria-label "消息输入区"}
             (react/createElement
              speech-ui/input-element
              #js {:sessionId session-id
                   :draft (clj->js (or (:draft props) {}))
                   :onInsert (:insert-transcript! callbacks)
                   :onOpenSettings (:settings! callbacks)})
             (when (seq (:attachments composer))
               (dom/ul {:className "hc-attachments"}
                       (for [attachment (:attachments composer)]
                         (dom/li {:className "hc-chip" :key (:id attachment)}
                                 (dom/span nil (str (:name attachment)
                                                    (when-let [size (:size-label attachment)]
                                                      (str " · " size))))
                                 (dom/span nil (:status-label attachment))
                                 (dom/button {:className "hc-button"
                                              :type "button"
                                             :style (style {:minHeight secondary-target
                                                            :minWidth secondary-target})
                                              :aria-label (:remove-label attachment)
                                              :onClick #(call! callbacks :remove-attachment!
                                                               {:session-id session-id
                                                                :attachment-id (:id attachment)})}
                                             "移除")))))
             (when (seq (:quotes composer))
               (dom/ul {:className "hc-quotes"}
                       (for [quote (:quotes composer)]
                         (dom/li {:className "hc-chip" :key (:id quote)}
                                 (dom/span {:style (style {:whiteSpace "pre-wrap"})}
                                           (or (:text quote) "引用"))
                                 (dom/button {:className "hc-button"
                                              :type "button"
                                             :style (style {:minHeight secondary-target
                                                            :minWidth secondary-target})
                                              :aria-label (:remove-label quote)
                                              :onClick #(call! callbacks :remove-quote!
                                                               {:session-id session-id
                                                                :quote-id (:id quote)})}
                                             "移除")))))
             (let [pending (or (:pending-send composer) pending-send)
                   pending-label (or (get-in composer [:pending-send :label])
                                     "停止后将发送本条")
                   cancel-label (or (get-in composer [:pending-send :cancel-label])
                                    "取消待发送内容")]
               (when pending
                 (dom/div {:className "hc-banner hc-banner-info" :role "status"}
                          (dom/span nil pending-label)
                          (dom/button {:className "hc-button"
                                       :type "button"
                                       :onClick #(do
                                                   (set-pending-send! nil)
                                                   (call! callbacks :cancel-pending-send!
                                                          {:session-id session-id}))}
                                      cancel-label))))
             (decision-block decision on-stop-and-send on-continue
                             (fn? (:stop! callbacks)))
             (dom/div {:className "hc-composer-row"}
                      (dom/textarea {:key (str "composer-" session-id)
                                     :ref textarea-ref
                                     :defaultValue (:default-value composer)
                                     :rows 2
                                     :placeholder "输入消息…"
                                     :aria-label (get-in composer [:aria :label])
                                     :aria-describedby (get-in composer [:aria :describedby])
                                     :aria-keyshortcuts (get-in composer [:aria :keyshortcuts])
                                     :style (style {:minHeight (:target-size composer)})
                                     :onInput (fn [event]
                                                (let [target (.-target event)
                                                      value (.-value target)
                                                      start (.-selectionStart target)
                                                      end (.-selectionEnd target)]
                                                  (call! callbacks :edit-draft!
                                                         {:session-id session-id
                                                          :text value
                                                          :cursor start
                                                          :selection {:start start :end end}
                                                          :composing? (boolean
                                                                       (and (.-nativeEvent event)
                                                                            (.-isComposing (.-nativeEvent event))))})))
                                     :onSelect (fn [event]
                                                 (let [target (.-target event)]
                                                   (call! callbacks :edit-draft!
                                                          {:session-id session-id
                                                           :text (.-value target)
                                                           :cursor (.-selectionStart target)
                                                           :selection {:start (.-selectionStart target)
                                                                       :end (.-selectionEnd target)}})))
                                     :onCompositionStart (fn [_]
                                                           (set! (.-current composing-ref) true)
                                                           (set-selection-locked! true)
                                                           (call! callbacks :edit-draft!
                                                                  {:session-id session-id
                                                                   :composing? true}))
                                     :onCompositionEnd (fn [_]
                                                         (set! (.-current composing-ref) false)
                                                         (set-selection-locked! false)
                                                         (call! callbacks :edit-draft!
                                                                (let [node (.-current textarea-ref)]
                                                                  {:session-id session-id
                                                                   :text (when node (.-value node))
                                                                   :cursor (when node (.-selectionStart node))
                                                                   :selection (when node
                                                                                {:start (.-selectionStart node)
                                                                                 :end (.-selectionEnd node)})
                                                                   :composing? false})))
                                     :onBlur (fn [event]
                                               (set! (.-current composing-ref) false)
                                               (let [target (.-target event)]
                                                 (call! callbacks :edit-draft!
                                                        {:session-id session-id
                                                         :text (.-value target)
                                                         :cursor (.-selectionStart target)
                                                         :selection {:start (.-selectionStart target)
                                                                     :end (.-selectionEnd target)}
                                                         :composing? false})))
                                     :onKeyDown (fn [event]
                                                  (let [decision (composer/keydown
                                                                  {:platform (or (:platform props) :desktop)
                                                                   :send-key (or (:send-key props) :enter)}
                                                                  ;; The local composition flag is
                                                                  ;; synchronous and does not depend on
                                                                  ;; the browser exposing isComposing on
                                                                  ;; the synthetic key event.
                                                                  (assoc (event-map event)
                                                                         :composing?
                                                                         (boolean
                                                                          (and composing-ref
                                                                               (.-current composing-ref)))))]
                                                    (case (:action decision)
                                                      :send (do
                                                              (.preventDefault event)
                                                              (request-send!
                                                               props view-model
                                                               local-decision
                                                               set-local-decision!
                                                               textarea-ref))
                                                      :newline nil
                                                      :ime-candidate nil
                                                      :ignore nil)))
                                     :onClick (fn [_] (set-selection-locked! false))})
                      (when (get-in composer [:stop :visible?])
                        (dom/button {:className "hc-button"
                                     :type "button"
                                     :disabled (:disabled? stop)
                                     :aria-label (:aria-label stop)
                                     :aria-disabled (boolean (:disabled? stop))
                                     :style (style {:minHeight (:target-size stop)
                                                    :minWidth (:target-size stop)})
                                     :onClick on-stop}
                                    (:label stop)))
                      ;; R-05/B-03: with no bound model the send button is
                      ;; disabled; the repair entry sits beside it so the
                      ;; operator always has a visible way forward.
                      (let [model-entry (get send :model-entry)]
                       (when (:visible? model-entry)
                      (dom/button {:className "hc-button hc-button-primary"
                                   :type "button"
                                      :disabled (:disabled? model-entry)
                                      :aria-label (:primary-label model-entry)
                                      :aria-disabled (boolean (:disabled? model-entry))
                                      :title (:reason model-entry)
                                   :style (style {:minHeight (:target-size send)
                                                  :minWidth (:target-size send)})
                                      :onClick (fn [_]
                                                 (cond
                                                   (fn? (:fix! callbacks))
                                                   ((:fix! callbacks)
                                                    {:session-id session-id
                                                     :intent :select-model})
                                                   (fn? (:settings! callbacks))
                                                   ((:settings! callbacks)
                                                    {:session-id session-id})
                                                   :else nil))}
                                 (:primary-label model-entry))))
                      (dom/button {:className "hc-button hc-button-primary"
                                  :type "button"
                                   :disabled send-disabled?
                                   :aria-label (:aria-label send)
                                   :aria-disabled (boolean send-disabled?)
                                   :title (:reason send)
                                   :style (style {:minHeight (:target-size send)
                                                  :minWidth (:target-size send)})
                                   :onClick on-send}
                                  (:label send)))
                      (let [model-entry (get send :model-entry)]
                       (when (:defer? model-entry)
                        (dom/button {:className "hc-button"
                                      :type "button"
                                      :aria-label (:defer-label model-entry)
                                   :style (style {:minHeight (:target-size send)
                                                  :minWidth (:target-size send)})
                                      :onClick (fn [_]
                                                 (when (fn? (:settings! callbacks))
                                                   ((:settings! callbacks)
                                                    {:session-id session-id})))}
                                  (:defer-label model-entry))))
             (dom/div {:className "hc-composer-hint" :id "hc-composer-hint"}
                      (:hint composer)
                      (when-let [reason (:reason send)]
                        (str " · " reason)))
             (when-let [save-label (get-in composer [:save :label])]
               (dom/div {:className "hc-composer-save" :role "status"} save-label))
             (when-let [command-reason
                        (:reason (get composer :command))]
               (when-let [reason-label
                          (:label (some #(when (= (:id %)
                                                   command-reason)
                                              %)
                                            failures/failure-reason-categories))]
                 (dom/div {:className "hc-reason hc-composer-save" :role "status"}
                          reason-label))))))

;; ---------------------------------------------------------------------------
;; Ask card

(defn- ask-card
  "S1 card component.  It must be created as a real React element, not called
  inside a `for`: its `useState` slots belong to this component, and a varying
  number of cards must not change the parent's hook order."
  [props]
  (let [{:keys [ask session-id callbacks]} (component-props props)
        [text set-text!] (react/useState (or (:text ask) ""))
        [selected set-selected!] (react/useState
                                  (set (or (:selected ask) #{})))
        [submitted? set-submitted!] (react/useState false)
        draft-version (:draft-version ask)
        synced-version (react/useRef draft-version)
        input? (:input? ask)
        multiple? (:multiple? ask)
        status (:status ask)
        value (ask/answer-value {:input? input?
                                 :multiple? multiple?
                                 :draft {:text text :selected selected}})
        disabled? (or (= :submitting status)
                      (and submitted? (not= :rejected status))
                      (not (fn? (:submit-ask! callbacks)))
                      (and (empty? (str/trim text)) (empty? selected)))]
    (react/useEffect
     (fn []
       ;; Adopt a server-side draft only when its version changed (for
       ;; example after a rejected receipt restored it).  Ordinary re-renders
       ;; never replace the operator's answer text or selection.
       (when (not= draft-version (.-current synced-version))
         (set! (.-current synced-version) draft-version)
         (set-text! (or (:text ask) ""))
         (set-selected! (set (or (:selected ask) #{}))))
       js/undefined)
     #js [draft-version])
    (dom/div {:className "hc-ask"
              :role "group"
              :aria-label (get-in ask [:aria :label])
              :aria-describedby (get-in ask [:aria :describedby])
              :key (:ask-id ask)}
             (dom/h3 nil "需要你回答")
             (dom/div {:className "hc-ask-status" :id (str "hc-ask-" (:ask-id ask) "-status")}
                      (:status-label ask))
             (dom/p {:className "hc-ask-question"} (:question ask))
             (when-let [context (:context ask)]
               (dom/p {:className "hc-ask-context"} context))
             (when (seq (:options ask))
               (dom/div {:className "hc-ask-options" :role (if multiple? "group" "radiogroup")}
                        (for [option (:options ask)]
                          (dom/label {:className "hc-ask-option" :key (str (:value option))}
                                     (dom/input {:type (if multiple? "checkbox" "radio")
                                                 :name (str "ask-" (:ask-id ask))
                                                 :checked (contains? selected (:value option))
                                                 :onChange (fn [event]
                                                             (let [checked? (.-checked (.-target event))]
                                                               (set-submitted! false)
                                                               (set-selected!
                                                                (if multiple?
                                                                  (if checked?
                                                                    (conj selected (:value option))
                                                                    (disj selected (:value option)))
                                                                  (if checked? #{(:value option)} #{})))))}) 
                                     (dom/span nil (:label option))))))
             (when input?
               (dom/textarea {:value text
                              :placeholder "输入回答…"
                              :aria-label "回答内容"
                              :onChange (fn [event]
                                          (set-submitted! false)
                                          (set-text! (.-value (.-target event))))
                              :onCompositionStart (fn [_] (set-submitted! false))
                              :onCompositionEnd (fn [_] (set-submitted! false))}))
             (when (seq (:answers ask))
               (dom/div nil
                        (dom/div {:className "hc-ask-status"} "回答记录")
                        (dom/ul {:className "hc-ask-answers"}
                                (for [answer (:answers ask)]
                                  (dom/li {:className "hc-chip" :key (:record-id answer)}
                                          (dom/span {:style (style {:whiteSpace "pre-wrap"})}
                                                    (or (:label answer)
                                                        (pr-str (:value answer))))
                                          (dom/span nil (:status-label answer)))))))
             (dom/div {:className "hc-message-actions"}
                      (dom/button {:className "hc-button hc-button-primary"
                                   :type "button"
                                   :disabled disabled?
                                   :aria-disabled (boolean disabled?)
                                   :aria-label (get-in ask [:submit :aria-label])
                                   :style (style {:minHeight (get-in ask [:submit :target-size])
                                                  :minWidth (get-in ask [:submit :target-size])})
                                   :onClick (fn [_]
                                              (when-not disabled?
                                                (set-submitted! true)
                                                (call! callbacks :submit-ask!
                                                       {:session-id session-id
                                                        :ask-id (:ask-id ask)
                                                        :answer value
                                                        :text text
                                                        :selected (vec selected)})))}
                                  (cond
                                    (= :skipped status) "已跳过"
                                    submitted? "已提交回答"
                                    :else (get-in ask [:submit :label])))
                      (let [skip (:skip ask)
                            skip-disabled? (:disabled? skip)]
                        (dom/div {:className "hc-ask-skip"}
                                 (dom/button {:className "hc-button"
                                              :type "button"
                                              :disabled skip-disabled?
                                              :aria-disabled (boolean skip-disabled?)
                                              :aria-label (:aria-label skip)
                                              :title (:reason skip)
                                              :style (style {:minHeight (or (:target-size skip) 32)
                                                             :minWidth (or (:target-size skip) 32)})
                                              :onClick (fn [_]
                                                         (when-not skip-disabled?
                                                           (set-submitted! true)
                                                           (call! callbacks :skip-ask!
                                                                  {:session-id session-id
                                                                   :ask-id (:ask-id ask)})))}
                                             (:label skip))
                                 (when (and skip-disabled? (:reason skip))
                                   (dom/div {:className "hc-composer-hint"} (:reason skip)))))))))

;; ---------------------------------------------------------------------------
;; Main surface

(defn- message-search-text
  "Text a reader can find in one message.  Process items and sources are
  included so a match inside a folded process block is discoverable; the
  renderer opens the details element before scrolling to it."
  [message]
  (str/join
   "\n"
   (remove str/blank?
           (concat
            [(:body message)]
            (map (fn [item]
                   (str (:label item) " " (:detail item) " " (:status item)))
                 (:process message))
            (map (fn [source]
                   (str (:title source) " " (:text source) " " (:url source)))
                 (:sources message))))))

(defn- find-matches
  "Return `[{:message-id id :count n}]` in message order for QUERY."
  [messages query]
  (let [query (str/lower-case (str query))]
    (if (str/blank? query)
      []
      (vec
       (keep (fn [message]
               (let [text (str/lower-case (message-search-text message))
                     count (loop [from 0 n 0]
                             (if-let [index (str/index-of text query from)]
                               (recur (+ index (max 1 (count query))) (inc n))
                               n))]
                 (when (pos? count)
                   {:message-id (:id message) :count count})))
             messages)))))

(def ^:private first-run-examples
  [["整理会议记录"
    "把下面的会议记录整理成结论、待办与负责人。"]
   ["解释这段代码"
    "逐段解释这段代码做了什么，并指出可能的风险。"]
   ["起草一封邮件"
    "帮我起草一封简洁、礼貌的跟进邮件。"]])

(defn- write-draft!
  "Fill the composer with an example.  It never sends: the operator still
  reviews and presses send."
  [callbacks session-id text]
  (when (fn? (:edit-draft! callbacks))
    ((:edit-draft! callbacks)
     {:session-id session-id
      :text text
      :cursor (count text)
      :selection {:start (count text) :end (count text)}
      :composing? false})))

(defn- first-run-guide
  "First-screen guide for an empty conversation.  The three actions are real
  entry points and the three examples only fill the draft."
  [session-id callbacks on-dismiss!]
  (let [settings! (:settings! callbacks)
        fix! (:fix! callbacks)
        draft? (fn? (:edit-draft! callbacks))]
    (dom/section {:className "hc-first-run" :aria-label "开始使用"}
                 (dom/h3 nil "开始第一次对话")
                 (dom/p {:className "wb-muted"}
                        "还没有消息。先连接模型或恢复已有资料，也可以直接试用下面的示例。")
                 (dom/div {:className "hc-first-run-actions"}
                          (dom/button {:className "hc-button hc-button-primary"
                                       :type "button"
                                       :disabled (not (or (fn? fix!)
                                                          (fn? settings!)))
                                       :title (when-not (or (fn? fix!)
                                                            (fn? settings!))
                                                "当前无法打开模型设置")
                                       :onClick (fn [_]
                                                  (cond
                                                    (fn? fix!)
                                                    (fix! {:session-id session-id
                                                           :intent :select-model})
                                                    (fn? settings!)
                                                    (settings! {:session-id session-id})
                                                    :else nil))}
                                      "连接模型")
                          (dom/button {:className "hc-button"
                                       :type "button"
                                       :disabled (not (fn? settings!))
                                       :title (when-not (fn? settings!)
                                                "当前无法打开设置")
                                       :onClick (fn [_]
                                                  (when (fn? settings!)
                                                    (settings! {:session-id session-id})))}
                                      "恢复已有资料")
                          (dom/button {:className "hc-button hc-button--quiet"
                                       :type "button"
                                       :onClick (fn [_] (on-dismiss!))}
                                      "稍后设置"))
                 (dom/div {:className "hc-first-run-examples"}
                          (dom/p {:className "hc-first-run-example-label"}
                                 "示例（只填入输入框，不会发送）")
                          (for [[title example] first-run-examples]
                            (dom/button {:className "hc-first-run-example"
                                         :type "button"
                                         :key title
                                         :disabled (not draft?)
                                         :title (when-not draft?
                                                  "当前无法写入草稿")
                                         :onClick (fn [_]
                                                    (write-draft!
                                                     callbacks session-id
                                                     example))}
                                        (dom/strong nil title)
                                        (dom/span nil example)))))))

(defonce ^:private scroll-anchors
  ;; Route changes unmount this surface.  The anchor must outlive the React
  ;; tree, so it lives beside the component keyed by session id.
  (atom {}))

(defn scroll-anchor-at
  "Return `{:message-id id :delta px}` for the message at the viewport top.

  RENDERED is `[{:message-id :top :height}]` in DOM order, with `:top`
  measured in the scroll container's content coordinates.  The first message
  whose bottom edge is below VIEWPORT-TOP is the visible anchor.  The delta is
  how far the message top sits above the viewport top."
  [viewport-top rendered]
  (when-let [rendered (seq (filter :message-id rendered))]
    (let [viewport-top (double (or viewport-top 0))
          chosen (or (first (filter (fn [{:keys [top height]}]
                                      (> (+ (double top) (double height))
                                         viewport-top))
                                    rendered))
                     (last rendered))]
      {:message-id (:message-id chosen)
       :delta (- viewport-top (double (:top chosen)))})))

(defn scroll-restore-target
  "Return the scrollTop that puts ANCHOR back at the viewport top.

  RENDERED is the same shape as `scroll-anchor-at`.  Returns nil when the
  anchor message is not in the rendered slice."
  [anchor rendered]
  (when-let [message-id (:message-id anchor)]
    (when-let [chosen (first (filter #(= message-id (:message-id %))
                                     rendered))]
      (+ (double (:top chosen))
         (double (or (:delta anchor) 0))))))

(defn scroll-window-size
  "Return the trailing message count that contains ANCHOR-INDEX.

  Keeps at least WINDOW-SIZE messages (and the established 200-message
  minimum), plus a 200-message buffer below the anchor.  The result is capped
  at TOTAL and never shrinks the caller's current window."
  [total anchor-index window-size]
  (let [total (long total)
        current (long window-size)
        anchor-index (long anchor-index)]
    (min total
         (max current 200 (+ (- total anchor-index) 200)))))

(defn- rendered-message-geometry
  "Measure the rendered message nodes in SCROLLER content coordinates."
  [scroller]
  (let [scroll-top (double (or (.-scrollTop scroller) 0))
        scroller-top (.-top (.getBoundingClientRect scroller))]
    (mapv (fn [node]
            (let [rect (.getBoundingClientRect node)]
              {:message-id (.getAttribute node "data-message-id")
               :top (+ scroll-top (- (.-top rect) scroller-top))
               :height (.-height rect)}))
          (array-seq (.querySelectorAll scroller "[data-message-id]")))))

(defn- dom-scroll-anchor
  "Binary-search the rendered message nodes for the viewport-top anchor.

  Scroll events run often; building a geometry map for every rendered message
  is avoidable work.  The rendered nodes are ordered by their top edge, so a
  binary search reads O(log n) rectangles and allocates only the result."
  [scroller]
  (let [nodes (to-array
               (array-seq (.querySelectorAll scroller "[data-message-id]")))
        node-count (alength nodes)
        scroller-top (.-top (.getBoundingClientRect scroller))
        scroll-top (double (or (.-scrollTop scroller) 0))
        top-of (fn [node]
                 (+ scroll-top
                    (- (.-top (.getBoundingClientRect node)) scroller-top)))]
    (when (pos? node-count)
      (let [node
            (loop [low 0
                   high (dec node-count)
                   candidate nil]
              (if (> low high)
                (or candidate (aget nodes (dec node-count)))
                (let [middle (quot (+ low high) 2)
                      current (aget nodes middle)
                      top (top-of current)
                      bottom (+ top (.-height (.getBoundingClientRect current)))]
                  (if (> bottom scroll-top)
                    (recur low (dec middle) current)
                    (recur (inc middle) high candidate)))))]
        {:message-id (.getAttribute node "data-message-id")
         :delta (- scroll-top (top-of node))}))))

(defn- message-anchor-index
  [messages anchor]
  (when-let [message-id (:message-id anchor)]
    (first (keep-indexed (fn [index message]
                           (when (= message-id (:id message)) index))
                         messages))))

(defn- restore-scroll!
  "Put the reader back on ANCHOR (preferred) or SAVED pixels.

  Returns true when a target was available and written.  The anchor wins
  because a pixel offset measured against a different window slice is not a
  stable reading position."
  [node anchor saved session-id callbacks scroll-restored-ref]
  (let [target (or (scroll-restore-target
                    anchor
                    (rendered-message-geometry node))
                   saved)]
    (when (some? target)
      (set! (.-scrollTop node) target)
      (set! (.-current scroll-restored-ref) session-id)
      (call! callbacks :scroll!
             (cond-> {:session-id session-id
                      :scroll-top target
                      :scroll-height (.-scrollHeight node)
                      :client-height (.-clientHeight node)}
               (:message-id anchor)
               (assoc :anchor-message-id (:message-id anchor)
                      :anchor-delta (:delta anchor))))
      true)))

(defn conversation-surface
  "Render the conversation surface from a CLJS props map.

  Props follow the workbench contract documented in
  `src/hc/client/conversation/AGENTS.md`.  The function returns a React
  element and may be called directly from another component's render."
  [props]
  (let [props (if (map? props) props (js->clj props :keywordize-keys true))
        callbacks (or (:callbacks props) {})
        view-model (surface/surface props)
        ;; Secondary controls keep a 44px touch target on :medium/:compact
        ;; and may shrink to 32px only on :wide desktop.  The value comes
        ;; from the same a11y table the surface exposes.
        secondary-target (or (get-in view-model [:a11y :secondary-target]) 44)
        messages-ref (react/useRef nil)
        textarea-ref (react/useRef nil)
        ;; Synchronous composition state for the keydown guard.  React state
        ;; would re-render on every composition start/end; a ref is enough.
        composing-ref (react/useRef false)
        restored-session-ref (react/useRef nil)
        scroll-restored-ref (react/useRef nil)
        [local-jump? set-local-jump!] (react/useState false)
        [selection-locked? set-selection-locked!] (react/useState false)
        [pending-send set-pending-send!] (react/useState nil)
        [local-decision set-local-decision!] (react/useState nil)
        [selected-messages set-selected-messages!] (react/useState #{})
        [include-sources? set-include-sources!] (react/useState false)
        [include-tools? set-include-tools!] (react/useState false)
        [export-error set-export-error!] (react/useState nil)
        [find-open? set-find-open!] (react/useState false)
        [find-query set-find-query!] (react/useState "")
        [find-index set-find-index!] (react/useState 0)
        [message-window set-message-window!] (react/useState 200)
        [dismissed-guide-session set-dismissed-guide-session!]
        (react/useState ::guide-unset)
        find-input-ref (react/useRef nil)
        pending-scroll-ref (react/useRef nil)
        expand-anchor-ref (react/useRef nil)
        selection-ref (react/useRef nil)
        session-id (:session-id view-model)
        find-seq (:find-seq props)
        find-hits (find-matches (:messages view-model) find-query)
        find-total (reduce + (map :count find-hits))
        active-find (when (seq find-hits)
                      (nth find-hits (mod find-index (count find-hits))))
        composer-value (get-in view-model [:composer :value])
        composer-composing? (boolean (get-in view-model [:composer :composing?]))
        session (:session props)
        last-assistant-id (some (fn [m] (when (= :assistant (:role m)) (:id m)))
                                (reverse (:messages view-model)))
        jump-visible? (or (get-in view-model [:jump :visible?]) local-jump?)
        translation (surface/translation-context
                     {:composer (get-in view-model [:composer])
                      :draft (:draft props)})
        draft-selection (:draft-selection translation)
        draft-text (:draft-text translation)
        replace-translation!
        (fn [payload]
          (when (and (map? payload) (string? (:text payload)))
            (let [current (:draft-selection
                           (surface/translation-context
                            {:composer (get-in view-model [:composer])
                             :draft (:draft props)}))
                  action (surface/translation-replace-action
                          {:request payload
                           :current current
                           :replace? (or (fn? (:replace-transcript! callbacks))
                                         (fn? (:edit-draft! callbacks)))
                           :insert? (fn? (:insert-transcript! callbacks))})]
              (case (:action action)
                :replace (cond
                           (fn? (:replace-transcript! callbacks))
                           ((:replace-transcript! callbacks)
                            (assoc (:payload action) :session-id session-id))

                           ;; The workbench may not expose a dedicated
                           ;; replace mutation yet; the existing draft edit is
                           ;; an equivalent whole-text replacement, computed
                           ;; only after the range and selected text matched.
                           (fn? (:edit-draft! callbacks))
                           (let [{:keys [start end text]} (:payload action)
                                 source (:draft-text
                                         (surface/translation-context
                                          {:composer (get-in view-model [:composer])
                                           :draft (:draft props)}))
                                 next-text (str (subs source 0 start)
                                                text
                                                (subs source end))
                                 cursor (+ start (count text))]
                             ((:edit-draft! callbacks)
                              {:session-id session-id
                               :text next-text
                               :cursor cursor
                               :selection {:start cursor :end cursor}
                               :composing? false}))

                           :else nil)
                :insert ((:insert-transcript! callbacks)
                         (assoc (:payload action) :session-id session-id))
                :copy (if (fn? (:copy! callbacks))
                        ((:copy! callbacks) (:text action))
                        (copy-text! (:text action)))
                nil))))]
    (react/useEffect
     (fn []
       (when-let [target (get-in view-model [:focus :target])]
         (when-let [node (js/document.getElementById target)]
           (.focus node)))
       js/undefined)
     (clj->js [(get-in view-model [:focus :target])]))
    (react/useEffect
     (fn []
       ;; A reader's selection belongs to the message scroller.  Track it while
       ;; it is live and forget it as soon as a pointer or a navigation key
       ;; deliberately starts a new one; a collapsed selection caused by a
       ;; streamed text mutation is kept for the layout effect below.
       (when (and (exists? js/document) (exists? js/window))
         (let [capture! (fn [_]
                          (when-let [root (.-current messages-ref)]
                            (when-let [saved (capture-selection! root)]
                              (set! (.-current selection-ref) saved))))
               pointer! (fn [event]
                          ;; A primary pointer starts a new reading gesture.
                          ;; A secondary button (copy menu) leaves the range
                          ;; alone.
                          (let [button (.-button event)]
                            (when (or (not (number? button)) (zero? button))
                              (set! (.-current selection-ref) nil))))
               key! (fn [event]
                      (let [target (.-target event)
                            text-control? (or (contains? #{"INPUT" "TEXTAREA"
                                                           "SELECT"}
                                                         (some-> target .-tagName))
                                              (true? (some-> target
                                                             .-isContentEditable)))]
                        (when (and (not (.-ctrlKey event))
                                   (not (.-metaKey event))
                                   (not (.-altKey event))
                                   (or (= "Escape" (.-key event))
                                       (and (not text-control?)
                                            (contains?
                                             #{"ArrowLeft" "ArrowRight"
                                               "ArrowUp" "ArrowDown" "Home" "End"
                                               "PageUp" "PageDown"}
                                             (.-key event)))))
                          (set! (.-current selection-ref) nil))))]
           (.addEventListener js/document "selectionchange" capture!)
           (.addEventListener js/document "pointerdown" pointer! true)
           (.addEventListener js/document "mousedown" pointer! true)
           (.addEventListener js/document "keydown" key! true)
           (fn []
             (.removeEventListener js/document "selectionchange" capture!)
             (.removeEventListener js/document "pointerdown" pointer! true)
             (.removeEventListener js/document "mousedown" pointer! true)
             (.removeEventListener js/document "keydown" key! true))))
       js/undefined)
     #js [])
    (react/useEffect
     (fn []
       (when (pos? (long (or find-seq 0)))
         (set-find-open! true))
       js/undefined)
     #js [find-seq])
    (react/useEffect
     (fn []
       (when find-open?
         (when-let [node (.-current find-input-ref)]
           (.focus node)
           (.select node)))
       js/undefined)
     #js [find-open?])
    (react/useEffect
     (fn []
       (when (and find-open? (seq find-hits))
         (when-let [root (.-current messages-ref)]
           (let [match (nth find-hits (mod find-index (count find-hits)))
                 target (:message-id match)
                 all-messages (vec (:messages view-model))
                 total (count all-messages)
                 window-size (min total (max 200 message-window))
                 start (max 0 (- total window-size))
                 target-index (first
                               (keep-indexed
                                (fn [index message]
                                  (when (= target (:id message)) index))
                                all-messages))
                 node (some (fn [candidate]
                              (when (= target (.getAttribute candidate
                                                            "data-message-id"))
                                candidate))
                            (array-seq (.querySelectorAll root
                                                          "[data-message-id]")))]
             (cond
               node
               (do
                 ;; A match inside a folded process block must be visible
                 ;; before the browser can scroll to it.  Opening details
                 ;; does not create a command or a model request.
                 (doseq [details (array-seq (.querySelectorAll node "details"))]
                   (set! (.-open details) true))
                 (when (fn? (.-scrollIntoView node))
                   (.scrollIntoView node #js {:block "center"})))

               (and (some? target-index) (< target-index start))
               ;; The hit is above the rendered slice.  Extend the window;
               ;; this effect runs again with the same index and locates it.
               (set-message-window!
                (+ message-window (- start target-index) 200))))))
       js/undefined)
     #js [find-open? find-query find-index message-window])
    (react/useLayoutEffect
     (fn []
       ;; Selecting a session again restores the reader's message anchor.
       ;; A raw scrollTop belongs to the old window slice and goes stale as
       ;; soon as that slice changes; the message id and its relative offset
       ;; survive the route switch.  Scroll pixels remain the fallback for a
       ;; fresh page where no anchor was ever recorded.
       (when-let [node (.-current messages-ref)]
         (let [saved (get-in view-model [:stream :scroll-top])
               messages (:messages view-model)
               total (count messages)
               anchor (get @scroll-anchors session-id)
               anchor-index (message-anchor-index messages anchor)
               ;; The window keeps a trailing slice of the conversation.
               ;; Extend it to the anchor before writing scrollTop; the old
               ;; pixel heuristic could choose a slice that still ends above
               ;; the reader's position and clamp to the new bottom.
               needed (cond
                        (some? anchor-index)
                        (scroll-window-size total anchor-index message-window)

                        (some? saved)
                        (min total
                             (max 200
                                  (+ 200 (long (js/Math.ceil (/ saved 48))))))

                        :else nil)]
           (when (or (some? anchor) (some? saved))
             (if (and (some? needed) (> needed message-window))
               (do
                 (set-message-window! needed)
                 (set! (.-current pending-scroll-ref)
                       [session-id {:saved saved :anchor anchor}]))
               (restore-scroll! node anchor saved session-id callbacks
                                scroll-restored-ref)))))
       js/undefined)
     #js [session-id])
    (react/useLayoutEffect
     (fn []
       (when-let [[pending-session pending] (.-current pending-scroll-ref)]
         (when (= pending-session session-id)
           (when-let [node (.-current messages-ref)]
             (let [{:keys [saved anchor]}
                   (if (map? pending)
                     pending
                     {:saved pending})
                   anchor-ready?
                   (or (nil? (:message-id anchor))
                       (some #(= (:message-id anchor) (:message-id %))
                             (rendered-message-geometry node)))]
               ;; Setting the window in the mount layout effect does not
               ;; re-render before this effect runs in the same commit.  Do
               ;; not consume the pending anchor until the expanded DOM
               ;; actually contains it; otherwise scrollTop clamps against
               ;; the short window and lands at its bottom.
               (when anchor-ready?
                 (restore-scroll! node anchor saved session-id callbacks
                                  scroll-restored-ref)
                 (set! (.-current pending-scroll-ref) nil))))))
       js/undefined)
     #js [message-window session-id])
    (react/useLayoutEffect
     (fn []
       ;; Loading earlier messages inserts content above the viewport.  Shift
       ;; scrollTop by the same amount so the message the reader was reading
       ;; does not jump downward.
       (when-let [old-height (.-current expand-anchor-ref)]
         (when-let [node (.-current messages-ref)]
           (let [delta (- (.-scrollHeight node) old-height)]
             (when (pos? delta)
               (set! (.-scrollTop node) (+ (.-scrollTop node) delta)))))
         (set! (.-current expand-anchor-ref) nil))
       js/undefined)
     #js [message-window])
    (react/useEffect
     (fn []
       (when (and (not selection-locked?)
                  (not= session-id (.-current scroll-restored-ref))
                  (get-in view-model [:stream :follow?])
                  (get-in view-model [:stream :streaming?])
                  (not (get-in view-model [:stream :stopping?])))
         (when-let [node (.-current messages-ref)]
           (set! (.-scrollTop node) (.-scrollHeight node)))
         (set! (.-current scroll-restored-ref) nil))
       js/undefined)
     (clj->js [(get-in view-model [:stream :scroll-request])
               (get-in view-model [:stream :follow?])
               selection-locked?]))
    (react/useEffect
     (fn []
       ;; The follow effect above may not run when its dependencies did not
       ;; change across the session switch.  Clear the guard after this
       ;; commit so the next streamed chunk can follow normally.
       (when (= session-id (.-current scroll-restored-ref))
         (set! (.-current scroll-restored-ref) nil))
       js/undefined)
     #js [session-id])
    (react/useLayoutEffect
     (fn []
       ;; React commits the new streamed text before this effect.  Blink may
       ;; have collapsed the reader's Range while replacing character data;
       ;; put the same range back without touching the stream state.
       (when-let [root (.-current messages-ref)]
         (when-let [saved (.-current selection-ref)]
           (let [selection (when (exists? js/window) (.getSelection js/window))]
             (when (or (nil? selection)
                       (.-isCollapsed selection)
                       (not= (:text saved) (str selection)))
               (when-not (restore-selection! root saved)
                 ;; The selected text no longer exists (edit/version switch).
                 (set! (.-current selection-ref) nil))))))
       js/undefined))
    (react/useEffect
     (fn []
       (when (and pending-send
                  (= (:session-id pending-send) session-id)
                  (get-in view-model [:stream :stopped?])
                  (fn? (:send! callbacks)))
         ((:send! callbacks) pending-send)
         (set-pending-send! nil))
       js/undefined)
     (clj->js [pending-send session-id (get-in view-model [:stream :stopped?])]))
    (react/useEffect
     (fn []
       ;; A stop the Owner refused (or a run that ended without a stop
       ;; receipt) must not leave a banner promising a send that will never
       ;; happen.
       (when (and pending-send
                  (not (get-in view-model [:stream :streaming?]))
                  (not (get-in view-model [:stream :stopping?]))
                  (not (get-in view-model [:stream :stopped?])))
         (set-pending-send! nil))
       js/undefined)
     (clj->js [pending-send
               (get-in view-model [:stream :streaming?])
               (get-in view-model [:stream :stopping?])
               (get-in view-model [:stream :stopped?])]))
    (react/useEffect
     (fn []
       ;; Local composer decisions and pending sends belong to one session.
       ;; Never carry a promise from the previous session into the next one.
       (set-pending-send! nil)
       (set-local-decision! nil)
       js/undefined)
     (clj->js [session-id]))
    (react/useEffect
     (fn []
       (speech-playback/stop-if-stale! (:messages view-model))
       js/undefined)
     (clj->js [(:messages view-model)]))
    (react/useEffect
     (fn []
       ;; The textarea is uncontrolled so server pushes cannot replace text or
       ;; IME composition.  The composer view model is the same source that
       ;; seeded `defaultValue`; reading raw `:draft` here would clear the
       ;; field when the caller supplies a full `:conversation` state instead
       ;; of split props.
       (when-let [node (.-current textarea-ref)]
         (let [composer (:composer view-model)
               text (or (:value composer) "")
               cursor (or (:cursor composer) 0)
               selection (:selection composer)
               start (or (:start selection) cursor)
               end (or (:end selection) start)
               focused? (= (.-activeElement js/document) node)
               session-changed? (not= session-id (.-current restored-session-ref))
               value-differs? (not= (.-value node) text)
               ;; Adopt an external draft only when it cannot destroy the
               ;; operator's work: a pristine field (reload/restore), an
               ;; explicitly cleared draft (accepted send), or an unfocused
               ;; field (session switch).  A focused field that already holds
               ;; text keeps it until the operator moves focus.
               adopt? (and value-differs?
                           (or (empty? (.-value node))
                               (empty? text)
                               (not focused?)))]
           (when-not composer-composing?
             (when adopt?
               (set! (.-value node) text))
             ;; Restore the caret on mount, session switch, and draft
             ;; adoption.  Ordinary renders never move it under a focused
             ;; editor, so typing and IME composition keep their own caret.
             (when (and (or adopt? session-changed? (not focused?))
                        (fn? (.-setSelectionRange node)))
               (.setSelectionRange node start end))))
         (set! (.-current restored-session-ref) session-id))
       js/undefined)
     (clj->js [session-id composer-value composer-composing?
               (get-in props [:draft :version])
               (get-in view-model [:composer :draft-version])]))
    (dom/div {:className (:class view-model)
              :data-layout (name (:layout view-model))}
             (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
             (dom/div {:className "hc-visually-hidden"
                       :role "status"
                       :aria-live "polite"
                       :aria-atomic "true"}
                      (get-in view-model [:live-region :text]))
             (when-let [alert (get-in view-model [:alert :text])]
               (dom/div {:className "hc-visually-hidden" :role "alert" :aria-live "assertive"}
                        alert))
             (dom/div {:className "hc-conversation-header"}
                      (dom/h2 {:className "hc-conversation-title"}
                              (session-title-label
                               (get-in view-model [:header :title])))
                      (when (get-in view-model [:header :temporary?])
                        (dom/span {:className "hc-banner hc-banner-info"
                                   :style (style {:margin 0 :padding "2px 8px"})}
                                  (get-in view-model [:header :temporary-label])))
                      (dom/div {:className "hc-conversation-meta"}
                               (let [settings! (:settings! callbacks)
                                     model-label (str "模型："
                                                      (get-in view-model [:header :model-label]))]
                                 (if (fn? settings!)
                                   (dom/button {:type "button"
                                                :className "hc-button"
                                               :style (style {:minHeight secondary-target
                                                              :minWidth secondary-target
                                                               :padding "2px 8px"})
                                                :aria-label "打开设置选择模型"
                                                :onClick (fn [_] (settings! {}))}
                                               model-label)
                                   (dom/span nil model-label)))
                               (when-let [workspace (get-in view-model [:header :workspace-label])]
                                 (dom/span nil (str "工作区：" workspace)))
                               (when-let [connection (get-in view-model [:header :connection-label])]
                                 (dom/span nil connection))
                               (when-let [status (get-in view-model [:header :status])]
                                 (when-let [label (session-status-label status)]
                                   (dom/span nil label)))
                               (when-let [run-label (get-in view-model [:header :run-label])]
                                 (dom/span {:className "hc-banner hc-banner-info"
                                            :role "status"
                                            :style (style {:margin 0 :padding "2px 8px"})}
                                           run-label)))
                      (for [action (get-in view-model [:header :actions])]
                        (let [callback (case (:id action)
                                         :open-settings (:settings! callbacks)
                                         :open-aux (:aux! callbacks)
                                         nil)]
                          (when (fn? callback)
                            (dom/button {:className "hc-button"
                                         :type "button"
                                         :key (:id action)
                                         :id (str "hc-header-action-"
                                                  (name (:id action)))
                                         :aria-label (:aria-label action)
                                         :style (style {:minHeight secondary-target
                                                        :minWidth secondary-target})
                                         :onClick (fn [_] (callback {}))}
                                        (:label action))))))
             (for [banner (distinct (:banners view-model))]
               (dom/div {:className (class-names "hc-banner"
                                                 (case (:kind banner)
                                                   :error "hc-banner-error"
                                                   :warning "hc-banner-warning"
                                                   "hc-banner-info"))
                         :role (if (= :error (:kind banner)) "alert" "status")
                         :key (str (:kind banner) "-" (:text banner))}
                        (when-let [reason-label
                                   (:label (some #(when (= (:id %)
                                                            (:reason banner))
                                                   %)
                                                 failures/failure-reason-categories))]
                          (dom/div {:className "hc-reason"}
                                   reason-label))
                        (dom/span nil (banner-text-label (:text banner)))
                        (for [action (:actions banner)]
                          (let [local-action? (contains? #{:copy-draft :export-draft
                                                          :cancel-pending-send}
                                                        (:id action))
                                callback (case (:id action)
                                           :copy-draft nil
                                           :export-draft nil
                                           :check-connection (:resync! callbacks)
                                           :restore-rejected (:restore-rejected! callbacks)
                                           :select-model (or (:fix! callbacks)
                                                             (:settings! callbacks))
                                           :cancel-pending-send nil)]
                            (when (or local-action? (fn? callback))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :key (:id action)
                                           :style (style {:minHeight secondary-target
                                                          :minWidth secondary-target})
                                           :onClick (fn [_]
                                                      (case (:id action)
                                                        :copy-draft (copy-text! (get-in view-model [:composer :value]))
                                                        :export-draft (offer-download!
                                                                       (get-in view-model [:composer :value])
                                                                       "herdchestra-draft.txt"
                                                                       "text/plain;charset=utf-8"
                                                                       set-export-error!)
                                                        :check-connection (when (fn? callback)
                                                                            (callback {}))
                                                        :restore-rejected (when (fn? callback)
                                                                            (callback {:session-id session-id}))
                                                        :select-model (when (fn? callback)
                                                                        (callback {:session-id session-id
                                                                                   :intent :select-model}))
                                                        :cancel-pending-send (do
                                                                               (set-pending-send! nil)
                                                                               (call! callbacks :cancel-pending-send!
                                                                                      {:session-id session-id}))
                                                        nil))}
                                          (:label action)))))))
             (when export-error
               (dom/div {:className "hc-banner hc-banner-warning"
                         :role "alert"}
                        export-error))
             (let [selected (filterv #(contains? selected-messages (:id %))
                                     (:messages view-model))]
               (when (seq selected)
                 (dom/div {:className "hc-export-selection"
                           :role "group"
                           :aria-label "多选导出"}
                          (dom/span nil (str "已选 " (count selected) " 条消息"))
                          (dom/label {:className "hc-context-rounds"}
                                     (dom/input {:type "checkbox"
                                                 :checked include-sources?
                                                 :onChange (fn [event]
                                                             (set-include-sources!
                                                              (.-checked (.-target event))))})
                                     "含来源")
                          (dom/label {:className "hc-context-rounds"}
                                     (dom/input {:type "checkbox"
                                                 :checked include-tools?
                                                 :onChange (fn [event]
                                                             (set-include-tools!
                                                              (.-checked (.-target event))))})
                                     "包含工具详情")
                          (dom/button {:className "hc-button" :type "button"
                                       :aria-label "导出选中消息"
                                       :onClick #(offer-download!
                                                  (export/markdown
                                                   selected
                                                   {:include-sources? include-sources?
                                                    :include-tools? include-tools?
                                                    :fallback-sources
                                                    (search-model/source-cards
                                                     (:search-state props))})
                                                  "herdchestra-selected.md"
                                                  "text/markdown;charset=utf-8"
                                                  set-export-error!)}
                                      "导出选中")
                          (dom/button {:className "hc-button" :type "button"
                                       :aria-label "导出选中消息为纯文本"
                                       :onClick #(offer-download!
                                                  (export/plain-text
                                                   selected
                                                   {:include-sources? include-sources?
                                                    :include-tools? include-tools?
                                                    :fallback-sources
                                                    (search-model/source-cards
                                                     (:search-state props))})
                                                  "herdchestra-selected.txt"
                                                  "text/plain;charset=utf-8"
                                                  set-export-error!)}
                                      "导出纯文本")
                          (dom/button {:className "hc-button" :type "button"
                                       :aria-label "清除消息选择"
                                       :onClick #(set-selected-messages! #{})}
                                      "清除选择"))))
             (context-panel session session-id callbacks (:messages view-model)
                            (:search-state props)
                           secondary-target
                            (fn [text filename mime]
                              (offer-download! text filename mime
                                               set-export-error!)))
             (when-let [stream-status (get-in view-model [:stream :status-label])]
               (when (contains? #{:stopping :stopped}
                                (get-in view-model [:stream :status]))
                 (dom/div {:className "hc-stream-status"
                           :role "status"
                           :aria-live "polite"}
                          stream-status)))
             (when-let [blocked (:blocked view-model)]
               (dom/div {:className "hc-blocked" :role "alert"}
                       (when-let [reason-label
                                  (:label (some #(when (= (:id %)
                                                           (:reason blocked))
                                                      %)
                                                    failures/failure-reason-categories))]
                         (dom/div {:className "hc-reason"} reason-label))
                        (dom/strong nil (str "运行受阻："
                                             (or (:code blocked) "未知原因")))
                        (dom/div nil
                                 (str "成员 " (or (:member blocked) "—")
                                      " · 次数 " (or (:count blocked) "—")
                                      " · 最后错误 "
                                      (or (:last-code blocked) "—")))
                        (dom/div nil (or (:remedy blocked) "请检查工具准入配置后重试。"))))
             (when find-open?
               (dom/div {:className "hc-find"
                         :role "search"
                         :aria-label "页内查找"}
                        (dom/input
                         {:type "search"
                          :ref find-input-ref
                          :className "hc-find-input"
                          :value find-query
                          :placeholder "查找消息内容"
                          :aria-label "查找消息内容"
                          :onChange (fn [event]
                                      (set-find-query!
                                       (.. event -target -value))
                                      (set-find-index! 0))
                          :onKeyDown
                          (fn [event]
                            (case (.-key event)
                              "Enter"
                              (do
                                (.preventDefault event)
                                (.stopPropagation event)
                                (set-find-index!
                                 (mod (+ find-index
                                         (if (.-shiftKey event) -1 1))
                                      (max 1 (count find-hits)))))
                              "Escape"
                              (do
                                (.preventDefault event)
                                (.stopPropagation event)
                                (set-find-open! false))
                              nil))})
                        (dom/span {:className "hc-find-count"
                                   :role "status"
                                   :aria-live "polite"}
                                  (cond
                                    (str/blank? find-query) "输入关键词"
                                    (zero? find-total) "0 个命中"
                                    :else (str find-total " 个命中")))
                        (dom/button
                         {:type "button"
                          :className "hc-button"
                          :disabled (empty? find-hits)
                          :aria-label "上一个命中"
                          :onClick (fn [_]
                                     (set-find-index!
                                      (mod (dec find-index)
                                           (max 1 (count find-hits)))))}
                         "上一个")
                        (dom/button
                         {:type "button"
                          :className "hc-button"
                          :disabled (empty? find-hits)
                          :aria-label "下一个命中"
                          :onClick (fn [_]
                                     (set-find-index!
                                      (mod (inc find-index)
                                           (max 1 (count find-hits)))))}
                         "下一个")
                        (dom/button
                         {:type "button"
                          :className "hc-button"
                          :aria-label "关闭页内查找"
                          :onClick (fn [_] (set-find-open! false))}
                         "关闭")))
             (dom/div {:className "hc-conversation-messages"
                       :ref messages-ref
                       :role "log"
                       :aria-label "会话消息"
                       :onScroll (fn [_]
                                   (let [node (.-current messages-ref)]
                                     (when node
                                       (let [anchor (dom-scroll-anchor node)
                                             at-bottom? (<= (- (.-scrollHeight node)
                                                               (.-scrollTop node)
                                                               (.-clientHeight node))
                                                            stream/bottom-threshold)]
                                         (when anchor
                                           (swap! scroll-anchors assoc session-id
                                                  anchor))
                                         (set-local-jump! (not at-bottom?))
                                         (when at-bottom? (set-selection-locked! false))
                                         (call! callbacks :scroll!
                                                (cond-> {:session-id session-id
                                                         :scroll-top (.-scrollTop node)
                                                         :scroll-height (.-scrollHeight node)
                                                         :client-height (.-clientHeight node)}
                                                  (:message-id anchor)
                                                  (assoc :anchor-message-id
                                                         (:message-id anchor)
                                                         :anchor-delta
                                                         (:delta anchor))))))))
                       :onMouseDown (fn [_] (set-selection-locked! true))
                       :onMouseUp (fn [_]
                                    (let [selection (when-let [sel (js/window.getSelection)]
                                                      (str sel))]
                                      (set-selection-locked! (boolean (seq selection)))
                                      (when-let [root (.-current messages-ref)]
                                        (when-let [saved (capture-selection! root)]
                                          (set! (.-current selection-ref) saved)))))
                       :onTouchStart (fn [_] (set-selection-locked! true))
                       :onTouchEnd (fn [_]
                                     (set-selection-locked! false)
                                     (when-let [root (.-current messages-ref)]
                                       (when-let [saved (capture-selection! root)]
                                         (set! (.-current selection-ref) saved))))}
                      (dom/div {:className "hc-message-stream"}
                        (let [all-messages (vec (:messages view-model))
                            total (count all-messages)
                            window-size (min total (max 200 message-window))
                            start (max 0 (- total window-size))
                            visible (subvec all-messages start)]
                        (if (seq visible)
                          (concat
                           (when (pos? start)
                             [(dom/div {:className "hc-message-more"}
                                       (dom/button
                                        {:type "button"
                                         :className "hc-button"
                                         :onClick
                                         (fn [_]
                                           (when-let [node (.-current messages-ref)]
                                             (set! (.-current expand-anchor-ref)
                                                   (.-scrollHeight node)))
                                           (set-message-window!
                                            (+ window-size 200)))}
                                        (str "显示更早消息（还有 " start
                                             " 条）")))])
                           (for [[position message] (map-indexed vector visible)]
                             (message-item message
                                           {:callbacks callbacks
                                            :session-id session-id
                                            :commands (command-map (:command props))
                                            :pinned-ids (pinned-attachment-ids session)
                                            :draft-selection draft-selection
                                            :draft-text draft-text
                                            :on-replace replace-translation!
                                            :search-state (:search-state props)
                                            :last-assistant? (= (:id message)
                                                                last-assistant-id)
                                            :selected? (contains? selected-messages (:id message))
                                            :on-select! (fn [message-id checked?]
                                                          (set-selected-messages!
                                                           (if checked?
                                                             (conj selected-messages message-id)
                                                             (disj selected-messages message-id))))
                                            :secondary-target secondary-target})))
                          (if (and (empty? all-messages)
                                   (not= session-id dismissed-guide-session))
                            [(first-run-guide
                              session-id callbacks
                              #(set-dismissed-guide-session! session-id))]
                            [(dom/div {:className "hc-message-meta"
                                       :style (style {:padding "24px 0"})}
                                      "还没有消息。输入内容开始。")
                             ;; R-12: an empty conversation with no bound model
                             ;; gets the same repair entry as the composer, so
                             ;; the first screen is not a dead end.
                             (when (and (empty? all-messages)
                                        (= :select-model
                                           (get-in view-model [:composer :send :intent]))
                                        (get-in view-model
                                                [:composer :send :model-entry :visible?]))
                               [(dom/div {:className "hc-message-actions"
                                          :style (style {:justifyContent "center"})}
                                         (dom/button
                                          {:className "hc-button hc-button-primary"
                                           :type "button"
                                           :aria-label "连接模型"
                                           :onClick (fn [_]
                                                      (cond
                                                        (fn? (:fix! callbacks))
                                                        ((:fix! callbacks)
                                                         {:session-id session-id
                                                          :intent :select-model})
                                                        (fn? (:settings! callbacks))
                                                        ((:settings! callbacks)
                                                         {:session-id session-id})
                                                        :else nil))}
                                          "连接模型"))])])))))
             (when jump-visible?
               (dom/button {:className "hc-button hc-jump"
                            :type "button"
                            :aria-label (get-in view-model [:jump :label])
                            :onClick (fn [_]
                                       (set-local-jump! false)
                                       (set-selection-locked! false)
                                       (when-let [node (.-current messages-ref)]
                                         (set! (.-scrollTop node) (.-scrollHeight node)))
                                       (call! callbacks :scroll!
                                              {:session-id session-id :at-bottom? true}))}
                           (get-in view-model [:jump :label])))
             (for [ask (:asks view-model)]
               (react/createElement
                ask-card
                #js {:key (:ask-id ask)
                     :props {:ask ask
                             :session-id session-id
                             :callbacks callbacks}}))
             (composer-view {:props props
                             :view-model view-model
                             :textarea-ref textarea-ref
                             :composing-ref composing-ref
                             :local-decision local-decision
                             :set-local-decision! set-local-decision!
                             :pending-send pending-send
                             :set-pending-send! set-pending-send!
                             :selection-locked? selection-locked?
                             :set-selection-locked! set-selection-locked!})
             (react/createElement speech-playback/player-element))))

(defn- unwrap-props
  [props]
  (cond
    ;; A ClojureScript map must be returned whole: `js->clj` cannot see its
    ;; namespaced message keys (`:message/by-id`) and would silently drop
    ;; every message the surface is asked to render.
    (and (map? props) (contains? props :props)) (:props props)
    (map? props) props
    (and (object? props) (some? (.-props props)))
    (let [inner (.-props props)]
      (cond
        ;; React hands the element props as a plain JS object; when the
        ;; caller passed a ClojureScript map through `#js {:props map}`,
        ;; `inner` is already that map and must not be converted.
        (map? inner) inner
        :else (js->clj props :keywordize-keys true)))
    :else (js->clj props :keywordize-keys true)))

(defn conversation-page
  "React entry point that preserves CLJS maps across the JS boundary.

  Workbench may either call `(conversation-page props-map)` directly inside a
  render function, or create the element with a single JS property:
  `(react/createElement conversation-page #js {:props props-map})`.  Passing
  the map through `clj->js` would drop namespaced keys such as
  `:message/by-id`, so the single `:props` property is the safe React form."
  [props]
  (let [props (unwrap-props props)]
    ;; The workbench already supplies the Framework7 Page.  Adding `.page`
    ;; here would make this wrapper absolutely position over the whole view
    ;; and cover the session list, so keep it a normal grid-cell container.
    (dom/div {:className "hc-conversation-page"
              :style (style {:display "flex" :flexDirection "column"
                             :height "100%" :minWidth 0 :minHeight 0})}
             (conversation-surface props))))
