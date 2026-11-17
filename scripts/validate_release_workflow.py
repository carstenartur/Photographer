#!/usr/bin/env python3
"""Check the release entry points and that each publisher uses the verified output."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/deploy-release.yml'


def validate(workflow: str) -> list[str]:
    failures = []
    events, _, jobs = workflow.partition('\njobs:\n')
    sections = re.split(r'^  ([\w-]+):\s*$', jobs, flags=re.MULTILINE)
    job = dict(zip(sections[1::2], sections[2::2]))
    prepare = job.get('release', '')
    if ('  workflow_dispatch:' not in events or 'types: [closed]' not in events
            or 'pull_request_target:' in events):
        failures.append('Release entry points must use manual dispatch or an approved merged PR.')
    for condition in ("github.ref == 'refs/heads/main'", 'github.event.pull_request.merged',
                      'github.event.pull_request.head.repo.full_name == github.repository',
                      'github.event.pull_request.merge_commit_sha == github.sha',
                      "contains(github.event.pull_request.labels.*.name, 'release: publish')"):
        if condition not in prepare:
            failures.append('Missing release source check: ' + condition)
    if 'ref: ${{ github.sha }}' not in prepare or 'fetch-depth: 0' not in prepare:
        failures.append('Prepare must use the selected commit and its Git history.')
    for name in ('docker', 'central', 'fiji'):
        publisher = job.get(name, '')
        if 'needs: release' not in publisher or 'if: ${{ !inputs.dry_run }}' not in publisher:
            failures.append(f'{name} must depend on successful preparation and skip previews.')
        if 'needs.release.outputs.stable_sha' not in publisher:
            failures.append(f'{name} must use the prepared stable commit.')
    return failures


if __name__ == '__main__':
    errors = validate(WORKFLOW.read_text(encoding='utf-8'))
    for error in errors:
        print(error, file=sys.stderr)
    sys.exit(bool(errors))
