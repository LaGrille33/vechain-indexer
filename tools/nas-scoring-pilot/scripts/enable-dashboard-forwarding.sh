#!/bin/sh
# Operator-run, narrowly scoped SSH configuration change. No credentials printed.
set -eu
[ "$(id -u)" -eq 0 ] || { printf '%s\n' 'Run with sudo.' >&2; exit 1; }
config=/etc/ssh/sshd_config
operator=jerome_admin
sshd=
for candidate in /usr/sbin/sshd /usr/bin/sshd /bin/sshd /sbin/sshd; do
  if [ -x "$candidate" ]; then sshd=$candidate; break; fi
done
[ -n "$sshd" ] || { printf '%s\n' 'sshd not found; no changes made.' >&2; exit 1; }
connection=${1:-}
tunnel_port=${2:-2222}
case "$tunnel_port" in ''|*[!0-9]*) printf '%s\n' 'Invalid SSH port.' >&2; exit 1;; esac
[ "$tunnel_port" -ge 1 ] && [ "$tunnel_port" -le 65535 ] || exit 1
set -- $connection
[ "$#" -eq 4 ] || { printf '%s\n' 'Run from the NAS SSH session and pass "$SSH_CONNECTION".' >&2; exit 1; }
client=$1
server=$3
server_port=$tunnel_port
context="user=$operator,addr=$client,host=$client,laddr=$server,lport=$server_port"
[ -f "$config" ] && [ ! -L "$config" ] || { printf '%s\n' 'Unexpected SSH configuration path; inspect before proceeding.' >&2; exit 1; }
if grep -Fq '# BEGIN VEBETTER DASHBOARD FORWARDING' "$config"; then
  printf '%s\n' 'The dashboard rule already exists; inspect it instead of appending again.' >&2
  exit 1
fi
"$sshd" -t -f "$config"
engine_ip=$(docker inspect --format '{{(index .NetworkSettings.Networks "vebetter-scoring-pilot_outbound").IPAddress}}' vebetter-scoring-pilot-engine-1)
case "$engine_ip" in ''|*[!0-9.]*) printf '%s\n' 'No valid private engine IP; no changes made.' >&2; exit 1;; esac
pidfile=$("$sshd" -T -f "$config" | awk '$1=="pidfile" {print $2}')
[ -n "$pidfile" ] && [ -r "$pidfile" ] || { printf '%s\n' 'SSH master PID file unavailable; no changes made.' >&2; exit 1; }
master=$(cat "$pidfile")
case "$master" in ''|*[!0-9]*) printf '%s\n' 'Invalid SSH master PID; no changes made.' >&2; exit 1;; esac
[ "$master" -gt 1 ] && [ "$(cat "/proc/$master/comm")" = sshd ] || { printf '%s\n' 'SSH master validation failed; no changes made.' >&2; exit 1; }
baseline=$("$sshd" -T -f "$config" | awk '/^(allowtcpforwarding|disableforwarding|permitopen) /')
working=$(mktemp /etc/ssh/.vebetter-sshd.XXXXXX)
backup="$config.vebetter-before-$(date -u '+%Y%m%dT%H%M%SZ')"
changed=0
completed=0
cleanup() {
  result=$?
  trap - 0 1 2 15
  if [ "$changed" -eq 1 ] && [ "$completed" -ne 1 ]; then
    cp -p "$backup" "$config"
    printf '%s\n' 'Restored the original SSH configuration after a failed step.' >&2
  fi
  [ ! -f "$working" ] || rm -f "$working"
  exit "$result"
}
trap cleanup 0
trap 'exit 1' 1 2 15
cp -p "$config" "$working"
printf '\n# BEGIN VEBETTER DASHBOARD FORWARDING\nMatch User %s\n    AllowTcpForwarding local\n    PermitOpen %s:8788\n# END VEBETTER DASHBOARD FORWARDING\n' "$operator" "$engine_ip" >> "$working"
"$sshd" -t -f "$working"
proposed=$("$sshd" -T -f "$working" -C "$context" | awk '/^(allowtcpforwarding|disableforwarding|permitopen) /')
printf '%s\n' "$proposed" | grep -qx 'allowtcpforwarding local'
printf '%s\n' "$proposed" | grep -qx 'disableforwarding no'
printf '%s\n' "$proposed" | grep -qx "permitopen $engine_ip:8788"
global_after=$("$sshd" -T -f "$working" | awk '/^(allowtcpforwarding|disableforwarding|permitopen) /')
[ "$baseline" = "$global_after" ] || { printf '%s\n' 'Global forwarding policy would change; refusing.' >&2; exit 1; }
[ ! -e "$backup" ] || { printf '%s\n' 'Backup filename already exists; retry later.' >&2; exit 1; }
cp -p "$config" "$backup"
changed=1
cp -p "$working" "$config"
"$sshd" -t -f "$config"
[ "$(cat "$pidfile")" = "$master" ] && [ "$(cat "/proc/$master/comm")" = sshd ] || {
  printf '%s\n' 'SSH master changed during validation; restoring configuration.' >&2; exit 1;
}
kill -HUP "$master"
completed=1
printf '%s\n' 'Scoped forwarding rule validated; SSH master sent a reload signal.'
printf 'Backup: %s\n' "$backup"
printf '%s\n' "$proposed"
printf '%s\n' 'Keep this NAS SSH session open. In a new Mac terminal run:'
printf 'ssh -p %s -N -o ExitOnForwardFailure=yes -L 127.0.0.1:8788:%s:8788 %s@192.168.1.141\n' "$tunnel_port" "$engine_ip" "$operator"
