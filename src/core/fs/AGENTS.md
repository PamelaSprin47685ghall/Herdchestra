// @herdchestra/core/fs — package specification.
//
// `FileMembers` performs the filesystem members of a JS program surface:
// `cat`, `stat`, `ls`, `write`, `mkdir`, `rm`, `mv`. All methods are async.
//
// - `new FileMembers({ root, guard, onWrite })`. `root` is the session root and
//   must be a non-empty path. `guard(path)` returns a refusal receipt or null
//   and runs before every access. `onWrite(path)` runs after a successful write
//   with the path the caller used.
// - Paths: an absolute path is used as is; a relative path joins `root`; an
//   empty path is the root. Access is decided by the OS; `guard` is the only
//   policy hook, and it sees the caller's path, not the resolved one.
// - Text is UTF-8. `cat` decodes strictly and reports `INVALID_UTF8` on bad
//   bytes instead of replacing them.
// - `stat` of a missing path is `{ exists: false }`; it is not an error.
// - `ls` is not recursive; directory entries end with `/` and are sorted.
// - `write` is atomic (spec §3.2): a temp file is created in the target
//   directory, written, flushed, closed, and renamed onto the target. The rename
//   stays inside one filesystem because the temp file shares the target's
//   directory. The target is replaced whether or not it existed; there is no
//   create-only mode. On any failure the temp file is removed and the target
//   keeps its old bytes. Concurrent writes are not locked; the last rename wins.
// - `atomicWriteFile(target, text)` is the same operation without a
//   `FileMembers`. The temp file is always opened with `wx`, once: exclusive
//   creation guards the temp name, so a stale temp file left by an earlier crash
//   is never reused. An `EEXIST` on the temp name fails the write with that
//   errno and leaves both the target and the pre-existing file untouched; the
//   call has no right to remove a file it did not create. Only after this call
//   created its temp file does a later failure remove it.
// - Failures are receipts `{ ok: false, code, path }` with a POSIX code. They
//   never throw.
