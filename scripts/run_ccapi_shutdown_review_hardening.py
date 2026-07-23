from pathlib import Path
import re
import runpy
import sys


LEASE_TEST = Path(
    "app/src/test/java/com/photographer/focusstack/camera/session/"
    "InMemoryCameraSessionLeaseManagerTest.java"
)
LEASE_MANAGER = Path(
    "app/src/main/java/com/photographer/focusstack/camera/session/"
    "InMemoryCameraSessionLeaseManager.java"
)
COORDINATOR = Path(
    "app/src/main/java/com/photographer/focusstack/camera/session/"
    "CameraSessionShutdownCoordinator.java"
)
COORDINATOR_TEST = Path(
    "app/src/test/java/com/photographer/focusstack/camera/session/"
    "CameraSessionShutdownCoordinatorTest.java"
)

ASSERTION_PATTERN = re.compile(
    r'''(?m)^        assertThatThrownBy\(\(\) -> manager\.acquire\(request\(key, "owner"\)\)\)\n'''
    r'''\s+\.isInstanceOf\(IllegalStateException\.class\)\n'''
    r'''\s+\.hasMessageContaining\("shutting down"\);'''
)
CANONICAL_ASSERTION = '''        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutting down");'''

COORDINATOR_SHUTDOWN_ASSERTION = re.compile(
    r'''(?m)^(?P<indent>\s+)\.isInstanceOf\(IllegalStateException\.class\)\n'''
    r'''(?P=indent)\.hasMessageContaining\("shutting down"\);'''
)
COORDINATOR_IMPORT_ANCHOR = (
    "package com.photographer.focusstack.camera.session;\n\n"
)
COORDINATOR_IMPORT = (
    COORDINATOR_IMPORT_ANCHOR
    + "import com.photographer.focusstack.camera.exception."
    + "CameraServiceUnavailableException;\n"
)

OLD_CLOSE = '''    @PreDestroy
    public void close() {
        beginShutdown();
        int active = leases.size();
        if (active != 0) {
  throw new IllegalStateException(
          "Camera session lease manager closed with " + active + " active lease(s)");
        }
    }'''
NEW_CLOSE = '''    @PreDestroy
    public void close() {
        synchronized (lifecycleMonitor) {
            beginShutdown();
            int active = leases.size();
            if (active != 0) {
                throw new IllegalStateException(
                        "Camera session lease manager closed with " + active
                                + " active lease(s)");
            }
        }
    }'''

OLD_WAITER = "      duration -> Thread.sleep(duration.toMillis()));"
NEW_WAITER = "      Thread::sleep);"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def normalize_lease_test() -> None:
    text = LEASE_TEST.read_text(encoding="utf-8")
    normalized, count = ASSERTION_PATTERN.subn(CANONICAL_ASSERTION, text)
    if count != 1:
        raise SystemExit(
            f"shutdown lease test assertion anchor count was {count}, expected 1")
    LEASE_TEST.write_text(normalized, encoding="utf-8")


def update_coordinator_test() -> None:
    text = COORDINATOR_TEST.read_text(encoding="utf-8")
    if "CameraServiceUnavailableException;" not in text:
        if text.count(COORDINATOR_IMPORT_ANCHOR) != 1:
            raise SystemExit("coordinator test package/import anchor not unique")
        text = text.replace(
            COORDINATOR_IMPORT_ANCHOR,
            COORDINATOR_IMPORT,
            1)
    replacement = (
        r'''\g<indent>.isInstanceOf(CameraServiceUnavailableException.class)\n'''
        r'''\g<indent>.hasMessage("Camera services are shutting down");'''
    )
    updated, count = COORDINATOR_SHUTDOWN_ASSERTION.subn(replacement, text)
    if count != 1:
        raise SystemExit(
            f"coordinator shutdown assertion anchor count was {count}, expected 1")
    COORDINATOR_TEST.write_text(updated, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: run_ccapi_shutdown_review_hardening.py <base-script>")
    normalize_lease_test()
    runpy.run_path(sys.argv[1], run_name="__main__")
    replace_once(LEASE_MANAGER, OLD_CLOSE, NEW_CLOSE)
    replace_once(COORDINATOR, OLD_WAITER, NEW_WAITER)
    update_coordinator_test()


if __name__ == "__main__":
    main()
