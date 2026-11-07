# `hc.client.sessions` — real session metadata controls

Authority: root `AGENTS.md` §7.4 旅程 5.5（历史与已删除区） (history, rename, pin, archive, delete and
30-day deleted area) > `src/AGENTS.md` > `src/hc/client/AGENTS.md` > this file.

## Purpose

This package owns the browser controls for session metadata.  Every control
maps to a real server command; the UI never toggles a title, pin, archive, or
delete state locally.  A command is tracked in `:ui/command`, and only the
server receipt or the next state event (`STATE_SNAPSHOT`/`STATE_DELTA`)
changes the displayed state.

## Files

| File | Responsibility |
|---|---|
| `controls.cljc` | Pure action/payload/gating/receipt model; normalizes raw `:session/list` summaries. |
| `controls/ui.cljs` | React controls: rename inline form, pin/unpin, archive, restore, soft-delete confirmation, permanent-purge confirmation, receipt line. |

## Public entries

| Name | Contract |
|---|---|
| `controls/actions` | `(session connection command)` → ordered action maps with `:kind`, `:label`, `:disabled?`, `:reason`, `:danger?`, `:confirm?`. |
| `controls/command-payload` | `(action session {:title ...})` → payload or nil for invalid input. |
| `controls/metadata-command` | Return a command only when its kind is one of the six metadata kinds; send/stop/history commands never label or gate these controls. |
| `controls/latest-command` | Newest raw metadata command for a session, including `:rejected`/`:failed` receipts so the control line can show them. |
| `controls/command-status-text` | Human receipt text; rejection includes the server message. |
| `controls/deleted-sessions` | Normalize and sort raw `:session/list` rows, keeping `:deleted` only. |
| `controls.ui/SessionControls` | Plain React component mounted in the workbench session list; `:on-command` receives `{:kind keyword :action keyword :payload map}` and `:on-notice` receives `(tone text)`. |

## Commands and receipts

| Control | Kind | Payload |
|---|---|---|
| Rename | `:session/rename` | `{:title non-blank}` (trimmed, max 200) |
| Pin / unpin | `:session/pin` | `{:pinned? boolean}` |
| Archive | `:session/archive` | `{}` |
| Restore | `:session/restore` | `{}` |
| Delete | `:session/delete` | `{}` |
| Permanent purge | `:session/purge` | `{:confirm? true}` (available for any deleted session; 30 days is default retention, not a gate) |
| Deleted list | `:session/list` | `{:include-deleted? true}` (app-level command) |

`workbench.commands/session-metadata!` uses the per-session Owner epoch
(`bridge/command-epoch-for-session`) and tracks the command before sending.
`session-list!` uses the app-level `bridge/command-epoch`.  A missing epoch
returns `:command-epoch-unknown` and requests a fresh baseline; the transport
is never bypassed.

Receipts update `:ui/command` by `command-id` (`events/apply-receipt`).  The
controls read the newest tracked command for that session through
`workbench.model/session-command`, but ignore non-metadata kinds.  Accepted
rename/pin/archive/delete become visible only when the next snapshot arrives.
A `:session/list` accepted receipt stores raw sessions in `:ui/session-list`;
an accepted `:session/restore` removes the restored row from that local list;
an accepted `:session/purge` removes the purged row and any stale session
display copy.  Deleted rows carry the Owner-issued `:command-epoch`.  They are
absent from the adapter's confirmed `:session/by-id`, so
`bridge/command-epoch-for-session` reads the Fulcro display database first
and looks up the same `sid` in `:ui/session-list`; the adapter view is only
the fallback for visible sessions.  Restore and purge therefore use the same
per-session epoch as live-session metadata commands, never the app epoch or
another session's epoch.

## Behavior

1. **No placeholders.**  Disabled controls always carry a reason: not synced,
   missing session id, pending receipt, already archived/deleted, or restore
   not applicable.  There is no local optimistic rename/pin/delete.
2. **Action keyword boundary.**  A rendered action button passes its keyword
   (`:rename`, `:pin`, `:archive`, `:restore`, `:delete`, `:purge`) to
   `click!`, never the action map.  `click!` and `send!` dispatch on that
   keyword; an action map silently falls through to the title validator and
   sends nothing.
3. **Two destructive confirmations.**  Soft delete opens a confirmation with
   the 30-day recoverability text.  Permanent purge opens a separate
   `alertdialog` stating that the record is not recoverable and that workspace
   files are not deleted; only its second button sends `{:confirm? true}`.
   Temporary sessions are not shown in the deleted area.
4. **Archived sessions** remain in the normal list and show restore instead of
   archive.
5. **Deleted sessions** are absent from the server baseline.  The
   “已删除会话” disclosure sends `:session/list {:include-deleted? true}`; rows
   are read-only (not selectable) and offer restore plus permanent purge.
   Purge is available immediately for any soft-deleted row; the 30-day
   window is default retention, not a prerequisite for an explicit deletion.
   The server re-checks confirmation, deleted status and Owner quiescence
   before deleting.
6. **One metadata command at a time.**  A tracked `:created`/`:submitting`/
   `:sent` metadata command disables the other controls for that session; the
   receipt line uses `role="status"` and `aria-live="polite"`.  An
   already-open rename form or confirmation dialog disables its save/confirm
   button while a receipt is pending and rechecks that pending gate, so Enter
   cannot send a second command while the first receipt is pending.
7. **Accessibility.**  Buttons have accessible names that include the action
   and its disabled reason; rename input is labelled; delete confirmation uses
   `role="alertdialog"`; the deleted-session summary is a native disclosure.

## Integration

- `src/hc/client/workbench/views/session.cljs` mounts `SessionControls` for
  every visible session and for deleted rows; the conversation surface stays
  unchanged.
- `src/hc/client/workbench/commands.cljs` owns the additive
  `session-metadata!` and `session-list!` helpers.
- `src/hc/client/workbench/events.cljc` stores `:session/list` receipts and
  removes restored sessions from `:ui/session-list`.
- `src/hc/client/workbench/app.cljs` queries `:ui/session-list`;
  `db.cljs` initializes it to `[]`.

## Tests

JVM pure model:

```bash
clojure -M:dev -e "(require 'hc.client.sessions.controls-test) (clojure.test/run-tests 'hc.client.sessions.controls-test)"
```

DOM interaction (real component in jsdom):

```bash
./test/js/verify_session_controls_dom.sh
./test/js/verify_workbench_session_commands.sh
```

This mounts the actual `SessionControls` component and clicks the rendered
buttons.  It asserts `on-command` receives `{:kind ... :action ... :payload
...}` with a keyword `:action` for rename, pin, archive, restore, delete, and
purge; delete and purge send nothing on the first click and exactly one
command after the explicit confirmation; purge sends only `{:confirm? true}`
and never a workspace path; a failed send keeps the confirmation open; and a
pending receipt blocks an already-open rename form or confirmation dialog.
The second harness drives the real workbench command helper against a
headless Fulcro app: a deleted row that exists only in `:ui/session-list`
supplies its Owner epoch to `:session/purge`, while a missing session blocks
instead of borrowing the app epoch or another row's epoch.

Browser smoke (delegated to DevOps because this workspace forbids shell
execution in the current turn): open `#/sessions`, select a session, rename,
pin, archive, restore, delete with confirmation, then open “已删除会话” and
restore.  Verify each control shows `正在提交` then the server receipt, and
that title/pin/status only change after the server snapshot.  For a deleted
row, verify “永久删除” opens a second confirmation and that the row
disappears only after the accepted receipt.  Put a file in the workspace root
first and verify it still exists after the confirmed purge; the purge command
carries no workspace path.  Also verify a deleted row is not selectable and
temporary sessions do not appear in the deleted area.

## Limits

- `:session/list` returns normal deleted sessions only; temporary deleted
  sessions are intentionally absent.
- Permanent purge never deletes workspace files; it removes only the session
  record and cannot bypass the server's confirmation or Owner quiescence
  checks.  The 30-day window still governs default retention/cleanup.
- The controls are metadata-only; they do not start/stop runs or edit message
  history.
- The inline rename/confirm state is local React state and is discarded on
  unmount; it is not a second server truth.
