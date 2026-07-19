#!/usr/bin/env python3
"""Tests for the in-repository Canon CR3 coordinate validator."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_cr3_source_coordinates import find_legacy_dependencies, validate

POM_TEMPLATE = """<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<project xmlns=\"http://maven.apache.org/POM/4.0.0\">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>consumer</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>{group_id}</groupId>
      <artifactId>{artifact_id}</artifactId>
      <version>1</version>
    </dependency>
  </dependencies>
</project>
"""


class SourceCoordinateValidatorTest(unittest.TestCase):

    def test_accepts_public_group_id(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(
                root / "consumer" / "pom.xml",
                "io.github.carstenartur",
                "canon-cr3-processing",
            )

            validate(root)
            self.assertEqual([], find_legacy_dependencies(root))

    def test_rejects_legacy_cr3_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(
                root / "astro-core-benchmark" / "pom.xml",
                "com.photographer.focusstack",
                "canon-cr3-processing",
            )

            violations = find_legacy_dependencies(root)
            self.assertEqual(1, len(violations))
            self.assertEqual("canon-cr3-processing", violations[0].artifact_id)
            with self.assertRaisesRegex(RuntimeError, "retired Canon CR3 coordinates"):
                validate(root)

    def test_ignores_non_cr3_internal_dependency_and_target_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(
                root / "app" / "pom.xml",
                "com.photographer.focusstack",
                "common-api",
            )
            self._write_pom(
                root / "target" / "generated" / "pom.xml",
                "com.photographer.focusstack",
                "canon-cr3-core",
            )

            validate(root)

    @staticmethod
    def _write_pom(
        path: Path,
        group_id: str,
        artifact_id: str,
    ) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            POM_TEMPLATE.format(group_id=group_id, artifact_id=artifact_id),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
