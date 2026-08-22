# Docker Image Push Script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 提供一个由用户手动执行的最简 Docker Hub 上传脚本，推送已经构建好的 bitpongo-api 两个镜像标签。

**Architecture:** 脚本只按顺序运行两条 `docker push` 命令，并在任意命令失败时立即退出。它不负责登录、构建、重新打标签、修改代理或保存凭据。

**Tech Stack:** Bash、Docker CLI

## Global Constraints

- 只修改 `/Volumes/ExternalDrive/Code/github/bitpongo-api`。
- 不执行实际镜像上传；由用户手动运行脚本。
- 固定推送 `docker.io/corbettzhang/bitpongoapi:3a493c7` 和 `docker.io/corbettzhang/bitpongoapi:latest`。
- 不读取、输出或保存 Docker Hub 凭据。

---

### Task 1: Add and verify the manual push script

**Files:**
- Create: `scripts/push-bitpongoapi.sh`
- Create: `test/scripts/push-bitpongoapi-script-test.sh`

- [x] **Step 1: Write the failing contract test**

```bash
#!/usr/bin/env bash
set -euo pipefail

script_path="${1:-scripts/push-bitpongoapi.sh}"

test -x "$script_path"
bash -n "$script_path"
grep -Fxq 'docker push docker.io/corbettzhang/bitpongoapi:3a493c7' "$script_path"
grep -Fxq 'docker push docker.io/corbettzhang/bitpongoapi:latest' "$script_path"
test "$(grep -c '^docker push ' "$script_path")" -eq 2
! grep -Eq 'docker login|credential|proxy|docker build|docker tag' "$script_path"
```

- [x] **Step 2: Run the test and confirm it fails because the script does not exist**

Run: `bash test/scripts/push-bitpongoapi-script-test.sh`

Expected: FAIL at `test -x` because `scripts/push-bitpongoapi.sh` has not been created.

- [x] **Step 3: Implement the minimal push script**

```bash
#!/usr/bin/env bash
set -euo pipefail

docker push docker.io/corbettzhang/bitpongoapi:3a493c7
docker push docker.io/corbettzhang/bitpongoapi:latest
```

Run: `chmod +x scripts/push-bitpongoapi.sh test/scripts/push-bitpongoapi-script-test.sh`

- [x] **Step 4: Run the contract test and syntax check**

Run: `bash test/scripts/push-bitpongoapi-script-test.sh`

Expected: PASS with exit code 0 and no Docker upload performed.

Run: `bash -n scripts/push-bitpongoapi.sh`

Expected: PASS with exit code 0.

- [x] **Step 5: Review scope and commit**

Run: `git diff --check && git status --short`

Expected: only the plan, contract test, and push script are added or modified.

Run: `git add docs/superpowers/plans/2026-08-22-docker-image-push-script.md scripts/push-bitpongoapi.sh test/scripts/push-bitpongoapi-script-test.sh && git commit -m "feat: add Docker image push script"`

- [x] **Step 6: Push the source commit and verify the remote branch**

Run: `git push origin main`

Run: `git ls-remote origin refs/heads/main`

Expected: remote `main` points to the new local commit.

## Self-review

- [x] The script pushes exactly the two approved bitpongo-api tags.
- [x] The script does not log in, build, retag, change proxy settings, or handle credentials.
- [x] Verification does not execute `docker push`.
- [x] No `zhitoubao` repository or directory is modified.
