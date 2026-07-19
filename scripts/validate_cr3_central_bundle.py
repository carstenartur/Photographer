#!/usr/bin/env python3
"""Validate the exact Maven Central bundle for the supported CR3 libraries."""

from __future__ import annotations

import argparse
import hashlib
import io
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

PUBLIC_GROUP_ID = "io.github.carstenartur"
PUBLIC_GROUP_PATH = "io/github/carstenartur"
PUBLIC_ARTIFACTS = (
    "canon-cr3-container",
    "canon-cr3-metadata",
    "canon-cr3-codec",
    "canon-cr3-processing",
    "canon-cr3-core",
    "canon-cr3-imageio",
)
FORBIDDEN_ARTIFACTS = {
    "canon-focus-stack-parent",
    "canon-cr3-public-release",
    "canon-cr3-reference-testkit",
    "canon-cr3-research",
    "canon-cr3-maven-smoke",
    "canon-cr3-benchmark",
    "canon-cr3-cli",
}
PRIMARY_SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class BundleValidationError(RuntimeError):
    """Raised when the Central bundle violates the public release contract."""


@dataclass(frozen=True)
class BundleValidationResult:
    artifact_count: int
    verified_signature_count: int
    file_count: int


def _text(root: ET.Element, path: str) -> str:
    node = root.find(path, NS)
    return "" if node is None or node.text is None else node.text.strip()


def _sha(data: bytes, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    digest.update(data)
    return digest.hexdigest()


def _validate_checksum(entries: dict[str, bytes], file_name: str, algorithm: str) -> None:
    checksum_name = f"{file_name}.{algorithm}"
    if checksum_name not in entries:
        raise BundleValidationError(f"Missing checksum {checksum_name}")
    expected = entries[checksum_name].decode("ascii").strip().split()[0].lower()
    actual = _sha(entries[file_name], algorithm)
    if expected != actual:
        raise BundleValidationError(
            f"Invalid {algorithm} checksum for {file_name}: expected {expected}, got {actual}"
        )


def _validate_jar(entries: dict[str, bytes], file_name: str, required_entry: str) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(entries[file_name])) as jar:
            names = set(jar.namelist())
    except zipfile.BadZipFile as exc:
        raise BundleValidationError(f"Invalid JAR/ZIP {file_name}") from exc
    if not any(name.endswith(required_entry) for name in names):
        raise BundleValidationError(
            f"{file_name} does not contain a required {required_entry} entry"
        )


def _validate_pom(pom_bytes: bytes, artifact_id: str, version: str) -> None:
    try:
        root = ET.fromstring(pom_bytes)
    except ET.ParseError as exc:
        raise BundleValidationError(f"Invalid POM XML for {artifact_id}") from exc

    if root.find("m:parent", NS) is not None:
        raise BundleValidationError(
            f"Flattened public POM for {artifact_id} still contains an internal parent"
        )
    expected = {
        "m:groupId": PUBLIC_GROUP_ID,
        "m:artifactId": artifact_id,
        "m:version": version,
    }
    for path, value in expected.items():
        if _text(root, path) != value:
            raise BundleValidationError(
                f"POM {artifact_id} has {_text(root, path)!r} at {path}, expected {value!r}"
            )

    required_paths = (
        "m:name",
        "m:description",
        "m:url",
        "m:licenses/m:license/m:name",
        "m:licenses/m:license/m:url",
        "m:developers/m:developer/m:name",
        "m:scm/m:connection",
        "m:scm/m:url",
        "m:issueManagement/m:url",
        "m:ciManagement/m:url",
    )
    missing = [path for path in required_paths if not _text(root, path)]
    if missing:
        raise BundleValidationError(
            f"POM {artifact_id} is missing required Central metadata: {', '.join(missing)}"
        )

    for dependency in root.findall("m:dependencies/m:dependency", NS):
        dependency_group = _text(dependency, "m:groupId")
        dependency_artifact = _text(dependency, "m:artifactId")
        dependency_version = _text(dependency, "m:version")
        dependency_scope = _text(dependency, "m:scope") or "compile"
        if dependency_group == "com.photographer.focusstack":
            raise BundleValidationError(
                f"POM {artifact_id} leaks internal dependency {dependency_group}:{dependency_artifact}"
            )
        if dependency_artifact in FORBIDDEN_ARTIFACTS:
            raise BundleValidationError(
                f"POM {artifact_id} depends on forbidden artifact {dependency_artifact}"
            )
        if dependency_version.endswith("-SNAPSHOT"):
            raise BundleValidationError(
                f"POM {artifact_id} contains SNAPSHOT dependency {dependency_group}:{dependency_artifact}:{dependency_version}"
            )
        if dependency_scope in {"test", "system"}:
            raise BundleValidationError(
                f"POM {artifact_id} contains non-public dependency scope {dependency_scope}: {dependency_artifact}"
            )


def _verify_signature(
    entries: dict[str, bytes], file_name: str, gnupg_home: Path
) -> None:
    signature_name = f"{file_name}.asc"
    with tempfile.TemporaryDirectory(prefix="cr3-central-signature-") as temp_dir:
        temp = Path(temp_dir)
        payload = temp / PurePosixPath(file_name).name
        signature = temp / PurePosixPath(signature_name).name
        payload.write_bytes(entries[file_name])
        signature.write_bytes(entries[signature_name])
        completed = subprocess.run(
            [
                "gpg",
                "--batch",
                "--homedir",
                str(gnupg_home),
                "--verify",
                str(signature),
                str(payload),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if completed.returncode != 0:
            raise BundleValidationError(
                f"Invalid GPG signature for {file_name}:\n{completed.stdout}"
            )


def validate_bundle(
    bundle: Path, version: str, gnupg_home: Path | None = None
) -> BundleValidationResult:
    if not bundle.is_file():
        raise BundleValidationError(f"Central bundle does not exist: {bundle}")
    if not version or version.endswith("-SNAPSHOT"):
        raise BundleValidationError(
            f"Preflight version must be a non-SNAPSHOT release version, got {version!r}"
        )

    try:
        with zipfile.ZipFile(bundle) as archive:
            entries = {
                name: archive.read(name)
                for name in archive.namelist()
                if not name.endswith("/")
            }
    except zipfile.BadZipFile as exc:
        raise BundleValidationError(f"Invalid Central bundle ZIP: {bundle}") from exc

    for name in entries:
        if "../" in name or name.startswith("/"):
            raise BundleValidationError(f"Unsafe bundle path: {name}")
        parts = PurePosixPath(name).parts
        if any(forbidden in parts for forbidden in FORBIDDEN_ARTIFACTS):
            raise BundleValidationError(f"Forbidden artifact present in bundle: {name}")
        if name.endswith((".pom", ".jar")) and not name.startswith(
            f"{PUBLIC_GROUP_PATH}/"
        ):
            raise BundleValidationError(f"Unexpected deployable coordinate in bundle: {name}")

    verified_signatures = 0
    for artifact_id in PUBLIC_ARTIFACTS:
        directory = f"{PUBLIC_GROUP_PATH}/{artifact_id}/{version}"
        base = f"{artifact_id}-{version}"
        primary_files = {
            ".pom": f"{directory}/{base}.pom",
            ".jar": f"{directory}/{base}.jar",
            "-sources.jar": f"{directory}/{base}-sources.jar",
            "-javadoc.jar": f"{directory}/{base}-javadoc.jar",
        }
        for suffix in PRIMARY_SUFFIXES:
            file_name = primary_files[suffix]
            if file_name not in entries:
                raise BundleValidationError(f"Missing required artifact {file_name}")
            signature_name = f"{file_name}.asc"
            if signature_name not in entries:
                raise BundleValidationError(f"Missing GPG signature {signature_name}")
            for checksum_suffix in CHECKSUM_SUFFIXES:
                _validate_checksum(entries, file_name, checksum_suffix.removeprefix("."))
                _validate_checksum(
                    entries, signature_name, checksum_suffix.removeprefix(".")
                )
            if gnupg_home is not None:
                _verify_signature(entries, file_name, gnupg_home)
                verified_signatures += 1

        _validate_pom(entries[primary_files[".pom"]], artifact_id, version)
        _validate_jar(entries, primary_files[".jar"], ".class")
        _validate_jar(entries, primary_files["-sources.jar"], ".java")
        _validate_jar(entries, primary_files["-javadoc.jar"], "index.html")

    return BundleValidationResult(
        artifact_count=len(PUBLIC_ARTIFACTS),
        verified_signature_count=verified_signatures,
        file_count=len(entries),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("version")
    parser.add_argument(
        "--gnupg-home",
        type=Path,
        help="Verify every detached signature against this ephemeral keyring",
    )
    args = parser.parse_args()
    result = validate_bundle(args.bundle, args.version, args.gnupg_home)
    print(
        "Validated CR3 Central bundle: "
        f"artifacts={result.artifact_count}, files={result.file_count}, "
        f"verified_signatures={result.verified_signature_count}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
