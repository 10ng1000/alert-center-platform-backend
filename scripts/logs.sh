#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"

mkdir -p "$LOG_DIR"

if [[ $# -eq 0 ]]; then
  tail -f "$LOG_DIR"/*.log
  exit 0
fi

for svc in "$@"; do
  if [[ -f "$LOG_DIR/$svc.log" ]]; then
    tail -n 200 -f "$LOG_DIR/$svc.log"
  else
    echo "[WARN] log file not found: $LOG_DIR/$svc.log"
  fi
done
