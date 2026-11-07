#!/bin/sh
# Run with sudo after extracting into a NEW isolated project directory.
# Never print the generated local DB password or touch other Compose projects.
set -eu
ssh_port=${1:-2222}
case "$ssh_port" in ''|*[!0-9]*) printf '%s\n' 'Invalid SSH port.' >&2; exit 1;; esac
[ "$ssh_port" -ge 1 ] && [ "$ssh_port" -le 65535 ] || exit 1
if [ "$(id -u)" -ne 0 ]; then
  printf '%s\n' 'Run this installer with sudo.' >&2
  exit 1
fi
root=$(CDPATH= cd "$(dirname "$0")/.." && pwd -P)
case "$root" in
  /volume*/docker/vebetter-scoring-pilot) ;;
  *) printf '%s\n' 'Unexpected project directory; stop and inspect before installing.' >&2; exit 1 ;;
esac
cd "$root"
command -v docker >/dev/null
command -v openssl >/dev/null
docker compose version
operator=${SUDO_USER:-jerome_admin}
operator_uid=$(id -u "$operator")
existing=$(docker ps -aq --filter label=com.docker.compose.project=vebetter-scoring-pilot)
if [ -n "$existing" ] && [ ! -f .nas-install-prepared ]; then
  printf '%s\n' 'An existing Compose project has this name. Refusing to adopt/change it.' >&2
  exit 1
fi
for container in $existing; do
  previous_root=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$container")
  if [ "$previous_root" != "$root" ]; then
    printf '%s\n' 'A container belongs to a different project directory. Refusing to change it.' >&2
    exit 1
  fi
done

if [ -e data/postgres/PG_VERSION ] && [ ! -s config/database_password ]; then
  printf '%s\n' 'Existing database has no password file. Refusing to generate a replacement.' >&2
  exit 1
fi
mkdir -p config data/postgres data/imports data/exports data/logs backups
chmod 700 config backups
if [ ! -s config/database_password ]; then
  # Noclobber refuses to replace even an empty existing file. Secret stays on NAS.
  (umask 077; set -C; openssl rand -hex 32 > config/database_password)
fi
chmod 444 config/database_password
chown "$operator_uid:1000" data/imports
chmod 2770 data/imports
chown 1000:1000 data/exports data/logs
chmod 750 data/exports data/logs
touch .nas-install-prepared
chmod 600 .nas-install-prepared
docker compose config --quiet
docker compose up -d --build

engine_id=$(docker compose ps -q engine)
db_id=$(docker compose ps -q db)
attempt=0
while :; do
  engine_health=$(docker inspect --format '{{.State.Health.Status}}' "$engine_id")
  db_health=$(docker inspect --format '{{.State.Health.Status}}' "$db_id")
  if [ "$engine_health" = healthy ] && [ "$db_health" = healthy ]; then break; fi
  attempt=$((attempt+1))
  if [ "$attempt" -ge 60 ]; then
    docker compose ps
    printf '%s\n' 'Health checks did not finish. Preserve data/password and inspect this project.' >&2
    exit 1
  fi
  sleep 3
done
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U scoring_owner -d vebetter_scoring -c \
  "BEGIN READ ONLY; SELECT to_regclass('reputation_pilot.round_snapshot') AS snapshots, to_regclass('reputation_pilot.backfill_job') AS progress; ROLLBACK;"
docker compose ps
engine_ip=$(docker inspect --format '{{(index .NetworkSettings.Networks "vebetter-scoring-pilot_outbound").IPAddress}}' "$engine_id")
case "$engine_ip" in ''|*[!0-9.]*) printf '%s\n' 'Could not determine the private dashboard address.' >&2; exit 1;; esac
printf '\n%s\n' 'Services ready. No production data fetched or backfill started.'
printf '%s\n' 'In a NEW Mac terminal, run this tunnel and leave it open:'
printf 'ssh -p %s -N -L 8788:%s:8788 %s@192.168.1.141\n' "$ssh_port" "$engine_ip" "$operator"
printf '%s\n' 'Then open http://localhost:8788/progress (job status) or http://localhost:8788/ (saved scores).'
