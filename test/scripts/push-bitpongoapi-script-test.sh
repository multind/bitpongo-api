#!/usr/bin/env bash
set -euo pipefail

script_path="${1:-scripts/push-bitpongoapi.sh}"

test -x "$script_path"
bash -n "$script_path"
grep -Fxq 'docker push docker.io/corbettzhang/bitpongoapi:3a493c7' "$script_path"
grep -Fxq 'docker push docker.io/corbettzhang/bitpongoapi:latest' "$script_path"
test "$(grep -c '^docker push ' "$script_path")" -eq 2
! grep -Eq 'docker login|credential|proxy|docker build|docker tag' "$script_path"
