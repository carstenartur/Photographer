#!/usr/bin/env python3
"""Tests for validate_ccapi_transport_boundary.py."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_ccapi_transport_boundary import (
    BoundaryValidationError,
    find_pom_violations,
    find_source_violations,
    validate,
)

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>module</artifactId>
  <version>1</version>
  {dependency}
</project>
"""


class CcapiTransportBoundaryTest(unittest.TestCase):

    def test_accepts_reviewed_app_transport_and_binding_components(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root / "app/pom.xml", apache=True)
            self._write_pom(root / "common-api/pom.xml", apache=False)
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/camera/backend/CcapiRestTemplateFactory.java",
                "package com.photographer.focusstack.camera.backend;\n"
                "import org.apache.hc.client5.http.auth.AuthScope;\n"
                "class CcapiRestTemplateFactory { void use(Object c) { c.toString(); } }\n",
            )
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/app/config/CcapiAuthenticationProperties.java",
                "package com.photographer.focusstack.app.config;\n"
                "class CcapiAuthenticationProperties { public String toString() { return \"<redacted>\"; } }\n",
            )
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/app/config/CcapiCredentialStartupValidator.java",
                "package com.photographer.focusstack.app.config;\n"
                "class CcapiCredentialStartupValidator { "
                "CcapiAuthenticationProperties credentials; }\n",
            )

            validate(root)
            self.assertEqual([], find_pom_violations(root))
            self.assertEqual([], find_source_violations(root))

    def test_rejects_apache_dependency_outside_app(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_pom(root / "common-api/pom.xml", apache=True)

            with self.assertRaisesRegex(BoundaryValidationError, "outside app"):
                validate(root)

    def test_rejects_apache_import_outside_exact_transport_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "plugin-api/src/main/java/example/PublicCameraPlugin.java",
                "package example;\nimport org.apache.hc.client5.http.auth.AuthScope;\n"
                "public interface PublicCameraPlugin {}\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "two transport implementation"):
                validate(root)

    def test_rejects_credential_type_in_controller_or_public_api(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/example/CameraController.java",
                "package example;\n"
                "import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;\n"
                "class CameraController { CcapiAuthenticationProperties credentials; }\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "credential type"):
                validate(root)

    def test_does_not_allow_arbitrary_config_class_to_join_credential_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/app/config/LeakyCredentialExporter.java",
                "package com.photographer.focusstack.app.config;\n"
                "class LeakyCredentialExporter { CcapiAuthenticationProperties credentials; }\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "credential type"):
                validate(root)

    def test_rejects_password_access_outside_transport(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/example/LeakyService.java",
                "package example;\nclass LeakyService { char[] leak(Object value) { "
                "return value.passwordCopy(); } }\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "Password accessor"):
                validate(root)

    def test_rejects_camera_authorization_logging(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/camera/backend/LeakyLogger.java",
                "package com.photographer.focusstack.camera.backend;\n"
                "class LeakyLogger { void run(Object log, Object authorization) { "
                "log.info(\"Authorization {}\", authorization); } }\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "protected authentication"):
                validate(root)

    def test_rejects_camera_www_authenticate_challenge_logging(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/camera/backend/ChallengeLogger.java",
                "package com.photographer.focusstack.camera.backend;\n"
                "class ChallengeLogger { void run(Object log, Object challenge) { "
                "log.debug(\"WWW-Authenticate nonce and opaque {}\", challenge); } }\n",
            )

            with self.assertRaisesRegex(BoundaryValidationError, "protected authentication"):
                validate(root)

    def test_does_not_apply_ccapi_log_vocabulary_to_unrelated_application_security(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/main/java/com/photographer/focusstack/app/security/AdminInitializer.java",
                "package com.photographer.focusstack.app.security;\n"
                "class AdminInitializer { void run(Object log) { "
                "log.info(\"Admin password configuration updated\"); } }\n",
            )

            validate(root)

    def test_ignores_test_sources_and_generated_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self._write_java(
                root / "app/src/test/java/example/ProtocolFixture.java",
                "package example;\nimport org.apache.hc.client5.http.auth.AuthScope;\n"
                "class ProtocolFixture {}\n",
            )
            self._write_pom(root / "target/generated/pom.xml", apache=True)

            validate(root)

    @staticmethod
    def _write_pom(path: Path, *, apache: bool) -> None:
        dependency = (
            "<dependencies><dependency>"
            "<groupId>org.apache.httpcomponents.client5</groupId>"
            "<artifactId>httpclient5</artifactId>"
            "<version>5</version>"
            "</dependency></dependencies>"
            if apache
            else ""
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(POM.format(dependency=dependency), encoding="utf-8")

    @staticmethod
    def _write_java(path: Path, source: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
