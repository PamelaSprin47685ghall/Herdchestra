#!/usr/bin/env bash
# P-BENCH2 wrapper: single session, 2000 mixed activities.
# Runs scripts/bench-2000-activity.mjs against a fresh root under /tmp.
#
#   ./scripts/bench-2000-activity.sh [--messages 2000] [--msg-chars 400]
#                                    [--keep-temp] [--skip-seed]
#                                    [--jvm-opts "..."]
#
# Evidence lands in plans/evidence/p-bench2/.
set -euo pipefail

ROOT="${HC_BENCH2_ROOT:-/tmp/hc-bench2/run}"
export HC_BENCH2_ROOT="$ROOT"

ARGS=(--root "$ROOT" --json "plans/evidence/p-bench2/bench-2000-$(date -u +%Y%m%dT%H%M%SZ).json")
for a in "$@"; do ARGS+=("$a"); done

exec node scripts/bench-2000-activity.mjs "${ARGS[@]}"
