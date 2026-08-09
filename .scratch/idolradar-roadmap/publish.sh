#!/usr/bin/env bash
# 将 issues/ 下的 ticket 按依赖顺序发布到 GitHub Issues。
# ticket 正文里的 "NN — 标题" 引用会被替换成真实 issue 编号。
set -euo pipefail
cd "$(dirname "$0")"

for label in needs-triage needs-info ready-for-agent ready-for-human wontfix; do
  gh label create "$label" 2>/dev/null || true
done

# ticket 序号 -> issue 编号。用索引数组而非关联数组，兼容 macOS 自带的 bash 3.2。
num=()

for file in issues/*.md; do
  id="${file##*/}"; id="${id%%-*}"
  title="$(head -1 "$file" | sed 's/^# [0-9]* — //')"
  body="$(tail -n +2 "$file")"
  # 把 "Blocked by: 07 — ..." 里的 ticket 序号替换为已发布的 issue 编号
  for k in "${!num[@]}"; do
    body="${body//$(printf '%02d' "$k") — /#${num[$k]} }"
  done
  url="$(gh issue create --title "$title" --body "$body" --label ready-for-agent)"
  num[$((10#$id))]="${url##*/}"
  echo "$id -> ${num[$((10#$id))]}  $title"
done
