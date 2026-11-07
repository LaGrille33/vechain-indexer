#!/bin/sh
# Incremental update of Jerome's installed pilot. Run from a separate extraction.
# Preserves compose.yaml, credentials, data and SSH settings. Starts no data job.
set -eu
# DSM non-interactive SSH/sudo sessions omit the Container Manager CLI paths.
# Use only fixed system/package directories, not the caller's inherited PATH.
PATH=/usr/local/bin:/var/packages/ContainerManager/target/usr/bin:/var/packages/Docker/target/usr/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
[ "$(id -u)" -eq 0 ] || { printf '%s\n' 'Run with sudo.' >&2; exit 1; }
command -v docker >/dev/null || { printf '%s\n' 'Docker CLI not found in DSM system/package paths; run command -v docker in the NAS interactive terminal.' >&2; exit 1; }
printf 'Using Docker CLI: %s\n' "$(command -v docker)"
source_root=$(CDPATH= cd "$(dirname "$0")/.." && pwd -P)
target=/volume1/docker/vebetter-scoring-pilot
[ "$source_root" != "$target" ] || { printf '%s\n' 'Extract this update separately; do not overwrite the installed project.' >&2; exit 1; }
[ -f "$target/.nas-install-prepared" ] && [ -s "$target/config/database_password" ] && [ -f "$target/data/postgres/PG_VERSION" ] || {
  printf '%s\n' 'Expected installed pilot not found; no changes made.' >&2; exit 1;
}
cd "$source_root"
command -v sha256sum >/dev/null
sha256sum -c bundle-sha256.txt >/dev/null
[ -z "$(find src sql scripts -type l -print)" ] || { printf '%s\n' 'Unexpected update symlink.' >&2; exit 1; }
cd "$target"
docker compose config --quiet
for service in db engine; do
  container=$(docker compose ps -q "$service")
  [ -n "$container" ] && [ "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$container")" = "$target" ] || {
    printf '%s\n' 'Unexpected Compose project; stop and inspect.' >&2; exit 1;
  }
done
active=$(docker compose exec -T db psql -Atq -v ON_ERROR_STOP=1 -U scoring_owner -d vebetter_scoring -c "SELECT count(*) FROM reputation_pilot.backfill_job WHERE status IN ('pending','running')")
[ "$active" = 0 ] || { printf '%s\n' 'A pending/running job exists. Stop and inspect before updating.' >&2; exit 1; }
old_ip=$(docker inspect --format '{{(index .NetworkSettings.Networks "vebetter-scoring-pilot_outbound").IPAddress}}' "$(docker compose ps -q engine)")
stamp=$(date -u '+%Y%m%dT%H%M%SZ')
backup="$target/backups/update-$stamp"
umask 077
mkdir -p "$backup"
printf 'Backing up code and database to %s; the database dump can take several minutes.\n' "$backup"
# Code archive contains no config/password files or data.
tar -czf "$backup/code-before.tar.gz" src sql scripts package.json package-lock.json Dockerfile source-manifest.json SETUP.txt
docker compose exec -T db pg_dump -U scoring_owner -d vebetter_scoring -Fc > "$backup/database.dump.partial"
mv "$backup/database.dump.partial" "$backup/database.dump"
printf '%s\n' 'Database backup complete. Installing updated source.'
for file in package.json package-lock.json Dockerfile source-manifest.json SETUP.txt; do
  cp "$source_root/$file" "$target/$file"
  chmod 644 "$target/$file"
done
for directory in src sql scripts; do
  cp -R "$source_root/$directory/." "$target/$directory/"
  find "$target/$directory" -type d -exec chmod 755 {} \;
  find "$target/$directory" -type f -exec chmod 644 {} \;
done
# Build first. A build failure leaves the currently running engine untouched.
printf '%s\n' 'Building the updated engine.'
docker compose build engine
migrated=$(docker compose exec -T db psql -Atq -v ON_ERROR_STOP=1 -U scoring_owner -d vebetter_scoring -c "SELECT to_regclass('reputation_pilot.chain_round_cache') IS NOT NULL")
case "$migrated" in
  f) docker compose exec -T db psql -v ON_ERROR_STOP=1 -U scoring_owner -d vebetter_scoring < sql/004-chain-source.sql ;;
  t) printf '%s\n' 'Source migration already present; preserving its records.' ;;
  *) printf '%s\n' 'Could not determine migration state; engine not replaced.' >&2; exit 1 ;;
esac
docker compose up -d --no-deps engine
engine_id=$(docker compose ps -q engine)
attempt=0
while [ "$(docker inspect --format '{{.State.Health.Status}}' "$engine_id")" != healthy ]; do
  attempt=$((attempt+1))
  [ "$attempt" -lt 40 ] || { printf 'Engine health timeout. Preserve data. Backup: %s\n' "$backup" >&2; exit 1; }
  sleep 3
done
new_ip=$(docker inspect --format '{{(index .NetworkSettings.Networks "vebetter-scoring-pilot_outbound").IPAddress}}' "$engine_id")
printf '\nUpdate complete. No backfill started. Backup: %s\n' "$backup"
printf 'Dashboard bridge address: %s:8788 (previous %s:8788)\n' "$new_ip" "$old_ip"
if [ "$new_ip" != "$old_ip" ]; then
  printf '%s\n' 'Bridge IP changed. The scoped SSH PermitOpen rule and Mac tunnel must use the new address before browser access works.'
else
  printf '%s\n' 'The existing SSH tunnel target is unchanged. Refresh /progress.'
fi
docker compose ps
