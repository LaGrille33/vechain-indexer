#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p backups
stamp=$(date -u '+%Y%m%dT%H%M%SZ')
target="backups/vebetter-scoring-${stamp}.dump"
docker compose exec -T db pg_dump -U scoring_owner -d vebetter_scoring -Fc > "${target}.partial"
mv "${target}.partial" "$target"
printf '%s\n' "$target"
