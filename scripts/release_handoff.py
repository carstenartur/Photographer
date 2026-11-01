#!/usr/bin/env python3
"""Finite protected-main release handoff. No direct main update or recovery mode.

Only immutable version metadata is accepted. GitHub uncertainty is a hold, and
publication may start only after a normally merged release PR and exact-main CI.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('release_version_plan', ROOT / '.github/scripts/release-version-plan.py')
_planner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_planner)
SHA = re.compile(r'^[0-9a-f]{40}$')
REQUIRED_JOBS = {'Maven verification', 'Frontend Test, Lint & Build', 'Docker Build Smoke Test'}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def run(argv: list[str], cwd: Path = ROOT) -> str:
    result = subprocess.run(argv, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError(f'{argv[0]} failed ({result.returncode}): {result.stderr.strip()}')
    return result.stdout.strip()


@dataclass(frozen=True)
class Plan:
    source: str
    release: str
    next: str

    @classmethod
    def create(cls, source: str, project: str, revision: str, exact: str = '', increment: str = 'patch') -> Plan:
        require(bool(SHA.fullmatch(source)), 'invalid source SHA')
        require(project == revision, 'Maven project.version and revision disagree')
        require(bool(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT', project)), 'source must use X.Y.Z-SNAPSHOT')
        resolved = _planner.resolve_plan(project.removesuffix('-SNAPSHOT'), exact, increment)
        return cls(source, resolved['release'], resolved['next'])

    @property
    def branch(self) -> str:
        return f'release/prepare-{self.release}-{self.source[:12]}'

    @property
    def snapshot_branch(self) -> str:
        return f'release/prepare-next-{self.next}-{self.source[:12]}'

    @property
    def maintenance_branch(self) -> str:
        return _planner.resolve_plan(self.release, self.next)['maintenance_branch']

    def message(self, title: str) -> str:
        return f'{title} {self.release}\n\nRelease-Source: {self.source}\nRelease-Version: {self.release}\nRelease-Next: {self.next}\n'

    @classmethod
    def from_message(cls, message: str) -> Plan:
        values = {}
        for key in ('Release-Source', 'Release-Version', 'Release-Next'):
            found = re.findall(r'^'+key+r': (\S+)$', message, re.MULTILINE)
            require(len(found) == 1, f'missing or duplicate immutable {key}')
            values[key] = found[0]
        return cls.create(values['Release-Source'], values['Release-Version']+'-SNAPSHOT', values['Release-Version']+'-SNAPSHOT', values['Release-Next'])


class Git:
    def __init__(self, path: Path = ROOT):
        self.path = path

    def call(self, *args: str) -> str:
        return run(['git', *args], self.path)

    def parents(self, sha: str) -> list[str]:
        require(bool(SHA.fullmatch(sha)), 'invalid Git SHA')
        return self.call('show', '-s', '--format=%P', sha).split()

    def tree(self, sha: str) -> str:
        return self.call('rev-parse', sha+'^{tree}')

    def entries(self, sha: str) -> dict[str, tuple[str, str, str]]:
        rows = self.call('ls-tree', '-r', '-z', sha).split('\0')
        entries = {}
        for row in rows:
            if row:
                metadata, name = row.split('\t', 1)
                mode, kind, object_id = metadata.split()
                entries[name] = (mode, kind, object_id)
        return entries

    def files(self, sha: str) -> dict[str, bytes]:
        # Read only metadata contents, never large application/media blobs. The
        # complete tree entries separately prove all other objects unchanged.
        names = [name for name in self.entries(sha) if name == 'pom.xml' or name.endswith('/pom.xml')]
        return {name: subprocess.check_output(['git', 'show', f'{sha}:{name}'], cwd=self.path) for name in names}


def reactor_poms(files: dict[str, bytes]) -> dict[str, ET.Element]:
    """All root/nested declared modules, including inactive profile modules.

    Consumer fixtures and the separate Central bundle are not reactor projects.
    Module paths must resolve to an actual tracked POM inside this repository.
    """
    import posixpath
    pending, parsed = ['pom.xml'], {}
    while pending:
        name = pending.pop()
        if name in parsed:
            continue
        require(name in files, f'declared reactor POM is missing: {name}')
        root = ET.fromstring(files[name])
        parsed[name] = root
        for node in root.iter():
            if node.tag.rsplit('}', 1)[-1] == 'modules':
                for module in node:
                    if module.tag.rsplit('}', 1)[-1] != 'module':
                        continue
                    value = (module.text or '').strip()
                    require(bool(value) and '${' not in value and not value.startswith('/'), 'unsupported reactor module path')
                    path = posixpath.normpath(posixpath.join(posixpath.dirname(name), value))
                    require(path != '..' and not path.startswith('../'), 'module escapes repository')
                    pending.append(path if path.endswith('/pom.xml') else path+'/pom.xml')
    return parsed


def pom_transform(files: dict[str, bytes], old: str, new: str) -> dict[str, bytes]:
    """Exact byte transformation of project coordinates; dependencies stay frozen.

    Allow project version, revision, internal parent and literal internal dependency
    versions only. No arbitrary POM edits, formatting changes, plugins or external
    dependency changes are authorized by a version metadata PR.
    """
    parsed = reactor_poms(files)
    def local(tag):
        return tag.rsplit('}', 1)[-1]
    def child(node, name):
        return next((c for c in node if local(c.tag) == name), None)
    def text(node, name):
        c = child(node, name)
        return c.text.strip() if c is not None and c.text else ''
    coordinates = set()
    for root in parsed.values():
        parent = child(root, 'parent')
        group = text(root, 'groupId') or (text(parent, 'groupId') if parent is not None else '')
        coordinates.add((group, text(root, 'artifactId')))
    result = dict(files)
    for name, root in parsed.items():
        allowed = set()
        version = child(root, 'version')
        if version is not None:
            allowed.add(id(version))
        properties = child(root, 'properties')
        if properties is not None:
            revision = child(properties, 'revision')
            if revision is not None:
                allowed.add(id(revision))
        for node in root.iter():
            if local(node.tag) in ('parent', 'dependency') and (text(node, 'groupId'), text(node, 'artifactId')) in coordinates:
                value = child(node, 'version')
                if value is not None:
                    allowed.add(id(value))
        elements = iter(node for node in root.iter() if local(node.tag) in ('version', 'revision'))
        def replace(match):
            node = next(elements)
            if id(node) in allowed and (node.text or '').strip() == old:
                return match.group(1) + re.sub(re.escape(old), lambda _: new, match.group(2), count=1) + match.group(3)
            return match.group(0)
        source = files[name].decode('utf-8')
        # Skip comments: they can contain example version tags.
        token = re.compile(r'<!--.*?-->|(<(?:[\w.-]+:)?(?:version|revision)\b[^>]*>)([^<]*)(</(?:[\w.-]+:)?(?:version|revision)\s*>)', re.DOTALL)
        result[name] = token.sub(lambda m: m.group(0) if m.group(1) is None else replace(m), source).encode('utf-8')
    require(result != files, 'version transition changes no project metadata')
    return result


def validate_transition(git: Git, source: str, target: str, old: str, new: str) -> None:
    require(pom_transform(git.files(source), old, new) == git.files(target), 'commit is not the exact intended version-only POM transformation')
    before, after = git.entries(source), git.entries(target)
    require(before.keys() == after.keys(), 'version transition adds/removes files')
    for name, entry in before.items():
        if name == 'pom.xml' or name.endswith('/pom.xml'):
            require(entry[:2] == after[name][:2] == ('100644', 'blob'), 'POM mode or file shape changed')
        else:
            require(entry == after[name], 'version transition changes non-metadata content')


def validate_pr(git: Git, pr: dict, repository: str, main: str) -> tuple[Plan, str]:
    require(pr.get('merged') is True and pr.get('state') == 'closed' and pr.get('commits') == 1, 'release PR must be normally merged with exactly one commit')
    head, base = pr.get('head', {}), pr.get('base', {})
    require(head.get('repo', {}).get('full_name') == repository and base.get('repo', {}).get('full_name') == repository and base.get('ref') == 'main', 'foreign repository or wrong PR base')
    prepared, merged = head.get('sha', ''), pr.get('merge_commit_sha', '')
    git.parents(prepared)
    plan = Plan.from_message(git.call('show', '-s', '--format=%B', prepared))
    require(head.get('ref') == plan.branch, 'unexpected preparation branch')
    require(git.parents(prepared) == [plan.source], 'prepared commit parent does not match source')
    validate_transition(git, plan.source, prepared, plan.release+'-SNAPSHOT', plan.release)
    require(git.parents(merged) == [plan.source], 'merged release is not one linear commit directly on source')
    require(git.tree(merged) == git.tree(prepared), 'merged tree differs from prepared release')
    require(main == merged, 'main moved; hold and re-plan')
    return plan, merged


class GitHub:
    """Authenticated gh adapter. Only typed HTTP 404 is an absent resource."""
    def __init__(self, repository: str):
        require(bool(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)), 'invalid repository identity')
        self.repository = repository

    def request(self, path: str, payload: dict | None = None, absent: bool = False, paginated: bool = False):
        argv = ['gh', 'api', '--include', '-H', 'Accept: application/vnd.github+json', '-H', 'X-GitHub-Api-Version: 2022-11-28', f'repos/{self.repository}/{path}']
        if payload is not None:
            argv += ['--method', 'POST', '--input', '-']
        result = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            header, body = re.split(r'\r?\n\r?\n', result.stdout, maxsplit=1)
            status = int(re.match(r'HTTP/\S+ (\d{3})', header).group(1))
            data = json.loads(body)
        except (ValueError, AttributeError):
            raise ValueError(f'Unknown GitHub response for {path}; hold') from None
        if absent and status == 404 and isinstance(data, dict) and data.get('message') == 'Not Found':
            return None
        require(result.returncode == 0 and 200 <= status < 300, f'GitHub HTTP {status} for {path}; hold')
        if paginated:
            require(isinstance(data, list) and len(data) <= 100, f'malformed GitHub collection for {path}')
            links = re.findall(r'^link: *([^\r\n]+)', header, re.IGNORECASE | re.MULTILINE)
            require(len(links) <= 1, 'ambiguous API pagination headers')
            link = links[0] if links else ''
            relations = []
            for part in link.split(',') if link else []:
                matched = re.fullmatch(r' *<https://[^>]+>; *rel="(next|prev|first|last)" *', part)
                require(matched is not None, 'malformed release pagination Link header')
                relations.append(matched.group(1))
            require(len(set(relations)) == len(relations), 'duplicate pagination relations')
            has_next = 'next' in relations
            require(not has_next or len(data) == 100, 'truncated release collection')
            return data, has_next
        require(isinstance(data, dict), f'malformed GitHub object for {path}')
        return data

    def read(self, path: str, absent: bool = False):
        return self.request(path, absent=absent)

    def read_page(self, path: str) -> tuple[list[dict], bool]:
        return self.request(path, paginated=True)

    def write(self, path: str, payload: dict):
        return self.request(path, payload=payload)

    def command(self, argv: list[str]):
        return run(['gh', *argv, '--repo', self.repository])


def object_sha(data: dict) -> str:
    require(isinstance(data, dict) and isinstance(data.get('object'), dict), 'malformed GitHub ref')
    sha = data['object'].get('sha', '')
    require(bool(SHA.fullmatch(sha)), 'malformed GitHub ref SHA')
    return sha


def current_main(api) -> str:
    return object_sha(api.read('git/ref/heads/main'))


def paginate(api, path: str, key: str) -> list[dict]:
    items, total, page = [], None, 1
    separator = '&' if '?' in path else '?'
    while True:
        data = api.read(f'{path}{separator}per_page=100&page={page}')
        require(isinstance(data, dict) and type(data.get('total_count')) is int and isinstance(data.get(key), list), 'malformed paginated API response')
        if total is None:
            total = data['total_count']
            require(0 <= total <= 1000, 'API result exceeds complete inspection limit; hold')
        require(total == data['total_count'], 'API pagination changed during validation')
        batch = data[key]
        require(all(isinstance(item, dict) and type(item.get('id')) is int for item in batch), 'malformed API item')
        items.extend(batch)
        require(len(items) <= total and len({item['id'] for item in items}) == len(items), 'duplicate or inconsistent API pagination')
        if len(items) == total:
            return items
        require(len(batch) == 100, 'truncated API pagination')
        page += 1


def validate_ci(api, merged: str) -> None:
    workflow = api.read('actions/workflows/ci.yml')
    require(workflow.get('path') == '.github/workflows/ci.yml' and type(workflow.get('id')) is int, 'missing canonical CI workflow')
    runs = paginate(api, f'actions/workflows/{workflow["id"]}/runs?head_sha={merged}&event=push&branch=main', 'workflow_runs')
    require(bool(runs), 'missing exact-main canonical CI push run')
    for candidate in runs:
        require(candidate.get('head_sha') == merged and candidate.get('head_branch') == 'main' and candidate.get('event') == 'push' and candidate.get('path') == '.github/workflows/ci.yml', 'stale or foreign CI run')
    latest = max(runs, key=lambda item: item['id'])
    require(latest.get('status') == 'completed' and latest.get('conclusion') == 'success', 'latest canonical CI run is not successful; normal approval/checks required')
    jobs = paginate(api, f'actions/runs/{latest["id"]}/jobs?filter=latest', 'jobs')
    for name in REQUIRED_JOBS:
        matched = [job for job in jobs if job.get('name') == name]
        require(len(matched) == 1 and matched[0].get('status') == 'completed' and matched[0].get('conclusion') == 'success', f'required CI job is missing, duplicate or unsuccessful: {name}')


def fetch_commit(git: Git, sha: str) -> None:
    require(bool(SHA.fullmatch(sha)), 'invalid fetch SHA')
    try:
        git.call('cat-file', '-e', sha+'^{commit}')
    except ValueError:
        git.call('fetch', '--no-tags', 'origin', sha)


def fetch_pr_head(git: Git, number: str, sha: str) -> None:
    require(bool(SHA.fullmatch(sha)), 'invalid preparation PR head SHA')
    try:
        git.call('cat-file', '-e', sha+'^{commit}')
    except ValueError:
        # Squash/rebase main history need not contain R, and its branch may have
        # been deleted. Acquire GitHub's PR head ref, then verify immutable SHA.
        git.call('fetch', '--no-tags', 'origin', f'refs/pull/{number}/head')
        require(git.call('rev-parse', 'FETCH_HEAD') == sha, 'fetched PR head differs from immutable merged PR head')
        git.call('cat-file', '-e', sha+'^{commit}')


def require_release_absent(api, tag: str) -> None:
    require(api.read('releases/tags/'+tag, absent=True) is None, 'existing or partial publication; hold for separate diagnosis')
    # GitHub can return404 by tag for a draft release. Only a complete release
    # collection inspection, including drafts without a tag ref, closes that gap.
    seen, page = set(), 1
    while True:
        items, has_next = api.read_page(f'releases?per_page=100&page={page}')
        require(isinstance(items, list) and type(has_next) is bool and len(items) <= 100, 'malformed releases pagination')
        require(not has_next or len(items) == 100, 'truncated releases pagination')
        for release in items:
            require(isinstance(release, dict) and type(release.get('id')) is int and isinstance(release.get('tag_name'), str) and type(release.get('draft')) is bool, 'malformed release collection item')
            require(release['id'] not in seen, 'duplicate release collection item; hold')
            seen.add(release['id'])
            require(release['tag_name'] != tag, 'existing draft or published release; partial-publication hold')
        if not has_next:
            return
        require(page < 10, 'release collection exceeds complete inspection limit; hold')
        page += 1


def upload_commit(git: Git, api, sha: str) -> None:
    """Upload exactly one metadata commit, based on the complete parent tree.

    Git Database object creation is immutable. Only the final create-ref mutates
    a name; a raced creation fails rather than updating somebody else's branch.
    """
    parents = git.parents(sha)
    require(len(parents) == 1, 'handoff must have one parent')
    parent = parents[0]
    source, target = git.files(parent), git.files(sha)
    entries = []
    import base64
    for name, data in target.items():
        if source.get(name) != data:
            require(name == 'pom.xml' or name.endswith('/pom.xml'), 'only POM blobs may be uploaded')
            blob = api.write('git/blobs', {'content': base64.b64encode(data).decode('ascii'), 'encoding': 'base64'})
            expected_blob = hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
            require(blob.get('sha') == expected_blob, 'GitHub blob SHA differs from local metadata')
            entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': expected_blob})
    tree = api.write('git/trees', {'base_tree': git.tree(parent), 'tree': entries})
    require(tree.get('sha') == git.tree(sha), 'GitHub tree differs from complete local release tree; hold')
    identity = git.call('show', '-s', '--format=%an%n%ae%n%aI%n%cn%n%ce%n%cI', sha).splitlines()
    require(len(identity) == 6, 'invalid commit identity')
    message = subprocess.check_output(['git', 'show', '-s', '--format=%B', sha], cwd=git.path, text=True).rstrip('\n')+'\n'
    commit = api.write('git/commits', {'message': message, 'tree': tree['sha'], 'parents': [parent], 'author': dict(zip(('name', 'email', 'date'), identity[:3])), 'committer': dict(zip(('name', 'email', 'date'), identity[3:]))})
    require(commit.get('sha') == sha, 'GitHub commit serialization differs from exact local handoff SHA; hold')


def stage_branch(git: Git, api, plan: Plan, sha: str, dry_run: bool, snapshot: bool = False) -> str:
    parent = git.parents(sha)
    require(len(parent) == 1, 'handoff must have exactly one parent')
    old, new = (plan.release, plan.next) if snapshot else (plan.release+'-SNAPSHOT', plan.release)
    validate_transition(git, parent[0], sha, old, new)
    require(Plan.from_message(git.call('show', '-s', '--format=%B', sha)) == plan, 'local handoff plan mismatch')
    if not snapshot:
        require(parent == [plan.source], 'stable handoff source mismatch')
    branch = plan.snapshot_branch if snapshot else plan.branch
    existing = api.read('git/ref/heads/'+branch, absent=True)
    if existing is not None:
        candidate = object_sha(existing)
        fetch_commit(git, candidate)
        require(git.parents(candidate) == parent and git.tree(candidate) == git.tree(sha), 'existing handoff branch conflicts in parent or tree')
        require(Plan.from_message(git.call('show', '-s', '--format=%B', candidate)) == plan, 'existing branch immutable plan conflicts')
        validate_transition(git, parent[0], candidate, old, new)
        require(current_main(api) == parent[0], 'main moved before existing handoff reuse')
        return candidate
    if not dry_run:
        require(current_main(api) == parent[0], 'main moved before handoff staging')
        upload_commit(git, api, sha)
        require(current_main(api) == parent[0], 'main moved before ref creation')
        created = api.write('git/refs', {'ref': 'refs/heads/'+branch, 'sha': sha})
        require(created.get('ref') == 'refs/heads/'+branch and object_sha(created) == sha, 'created ref did not match intended handoff')
    return sha


def artifact_hash(path: Path) -> str:
    import zipfile
    require(path.is_file() and path.stat().st_size > 0, 'required executable app/target/focusstack.jar is missing')
    try:
        with zipfile.ZipFile(path) as jar:
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
            require('Main-Class:' in manifest and 'Start-Class:' in manifest and any(name.startswith('BOOT-INF/classes/') for name in jar.namelist()), 'artifact is not an executable Spring Boot application JAR')
    except (zipfile.BadZipFile, KeyError, UnicodeDecodeError) as error:
        raise ValueError('invalid executable application JAR') from error
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_checkout(git: Git, merged: str) -> None:
    require(git.call('rev-parse', 'HEAD') == merged, 'checkout moved away from exact merged stable source')
    require(not git.call('status', '--porcelain', '--untracked-files=no'), 'tracked release source is dirty')


def publication_guard(git: Git, api, plan: Plan, merged: str, jar: Path, digest: str) -> None:
    stable_checkout(git, merged)
    require(artifact_hash(jar) == digest, 'application artifact changed after verified build')
    require(current_main(api) == merged, 'main moved before publication')
    require(api.read('git/ref/tags/v'+plan.release, absent=True) is None, 'existing or partial publication; hold for separate diagnosis')
    require_release_absent(api, 'v'+plan.release)

class Session:
    def __init__(self, git: Git, api, control: str, ref: str, dry_run: bool = True, skip_tests: bool = False):
        self.git, self.api, self.control, self.ref = git, api, control, ref
        self.dry_run, self.skip_tests = dry_run, skip_tests
        self.jar = git.path / 'app/target/focusstack.jar'

    def check_control(self) -> None:
        require(self.ref == 'refs/heads/main', 'dispatch must select the approved main control ref')
        require(not self.skip_tests, 'release preparation and resume require tests enabled')
        require(bool(SHA.fullmatch(self.control)), 'invalid control commit')
        stable_checkout(self.git, self.control)
        require(current_main(self.api) == self.control, 'dispatch/control/main drift; hold')

    def versions(self) -> tuple[str, str]:
        return tuple(run(['mvn', 'help:evaluate', '-Dexpression='+expression, '-q', '-DforceStdout'], self.git.path) for expression in ('project.version', 'revision'))

    def set_version(self, version: str) -> None:
        # Apply the same exact transformation that provenance validation expects.
        # Include inactive profile modules deterministically, preserving consumer
        # fixtures instead of relying on Maven Versions plugin module discovery.
        project, revision = self.versions()
        require(project == revision, 'Maven versions disagree before transition')
        source = self.git.files(self.git.call('rev-parse', 'HEAD'))
        for name, data in pom_transform(source, project, version).items():
            if data != source[name]:
                (self.git.path / name).write_bytes(data)
        require(self.versions() == (version, version), 'Maven project.version/revision do not match planned transition')

    def maven(self, args: list[str]) -> None:
        result = subprocess.run(['mvn', '-B', *args], cwd=self.git.path)
        require(result.returncode == 0, 'Maven release verification failed')

    def build(self) -> None:
        # Full default reactor, including the original release verification. No
        # -pl shortcut or tests-skipped publication path is supported.
        files = self.git.files(self.git.call('rev-parse', 'HEAD'))
        for path in reactor_poms(files):
            require(b'-SNAPSHOT' not in files[path], f'release reactor POM still contains SNAPSHOT: {path}')
        self.maven(['clean', 'verify'])

    def commit(self, plan: Plan, title: str) -> str:
        names = [name for name in self.git.files(self.git.call('rev-parse', 'HEAD')) if name == 'pom.xml' or name.endswith('/pom.xml')]
        self.git.call('add', '--', *names)
        self.git.call('-c', 'commit.gpgsign=false', '-c', 'user.name=github-actions[bot]', '-c', 'user.email=github-actions[bot]@users.noreply.github.com', 'commit', '-m', plan.message(title))
        return self.git.call('rev-parse', 'HEAD')

    def result(self, plan: Plan, phase: str, prepared: str, **extra) -> dict[str, str]:
        branch = plan.snapshot_branch if phase == 'snapshot' else plan.branch
        repository = self.api.repository
        return {'phase': phase, 'source': plan.source, 'prepared': prepared, 'merged': '', 'control': self.control, 'release': plan.release, 'next': plan.next, 'maintenance_branch': plan.maintenance_branch, 'branch': branch, 'compare_url': f'https://github.com/{repository}/compare/main...{branch}?expand=1', 'verified_resume': 'false', **extra}

    def prepare(self, exact: str, increment: str) -> dict[str, str]:
        self.check_control()
        project, revision = self.versions()
        plan = Plan.create(self.control, project, revision, exact, increment)
        self.set_version(plan.release)
        prepared = self.commit(plan, 'Release version')
        validate_transition(self.git, plan.source, prepared, project, plan.release)
        self.build()
        stable_checkout(self.git, prepared)
        # Prove the successor locally now, restore stable source before staging.
        self.set_version(plan.next)
        successor = self.commit(plan, 'Validate next development iteration')
        validate_transition(self.git, prepared, successor, plan.release, plan.next)
        self.git.call('checkout', '--detach', prepared)
        require(current_main(self.api) == plan.source, 'main moved during preparation')
        staged = stage_branch(self.git, self.api, plan, prepared, self.dry_run)
        return self.result(plan, 'prepare', staged)

    def resume(self, number: str) -> dict[str, str]:
        self.check_control()
        require(bool(re.fullmatch(r'[1-9][0-9]*', number)), 'resume_release_pr must be a positive PR number')
        pr = self.api.read('pulls/'+number)
        require(pr.get('number') == int(number), 'PR number mismatch')
        prepared, merged = pr.get('head', {}).get('sha', ''), pr.get('merge_commit_sha', '')
        fetch_pr_head(self.git, number, prepared)
        plan = Plan.from_message(self.git.call('show', '-s', '--format=%B', prepared))
        fetch_commit(self.git, plan.source)
        fetch_commit(self.git, merged)
        repository = self.api.repository
        plan, merged = validate_pr(self.git, pr, repository, current_main(self.api))
        require(merged == self.control, 'stable artifact source differs from approved control commit')
        validate_ci(self.api, merged)
        self.git.call('checkout', '--detach', merged)
        require(self.versions() == (plan.release, plan.release), 'merged Maven project.version/revision mismatch')
        self.build()
        stable_checkout(self.git, merged)
        digest = artifact_hash(self.jar)
        # A dry resume validates/builds but can never authorize any publication.
        return self.result(plan, 'resume', prepared, merged=merged, artifact_sha256=digest, verified_resume='false' if self.dry_run else 'true')

    def validated_state(self, state: dict[str, str]) -> tuple[Plan, str]:
        require(not self.dry_run and not self.skip_tests and self.ref == 'refs/heads/main', 'publication requires live tests-enabled main control')
        require(state.get('phase') == 'resume' and state.get('verified_resume') == 'true', 'publication requires a verified explicit resume')
        require(state.get('control') == self.control == state.get('merged'), 'publication control/source mismatch')
        plan = Plan.create(state['source'], state['release']+'-SNAPSHOT', state['release']+'-SNAPSHOT', state['next'])
        stable_checkout(self.git, state['merged'])
        require(current_main(self.api) == state['merged'], 'main moved before publication side effect')
        require(artifact_hash(self.jar) == state.get('artifact_sha256'), 'verified executable JAR changed')
        return plan, state['merged']

    def publish(self, state: dict[str, str]) -> None:
        plan, merged = self.validated_state(state)
        publication_guard(self.git, self.api, plan, merged, self.jar, state['artifact_sha256'])
        maintenance = self.api.read('git/ref/heads/'+plan.maintenance_branch, absent=True)
        state['maintenance_sha'] = object_sha(maintenance) if maintenance is not None else 'absent'
        self.validated_state(state)
        tag = self.api.write('git/tags', {'tag': 'v'+plan.release, 'message': 'Release '+plan.release, 'object': merged, 'type': 'commit'})
        require(bool(SHA.fullmatch(tag.get('sha', ''))), 'invalid annotated tag SHA')
        # Objects are immutable, but refs and release assets are never rewritten.
        self.validated_state(state)
        require(self.api.read('git/ref/tags/v'+plan.release, absent=True) is None, 'tag appeared during publication; hold')
        require_release_absent(self.api, 'v'+plan.release)
        created = self.api.write('git/refs', {'ref': 'refs/tags/v'+plan.release, 'sha': tag['sha']})
        require(object_sha(created) == tag['sha'], 'tag ref mismatch; partial-publication hold')
        state['publication_tag'] = tag['sha']
        self.validated_state(state)
        self.check_published_tag(state, plan, merged)
        require_release_absent(self.api, 'v'+plan.release)
        self.api.command(['release', 'create', 'v'+plan.release, str(self.jar), '--verify-tag', '--title', 'Release '+plan.release, '--generate-notes'])
        release = self.api.read('releases/tags/v'+plan.release)
        require(type(release.get('id')) is int and release.get('tag_name') == 'v'+plan.release and release.get('draft') is False, 'release creation could not be verified; partial-publication hold')
        state['publication_release'] = str(release['id'])

    def check_published_tag(self, state: dict[str, str], plan: Plan, merged: str) -> None:
        tag_sha = state.get('publication_tag', '')
        require(bool(SHA.fullmatch(tag_sha)) and object_sha(self.api.read('git/ref/tags/v'+plan.release)) == tag_sha, 'release tag changed or is unverified')
        tag = self.api.read('git/tags/'+tag_sha)
        target = tag.get('object', {})
        require(isinstance(target, dict) and target.get('sha') == merged and target.get('type') == 'commit', 'annotated tag does not target exact stable source')

    def image_guard(self, state: dict[str, str]) -> None:
        plan, merged = self.validated_state(state)
        self.check_published_tag(state, plan, merged)
        release = self.api.read('releases/tags/v'+plan.release)
        require(str(release.get('id')) == state.get('publication_release') and release.get('tag_name') == 'v'+plan.release and release.get('draft') is False, 'release changed before image publication')

    def finish(self, state: dict[str, str]) -> dict[str, str]:
        self.image_guard(state)
        plan, merged = self.validated_state(state)
        maintenance = self.api.read('git/ref/heads/'+plan.maintenance_branch, absent=True)
        if maintenance is not None:
            expected = state.get('maintenance_sha')
            require(object_sha(maintenance) == expected or (expected == 'absent' and object_sha(maintenance) == merged), 'maintenance ref changed since prepublication inspection; never repoint')
        else:
            require(state.get('maintenance_sha') == 'absent', 'previously existing maintenance branch disappeared; hold')
            self.validated_state(state)
            created = self.api.write('git/refs', {'ref': 'refs/heads/'+plan.maintenance_branch, 'sha': merged})
            require(object_sha(created) == merged, 'maintenance creation mismatch')
        self.set_version(plan.next)
        successor = self.commit(plan, 'Prepare for next development iteration')
        validate_transition(self.git, merged, successor, plan.release, plan.next)
        self.git.call('checkout', '--detach', merged)
        staged = stage_branch(self.git, self.api, plan, successor, False, snapshot=True)
        return self.result(plan, 'snapshot', state['prepared'], merged=merged, successor=staged, artifact_sha256=state['artifact_sha256'])


def emit(result: dict[str, str]) -> None:
    for key, value in result.items():
        require('\n' not in value and '\r' not in value, 'unsafe multiline workflow output')
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a', encoding='utf-8') as handle:
            for key, value in result.items():
                print(f'{key}={value}', file=handle)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as handle:
            print('\n## Protected release handoff\n', file=handle)
            for key, value in result.items():
                print(f'- {key}: `{value}`', file=handle)
            if result['phase'] in ('prepare', 'snapshot'):
                print('\nOpen the printed comparison through the ordinary authorized GitHub PR flow. Merge only after real checks/review. Preparation is not publication.', file=handle)
    print(json.dumps(result, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'resume', 'publish', 'image-guard', 'finish'), help='prepare stages stable metadata; resume validates merged PR/build; publish requires saved verified resume; image-guard rechecks exact stable source; finish stages next snapshot')
    parser.add_argument('--resume-release-pr', default='', help='positive number of the normally merged stable preparation PR')
    parser.add_argument('--next-development-version', default='', help='prepare only: validated exact X.Y.Z-SNAPSHOT successor')
    parser.add_argument('--increment', choices=('patch', 'minor', 'major'), default='patch', help='prepare only: conventional successor increment')
    parser.add_argument('--dry-run', choices=('true', 'false'), default='true', help='true performs no remote writes and never authorizes publication')
    parser.add_argument('--skip-tests', choices=('true', 'false'), default='false', help='retained input; true is rejected for this qualified handoff')
    args = parser.parse_args()
    try:
        git = Git()
        api = GitHub(os.environ.get('GITHUB_REPOSITORY', ''))
        session = Session(git, api, os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_REF', ''), args.dry_run == 'true', args.skip_tests == 'true')
        state_path = Path(git.call('rev-parse', '--absolute-git-dir')) / 'release-handoff.json'
        if args.command == 'prepare':
            require(not args.resume_release_pr, 'prepare cannot accept a resume PR')
            state = session.prepare(args.next_development_version, args.increment)
        elif args.command == 'resume':
            state = session.resume(args.resume_release_pr)
            require(not args.next_development_version or args.next_development_version == state['next'], 'resume next version conflicts with immutable prepared plan')
        else:
            require(state_path.is_file(), 'no verified resume state in this checkout')
            state = json.loads(state_path.read_text(encoding='utf-8'))
            if args.command == 'publish':
                session.publish(state)
            elif args.command == 'image-guard':
                session.image_guard(state)
            else:
                state = session.finish(state)
        state_path.write_text(json.dumps(state), encoding='utf-8')
        emit(state)
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError) as error:
        print(f'::error::{error}. Stop; publication recovery requires separate diagnosis.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
