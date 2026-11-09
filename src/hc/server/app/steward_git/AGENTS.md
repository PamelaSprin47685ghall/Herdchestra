# `hc.server.app.steward-git`

Real Git evidence adapter for the Standing Sphinx Steward. It turns one
bounded `git log` observation into raw `:git-commit` evidence maps and never
throws.

## Why it reuses `fleet.git/run-command`

`hc.compose.fleet.git/GitPort` exposes only the fleet's narrow operations
(head, ancestor, rebase, merge). It has no generic log call. This adapter
therefore calls the public `hc.compose.fleet.git/run-command` with an explicit
argv and `:dir`; that function already provides:

- no shell, no string interpolation, no prompt;
- explicit working directory and `LC_ALL=C`;
- a bounded timeout with forced child destruction;
- concurrent stdout/stderr capture and structured status/exit data.

It does not create a second `ProcessBuilder` wrapper. The GitPort itself is
still the source of the repository path (`:repo`); `:repo` and `:run-command`
factory options exist for tests and alternate compositions.

## Public contract

```clojure
(git-log-since-fn git-port
  {:ref "master"          ; default "HEAD"
   :max-commits 50        ; default 50, clamped to 1..500
   :timeout-ms 30000      ; per Git command
   :repo "/repo"          ; optional override
   :run-command f})       ; optional `(fn [argv opts])` test seam
;; => (fn [{:keys [since ref now]}]
;;      => {:ok? true :head sha-or-nil :commits [raw-evidence]
;;          :master-version n :truncated? bool :scanned n}
;;      |  {:ok? false :error {:code kw :message string :details map}})
```

The per-call `:ref` overrides the factory default. `:since` is exclusive
(`git log <since>..<ref>`); nil or blank reads the newest bounded commits.
`:now` is accepted for the Steward runtime but is not used: the commit's own
`%ct` is authoritative.

The returned function never throws. The Steward's `fetch-git!` also catches
defensively, but this adapter relies only on explicit error maps.

## Git command

```
git -c core.quotePath=false log --no-merges --numstat --no-color --date=unix \
  --format="%H%x00%P%x00%T%x00%an%x00%ct%x00%s" \
  -n <max-commits+1> [<since>..<ref> | <ref>]
```

`-n` is always present, so even the `since` form is bounded. One extra sentinel
commit (`max-commits+1`) is requested so the reader can distinguish "exactly
at the limit" from "more commits exist" without a second count call.

`--no-color` keeps a user's `color.ui=always` configuration from injecting
ANSI escapes into the parser. `core.quotePath=false` keeps UTF-8 paths raw.
Git still C-quotes paths that
contain quotes, tabs, newlines, backslashes, or control bytes; the parser
decodes `\\`, `\"`, `\t`, `\n`, `\r`, `\a`, `\b`, `\f`, `\v`, and up to three
octal digits. A quoted path is recognized only when the whole numstat path is
wrapped in double quotes.

## Evidence keys

Each element of `:commits` is accepted verbatim by
`hc.core.steward-sphinx.evidence/normalize-evidence`:

| key | type | source |
|---|---|---|
| `:evidence-id` | `"git:<sha>"` | computed |
| `:kind` | `:git-commit` | literal |
| `:commit` | non-blank string | `%H` |
| `:parents` | vector of non-blank strings | `%P`, split on spaces; root is `[]` |
| `:changed-paths` | sorted distinct vector of non-blank strings | numstat paths, binary paths kept |
| `:subject` | string | `%s` |
| `:tree` | string | `%T` |
| `:author` | string | `%an` |
| `:committed-at` | numeric string (epoch seconds) | `%ct` |
| `:insertions` | non-negative integer | sum of numstat insertions; binary `-` counts 0 |
| `:deletions` | non-negative integer | sum of numstat deletions; binary `-` counts 0 |

`normalize-evidence` supplies `:source :git` and normalizes paths again; the
adapter includes the fields above so the raw map already carries the identity
and can be inspected without normalization.

Numstat lines attach to the preceding header. Unknown non-blank lines are
ignored so a future Git decoration cannot break an observation. Binary
numstat entries (`-\t-\t<path>`) keep the path and contribute `0/0`.

## Deterministic ordering

Commits are sorted by **commit time ascending, then sha ascending**. This
order is independent of `git log`'s newest-first output and of equal
timestamps. `:changed-paths` are sorted and de-duplicated.

## `:master-version`

`:master-version` is `git rev-list --count <ref>`, the total number of commits
reachable from the observed ref. It is **not** the number of commits in the
`since` window and **not** a truncated count. On an empty repository it is
`0`; the reader returns `:head nil`, `:commits []` before attempting the log.

## Truncation policy

1. Git reads at most `max-commits + 1`. If more than `max-commits` were
   parsed, the newest `max-commits` are kept (Git's newest-first order) and
   `:truncated? true`.
2. The kept commits are sorted (time ascending, sha ascending).
3. The serialized `:commits` vector is capped at **2 MiB UTF-8**. The
   accumulator charges brackets, one separator per commit, and each commit's
   `pr-str` byte size. Selection follows **sha ascending**; the selected
   prefix is then re-sorted into the contract order (commit time ascending,
   sha ascending). The rest is dropped and `:truncated? true`.
4. `:scanned` is the number of commits parsed from the bounded Git output
   before either truncation step (at most `max-commits + 1`).

Truncation is success, never an error. `:truncated? false` still appears on
non-truncated results so callers can use one shape.

## Empty repository and no-new-commits

- Empty repository (inside a worktree, ref resolution fails, and
  `git rev-list --all --count` is `0`):
  `{:ok? true :head nil :commits [] :master-version 0 :truncated? false :scanned 0}`.
- No new commits since `since`:
  `{:ok? true :head <resolved-ref> :commits [] :master-version <count> ...}`.

The Steward advances its cursor to `:head`; `:head` is the resolved observed
ref, not a fabricated or stale head.

## Errors

All failures return `{:ok? false :error {:code kw :message string :details map}}`:

| code | cause |
|---|---|
| `:STEWARD_GIT_UNAVAILABLE` | `git` executable missing/unavailable, path is not a worktree, or the runner cannot start Git |
| `:STEWARD_GIT_TIMEOUT` | any bounded Git command timed out |
| `:STEWARD_GIT_FAILED` | invalid ref, unreadable commit count, malformed output, runner exception, or any other failed Git command |

`:details` carries at least `:operation`, `:repo`, and (when present)
`:status`, `:exit`, `:stderr`, `:stdout`, each bounded. Stdout/stderr are
truncated in the error map so a failing command cannot blow the observation
budget.

## 0.0.3 契约完备性补注

### 不变量

保证：所有 Git 调用走显式 argv、无 shell、`LC_ALL=C`、有界超时；返回函数
永不抛出；commit 顺序稳定（时间升序 + sha 升序）；`:changed-paths` 排序
去重；超限只截断并标 `:truncated? true`，不静默丢字段；空仓库与无新 commit
返回同一成功形状。

不保证：不保证 Git 仓库在 `rev-list` 与 `log` 之间不变化（两次调用非原子）；
不保证任意 Git `core.quotePath` 引号形式都能还原（只解码文档列出的转义）；
不保证 commit 时间可信（使用 `%ct` 原值）；不保证 `:master-version` 与
窗口内 commit 数一致；不执行 fetch/rebase/merge；不拥有 GitPort 生命周期。

### 验证

```bash
clojure -M:test:server -n hc.server.app.steward-git-test
```

代码事实，未复测：9 tests / 71 个 `is`。
