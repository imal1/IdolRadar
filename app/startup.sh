#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${BASE_DIR}/.." && pwd)"
JAR_FILE="${BASE_DIR}/app.jar"
ENV_FILE="${DEPLOY_DIR}/.env"
PID_FILE="${BASE_DIR}/app.pid"
LOG_DIR="${BASE_DIR}/logs"
LOG_FILE="${LOG_DIR}/app.log"

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi

if [[ -z "${JAVA_BIN}" ]]; then
  echo "未找到 Java，请先安装 OpenJDK 21。"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "未找到 curl，无法执行启动健康检查。"
  exit 1
fi

JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | awk -F '[".]' '/version/ {print $2; exit}')"
if [[ ! "${JAVA_MAJOR}" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 21 )); then
  echo "当前 Java 版本为 ${JAVA_MAJOR:-未知}，IdolRadar 需要 Java 21 或更高版本。"
  exit 1
fi

if [[ ! -f "${JAR_FILE}" ]]; then
  echo "缺少 ${JAR_FILE}。"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请从 .env.example 复制并填写。"
  exit 1
fi

if [[ -f "${PID_FILE}" ]]; then
  EXISTING_PID="$(<"${PID_FILE}")"
  if [[ "${EXISTING_PID}" =~ ^[0-9]+$ ]] && kill -0 "${EXISTING_PID}" 2>/dev/null; then
    # 部署者只需执行 startup.sh；复用 shutdown.sh 保持防误杀和优雅停止规则一致。
    echo "IdolRadar 已运行，先执行优雅停止，PID=${EXISTING_PID}。"
    "${BASE_DIR}/shutdown.sh"
  else
    rm -f "${PID_FILE}"
  fi
fi

# .env 由 root 管理且权限为 600；导出后供 Spring 配置占位符读取。
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

for REQUIRED_KEY in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD REDIS_PASSWORD WECHAT_APP_ID WECHAT_APP_SECRET; do
  if [[ -z "${!REQUIRED_KEY:-}" ]]; then
    echo "${REQUIRED_KEY} 未配置。"
    exit 1
  fi
done

JAVA_ARGS=(
  "-Xms${JAVA_XMS:-256m}"
  "-Xmx${JAVA_XMX:-768m}"
  "-XX:+ExitOnOutOfMemoryError"
)
SPRING_ARGS=(
  "--spring.profiles.active=prod"
  "--spring.config.additional-location=file:${BASE_DIR}/conf/"
)

mkdir -p "${LOG_DIR}"
cd "${BASE_DIR}"

# API 不负责 DDL；每次启动前先执行幂等 migration，失败时禁止启动旧结构上的新代码。
echo "执行数据库 migration..."
APP_MODE=migrate SPRING_FLYWAY_ENABLED=true \
  "${JAVA_BIN}" "${JAVA_ARGS[@]}" -jar "${JAR_FILE}" "${SPRING_ARGS[@]}"

echo "启动 IdolRadar..."
APP_MODE=api SPRING_FLYWAY_ENABLED=false \
  nohup "${JAVA_BIN}" "${JAVA_ARGS[@]}" -jar "${JAR_FILE}" "${SPRING_ARGS[@]}" \
  >>"${LOG_FILE}" 2>&1 &
APP_PID=$!
printf '%s\n' "${APP_PID}" >"${PID_FILE}"

# 启动成功必须同时满足进程存活和数据库、Redis 就绪。
for ((ATTEMPT = 1; ATTEMPT <= 30; ATTEMPT++)); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    echo "IdolRadar 启动失败，最后日志："
    tail -n 50 "${LOG_FILE}" || true
    rm -f "${PID_FILE}"
    exit 1
  fi
  if curl -fsS "http://127.0.0.1:${APP_PORT:-8080}/readyz" >/dev/null 2>&1; then
    echo "IdolRadar 启动成功，PID=${APP_PID}。"
    exit 0
  fi
  sleep 1
done

echo "IdolRadar 启动超时，请检查 ${LOG_FILE}。"
kill -TERM "${APP_PID}" 2>/dev/null || true
rm -f "${PID_FILE}"
tail -n 50 "${LOG_FILE}" || true
exit 1
