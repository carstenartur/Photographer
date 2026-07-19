#!/usr/bin/env python3
"""Tests for deterministic assembly of the public CR3 Central bundle."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from build_cr3_central_bundle import BundleBuildError, build_bundle
from validate_cr3_central_bundle import (
    CHECKSUM_SUFFIXES,
    PRIMARY_SUFFIXES,
    PUBLIC_ARTIFACTS,
    PUBLIC_GROUP_PATH,
)


class CentralBundleBuilderTest(unittest.TestCase):

    def test_builds_only_required_public_files_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = root / "repository"
            version = "1.2.3"
            self._write_release_repository(repository, version)
            output_a = root / "bundle-a.zip"
            output_b = root / "bundle-b.zip"

            build_bundle(repository, version, output_a)
            build_bundle(repository, version, output_b)

            self.assertEqual(output_a.read_bytes(), output_b.read_bytes())
            with zipfile.ZipFile(output_a) as archive:
                names = set(archive.namelist())
                expected_count = (
                    len(PUBLIC_ARTIFACTS)
                    * len(PRIMARY_SUFFIXES)
                    * 2
                    * (1 + len(CHECKSUM_SUFFIXES))
                )
                self.assertEqual(expected_count, len(names))
                self.assertFalse(any("-tests.jar" in name for name in names))
                self.assertFalse(any("reference-testkit" in name for name in names))

                sample = (
                    f"{PUBLIC_GROUP_PATH}/canon-cr3-core/{version}/"
                    f"canon-cr3-core-{version}.jar"
                )
                payload = archive.read(sample)
                for suffix in CHECKSUM_SUFFIXES:
                    algorithm = suffix.removeprefix(".")
                    self.assertEqual(
                        hashlib.new(algorithm, payload).hexdigest(),
                        archive.read(f"{sample}{suffix}").decode("ascii").strip(),
                    )

    def test_fails_when_aRequiredSignatureIsMissing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = root / "repository"
            version = "1.2.3"
            self._write_release_repository(repository, version)
            missing = (
                repository
                / PUBLIC_GROUP_PATH
                / "canon-cr3-imageio"
                / version
                / f"canon-cr3-imageio-{version}-javadoc.jar.asc"
            )
            missing.unlink()

            with self.assertRaisesRegex(BundleBuildError, "Missing release file"):
                build_bundle(repository, version, root / "bundle.zip")

    def test_rejects_snapshotVersion(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(BundleBuildError, "non-SNAPSHOT"):
                build_bundle(root, "1.2.3-SNAPSHOT", root / "bundle.zip")

    @staticmethod
    def _write_release_repository(repository: Path, version: str) -> None:
        for artifact_id in PUBLIC_ARTIFACTS:
            directory = repository / PUBLIC_GROUP_PATH / artifact_id / version
            directory.mkdir(parents=True, exist_ok=True)
            base = f"{artifact_id}-{version}"
            for suffix in PRIMARY_SUFFIXES:
                payload = directory / f"{base}{suffix}"
                payload.write_bytes(f"payload:{artifact_id}:{suffix}".encode("utf-8"))
                payload.with_name(f"{payload.name}.asc").write_bytes(
                    f"signature:{artifact_id}:{suffix}".encode("utf-8")
                )
            (directory / f"{base}-tests.jar").write_bytes(b"internal test classifier")


if __name__ == "__main__":
    unittest.main()
