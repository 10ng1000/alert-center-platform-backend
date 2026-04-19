#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
PID_DIR="$ROOT_DIR/.run"

mkdir -p "$LOG_DIR" "$PID_DIR"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[ERR] Missing command: $cmd"
    exit 1
  fi
}

require_cmd docker
require_cmd mvn
require_cmd java
require_cmd curl
require_cmd nc

compose_cmd() {
  docker compose -f "$ROOT_DIR/docker-compose.yml" "$@"
}

wait_for_port() {
  local host="$1"
  local port="$2"
  local name="$3"
  local retries="${4:-120}"
  local pid="${5:-}"
  local log_file="${6:-}"

  echo "[WAIT] $name ($host:$port)"
  for ((i=1; i<=retries; i++)); do
    if nc -z "$host" "$port" >/dev/null 2>&1; then
      echo "[OK] $name is ready"
      return 0
    fi
    if [[ -n "$pid" ]] && ! ps -p "$pid" >/dev/null 2>&1; then
      echo "[ERR] $name process exited before port $port was ready"
      if [[ -n "$log_file" && -f "$log_file" ]]; then
        echo "[INFO] Last 60 lines of $log_file"
        tail -n 60 "$log_file" || true
      fi
      return 1
    fi
    sleep 1
  done

  echo "[ERR] $name failed to start in time"
  if [[ -n "$log_file" && -f "$log_file" ]]; then
    echo "[INFO] Last 60 lines of $log_file"
    tail -n 60 "$log_file" || true
  fi
  return 1
}

wait_for_http() {
  local url="$1"
  local name="$2"
  local retries="${3:-120}"

  echo "[WAIT] $name ($url)"
  for ((i=1; i<=retries; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "[OK] $name is ready"
      return 0
    fi
    sleep 1
  done

  echo "[ERR] $name failed to start in time"
  return 1
}

ensure_port_free() {
  local host="$1"
  local port="$2"
  local name="$3"

  if nc -z "$host" "$port" >/dev/null 2>&1; then
    echo "[ERR] $name port $port is already in use on $host"
    echo "[HINT] Stop the conflicting process or adjust Dubbo QoS port mapping before retrying"
    return 1
  fi

  return 0
}

is_port_in_use() {
  local host="$1"
  local port="$2"
  nc -z "$host" "$port" >/dev/null 2>&1
}

start_java_service() {
  local module="$1"
  local service_name="$2"
  local port="$3"
  local qos_port="${4:-}"

  if is_port_in_use 127.0.0.1 "$port"; then
    echo "[SKIP] $service_name port $port already in use, skip startup"
    return 0
  fi

  if [[ -n "$qos_port" ]] && is_port_in_use 127.0.0.1 "$qos_port"; then
    echo "[SKIP] $service_name qos port $qos_port already in use, skip startup"
    return 0
  fi

  local jar_path
  jar_path="$(find "$ROOT_DIR/$module/target" -maxdepth 1 -type f -name "*.jar" | grep -v "original" | head -n 1)"
  if [[ -z "$jar_path" ]]; then
    echo "[ERR] JAR not found for $service_name in $module/target"
    return 1
  fi

  local pid_file="$PID_DIR/$service_name.pid"
  if [[ -f "$pid_file" ]]; then
    local old_pid
    old_pid="$(cat "$pid_file")"
    if ps -p "$old_pid" >/dev/null 2>&1; then
      echo "[INFO] Stopping stale process $service_name($old_pid)"
      kill "$old_pid" || true
      sleep 1
    fi
  fi

  echo "[START] $service_name"
  local log_file="$LOG_DIR/$service_name.log"
  nohup java -jar "$jar_path" >"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" >"$pid_file"

  wait_for_port 127.0.0.1 "$port" "$service_name" 120 "$pid" "$log_file"
  echo "[OK] $service_name pid=$pid"
}

echo "[STEP] Starting middleware with docker compose"
# compose_cmd up -d

wait_for_port 127.0.0.1 3306 mysql
wait_for_port 127.0.0.1 6379 redis
wait_for_http "http://127.0.0.1:8848/nacos" nacos
wait_for_port 127.0.0.1 9876 rocketmq-namesrv

# Ensure business DB schema exists.
echo "[STEP] Initializing database schema"
docker exec -i alert-mysql mysql -uroot -proot < "$ROOT_DIR/sql/init.sql"

echo "[STEP] Building all modules"
cd "$ROOT_DIR"
mvn -DskipTests package

echo "[STEP] Building agent-service"
mvn -DskipTests -f "$ROOT_DIR/agent-service/pom.xml" package

start_java_service "admin-service" "admin-service" "8085" "22225"
start_java_service "workorder-service" "workorder-service" "8082"
start_java_service "alarm-service" "alarm-service" "8081" "22223"
start_java_service "notification-service" "notification-service" "8083"
start_java_service "agent-service" "agent-service" "9900"
start_java_service "gateway-service" "gateway-service" "8080"

echo "[DONE] Full stack started successfully"
echo "[INFO] Logs: $LOG_DIR"
