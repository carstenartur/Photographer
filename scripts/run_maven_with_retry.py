#!/usr/bin/env python3
"""Run Maven, retrying only transient remote dependency transfer failures.

Maven already retries individual HTTP requests. A longer pause between at most
three invocations also gives a rate-limited repository time to recover. Build
and test failures are never made successful by retrying them.
"""

import re
import subprocess
import sys
import time
from collections.abc import Sequence


ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
ERROR_LEVEL = re.compile(r"^\s*\[(?:ERROR|FATAL)\]")
TRANSFER = re.compile(r"Could not transfer (?:artifact|metadata)\b.*https?://", re.I)
HTTP_STATUS = re.compile(r"(?:HTTP\s+)?status(?:\s+code)?\s*[:=]\s*(\d{3})\b", re.I)
TRANSIENT_STATUSES = {"429", "500", "502", "503", "504"}
BUILD_FAILURE = re.compile(
    r"Compilation (?:failure|error)|There are test failures|"
    r"There was an error in the forked process|Could not find artifact|"
    r"Malformed POM|Non-parseable POM|Unknown lifecycle phase",
    re.I,
)


def is_transient_resolution_failure(errors: Sequence[str]) -> bool:
    """Require failed transfers, not a recovered HTTP warning before a test error."""
    transfers = [line for line in errors if TRANSFER.search(line)]
    if not transfers:
        return False
    for line in errors:
        if BUILD_FAILURE.search(line):
            return False
        # In a fail-at-end reactor another module may have a genuine goal failure.
        if "Failed to execute goal" in line and not TRANSFER.search(line):
            return False
    for line in transfers:
        statuses = HTTP_STATUS.findall(line)
        if not statuses or any(status not in TRANSIENT_STATUSES for status in statuses):
            return False
    return True


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    for attempt in range(1, 4):
        command = ["mvn", *arguments]
        if attempt > 1 and not {"-U", "--update-snapshots"}.intersection(arguments):
            command.insert(1, "-U")  # Recheck artifacts cached as missing on the first attempt.
        errors = []
        try:
            with subprocess.Popen(
                command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, errors="replace", bufsize=1,
            ) as process:
                for line in process.stdout:
                    print(line, end="", flush=True)
                    plain = ANSI_ESCAPE.sub("", line)
                    if ERROR_LEVEL.match(plain):
                        errors.append(plain)
                status = process.wait()
        except FileNotFoundError as error:
            print(f"Unable to start Maven: {error}", file=sys.stderr, flush=True)
            return 127
        if status < 0:
            return 128 - status
        if status == 0 or attempt == 3 or not is_transient_resolution_failure(errors):
            return status
        print(
            "Transient Maven dependency transfer failed; retrying in 45 seconds "
            f"(attempt {attempt + 1}/3, with -U).",
            file=sys.stderr, flush=True,
        )
        time.sleep(45)
    raise AssertionError("unreachable")


if __name__ == "__main__":
    raise SystemExit(main())
