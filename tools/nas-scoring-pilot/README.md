# NAS scoring pilot

Versioned source for the temporary Synology calculation engine, Postgres storage,
backfill progress page and offline app-score reports. This package is separate
from the Kotlin indexer and from `../scoring-pilot` (Cloudflare/read-only adapter).
Committing or pushing these files does not change a running NAS container.

## Deployed-source provenance

The `src/` and `sql/` files match the operator-installed November 6 bundle byte
for byte. `deployment-provenance/` records its archive hash and original per-file
hashes. Those manifests describe the deployed snapshot, not later repository
metadata such as this README, the packaging script or ignore rules.

The NAS contains private runtime state; it is deliberately absent from Git:
password files, database files, source wallet ledgers, imports, exports, logs,
backups, installed dependencies and generated archives. The local `preview/`
directory contains generated synthetic examples and public app-level samples. It
is excluded from Git along with runtime state; regenerate reports from a
reviewed saved run when needed.

## Local checks

Use Node 22 or newer. These commands run local tests, never a NAS job:

```sh
npm ci --ignore-scripts
npm test
python3 scripts/package-bundle.py
```

The package command creates `dist/vebetter-scoring-pilot.tar.gz` with a fresh
checksum manifest and the original `vebetter-scoring-pilot/` extraction root.
It uses an explicit source-directory allowlist and excludes all runtime state.
No deploy, restart, database migration or backfill is triggered by packaging.

## Operations

`SETUP.txt` preserves the original operator notes, including the completed
round-50 timeout recovery. Its dated workspace paths are historical; use this
repository and the package command above to prepare a new archive.

`recover-from-mac.sh` is the existing operator-specific SSH/SCP helper, adapted
to read `dist/vebetter-scoring-pilot.tar.gz` by default. Set
`VEBETTER_SCORING_ARCHIVE` to override the local archive path. It targets the
operator's existing NAS/job and is not a general installer. Do not run it during
an active backfill. It backs up the isolated pilot, updates its engine, then
requests resume of the same saved job. Authentication is entered privately in
the operator's terminal, never embedded in the repository.

The score is Activity plus Distribution (maximum 60), not the complete /100
reputation score. If first-seen history is incomplete, reports explicitly exclude
the new-user term (maximum 51); no new-user zeros or reweighting are invented.
