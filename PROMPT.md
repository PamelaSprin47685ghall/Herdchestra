# Herdchestra — implement AGENTS.md (omp-vibe director)

Repo: `/home/kunweiz/Desktop/vibe/Herdchestra`. Root `AGENTS.md` is highest authority — do **not** edit it. You may and must sync package AGENTS + source to match it without asking.

## Goal
Build the independent Herdchestra system per AGENTS.md §8.1 layout and §8.4 L29 five-phase order. Start with scaffolding + **阶段一（控制内核）**, then continue through later phases as capacity allows.

## How to work
- You are the **vibe director**. Spawn workers with vibe tools (`vibe_spawn` / send / wait / kill / list) — do **not** hand-launch second omp/tmux sessions.
- Fast workers → `smol` role; thorough → `task` role (already `kw92.cyou/lazy/{default,task}`).
- Always `--approval-mode yolo` / Nudge-friendly: keep moving; only stop at legitimate exits.
- Prefer high concurrency on this 12-core / 62G host.

## Success criteria
1. Skeleton: `HACK.md`, `pixi.toml`, `deps.edn`, `plans/`, `src/AGENTS.md`, `src/hc/{core,compose,server,client}/…` with package AGENTS stubs.
2. Phase 1 under `src/hc/core/swarm_stm/`: short STM for tasks/agents/claim/deps/responsibility; file ops as stubs; 2–3 scripted agents prove concurrent split/claim, parent `:waiting`→`:ready` (never auto `:done`), sleep/wake races.
3. Parallelize independent cores via vibe workers: `ufss` (§6.3 bijection), `workspace_gate` (§4.2 stamp/W/dry-run prep), `scope`, `pair_hint`, `relay`, etc.
4. `clojure.test.check` where claimed; label NOT RUN honestly for V0x/Fxx/Txx.
5. Write `/home/kunweiz/work//RESULT.md` with first line `VERDICT: DONE|PARTIAL|BLOCKED` plus evidence (paths, test cmds, commit SHAs).
6. Local commits OK; do not push unless asked. No GPU.

Start by reading AGENTS.md §1.1, §3, §8.1, §8.4; then implement and spawn workers.
