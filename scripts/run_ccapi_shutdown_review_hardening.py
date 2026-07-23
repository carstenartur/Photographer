from pathlib import Path
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

IRREGULAR_ASSERTION = '''        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
       .isInstanceOf(IllegalStateException.class)
       .hasMessageContaining("shutting down");'''
CANONICAL_ASSERTION = '''        assertThatThrownBy(() -> manager.acquire(request(key, "owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutting down");'''

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
    if IRREGULAR_ASSERTION in text:
        LEASE_TEST.write_text(
            text.replace(IRREGULAR_ASSERTION, CANONICAL_ASSERTION),
            encoding="utf-8")
    elif CANONICAL_ASSERTION not in text:
        raise SystemExit("shutdown lease test assertion anchor not found")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: run_ccapi_shutdown_review_hardening.py <base-script>")
    normalize_lease_test()
    runpy.run_path(sys.argv[1], run_name="__main__")
    replace_once(LEASE_MANAGER, OLD_CLOSE, NEW_CLOSE)
    replace_once(COORDINATOR, OLD_WAITER, NEW_WAITER)


if __name__ == "__main__":
    main()
