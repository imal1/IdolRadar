#!/usr/bin/env bash
set -Eeuo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="${BASE_DIR}/app.jar"
PID_FILE="${BASE_DIR}/app.pid"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "IdolRadar 未运行。"
  exit 0
fi

APP_PID="$(<"${PID_FILE}")"
if [[ ! "${APP_PID}" =~ ^[0-9]+$ ]]; then
  echo "PID 文件无效：${PID_FILE}。"
  exit 1
fi

if ! kill -0 "${APP_PID}" 2>/dev/null; then
  rm -f "${PID_FILE}"
  echo "IdolRadar 已停止，已清理过期 PID 文件。"
  exit 0
fi

# 防止 PID 被系统复用后误杀其他进程。
COMMAND_LINE="$(tr '\0' ' ' <"/proc/${APP_PID}/cmdline" 2>/dev/null || true)"
if [[ "${COMMAND_LINE}" != *"${JAR_FILE}"* ]]; then
  echo "PID=${APP_PID} 不是 IdolRadar 进程，拒绝停止。"
  exit 1
fi

echo "停止 IdolRadar，PID=${APP_PID}..."
kill -TERM "${APP_PID}"

# Spring 最多使用 20 秒优雅关闭；额外预留 10 秒后才强制终止。
for ((ATTEMPT = 1; ATTEMPT <= 30; ATTEMPT++)); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    rm -f "${PID_FILE}"
    echo "IdolRadar 已停止。"
    exit 0
  fi
  sleep 1
done

echo "优雅停止超时，强制终止 PID=${APP_PID}。"
kill -KILL "${APP_PID}"
rm -f "${PID_FILE}"
