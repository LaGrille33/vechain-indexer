#!/usr/bin/env python3
"""Build a source-only NAS archive without deploying or reading runtime state."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent.parent
ROOT_FILES = (
    ".dockerignore", ".gitignore", "Dockerfile", "README.md", "SETUP.txt",
    "compose.yaml", "package.json", "package-lock.json", "source-manifest.json",
    "recover-from-mac.sh",
)
SOURCE_DIRS = ("src", "sql", "scripts", "test", "preview", "deployment-provenance")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/vebetter-scoring-pilot.tar.gz")
    args = parser.parse_args()
    files = [ROOT / name for name in ROOT_FILES]
    for name in SOURCE_DIRS:
        for path in sorted((ROOT / name).rglob("*")):
            if path.is_symlink():
                raise SystemExit("Refusing a source symlink")
            if path.is_file():
                files.append(path)
    files.sort(key=lambda path: path.relative_to(ROOT).as_posix())
    for path in files:
        if path.is_symlink() or not path.is_file():
            raise SystemExit("Missing or unsafe source file")
        if any(part in {"data", "config", "backups", "node_modules", "__pycache__"}
               for part in path.relative_to(ROOT).parts):
            raise SystemExit("Runtime directory in source allowlist")
        if path.name.startswith(".env") or path.suffix in {".log", ".pyc", ".pem"}:
            raise SystemExit("Private or generated file in source allowlist")
    hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in files}
    manifests = {
        "bundle-sha256.json": (json.dumps(hashes, indent=2) + "\n").encode(),
        "bundle-sha256.txt": "".join(value + "  " + name + "\n"
                                     for name, value in hashes.items()).encode(),
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + ".partial")
    with tarfile.open(partial, "w:gz") as archive:
        for path in files:
            archive.add(path, arcname="vebetter-scoring-pilot/" + path.relative_to(ROOT).as_posix(), recursive=False)
        for name, content in manifests.items():
            info = tarfile.TarInfo("vebetter-scoring-pilot/" + name)
            info.size = len(content)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(content))
    partial.replace(output)
    print(json.dumps({"archive": str(output), "sourceFiles": len(files),
                      "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
