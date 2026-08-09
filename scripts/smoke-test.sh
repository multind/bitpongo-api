#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://localhost:8000}"

curl -fsS "${base_url}/health" | grep -q '"status":"ok"'
curl -fsS "${base_url}/" | grep -q 'Welcome to the API'

status="$(curl -sS -o /dev/null -w '%{http_code}' "${base_url}/api/plans/list/active")"
test "${status}" = "401"

ws_url="${base_url}/api/ws/price"
ws_headers="$(curl --http1.1 -sS -i --max-time 2 \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "${ws_url}" || true)"
grep -q '101' <<<"${ws_headers}"

echo "冒烟测试通过: ${base_url}"
