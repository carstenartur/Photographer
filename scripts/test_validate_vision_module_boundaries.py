#!/usr/bin/env python3
"""Tests for validate_vision_module_boundaries.py."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_vision_module_boundaries import (
    BoundaryValidationError,
    CONTRACT_FILES,
    COMMON_MAIN,
    find_contract_ownership_violations,
    find_pom_violations,
    validate,
)

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.photographer.focusstack</groupId>
  <artifactId>{artifact}</artifactId>
  <version>1</version>
  <dependencies>{dependencies}</dependencies>
</project>
"""


class VisionModuleBoundaryTest(unittest.TestCase):

    def test_accepts_contracts_in_common_api_and_clean_dependency_edges(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root, "plugin-api", ["common-api"])
            self._write_pom(root, "ai-vision", ["common-api"])
            self._write_contracts(root)

            validate(root)
            self.assertEqual([], find_pom_violations(root))
            self.assertEqual([], find_contract_ownership_violations(root))

    def test_rejects_plugin_api_dependency_on_ai_vision(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root, "plugin-api", ["common-api", "ai-vision"])
            self._write_pom(root, "ai-vision", ["common-api"])
            self._write_contracts(root)

            with self.assertRaisesRegex(
                    BoundaryValidationError,
                    "plugin-api -> ai-vision"):
                validate(root)

    def test_rejects_ai_vision_dependency_on_observer_core(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root, "plugin-api", ["common-api"])
            self._write_pom(root, "ai-vision", ["common-api", "observer-core"])
            self._write_contracts(root)

            with self.assertRaisesRegex(
                    BoundaryValidationError,
                    "ai-vision -> observer-core"):
                validate(root)

    def test_rejects_missing_common_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root, "plugin-api", ["common-api"])
            self._write_pom(root, "ai-vision", ["common-api"])
            self._write_contracts(root)
            missing = root / COMMON_MAIN / CONTRACT_FILES[0]
            missing.unlink()

            with self.assertRaisesRegex(
                    BoundaryValidationError,
                    "not owned by common-api"):
                validate(root)

    def test_rejects_duplicate_contract_in_implementation_module(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root, "plugin-api", ["common-api"])
            self._write_pom(root, "ai-vision", ["common-api"])
            self._write_contracts(root)
            duplicate = root / "ai-vision/src/main/java" / CONTRACT_FILES[0]
            duplicate.parent.mkdir(parents=True, exist_ok=True)
            duplicate.write_text("package example;\n", encoding="utf-8")

            with self.assertRaisesRegex(
                    BoundaryValidationError,
                    "duplicated outside common-api"):
                validate(root)

    @staticmethod
    def _write_contracts(root: Path) -> None:
        for relative in CONTRACT_FILES:
            path = root / COMMON_MAIN / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package example;\n", encoding="utf-8")

    @staticmethod
    def _write_pom(root: Path, module: str, dependencies: list[str]) -> None:
        values = "".join(
            "<dependency>"
            "<groupId>com.photographer.focusstack</groupId>"
            f"<artifactId>{dependency}</artifactId>"
            "<version>1</version>"
            "</dependency>"
            for dependency in dependencies
        )
        path = root / module / "pom.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(POM.format(artifact=module, dependencies=values), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
