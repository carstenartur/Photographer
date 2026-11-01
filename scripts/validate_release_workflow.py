#!/usr/bin/env python3
"""Pin the narrow protected-release orchestration contract (standard library).

Behavior belongs to release_handoff.py and its offline tests. This validator
checks the deliberately fixed workflow shape, replacing the obsolete contract
that only recognized duplicated inline version-planning text.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/deploy-release.yml'
GATE = "if: ${{ steps.handoff.outputs.verified_resume == 'true' && !inputs.dry_run }}"
STEPS = (
    'Checkout exact dispatched main control commit with full history',
    'Set up JDK 25',
    'Prepare protected handoff or validate explicit merged release resume',
    'Guard publication and create exact stable tag and application release',
    'Log in to GitHub Container Registry',
    'Extract Docker metadata',
    'Recheck exact stable source and publication targets before image publication',
    'Build and push Docker image from merged stable checkout',
    'Preserve maintenance responsibility and stage next snapshot handoff',
)


def validate(workflow: str) -> list[str]:
    failures = []
    def check(condition, message):
        if not condition:
            failures.append(message)
    event = re.search(r'^on:\n((?:[ \t].*\n|\n)*)', workflow, re.MULTILINE)
    check(event is not None and re.findall(r'^  ([\w_]+):', event.group(1), re.MULTILINE) == ['workflow_dispatch'], 'only workflow_dispatch may enter the canonical publisher')
    names = re.findall(r'^      - name: (.+)$', workflow, re.MULTILINE)
    check(tuple(names) == STEPS, 'release steps/order differ from the reviewed finite handoff')
    chunks = re.split(r'^      - name: ', workflow, flags=re.MULTILINE)[1:]
    if len(chunks) == len(STEPS):
        for step in chunks[3:]:
            check(GATE in step, 'every publication/continuation step requires a successful verified live resume')
        check('ref: ${{ github.sha }}' in chunks[0] and 'fetch-depth: 0' in chunks[0], 'checkout must bind exact dispatched control SHA with full history')
        check('scripts/release_handoff.py prepare ' in chunks[2] and 'scripts/release_handoff.py resume ' in chunks[2], 'preparation and explicit resume must call the repository helper')
        check('scripts/release_handoff.py publish ' in chunks[3], 'application publication must use the final guarded helper')
        check('scripts/release_handoff.py image-guard ' in chunks[6], 'image publication requires a fresh stable-source/target guard')
        check('context: .' in chunks[7] and 'push: true' in chunks[7], 'image must use the still-stable working checkout')
        check('scripts/release_handoff.py finish ' in chunks[8], 'snapshot handoff must follow image publication')
    required = (
        '      next_development_version:\n', 'Optional exact next version (X.Y.Z-SNAPSHOT)',
        '      next_version_increment:\n', '        type: choice\n',
        '          - patch\n', '          - minor\n', '          - major\n',
        '      resume_release_pr:\n', '  actions: read\n', '  contents: write\n',
        '  packages: write\n', '  cancel-in-progress: false\n',
        'EXACT_NEXT_VERSION: ${{ inputs.next_development_version }}',
        'NEXT_VERSION_INCREMENT: ${{ inputs.next_version_increment }}',
        'RESUME_RELEASE_PR: ${{ inputs.resume_release_pr }}',
        '--dry-run "$DRY_RUN" --skip-tests "$SKIP_TESTS"',
    )
    for token in required:
        check(token in workflow, f'missing release contract token {token!r}')
    for token in ('      release_version:', '${{ inputs.release_version }}', 'ref: main', 'git push', '--method PATCH', '--clobber', 'gh pr create', 'pull_request_target', 'repository_dispatch', 'workflow_run'):
        check(token not in workflow, f'unsafe release orchestration token {token!r}')
    return failures


def main() -> int:
    failures = validate(WORKFLOW.read_text(encoding='utf-8'))
    gate = (ROOT / 'scripts/verify-product.sh').read_text(encoding='utf-8')
    if 'python3 scripts/test_release_handoff.py\n' not in gate:
        failures.append('canonical product gate must execute offline release handoff tests')
    helper = (ROOT / 'scripts/release_handoff.py').read_text(encoding='utf-8')
    for token in ("'-Dexpression='+expression", "('project.version', 'revision')", '_planner.resolve_plan', "self.maven(['clean', 'verify'])", "'required executable app/target/focusstack.jar is missing'"):
        if token not in helper:
            failures.append(f'missing executable helper contract {token!r}')
    if failures:
        print('Release workflow contract failed:', file=sys.stderr)
        for failure in failures:
            print('- '+failure, file=sys.stderr)
        return 1
    print('Protected release handoff: Maven-derived plan, exact control checkout, verified-resume publication and canonical product test gate.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
