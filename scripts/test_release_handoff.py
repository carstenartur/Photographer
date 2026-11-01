#!/usr/bin/env python3
"""Offline release handoff contract tests; real Git objects, no network."""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('release_handoff', Path(__file__).with_name('release_handoff.py'))
if not SPEC.origin or not Path(SPEC.origin).exists():
    raise SystemExit('FAIL: repository-owned release_handoff.py has not been implemented')
h = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = h
SPEC.loader.exec_module(h)

POM = '''<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>root</artifactId><version>0.1.5-SNAPSHOT</version><properties><revision>0.1.5-SNAPSHOT</revision></properties><dependencies><dependency><groupId>other</groupId><artifactId>lib</artifactId><version>0.1.5-SNAPSHOT</version></dependency></dependencies></project>'''

class RepoCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        (self.path/'pom.xml').write_text(POM)
        (self.path/'source.txt').write_text('source\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'source')
        self.s = self.git('rev-parse', 'HEAD')
        self.repo = h.Git(self.path)
        self.plan = h.Plan.create(self.s, '0.1.5-SNAPSHOT', '0.1.5-SNAPSHOT')
        (self.path/'pom.xml').write_text(POM.replace('<version>0.1.5-SNAPSHOT</version><properties>', '<version>0.1.5</version><properties>').replace('<revision>0.1.5-SNAPSHOT</revision>', '<revision>0.1.5</revision>'))
        self.git('add', '.')
        self.git('commit', '-qm', self.plan.message('Release'))
        self.r = self.git('rev-parse', 'HEAD')
        # A squash/rebase may preserve the tree while changing commit identity.
        self.m = self.git('commit-tree', self.git('rev-parse', 'HEAD^{tree}'), '-p', self.s, '-m', 'normal squash')
        self.pr = {'number': 9, 'state': 'closed', 'merged': True, 'commits': 1,
                   'merge_commit_sha': self.m, 'head': {'sha': self.r, 'ref': self.plan.branch,
                   'repo': {'full_name': 'owner/repo'}}, 'base': {'ref': 'main', 'repo': {'full_name': 'owner/repo'}}}

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.path), *args], text=True).strip()

class ProvenanceTests(RepoCase):
    def test_plan_and_overrides(self):
        self.assertEqual((self.plan.release, self.plan.next), ('0.1.5', '0.1.6-SNAPSHOT'))
        self.assertEqual(h.Plan.create(self.s, '0.1.5-SNAPSHOT', '0.1.5-SNAPSHOT', '0.2.0-SNAPSHOT').next, '0.2.0-SNAPSHOT')
        for version in ('0.1.5-SNAPSHOT', '0.1.4-SNAPSHOT', 'banana'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                h.Plan.create(self.s, '0.1.5-SNAPSHOT', '0.1.5-SNAPSHOT', version)
        with self.assertRaises(ValueError):
            h.Plan.create(self.s, '0.1.5-SNAPSHOT', '0.1.4-SNAPSHOT')

    def test_valid_squash_and_rebase_map_by_tree(self):
        for merged in (self.r, self.m):
            pr = copy.deepcopy(self.pr)
            pr['merge_commit_sha'] = merged
            plan, source = h.validate_pr(self.repo, pr, 'owner/repo', merged)
            self.assertEqual(plan, self.plan)
            self.assertEqual(source, merged)

    def test_bad_pr_shapes_fail(self):
        mutations = [lambda p: p.update(merged=False), lambda p: p.update(state='open'),
                     lambda p: p.update(commits=2), lambda p: p['head']['repo'].update(full_name='foreign/repo'),
                     lambda p: p['base'].update(ref='other'), lambda p: p['head'].update(ref='other'),
                     lambda p: p['head'].update(sha=self.s), lambda p: p.update(merge_commit_sha=self.s)]
        for mutate in mutations:
            pr = copy.deepcopy(self.pr)
            mutate(pr)
            with self.subTest(pr=pr), self.assertRaises(ValueError):
                h.validate_pr(self.repo, pr, 'owner/repo', self.m)
        with self.assertRaises(ValueError):
            h.validate_pr(self.repo, self.pr, 'owner/repo', self.s)

    def test_unrelated_pom_edit_and_source_edit_fail(self):
        for path, value in [('pom.xml', (self.path/'pom.xml').read_text().replace('<artifactId>lib</artifactId>', '<artifactId>changed</artifactId>')), ('source.txt', 'changed')]:
            self.git('checkout', '-q', self.r)
            (self.path/path).write_text(value)
            self.git('add', '.')
            self.git('commit', '-qm', self.plan.message('Release'))
            with self.subTest(path=path), self.assertRaises(ValueError):
                h.validate_transition(self.repo, self.s, self.git('rev-parse', 'HEAD'), '0.1.5-SNAPSHOT', '0.1.5')

    def test_merge_with_extra_parent_or_wrong_tree_fails(self):
        wrong = self.git('commit-tree', self.git('rev-parse', self.s+'^{tree}'), '-p', self.s, '-m', 'wrong')
        nonlinear = self.git('commit-tree', self.git('rev-parse', self.r+'^{tree}'), '-p', self.s, '-p', self.r, '-m', 'merge')
        for sha in (wrong, nonlinear):
            pr = copy.deepcopy(self.pr)
            pr['merge_commit_sha'] = sha
            with self.assertRaises(ValueError):
                h.validate_pr(self.repo, pr, 'owner/repo', sha)

    def test_trailers_are_complete_unique_and_validated(self):
        self.assertEqual(h.Plan.from_message(self.plan.message('Release')), self.plan)
        for message in ('release', self.plan.message('Release')+'\nRelease-Next: 9.0.0-SNAPSHOT', self.plan.message('Release').replace(self.s, 'bad')):
            with self.assertRaises(ValueError):
                h.Plan.from_message(message)


MISSING = object()


class FakeAPI:
    repository = 'owner/repo'

    def __init__(self, values=None):
        self.values = {'releases?per_page=100&page=1': {'items': [], 'next': False}, **(values or {})}
        self.writes = []
    def read(self, path, absent=False):
        value = self.values.get(path, MISSING)
        if isinstance(value, Exception):
            raise value
        if value is MISSING:
            if absent:
                return None
            raise ValueError('unmocked or missing API response: '+path)
        return copy.deepcopy(value)
    def read_page(self, path):
        value = self.read(path)
        return value['items'], value['next']

    def write(self, path, payload):
        self.writes.append((path, payload))
        value = self.values.get('POST '+path)
        if callable(value):
            return value(payload)
        if value is None:
            raise ValueError('unmocked write: '+path)
        return copy.deepcopy(value)
    def command(self, argv):
        self.writes.append(('command', argv))

class StateFixture(RepoCase):
    def main_api(self):
        return FakeAPI({'git/ref/heads/main': {'object': {'sha': self.m}}})

    def ci_api(self):
        api = self.main_api()
        api.values['actions/workflows/ci.yml'] = {'id': 5, 'path': '.github/workflows/ci.yml'}
        self.run = {'id': 8, 'head_sha': self.m, 'head_branch': 'main', 'event': 'push', 'path': '.github/workflows/ci.yml', 'status': 'completed', 'conclusion': 'success'}
        api.values[f'actions/workflows/5/runs?head_sha={self.m}&event=push&branch=main&per_page=100&page=1'] = {'total_count': 1, 'workflow_runs': [self.run]}
        self.jobs = [{'id': i, 'name': name, 'status': 'completed', 'conclusion': 'success'} for i, name in enumerate(h.REQUIRED_JOBS, 1)]
        api.values['actions/runs/8/jobs?filter=latest&per_page=100&page=1'] = {'total_count': 4, 'jobs': self.jobs+[{'id': 99, 'name': 'Scheduled Maven Site & Badges', 'status': 'completed', 'conclusion': 'skipped'}]}
        return api

    def artifact(self):
        import zipfile
        path = self.path/'app/target/focusstack.jar'
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: example.Application\n')
            jar.writestr('BOOT-INF/classes/example/Application.class', b'compiled')
        return path


class StateTests(StateFixture):
    def test_ci_accepts_only_complete_successful_canonical_push(self):
        api = self.ci_api()
        h.validate_ci(api, self.m)
        for status in ('queued', 'in_progress', 'action_required', 'cancelled', 'skipped', 'failure'):
            bad = self.ci_api()
            key = 'actions/runs/8/jobs?filter=latest&per_page=100&page=1'
            bad.values[key]['jobs'][0]['conclusion'] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                h.validate_ci(bad, self.m)
        for field, value in [('head_sha', self.s), ('event', 'pull_request'), ('status', 'in_progress'), ('conclusion', 'cancelled')]:
            bad = self.ci_api()
            key = f'actions/workflows/5/runs?head_sha={self.m}&event=push&branch=main&per_page=100&page=1'
            bad.values[key]['workflow_runs'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                h.validate_ci(bad, self.m)

    def test_ci_missing_duplicate_newer_failed_and_truncation_hold(self):
        for variant in ('missing', 'duplicate', 'truncated', 'error', 'newer_failed'):
            api = self.ci_api()
            key = 'actions/runs/8/jobs?filter=latest&per_page=100&page=1'
            if variant == 'missing':
                api.values[key]['jobs'] = []
                api.values[key]['total_count'] = 0
            elif variant == 'duplicate':
                api.values[key]['jobs'][1] = dict(api.values[key]['jobs'][0], id=100)
            elif variant == 'truncated':
                api.values[key]['total_count'] = 200
            elif variant == 'error':
                api.values[key] = ValueError('HTTP 403')
            else:
                key = f'actions/workflows/5/runs?head_sha={self.m}&event=push&branch=main&per_page=100&page=1'
                api.values[key]['workflow_runs'].append(dict(self.run, id=9, conclusion='cancelled'))
                api.values[key]['total_count'] = 2
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                h.validate_ci(api, self.m)

    def test_pagination_reads_all_and_rejects_bad_counts(self):
        api = FakeAPI({'items?per_page=100&page=1': {'total_count': 101, 'jobs': [{'id': i} for i in range(100)]}, 'items?per_page=100&page=2': {'total_count': 101, 'jobs': [{'id': 100}]}})
        self.assertEqual(len(h.paginate(api, 'items', 'jobs')), 101)
        api.values['items?per_page=100&page=2'] = {'total_count': 101, 'jobs': []}
        with self.assertRaises(ValueError):
            h.paginate(api, 'items', 'jobs')

    def test_existing_branch_reused_by_parent_tree_and_plan(self):
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        api.values['git/ref/heads/'+self.plan.branch] = {'object': {'sha': self.r}}
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, self.r, False), self.r)
        self.assertEqual(api.writes, [])
        # Different identity with same immutable plan, parent and tree qualifies.
        equivalent = self.git('commit-tree', self.git('rev-parse', self.r+'^{tree}'), '-p', self.s, '-m', self.plan.message('Equivalent identity'))
        api.values['git/ref/heads/'+self.plan.branch]['object']['sha'] = equivalent
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, self.r, False), equivalent)
        for sha in (self.s, self.m):
            api.values['git/ref/heads/'+self.plan.branch]['object']['sha'] = sha
            with self.assertRaises(ValueError):
                h.stage_branch(self.repo, api, self.plan, self.r, False)
        self.assertEqual(api.writes, [])

    def test_existing_handoff_plan_parent_mismatch_and_main_drift_hold(self):
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        other_plan = h.Plan.create(self.s, '0.1.5-SNAPSHOT', '0.1.5-SNAPSHOT', '0.1.7-SNAPSHOT')
        tree = self.repo.tree(self.r)
        wrong_plan = self.git('commit-tree', tree, '-p', self.s, '-m', other_plan.message('Release'))
        wrong_parent = self.git('commit-tree', tree, '-p', self.r, '-m', self.plan.message('Release'))
        for candidate in (wrong_plan, wrong_parent):
            api.values['git/ref/heads/'+self.plan.branch] = {'object': {'sha': candidate}}
            with self.assertRaises(ValueError):
                h.stage_branch(self.repo, api, self.plan, self.r, False)
        api.values['git/ref/heads/'+self.plan.branch] = {'object': {'sha': self.r}}
        api.values['git/ref/heads/main']['object']['sha'] = self.m
        with self.assertRaises(ValueError):
            h.stage_branch(self.repo, api, self.plan, self.r, False)
        self.assertEqual(api.writes, [])

    def test_git_database_payload_reconstructs_exact_full_tree_and_commit(self):
        import base64
        import os
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        def blob(payload):
            sha = subprocess.check_output(['git', 'hash-object', '-w', '--stdin'], cwd=self.path, input=base64.b64decode(payload['content'])).decode().strip()
            return {'sha': sha}
        def tree(payload):
            self.assertEqual(payload['base_tree'], self.repo.tree(self.s))
            env = dict(os.environ, GIT_INDEX_FILE=str(self.path/'temporary-index'))
            subprocess.check_call(['git', 'read-tree', payload['base_tree']], cwd=self.path, env=env)
            for entry in payload['tree']:
                subprocess.check_call(['git', 'update-index', '--add', '--cacheinfo', entry['mode'], entry['sha'], entry['path']], cwd=self.path, env=env)
            sha = subprocess.check_output(['git', 'write-tree'], cwd=self.path, env=env).decode().strip()
            # Full source content outside POMs survives base-tree preservation.
            self.assertEqual(subprocess.check_output(['git', 'show', sha+':source.txt'], cwd=self.path), b'source\n')
            return {'sha': sha}
        def commit(payload):
            env = dict(os.environ)
            for role in ('author', 'committer'):
                for key in ('name', 'email', 'date'):
                    env['GIT_'+role.upper()+'_'+key.upper()] = payload[role][key]
            sha = subprocess.check_output(['git', 'commit-tree', payload['tree'], '-p', payload['parents'][0]], input=payload['message'].encode(), cwd=self.path, env=env).decode().strip()
            return {'sha': sha}
        api.values.update({'POST git/blobs': blob, 'POST git/trees': tree, 'POST git/commits': commit,
                           'POST git/refs': lambda payload: {'ref': payload['ref'], 'object': {'sha': payload['sha']}}})
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, self.r, False), self.r)

    def test_serialization_mismatch_or_ref_creation_race_never_updates_ref(self):
        for mismatch in ('tree', 'commit', 'race'):
            api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}},
                           'POST git/blobs': {'sha': self.git('rev-parse', self.r+':pom.xml')},
                           'POST git/trees': {'sha': self.repo.tree(self.r)},
                           'POST git/commits': {'sha': self.r}})
            if mismatch == 'tree':
                api.values['POST git/trees'] = {'sha': 'f'*40}
            elif mismatch == 'commit':
                api.values['POST git/commits'] = {'sha': 'f'*40}
            else:
                def conflict(payload):
                    raise ValueError('HTTP 422: ref already exists')
                api.values['POST git/refs'] = conflict
            with self.subTest(mismatch=mismatch), self.assertRaises(ValueError):
                h.stage_branch(self.repo, api, self.plan, self.r, False)
            paths = [path for path, _ in api.writes]
            self.assertTrue(all(path in ('git/blobs', 'git/trees', 'git/commits', 'git/refs') for path in paths))
            self.assertEqual(paths.count('git/refs'), 1 if mismatch == 'race' else 0)

    def test_branch_dry_run_and_conflict_have_no_writes(self):
        api = self.main_api()
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, self.r, True), self.r)
        self.assertEqual(api.writes, [])
        api.values['git/ref/heads/'+self.plan.branch] = ValueError('HTTP 500')
        with self.assertRaises(ValueError):
            h.stage_branch(self.repo, api, self.plan, self.r, False)
        self.assertEqual(api.writes, [])

    def test_new_branch_uses_creation_only_after_main_check(self):
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        api.values['POST git/blobs'] = {'sha': self.git('rev-parse', self.r+':pom.xml')}
        api.values['POST git/trees'] = {'sha': self.repo.tree(self.r)}
        api.values['POST git/commits'] = {'sha': self.r}
        api.values['POST git/refs'] = {'ref': 'refs/heads/'+self.plan.branch, 'object': {'sha': self.r}}
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, self.r, False), self.r)
        self.assertEqual(api.writes[-1][1], {'ref': 'refs/heads/'+self.plan.branch, 'sha': self.r})
        self.assertFalse(any('tags' in path or 'releases' in path for path, _ in api.writes))
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.m}}})
        with self.assertRaises(ValueError):
            h.stage_branch(self.repo, api, self.plan, self.r, False)
        self.assertEqual(api.writes, [])

    def test_publication_guard_requires_main_artifact_and_explicit_absence(self):
        self.git('checkout', '-q', self.m)
        api = self.main_api()
        jar = self.artifact()
        digest = h.artifact_hash(jar)
        h.publication_guard(self.repo, api, self.plan, self.m, jar, digest)
        self.assertEqual(api.writes, [])
        for path, value in [('git/ref/tags/v0.1.5', {'object': {'sha': self.r}}), ('releases/tags/v0.1.5', {'id': 1}), ('git/ref/tags/v0.1.5', ValueError('HTTP 401')), ('releases/tags/v0.1.5', ValueError('HTTP 500')), ('git/ref/tags/v0.1.5', {})]:
            api = self.main_api()
            api.values[path] = value
            with self.subTest(path=path, value=value), self.assertRaises(ValueError):
                h.publication_guard(self.repo, api, self.plan, self.m, jar, digest)
            self.assertEqual(api.writes, [])
        api = self.main_api()
        api.values['git/ref/heads/main']['object']['sha'] = self.s
        with self.assertRaises(ValueError):
            h.publication_guard(self.repo, api, self.plan, self.m, jar, digest)
        jar.unlink()
        with self.assertRaises(ValueError):
            h.artifact_hash(jar)

    def test_hidden_draft_and_collection_uncertainty_block_fresh_publication(self):
        self.git('checkout', '-q', self.m)
        jar = self.artifact()
        digest = h.artifact_hash(jar)
        for value in ({'items': [{'id': 17, 'tag_name': 'v0.1.5', 'draft': True}], 'next': False},
                      ValueError('HTTP 403 releases collection'),
                      {'items': [{'id': 17}], 'next': False}):
            api = self.main_api()
            # Tag lookup and release-by-tag both explicitly404, but the
            # collection may contain a draft without an existing tag ref.
            api.values['releases?per_page=100&page=1'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                h.publication_guard(self.repo, api, self.plan, self.m, jar, digest)
            self.assertEqual(api.writes, [])

    def test_release_collection_pagination_rejects_later_draft_and_duplicates(self):
        api = self.main_api()
        api.values['releases?per_page=100&page=1'] = {'items': [{'id': i, 'tag_name': f'v9.0.{i}', 'draft': False} for i in range(100)], 'next': True}
        api.values['releases?per_page=100&page=2'] = {'items': [{'id': 100, 'tag_name': 'v0.1.5', 'draft': True}], 'next': False}
        with self.assertRaises(ValueError):
            h.require_release_absent(api, 'v0.1.5')
        api.values['releases?per_page=100&page=2'] = {'items': [{'id': 100, 'tag_name': 'v9.1.0', 'draft': False}], 'next': False}
        h.require_release_absent(api, 'v0.1.5')
        api.values['releases?per_page=100&page=2']['items'][0]['id'] = 0
        with self.assertRaises(ValueError):
            h.require_release_absent(api, 'v0.1.5')

    def test_api_only_typed_404_is_absence(self):
        from unittest.mock import patch
        for code in (200, 401, 403, 404, 500):
            payload = {'message': 'Not Found'} if code == 404 else {'message': 'error'}
            result = subprocess.CompletedProcess([], 0 if code == 200 else 1, f'HTTP/2.0 {code} Status\r\nContent-Type: application/json\r\n\r\n'+__import__('json').dumps(payload), '')
            with patch.object(h.subprocess, 'run', return_value=result):
                api = h.GitHub('owner/repo')
                if code == 404:
                    self.assertIsNone(api.read('git/ref/tags/v0.1.5', absent=True))
                elif code == 200:
                    self.assertEqual(api.read('example'), payload)
                else:
                    with self.assertRaises(ValueError):
                        api.read('example', absent=True)
        for output in ('', 'HTTP/2.0 404 Status\r\n\r\nnot-json', 'HTTP/2.0 404 Status\r\n\r\n{}'):
            with patch.object(h.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, output, '')):
                with self.assertRaises(ValueError):
                    h.GitHub('owner/repo').read('example', absent=True)


    def test_release_collection_adapter_follows_link_header_and_fails_closed(self):
        from unittest.mock import patch
        import json
        rows = [{'id': i, 'tag_name': f'v9.0.{i}', 'draft': False} for i in range(100)]
        cases = [
            ('HTTP/2.0 200 OK\r\nLink: <https://api.github.com/repos/owner/repo/releases?per_page=100&page=2>; rel="next"\r\n\r\n'+json.dumps(rows), 0, True),
            ('HTTP/2.0 200 OK\r\n\r\n[]', 0, False),
        ]
        for stdout, code, more in cases:
            with patch.object(h.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, stdout, '')):
                data, has_next = h.GitHub('owner/repo').read_page('releases?per_page=100&page=1')
                self.assertEqual(has_next, more)
                self.assertEqual(len(data), 100 if more else 0)
        for stdout, code in [
            ('HTTP/2.0 200 OK\r\n\r\n{}', 0),
            ('HTTP/2.0 200 OK\r\nLink: garbage\r\n\r\n[]', 0),
            ('HTTP/2.0 200 OK\r\nLink: <https://api.github.com/page=2>; rel="next"\r\n\r\n[]', 0),
            ('HTTP/2.0 503 Error\r\n\r\n{"message":"Server Error"}', 1),
        ]:
            with patch.object(h.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, stdout, '')):
                with self.assertRaises(ValueError):
                    h.GitHub('owner/repo').read_page('releases?per_page=100&page=1')

class OrchestrationTests(StateFixture):
    def session(self, api, dry_run=False, skip_tests=False):
        self.git('checkout', '-q', self.m)
        return h.Session(self.repo, api, self.m, 'refs/heads/main', dry_run, skip_tests)

    def test_wrong_control_and_skipped_tests_fail_before_writes(self):
        for control, ref, skip in [(self.s, 'refs/heads/main', False), (self.m, 'refs/heads/other', False), (self.m, 'refs/heads/main', True)]:
            api = self.main_api()
            self.git('checkout', '-q', self.m)
            with self.assertRaises(ValueError):
                h.Session(self.repo, api, control, ref, False, skip).check_control()
            self.assertEqual(api.writes, [])

    def test_resume_builds_exact_m_and_dry_run_cannot_publish(self):
        api = self.ci_api()
        api.values['pulls/9'] = self.pr
        session = self.session(api, dry_run=True)
        calls = []
        session.build = lambda: calls.append(self.git('rev-parse', 'HEAD'))
        session.versions = lambda: ('0.1.5', '0.1.5')
        jar = self.artifact()
        result = session.resume('9')
        self.assertEqual(calls, [self.m])
        self.assertEqual(result['merged'], self.m)
        self.assertEqual(result['verified_resume'], 'false')
        self.assertEqual(result['artifact_sha256'], h.artifact_hash(jar))
        self.assertEqual(api.writes, [])
        with self.assertRaises(ValueError):
            session.publish(result)
        self.assertEqual(api.writes, [])

    def test_live_resume_authorizes_only_after_exact_m_build_and_artifact(self):
        api = self.ci_api()
        api.values['pulls/9'] = self.pr
        session = self.session(api)
        calls = []
        session.build = lambda: calls.append(self.git('rev-parse', 'HEAD'))
        session.versions = lambda: ('0.1.5', '0.1.5')
        self.artifact()
        result = session.resume('9')
        self.assertEqual(calls, [self.m])
        self.assertEqual(result['verified_resume'], 'true')
        self.assertEqual(result['control'], self.m)
        self.assertEqual(result['merged'], self.m)
        self.assertEqual(api.writes, [])

    def test_resume_fetches_original_pr_head_absent_from_squashed_main_clone(self):
        bare = self.path/'origin.git'
        clone = self.path/'fresh-main'
        subprocess.check_call(['git', 'init', '--bare', '-q', str(bare)])
        self.git('push', '-q', str(bare), self.m+':refs/heads/main', self.r+':refs/pull/9/head')
        subprocess.check_call(['git', 'clone', '-q', '--no-local', '--single-branch', '--branch', 'main', str(bare), str(clone)])
        absent = subprocess.run(['git', 'cat-file', '-e', self.r+'^{commit}'], cwd=clone, stderr=subprocess.PIPE)
        self.assertNotEqual(absent.returncode, 0)
        api = self.ci_api()
        api.values['pulls/9'] = self.pr
        session = h.Session(h.Git(clone), api, self.m, 'refs/heads/main', True, False)
        commands = []
        git_call = session.git.call
        def record_call(*args):
            commands.append(args)
            return git_call(*args)
        session.git.call = record_call
        session.versions = lambda: ('0.1.5', '0.1.5')
        def build():
            source = self.artifact()
            target = clone/'app/target/focusstack.jar'
            target.parent.mkdir(parents=True)
            target.write_bytes(source.read_bytes())
        session.build = build
        state = session.resume('9')
        self.assertEqual(state['prepared'], self.r)
        self.assertEqual(state['merged'], self.m)
        # Explicit hidden PR ref acquisition is independent of SHA fetch policy.
        self.assertIn(('fetch', '--no-tags', 'origin', 'refs/pull/9/head'), commands)
        self.assertEqual(subprocess.check_output(['git', 'rev-parse', 'FETCH_HEAD'], cwd=clone, text=True).strip(), self.r)
        self.assertEqual(api.writes, [])

    def test_resume_missing_jar_and_bad_pr_input_fail(self):
        api = self.ci_api()
        api.values['pulls/9'] = self.pr
        session = self.session(api)
        session.build = lambda: None
        session.versions = lambda: ('0.1.5', '0.1.5')
        for number in ('', '0', '-1', '9;echo bad', 'abc'):
            with self.assertRaises(ValueError):
                session.resume(number)
        with self.assertRaises(ValueError):
            session.resume('9')
        self.assertEqual(api.writes, [])

    def release_state(self, session):
        jar = self.artifact()
        return session.result(self.plan, 'resume', self.r, merged=self.m, artifact_sha256=h.artifact_hash(jar), verified_resume='true')

    def test_publish_holds_on_late_main_or_partial_state(self):
        for path, value in [('git/ref/heads/main', {'object': {'sha': self.s}}), ('git/ref/tags/v0.1.5', {'object': {'sha': self.r}}), ('releases/tags/v0.1.5', {'id': 7})]:
            api = self.main_api()
            session = self.session(api)
            state = self.release_state(session)
            api.values[path] = value
            with self.assertRaises(ValueError):
                session.publish(state)
            self.assertEqual(api.writes, [])

    def test_publish_and_image_guard_stay_at_m(self):
        api = self.main_api()
        session = self.session(api)
        state = self.release_state(session)
        tag_sha = 'b'*40
        api.values['POST git/tags'] = {'sha': tag_sha}
        def make_ref(payload):
            api.values['git/ref/tags/v0.1.5'] = {'object': {'sha': tag_sha}}
            api.values['git/tags/'+tag_sha] = {'object': {'sha': self.m, 'type': 'commit'}}
            return {'ref': 'refs/tags/v0.1.5', 'object': {'sha': tag_sha}}
        api.values['POST git/refs'] = make_ref
        def command(argv):
            api.writes.append(('command', argv))
            api.values['releases/tags/v0.1.5'] = {'id': 42, 'tag_name': 'v0.1.5', 'draft': False}
        api.command = command
        session.publish(state)
        session.image_guard(state)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.m)
        tag_payload = next(payload for path, payload in api.writes if path == 'git/tags')
        self.assertEqual(tag_payload['object'], self.m)
        self.assertTrue(any(path == 'command' and '--verify-tag' in payload and '--clobber' not in payload for path, payload in api.writes))
        # A repeated resume invocation cannot treat partial publication as fresh.
        with self.assertRaises(ValueError):
            session.publish(state)
        self.git('checkout', '-q', self.s)
        with self.assertRaises(ValueError):
            session.image_guard(state)

    def test_snapshot_branch_reuse_and_conflicts_are_exact(self):
        self.git('checkout', '-q', self.m)
        files = h.pom_transform(self.repo.files(self.m), '0.1.5', self.plan.next)
        (self.path/'pom.xml').write_bytes(files['pom.xml'])
        self.git('add', 'pom.xml')
        self.git('commit', '-qm', self.plan.message('Next snapshot'))
        n = self.git('rev-parse', 'HEAD')
        api = self.main_api()
        api.values['git/ref/heads/'+self.plan.snapshot_branch] = {'object': {'sha': n}}
        self.assertEqual(h.stage_branch(self.repo, api, self.plan, n, False, snapshot=True), n)
        api.values['git/ref/heads/'+self.plan.snapshot_branch]['object']['sha'] = self.r
        with self.assertRaises(ValueError):
            h.stage_branch(self.repo, api, self.plan, n, False, snapshot=True)
        self.assertEqual(api.writes, [])

    def test_prepare_dry_run_validates_next_transition_without_remote_writes(self):
        self.git('checkout', '-q', self.s)
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        session = h.Session(self.repo, api, self.s, 'refs/heads/main', True, False)
        current = ['0.1.5-SNAPSHOT']
        session.versions = lambda: (current[0], current[0])
        def set_version(new):
            files = h.pom_transform(self.repo.files(self.git('rev-parse', 'HEAD')), current[0], new)
            for name, data in files.items():
                (self.path/name).write_bytes(data)
            current[0] = new
        session.set_version = set_version
        builds = []
        session.build = lambda: builds.append(current[0])
        result = session.prepare('', 'patch')
        self.assertEqual(builds, ['0.1.5'])
        self.assertEqual(result['next'], '0.1.6-SNAPSHOT')
        self.assertEqual(result['phase'], 'prepare')
        self.assertEqual(result['verified_resume'], 'false')
        self.assertEqual(self.git('rev-parse', 'HEAD'), result['prepared'])
        self.assertEqual(api.writes, [])


    def test_maintenance_lookup_is_prepublication_and_existing_tip_is_preserved(self):
        api = self.main_api()
        session = self.session(api)
        state = self.release_state(session)
        api.values['git/ref/heads/'+self.plan.maintenance_branch] = ValueError('HTTP 403')
        with self.assertRaises(ValueError):
            session.publish(state)
        self.assertEqual(api.writes, [])
        api.values['git/ref/heads/'+self.plan.maintenance_branch] = {'object': {'sha': self.s}}
        # Continuation state is produced by successful publish and names the
        # existing maintenance tip; finishing must retain it, even when older.
        state.update(publication_tag='b'*40, publication_release='42', maintenance_sha=self.s)
        session.image_guard = lambda state: None
        session.set_version = lambda version: (self.path/'pom.xml').write_bytes(h.pom_transform(self.repo.files(self.m), '0.1.5', version)['pom.xml'])
        commit = session.commit
        def commit_and_register(plan, title):
            sha = commit(plan, title)
            api.values['git/ref/heads/'+plan.snapshot_branch] = {'object': {'sha': sha}}
            return sha
        session.commit = commit_and_register
        session.finish(state)
        self.assertEqual(api.writes, [])
        self.assertEqual(api.values['git/ref/heads/'+self.plan.maintenance_branch]['object']['sha'], self.s)

    def test_full_build_command_retains_all_tests(self):
        from unittest.mock import patch
        session = self.session(self.main_api())
        # The provenance fixture intentionally has an external snapshot. Build
        # must reject that before Maven, even though metadata validation permits
        # leaving unrelated external dependencies unchanged.
        with patch.object(session, 'maven') as execute:
            with self.assertRaises(ValueError):
                session.build()
            execute.assert_not_called()
        clean = (self.path/'pom.xml').read_text().replace('<version>0.1.5-SNAPSHOT</version>', '<version>1.2.3</version>')
        (self.path/'pom.xml').write_text(clean)
        self.git('add', 'pom.xml')
        self.git('commit', '-qm', 'no external snapshot')
        with patch.object(session.git, 'files', return_value={'pom.xml': clean.encode()}), patch.object(session.git, 'call', return_value=self.m), patch.object(h.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as execute:
            session.build()
            self.assertEqual(execute.call_args.args[0], ['mvn', '-B', 'clean', 'verify'])

    def test_profile_versions_update_and_external_consumer_snapshot_is_preserved(self):
        files = {
            'pom.xml': POM.replace('</project>', '<modules><module>app</module></modules><profiles><profile><modules><module>bench</module></modules></profile></profiles></project>').encode(),
            'app/pom.xml': b'<project><parent><groupId>example</groupId><artifactId>root</artifactId><version>0.1.5-SNAPSHOT</version></parent><artifactId>app</artifactId><version>0.1.5-SNAPSHOT</version></project>',
            'bench/pom.xml': b'<project><parent><groupId>example</groupId><artifactId>root</artifactId><version>0.1.5-SNAPSHOT</version></parent><artifactId>bench</artifactId><version>0.1.5-SNAPSHOT</version></project>',
            'app/src/it/external-consumer/pom.xml': b'<project><groupId>fixture</groupId><artifactId>consumer</artifactId><version>1.0-SNAPSHOT</version><dependencies><dependency><groupId>example</groupId><artifactId>root</artifactId><version>@revision@</version></dependency></dependencies></project>',
        }
        files['not-a-module/pom.xml'] = POM.encode()
        transformed = h.pom_transform(files, '0.1.5-SNAPSHOT', '0.1.5')
        self.assertEqual(transformed['not-a-module/pom.xml'], files['not-a-module/pom.xml'])
        self.assertNotIn(b'0.1.5-SNAPSHOT', transformed['bench/pom.xml'])
        fixture = 'app/src/it/external-consumer/pom.xml'
        self.assertEqual(transformed[fixture], files[fixture])
        self.assertEqual(set(h.reactor_poms(files)), {'pom.xml', 'app/pom.xml', 'bench/pom.xml'})

    def test_live_prepare_stages_only_a_new_stable_handoff(self):
        self.git('checkout', '-q', self.s)
        api = FakeAPI({'git/ref/heads/main': {'object': {'sha': self.s}}})
        import base64
        import hashlib
        api.values['POST git/blobs'] = lambda payload: {'sha': hashlib.sha1(b'blob '+str(len(base64.b64decode(payload['content']))).encode()+b'\0'+base64.b64decode(payload['content'])).hexdigest()}
        api.values['POST git/trees'] = lambda payload: {'sha': self.repo.tree(self.git('rev-parse', 'HEAD'))}
        api.values['POST git/commits'] = lambda payload: {'sha': self.git('rev-parse', 'HEAD')}
        api.values['POST git/refs'] = lambda payload: {'ref': payload['ref'], 'object': {'sha': payload['sha']}}
        session = h.Session(self.repo, api, self.s, 'refs/heads/main', False, False)
        def versions():
            import xml.etree.ElementTree as ET
            root = ET.fromstring((self.path/'pom.xml').read_bytes())
            return root.findtext('version'), root.findtext('properties/revision')
        session.versions = versions
        session.build = lambda: None
        state = session.prepare('0.2.0-SNAPSHOT', 'patch')
        self.assertEqual(state['next'], '0.2.0-SNAPSHOT')
        self.assertEqual(state['phase'], 'prepare')
        self.assertEqual(state['verified_resume'], 'false')
        paths = [path for path, _ in api.writes]
        self.assertEqual(paths, ['git/blobs', 'git/trees', 'git/commits', 'git/refs'])
        self.assertEqual(api.writes[-1][1]['ref'], 'refs/heads/'+self.plan.branch)
        self.assertEqual(self.git('rev-parse', 'HEAD'), state['prepared'])
        self.assertEqual(versions(), ('0.1.5', '0.1.5'))

    def test_late_movement_after_tag_ref_holds_before_release(self):
        api = self.main_api()
        session = self.session(api)
        state = self.release_state(session)
        api.values['POST git/tags'] = {'sha': 'b'*40}
        def ref(payload):
            api.values['git/ref/heads/main']['object']['sha'] = self.s
            return {'object': {'sha': payload['sha']}}
        api.values['POST git/refs'] = ref
        with self.assertRaises(ValueError):
            session.publish(state)
        self.assertEqual([path for path, _ in api.writes], ['git/tags', 'git/refs'])

class WorkflowTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('release_validator', Path(__file__).with_name('validate_release_workflow.py'))
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)
        self.workflow = (Path(__file__).resolve().parents[1]/'.github/workflows/deploy-release.yml').read_text()

    def test_actual_orchestration_contract(self):
        self.assertEqual(self.validator.validate(self.workflow), [])

    def test_unsafe_workflow_mutations_are_rejected(self):
        changes = [('  workflow_dispatch:', '  push:\n  workflow_dispatch:'),
                   ('  actions: read\n', ''),
                   ('ref: ${{ github.sha }}', 'ref: main'),
                   ("steps.handoff.outputs.verified_resume == 'true' && !inputs.dry_run", '!inputs.dry_run'),
                   ('scripts/release_handoff.py image-guard', 'scripts/release_handoff.py prepare'),
                   ('          push: true', '          push: true\n          ref: main'),
                   ('      resume_release_pr:', '      release_version:'),
                   ('          set -euo pipefail', '          git push origin HEAD:main\n          set -euo pipefail')]
        for old, new in changes:
            with self.subTest(old=old):
                self.assertTrue(self.validator.validate(self.workflow.replace(old,new)))







if __name__ == '__main__':
    unittest.main()
