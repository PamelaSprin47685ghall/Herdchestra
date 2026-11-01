// @herdchestra/core/command — package specification.
//
// A command runner is `async (command, { cwd, env, shell, signal, onData }) =>
// { stdout, stderr, exitCode }`.
//
// - `stdout`/`stderr` are UTF-8 text. `exitCode` is a number; a killed command
//   or a failed spawn reports a non-zero code and appends the failure to
//   `stderr`.
// - `onData({ stream, chunk })` is called for every chunk while the command
//   runs, so a program view can show live output.
// - `signal` (an `AbortSignal`) kills the process with `SIGKILL`; a signal that
//   is already aborted kills it before any output.
//
// `nodeCommandRunner` is the default implementation (node only).
// `fakeCommandRunner(respond)` returns a runner plus `runner.calls`, where
// `respond(command, index)` returns `{ stdout, stderr, exitCode }`; the fake
// replays both streams through `onData` so a view sees the same events.
//
// This package never interprets a command and never retries it. Draining before
// a command belongs to the caller (`program.drainedPromise()`).
