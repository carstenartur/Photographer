#!/usr/bin/env python3
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from validate_documentation import (
    DocumentationValidationError,
    anchors,
    parse_links,
    validate,
)


class DocumentationValidatorTest(unittest.TestCase):

    def test_accepts_relative_links_anchor_redirect_and_external_exclusion(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(
                root,
                "docs/index.md",
                "# Home\n\n[Guide](guide.md#build-and-test)\n"
                "[Web](https://example.com)\n",
            )
            self._write(root, "docs/guide.md", "# Guide\n\n## Build and test\n")
            self._write(root, "OLD.md", "Moved to [Home](docs/index.md).\n")
            self._manifest(
                root,
                topics=[{"id": "home", "owner": "docs/index.md"}],
                redirects=[
                    {"source": "OLD.md", "targets": ["docs/index.md"]}
                ],
            )
            validate(root)

    def test_rejects_missing_relative_target_with_line(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(
                root,
                "README.md",
                "# Project\n\n[Missing](docs/missing.md)\n",
            )
            self._manifest(
                root, topics=[{"id": "home", "owner": "README.md"}]
            )
            with self.assertRaisesRegex(
                DocumentationValidationError,
                r"README\.md:3: missing internal link target",
            ):
                validate(root)

    def test_rejects_missing_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "README.md", "[Section](docs/guide.md#absent)\n")
            self._write(root, "docs/guide.md", "# Present\n")
            self._manifest(
                root, topics=[{"id": "home", "owner": "README.md"}]
            )
            with self.assertRaisesRegex(
                DocumentationValidationError, "missing Markdown anchor #absent"
            ):
                validate(root)

    def test_rejects_duplicate_topic_owner_declarations(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "a.md", "# A\n")
            self._write(root, "b.md", "# B\n")
            self._manifest(
                root,
                topics=[
                    {"id": "api", "owner": "a.md"},
                    {"id": "api", "owner": "b.md"},
                ],
            )
            with self.assertRaisesRegex(
                DocumentationValidationError, "multiple owners"
            ):
                validate(root)

    def test_rejects_generated_document_as_canonical_owner(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "docs/generated/report.md", "# Report\n")
            self._manifest(
                root,
                topics=[
                    {"id": "state", "owner": "docs/generated/report.md"}
                ],
                generated=["docs/generated"],
            )
            with self.assertRaisesRegex(
                DocumentationValidationError,
                "generated document cannot own",
            ):
                validate(root)

    def test_rejects_redirect_without_declared_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "old.md", "See something else.\n")
            self._write(root, "new.md", "# New\n")
            self._manifest(
                root,
                topics=[{"id": "new", "owner": "new.md"}],
                redirects=[{"source": "old.md", "targets": ["new.md"]}],
            )
            with self.assertRaisesRegex(
                DocumentationValidationError, "redirect does not link"
            ):
                validate(root)

    def test_rejects_missing_module_manual_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "module/README.md", "# Module\n")
            self._write(root, "docs/manual.md", "# Manual\n")
            self._manifest(
                root,
                topics=[{"id": "manual", "owner": "docs/manual.md"}],
                module_links=[
                    {
                        "readme": "module/README.md",
                        "targets": ["../docs/manual.md"],
                    }
                ],
            )
            with self.assertRaisesRegex(
                DocumentationValidationError,
                "missing required canonical-manual link",
            ):
                validate(root)

    def test_rejects_committed_generated_output_pattern(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._write(root, "README.md", "# Home\n")
            self._write(root, "module/target/site/index.html", "generated")
            self._manifest(
                root,
                topics=[{"id": "home", "owner": "README.md"}],
                forbidden=["**/target/**"],
            )
            with self.assertRaisesRegex(
                DocumentationValidationError,
                "generated build/report output",
            ):
                validate(root)

    def test_ignores_links_inside_fences_comments_and_inline_code(self) -> None:
        text = """# Doc
```
[not a link](missing.md)
```
<!-- [also ignored](missing.md) -->
`[inline example](missing.md)`
[real](present.md)
"""
        links = parse_links(Path("README.md"), text)
        self.assertEqual(["present.md"], [link.target for link in links])

    def test_github_style_duplicate_heading_anchors(self) -> None:
        self.assertEqual({"title", "title-1"}, anchors("# Title\n# Title\n"))

    def test_github_style_inline_code_heading_anchors(self) -> None:
        self.assertEqual(
            {"camera-ptp", "ccapi-in-app"},
            anchors("### `camera-ptp`\n### CCAPI in `app`\n"),
        )

    @staticmethod
    def _write(root: Path, relative: str, content: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _manifest(
        self,
        root: Path,
        *,
        topics: list[dict],
        redirects: list[dict] | None = None,
        module_links: list[dict] | None = None,
        generated: list[str] | None = None,
        forbidden: list[str] | None = None,
    ) -> None:
        payload = {
            "schemaVersion": 1,
            "topics": topics,
            "redirects": redirects or [],
            "moduleManualLinks": module_links or [],
            "generatedDocumentPrefixes": generated or [],
            "forbiddenTrackedPatterns": forbidden or [],
            "ignoredLinkPrefixes": [],
        }
        self._write(
            root,
            "docs/developer/documentation-ownership.json",
            json.dumps(payload),
        )


if __name__ == "__main__":
    unittest.main()
