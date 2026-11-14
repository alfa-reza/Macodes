#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Transfer Agent Bridge payloads to CI, without transferring keys."""
import argparse
import io
import json
import os
from pathlib import Path
import re
import tarfile

TARGETS = ("Darwin-arm64", "Darwin-x86_64", "Linux-aarch64", "Linux-x86_64")
FILES = tuple(
    f"agentBridge/build/binaries/{name}"
    for name in ("manifest.json", *(f"macodes-agent-{target}" for target in TARGETS))
)
METADATA = "remote-services.json"
LIMIT = 128 * 1024 * 1024


def commit_id(value):
    if not re.fullmatch(r"[a-f0-9]{40}", value):
        raise ValueError("Full commit SHA required")
    return value


def pack(archive, root, commit):
    root = root.resolve()
    commit_id(commit)
    with tarfile.open(archive, "w:gz") as output:
        for name in FILES:
            source = root / name
            if source.is_symlink() or not source.is_file():
                raise ValueError("Missing regular payload: " + name)
            output.add(source, arcname=name, recursive=False)
        body = json.dumps({"commit": commit}).encode()
        info = tarfile.TarInfo(METADATA)
        info.size = len(body)
        info.mode = 0o600
        output.addfile(info, io.BytesIO(body))


def unpack(archive, root, commit):
    root = root.resolve()
    commit_id(commit)
    expected = set(FILES) | {METADATA}
    bodies = {}
    total = 0
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            if member.name not in expected or member.name in bodies or not member.isfile():
                raise ValueError("Unexpected/duplicate/non-regular archive member")
            total += member.size
            if member.size < 0 or total > LIMIT:
                raise ValueError("Payload archive exceeds limit")
            with source.extractfile(member) as stream:
                bodies[member.name] = stream.read()
    if bodies.keys() != expected:
        raise ValueError("Incomplete payload archive")
    if json.loads(bodies.pop(METADATA)).get("commit") != commit:
        raise ValueError("Payload archive belongs to another commit")
    # No tar extraction: only exact, allowlisted paths are written, after the
    # complete archive and commit have been validated. Reject parent symlinks.
    for name in FILES:
        target = root / name
        if any(parent.is_symlink() for parent in (target, *target.parents)):
            raise ValueError("Symlink in payload destination")
    for name, body in bodies.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".importing")
        if temporary.exists() or temporary.is_symlink():
            raise ValueError("Payload staging path already exists")
        with temporary.open("xb") as stream:
            stream.write(body)
        os.chmod(temporary, 0o600 if target.suffix == ".json" else 0o700)
        temporary.replace(target)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action in ("pack", "unpack"):
        command = commands.add_parser(action)
        command.add_argument("archive", type=Path)
        command.add_argument("commit", type=commit_id)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    {"pack": pack, "unpack": unpack}[args.action](args.archive, root, args.commit)


if __name__ == "__main__":
    main()
