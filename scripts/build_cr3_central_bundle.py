#!/usr/bin/env python3
"""Assemble the exact public CR3 Central bundle from an isolated Maven repository."""

from __future__ import annotations

import argparse
import hashlib
import sys
import zipfile
from pathlib import Path, PurePosixPath

from validate_cr3_central_bundle import (
    CHECKSUM_SUFFIXES,
    PRIMARY_SUFFIXES,
    PUBLIC_ARTIFACTS,
    PUBLIC_GROUP_PATH,
)

_FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


class BundleBuildError(RuntimeError):
    """Raised when the isolated Maven repository does not contain the release set."""


def _checksum(data: bytes, suffix: str) -> bytes:
    algorithm = suffix.removeprefix(".")
    digest = hashlib.new(algorithm)
    digest.update(data)
    return f"{digest.hexdigest()}\n".encode("ascii")


def _write_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=_FIXED_ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def collect_release_entries(
    maven_repository: Path,
    version: str,
) -> dict[str, bytes]:
    """Collect only main POM/JAR, sources, Javadocs and detached signatures."""
    if not version or version.endswith("-SNAPSHOT"):
        raise BundleBuildError(
            f"Release bundle requires a non-SNAPSHOT version, got {version!r}"
        )

    repository = maven_repository.resolve()
    entries: dict[str, bytes] = {}
    for artifact_id in PUBLIC_ARTIFACTS:
        relative_directory = PurePosixPath(
            PUBLIC_GROUP_PATH, artifact_id, version
        )
        source_directory = repository.joinpath(*relative_directory.parts)
        base = f"{artifact_id}-{version}"
        for suffix in PRIMARY_SUFFIXES:
            primary_name = f"{base}{suffix}"
            signature_name = f"{primary_name}.asc"
            for source_name in (primary_name, signature_name):
                source = source_directory / source_name
                if not source.is_file():
                    raise BundleBuildError(
                        f"Missing release file in isolated Maven repository: {source}"
                    )
                archive_name = str(relative_directory / source_name)
                data = source.read_bytes()
                entries[archive_name] = data
                for checksum_suffix in CHECKSUM_SUFFIXES:
                    entries[f"{archive_name}{checksum_suffix}"] = _checksum(
                        data, checksum_suffix
                    )
    return entries


def build_bundle(
    maven_repository: Path,
    version: str,
    output: Path,
) -> Path:
    """Create a deterministic ZIP containing exactly the supported release files."""
    entries = collect_release_entries(maven_repository, version)
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(f"{output.suffix}.tmp")
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
        ) as archive:
            for name in sorted(entries):
                _write_entry(archive, name, entries[name])
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("maven_repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        bundle = build_bundle(args.maven_repository, args.version, args.output)
    except BundleBuildError as exc:
        print(exc, file=sys.stderr)
        return 1
    print(f"Built deterministic CR3 Central bundle: {bundle}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
