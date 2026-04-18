#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/.run"

stop_by_pid_file() {
  local service_name="$1"
  local pid_file="$PID_DIR/$service_name.pid"

  if [[ ! -f "$pid_file" ]]; then
    echo "[SKIP] $service_name pid file not found"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  if ps -p "$pid" >/dev/null 2>&1; then
    echo "[STOP] $service_name pid=$pid"
    kill "$pid" || true
  else
    echo "[SKIP] $service_name process not running"
  fi

  rm -f "$pid_file"
}

stop_by_pid_file "gateway-service"
stop_by_pid_file "notification-service"
stop_by_pid_file "workorder-service"
stop_by_pid_file "admin-service"
stop_by_pid_file "alarm-service"
stop_by_pid_file "device-profile-service"

# docker compose -f "$ROOT_DIR/docker-compose.yml" down

echo "[DONE] All services and middleware stopped"
