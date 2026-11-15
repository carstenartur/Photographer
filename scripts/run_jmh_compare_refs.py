#!/usr/bin/env python3
"""Build and compare two Git refs with the CR3 JMH sentinels on one host."""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from compare_jmh import compare_files, render_markdown, write_json  # noqa: E402

DEFAULT_PATTERN = (
    r"com\.photographer\.focusstack\.cr3\.benchmark\."
    r"(Cr3BoxParseBenchmark|Cr3MetadataBenchmark|Cr3PreviewExtractBenchmark|"
    r"CrxLosslessDecodeBenchmark)\..*"
)
DEFAULT_JMH_ARGS = (
    "-p", "sample=sample.cr3",
    "-wi", "2",
    "-i", "3",
    "-w", "1s",
    "-r", "1s",
)


@dataclass(frozen=True)
class Revision:
    label: str
    sha: str


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_ref", help="Git ref or commit used as baseline")
    parser.add_argument("head_ref", help="Git ref or commit under test")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("target/jmh-ref-comparison"),
        help="Directory for raw JSON, environment metadata and comparison reports",
    )
    parser.add_argument(
        "--order",
        choices=("base-head", "head-base"),
        default="base-head",
        help="Execution order on the shared host",
    )
    parser.add_argument("--forks", type=int, default=1, help="Fresh JVMs per benchmark")
    parser.add_argument("--threshold", type=float, default=1.15)
    parser.add_argument("--fail-on-regression", action="store_true")
    parser.add_argument("--benchmark-pattern", default=DEFAULT_PATTERN)
    parser.add_argument(
        "--jmh-arg",
        action="append",
        default=[],
        help="Additional JMH argument; repeat for multiple arguments",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve refs and print commands without creating worktrees or running Maven/JMH",
    )
    return parser.parse_args(argv)


def run_command(
    command: Sequence[str],
    *,
    cwd: Path,
    capture: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(command), file=sys.stderr)
    return subprocess.run(
        list(command),
        cwd=cwd,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def resolve_repo_root() -> Path:
    result = run_command(
        ("git", "rev-parse", "--show-toplevel"),
        cwd=Path.cwd(),
        capture=True,
    )
    return Path(result.stdout.strip()).resolve()


def resolve_ref(repo_root: Path, ref: str) -> str:
    result = run_command(
        ("git", "rev-parse", "--verify", f"{ref}^{{commit}}"),
        cwd=repo_root,
        capture=True,
    )
    sha = result.stdout.strip()
    if len(sha) != 40:
        raise ValueError(f"Could not resolve {ref!r} to a full commit SHA: {sha!r}")
    return sha


def ordered_revisions(order: str, base_sha: str, head_sha: str) -> tuple[Revision, Revision]:
    base = Revision("base", base_sha)
    head = Revision("head", head_sha)
    return (base, head) if order == "base-head" else (head, base)


def maven_command() -> tuple[str, ...]:
    return (
        "mvn",
        "-B",
        "--no-transfer-progress",
        "-DskipTests",
        "-P",
        "benchmarks",
        "-pl",
        "canon-cr3-benchmark",
        "-am",
        "package",
        "-Djava.awt.headless=true",
    )


def jmh_command(
    worktree: Path,
    result_path: Path,
    benchmark_pattern: str,
    extra_args: Iterable[str] = (),
    forks: int = 1,
) -> tuple[str, ...]:
    samples = worktree / "canon-cr3-imageio" / "src" / "test" / "resources" / "samples"
    jar = worktree / "canon-cr3-benchmark" / "target" / "benchmarks.jar"
    return (
        "java",
        "-Djava.awt.headless=true",
        f"-Dsamples.dir={samples}",
        "-jar",
        str(jar),
        benchmark_pattern,
        *DEFAULT_JMH_ARGS,
        "-f", str(forks),
        "-foe", "true",
        *tuple(extra_args),
        "-rf",
        "json",
        "-rff",
        str(result_path),
    )


def command_output(command: Sequence[str], repo_root: Path) -> str:
    executable = shutil.which(command[0])
    if executable is None:
        return f"unavailable: {command[0]} not found\n"
    result = run_command(command, cwd=repo_root, capture=True, check=False)
    return result.stdout or ""


def write_environment(
    path: Path,
    *,
    repo_root: Path,
    base_ref: str,
    head_ref: str,
    base_sha: str,
    head_sha: str,
    order: str,
    forks: int,
) -> None:
    lines = [
        f"timestamp={datetime.now(timezone.utc).isoformat()}",
        f"base_ref={base_ref}",
        f"base_sha={base_sha}",
        f"head_ref={head_ref}",
        f"head_sha={head_sha}",
        f"forks={forks}",
        f"run_order={order.replace('-', ',')}",
        f"platform={platform.platform()}",
        f"machine={platform.machine()}",
        f"processor={platform.processor() or 'unknown'}",
        f"cpu_count={os.cpu_count() or 'unknown'}",
        f"runner_image={os.environ.get('ImageOS', 'local')}",
        f"runner_image_version={os.environ.get('ImageVersion', 'local')}",
        f"event={os.environ.get('GITHUB_EVENT_NAME', 'local')}",
        "",
        "## CPU",
        command_output(("lscpu",), repo_root).rstrip(),
        "",
        "## Java",
        command_output(("java", "-version"), repo_root).rstrip(),
        "",
        "## Maven",
        command_output(("mvn", "-version"), repo_root).rstrip(),
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def remove_worktree(repo_root: Path, worktree: Path) -> None:
    if worktree.exists():
        run_command(
            ("git", "worktree", "remove", "--force", str(worktree)),
            cwd=repo_root,
            check=False,
        )
    run_command(("git", "worktree", "prune"), cwd=repo_root, check=False)


def run_revision(
    *,
    repo_root: Path,
    worktree: Path,
    revision: Revision,
    output_dir: Path,
    benchmark_pattern: str,
    extra_jmh_args: Sequence[str],
    forks: int,
    dry_run: bool,
) -> None:
    result_path = output_dir / f"{revision.label}.json"
    commands = (
        ("git", "worktree", "add", "--detach", str(worktree), revision.sha),
        maven_command(),
        jmh_command(worktree, result_path, benchmark_pattern, extra_jmh_args, forks),
    )
    if dry_run:
        for command in commands:
            print("+", " ".join(command))
        return

    remove_worktree(repo_root, worktree)
    try:
        run_command(commands[0], cwd=repo_root)
        run_command(commands[1], cwd=worktree)
        run_command(commands[2], cwd=worktree)
    finally:
        remove_worktree(repo_root, worktree)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.forks < 1:
        raise ValueError("Fork count must be positive")
    if "-f" in args.jmh_arg:
        raise ValueError("Use --forks to set the number of fresh JVMs")
    if args.threshold <= 1.0:
        raise ValueError("Threshold must be greater than 1.0")

    repo_root = resolve_repo_root()
    base_sha = resolve_ref(repo_root, args.base_ref)
    head_sha = resolve_ref(repo_root, args.head_ref)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    write_environment(
        output_dir / "environment.txt",
        repo_root=repo_root,
        base_ref=args.base_ref,
        head_ref=args.head_ref,
        base_sha=base_sha,
        head_sha=head_sha,
        order=args.order,
        forks=args.forks,
    )

    revisions = ordered_revisions(args.order, base_sha, head_sha)
    if args.dry_run:
        for revision in revisions:
            run_revision(
                repo_root=repo_root,
                worktree=Path(f"<temporary-worktree-{revision.label}>"),
                revision=revision,
                output_dir=output_dir,
                benchmark_pattern=args.benchmark_pattern,
                extra_jmh_args=args.jmh_arg,
                forks=args.forks,
                dry_run=True,
            )
        return 0

    with tempfile.TemporaryDirectory(prefix="cr3-jmh-worktree-") as temporary:
        worktree = Path(temporary) / "revision"
        for revision in revisions:
            run_revision(
                repo_root=repo_root,
                worktree=worktree,
                revision=revision,
                output_dir=output_dir,
                benchmark_pattern=args.benchmark_pattern,
                extra_jmh_args=args.jmh_arg,
                forks=args.forks,
                dry_run=False,
            )

    comparisons = compare_files(
        output_dir / "base.json",
        output_dir / "head.json",
        args.threshold,
    )
    markdown = render_markdown(comparisons, args.threshold)
    (output_dir / "comparison.md").write_text(markdown, encoding="utf-8")
    write_json(output_dir / "comparison.json", comparisons, args.threshold)
    print(markdown)

    has_regression = any(item.verdict == "REGRESSION" for item in comparisons)
    return 1 if args.fail_on_regression and has_regression else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"JMH two-ref comparison failed: {error}", file=sys.stderr)
        raise SystemExit(2) from error
