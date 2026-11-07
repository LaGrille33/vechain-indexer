#!/bin/sh
# Run by Jerome in a LOCAL MAC Terminal. SSH/SCP handle authentication privately.
# No password, key or connection secret is read, stored or printed by this script.
set -eu
root=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
archive=${VEBETTER_SCORING_ARCHIVE:-"$root/dist/vebetter-scoring-pilot.tar.gz"}
[ -s "$archive" ] || { printf '%s\n' 'Update archive not found beside this script.' >&2; exit 1; }
nas=jerome_admin@192.168.1.141
printf '%s\n' 'Uploading the prepared timeout fix. Enter authentication only in your Terminal.'
scp -O -P 2222 "$archive" "$nas:vebetter-scoring-update.tar.gz"
ssh -t -p 2222 "$nas" 'set -eu
PATH=/usr/local/bin:/var/packages/ContainerManager/target/usr/bin:/var/packages/Docker/target/usr/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
docker_cli=$(command -v docker) || { printf "%s\n" "Docker CLI not found in DSM system/package paths." >&2; exit 1; }
stage=$(mktemp -d "$HOME/vebetter-scoring-update.XXXXXX")
tar -xzf "$HOME/vebetter-scoring-update.tar.gz" -C "$stage"
sudo sh "$stage/vebetter-scoring-pilot/scripts/update-nas.sh"
sudo "$docker_cli" compose -f /volume1/docker/vebetter-scoring-pilot/compose.yaml exec -d engine sh -c "node src/job.mjs chain mainnet 1 latest 0433a319-dc73-44ce-873f-54557f6b0338 >> /logs/launcher.jsonl 2>&1"
printf "\nResume command submitted for the existing job; no new job was requested.\n"
printf "%s\n" "Check http://localhost:8788/progress through your Mac tunnel."
'
