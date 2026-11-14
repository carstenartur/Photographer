#!/usr/bin/env python3
"""Unit tests for validate_cr3_central_bundle.py."""

from __future__ import annotations

import hashlib
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from validate_cr3_central_bundle import (  # noqa: E402
    BundleValidationError,
    CHECKSUM_SUFFIXES,
    PUBLIC_ARTIFACTS,
    PUBLIC_GROUP_PATH,
    REPO_ROOT,
    validate_bundle,
)

VERSION = "1.2.3"


def jar(entry_name: str, documents: dict[str, bytes] | None = None) -> bytes:
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as archive:
        archive.writestr(entry_name, b"content")
        for path, data in (documents or {}).items():
            archive.writestr(path, data)
    return result.getvalue()


def pom(
    artifact_id: str,
    *,
    parent: bool = False,
    dependency: str = "",
    include_issue_management: bool = True,
    include_ci_management: bool = True,
) -> bytes:
    parent_xml = (
        "<parent><groupId>com.photographer.focusstack</groupId>"
        "<artifactId>canon-focus-stack-parent</artifactId>"
        "<version>0.1.5-SNAPSHOT</version></parent>"
        if parent
        else ""
    )
    dependency_xml = (
        "<dependencies><dependency><groupId>com.photographer.focusstack</groupId>"
        f"<artifactId>{dependency}</artifactId><version>0.1.5-SNAPSHOT</version>"
        "</dependency></dependencies>"
        if dependency
        else ""
    )
    issue_xml = (
        "<issueManagement><system>GitHub</system>"
        "<url>https://github.com/carstenartur/Photographer/issues</url>"
        "</issueManagement>"
        if include_issue_management
        else ""
    )
    ci_xml = (
        "<ciManagement><system>GitHub Actions</system>"
        "<url>https://github.com/carstenartur/Photographer/actions</url>"
        "</ciManagement>"
        if include_ci_management
        else ""
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  {parent_xml}
  <groupId>io.github.carstenartur</groupId>
  <artifactId>{artifact_id}</artifactId>
  <version>{VERSION}</version>
  <name>{artifact_id}</name>
  <description>Public CR3 artifact</description>
  <url>https://github.com/carstenartur/Photographer</url>
  <licenses><license><name>MIT License</name><url>https://opensource.org/license/mit</url></license></licenses>
  <developers><developer><name>Carsten Hammer</name></developer></developers>
  <scm><connection>scm:git:https://github.com/carstenartur/Photographer.git</connection><url>https://github.com/carstenartur/Photographer</url></scm>
  {issue_xml}
  {ci_xml}
  {dependency_xml}
</project>
""".encode("utf-8")


def checksum(data: bytes, algorithm: str) -> bytes:
    return (hashlib.new(algorithm, data).hexdigest() + "\n").encode("ascii")


def valid_entries() -> dict[str, bytes]:
    entries: dict[str, bytes] = {}
    for artifact_id in PUBLIC_ARTIFACTS:
        directory = f"{PUBLIC_GROUP_PATH}/{artifact_id}/{VERSION}"
        base = f"{artifact_id}-{VERSION}"
        documents = {"LICENSE": (REPO_ROOT / "LICENSE").read_bytes()}
        if artifact_id in {"cr3-core", "cr3-imageio"}:
            documents["import.md"] = (REPO_ROOT / "docs/cr3/import.md").read_bytes()
        if artifact_id == "cr3-imageio":
            documents["imageio.md"] = (REPO_ROOT / "docs/cr3/imageio.md").read_bytes()
        resources = {f"META-INF/cr3/{name}": data for name, data in documents.items()}
        primaries = {
            f"{directory}/{base}.pom": pom(artifact_id),
            f"{directory}/{base}.jar": jar("example/Type.class", resources),
            f"{directory}/{base}-sources.jar": jar("example/Type.java", resources),
            f"{directory}/{base}-javadoc.jar": jar("index.html", documents),
        }
        for name, data in primaries.items():
            entries[name] = data
            signature_name = f"{name}.asc"
            entries[signature_name] = b"fake-signature"
            for suffix in CHECKSUM_SUFFIXES:
                algorithm = suffix.removeprefix(".")
                entries[f"{name}{suffix}"] = checksum(data, algorithm)
    return entries


def replace_pom(entries: dict[str, bytes], artifact: str, data: bytes) -> None:
    pom_name = f"{PUBLIC_GROUP_PATH}/{artifact}/{VERSION}/{artifact}-{VERSION}.pom"
    entries[pom_name] = data
    for suffix in CHECKSUM_SUFFIXES:
        algorithm = suffix.removeprefix(".")
        entries[f"{pom_name}{suffix}"] = checksum(data, algorithm)


def write_bundle(entries: dict[str, bytes], directory: Path) -> Path:
    bundle = directory / "central-bundle.zip"
    with zipfile.ZipFile(bundle, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return bundle


class ValidateCr3CentralBundleTest(unittest.TestCase):
    def test_accepts_complete_public_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            result = validate_bundle(
                write_bundle(valid_entries(), Path(temp)), VERSION
            )
        self.assertEqual(len(PUBLIC_ARTIFACTS), result.artifact_count)
        self.assertEqual(0, result.verified_signature_count)

    def test_rejects_changed_license_or_guide_even_with_valid_checksums(self) -> None:
        for suffix, content_name, document in (
            (".jar", "example/Type.class", "META-INF/cr3/LICENSE"),
            ("-sources.jar", "example/Type.java", "META-INF/cr3/import.md"),
            ("-javadoc.jar", "index.html", "imageio.md"),
        ):
            with self.subTest(suffix=suffix):
                entries = valid_entries()
                name = f"{PUBLIC_GROUP_PATH}/cr3-imageio/{VERSION}/cr3-imageio-{VERSION}{suffix}"
                with zipfile.ZipFile(io.BytesIO(entries[name])) as original:
                    documents = {n: original.read(n) for n in original.namelist() if n != content_name}
                documents[document] = b"obsolete"
                entries[name] = jar(content_name, documents)
                for algorithm in CHECKSUM_SUFFIXES:
                    entries[name + algorithm] = checksum(entries[name], algorithm.removeprefix("."))
                with tempfile.TemporaryDirectory() as temp:
                    with self.assertRaisesRegex(BundleValidationError, f"missing or incorrect {document}"):
                        validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_attached_test_artifacts(self) -> None:
        entries = valid_entries()
        artifact = PUBLIC_ARTIFACTS[0]
        name = f"{PUBLIC_GROUP_PATH}/{artifact}/{VERSION}/{artifact}-{VERSION}-tests.jar"
        entries[name] = jar("fixture/Test.class")
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BundleValidationError, "Unexpected release file"):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_missing_javadoc_artifact(self) -> None:
        entries = valid_entries()
        artifact = PUBLIC_ARTIFACTS[0]
        prefix = f"{PUBLIC_GROUP_PATH}/{artifact}/{VERSION}/{artifact}-{VERSION}-javadoc.jar"
        for name in [name for name in entries if name.startswith(prefix)]:
            del entries[name]
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BundleValidationError, "Missing required artifact"):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_unflattened_internal_parent(self) -> None:
        entries = valid_entries()
        artifact = PUBLIC_ARTIFACTS[0]
        replace_pom(entries, artifact, pom(artifact, parent=True))
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BundleValidationError, "internal parent"):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_stripped_issue_or_ci_metadata(self) -> None:
        for field, kwargs in (
            ("issueManagement", {"include_issue_management": False}),
            ("ciManagement", {"include_ci_management": False}),
        ):
            with self.subTest(field=field):
                entries = valid_entries()
                artifact = PUBLIC_ARTIFACTS[0]
                replace_pom(entries, artifact, pom(artifact, **kwargs))
                with tempfile.TemporaryDirectory() as temp:
                    with self.assertRaisesRegex(BundleValidationError, field):
                        validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_internal_or_snapshot_dependency(self) -> None:
        entries = valid_entries()
        artifact = PUBLIC_ARTIFACTS[0]
        replace_pom(entries, artifact, pom(artifact, dependency="canon-cr3-research"))
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(BundleValidationError):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_retired_public_dependency(self) -> None:
        entries = valid_entries()
        artifact = "cr3-imageio"
        old_pom = pom(artifact, dependency="canon-cr3-core").replace(
            b"com.photographer.focusstack", b"io.github.carstenartur"
        ).replace(b"0.1.5-SNAPSHOT", VERSION.encode())
        replace_pom(entries, artifact, old_pom)
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BundleValidationError, "invalid public CR3 dependency"):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)

    def test_rejects_corrupt_checksum(self) -> None:
        entries = valid_entries()
        artifact = PUBLIC_ARTIFACTS[0]
        name = f"{PUBLIC_GROUP_PATH}/{artifact}/{VERSION}/{artifact}-{VERSION}.jar.sha256"
        entries[name] = b"00\n"
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BundleValidationError, "Invalid sha256"):
                validate_bundle(write_bundle(entries, Path(temp)), VERSION)


if __name__ == "__main__":
    unittest.main()
