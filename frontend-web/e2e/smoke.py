"""Exercise the packaged web UI in an isolated H2/mock-camera container."""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
import zipfile

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def isolated_app(image, artifacts):
    password = secrets.token_urlsafe(24)
    env = dict(os.environ, FOCUSSTACK_SECURITY_ADMIN_PASSWORD=password)
    container = subprocess.check_output([
        'docker', 'run', '-d', '-p', '127.0.0.1::8080',
        '-e', 'FOCUSSTACK_SECURITY_ADMIN_PASSWORD',
        '-e', 'CAMERA_MOCK_ENABLED=true', '-e', 'FOCUSSTACK_CAMERA_MOCK_ENABLED=true',
        image,
    ], text=True, env=env).strip()
    try:
        address = subprocess.check_output(['docker', 'port', container, '8080/tcp'], text=True).strip()
        url = 'http://' + address
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(url + '/api/v1/analysis/health', timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            time.sleep(0.5)
        else:
            raise RuntimeError('Application health endpoint did not become ready in 120 seconds')
        yield url, password
    except BaseException:
        artifacts.mkdir(parents=True, exist_ok=True)
        with (artifacts / 'application.log').open('w') as log:
            subprocess.run(['docker', 'logs', container], stdout=log, stderr=subprocess.STDOUT)
        raise
    finally:
        subprocess.run(['docker', 'rm', '--force', '--volumes', container], check=True)


def login(page, password):
    page.get_by_label('Username').fill('admin')
    page.get_by_label('Password').fill(password)
    page.get_by_role('button', name='Sign in').click()


def capture(view, directory, filename):
    if directory:
        directory.mkdir(parents=True, exist_ok=True)
        view.screenshot(path=directory / filename, animations='disabled')


def exercise(page, url, password, captures):
    page.goto(url)
    login(page, 'invalid')
    expect(page.get_by_role('alert')).to_have_text('Invalid username or password')
    login(page, password)
    expect(page.get_by_role('heading', name='Dashboard', exact=True)).to_be_visible()

    page.get_by_role('link', name='Add Camera').click()
    page.get_by_label('Name', exact=True).fill('Practice camera')
    page.get_by_label('Backend Type').select_option('mock')
    page.get_by_role('button', name='Create', exact=True).click()
    camera = page.locator('.cam-card').filter(has_text='Practice camera')
    camera.get_by_role('button', name='Edit').click()
    page.get_by_label('Name', exact=True).fill('Mock studio camera')
    page.get_by_role('button', name='Save', exact=True).click()
    camera = page.locator('.cam-card').filter(has_text='Mock studio camera')
    expect(camera.get_by_role('img', name='Mock studio camera live view')).to_be_visible()
    expect(camera.get_by_text('85%')).to_be_visible()
    capture(camera, captures, 'mock-camera.png')

    page.get_by_role('link', name='Jobs', exact=True).click()
    page.get_by_role('button', name='Create Job').click()
    page.get_by_label('Camera Profile').select_option(label='Mock studio camera')
    page.get_by_role('button', name=re.compile('Fast')).click()
    page.get_by_label('Max Frames').fill('2')
    page.get_by_label('Enable Motion Detection').uncheck()
    capture(page.locator('form'), captures, 'new-job.png')
    page.get_by_role('button', name='Create', exact=True).click()
    page.get_by_role('row').filter(has_text='CREATED').get_by_role('link').click()
    expect(page.get_by_text('0 / 2 frames captured (0%)')).to_be_visible()
    page.get_by_role('button', name='Start', exact=True).click()
    expect(page.get_by_text('COMPLETED', exact=True).first).to_be_visible(timeout=45000)
    expect(page.get_by_text('2 / 2 frames captured (100%)')).to_be_visible()
    with page.expect_download() as download:
        page.get_by_role('button', name='Export Frames (ZIP)').click()
    with zipfile.ZipFile(download.value.path()) as archive:
        names = archive.namelist()
        assert len(names) == 2 and len(set(names)) == 2, names
        assert all(archive.read(name).startswith(b'\x89PNG\r\n\x1a\n') for name in names)

    page.reload()
    login(page, password)
    expect(page.get_by_text('2 / 2 frames captured (100%)')).to_be_visible()
    page.once('dialog', lambda dialog: dialog.accept())
    page.get_by_role('button', name='Delete Job').click()
    expect(page.get_by_text('No jobs yet.')).to_be_visible()

    page.get_by_role('link', name='HDR Timelapse').click()
    page.get_by_role('button', name='New Session').click()
    page.get_by_label('Total Intervals').fill('1')
    capture(page.locator('form'), captures, 'hdr-session.png')
    page.get_by_role('button', name='Create Session').click()
    expect(page.get_by_role('heading', name=re.compile('Session:'))).to_be_visible()
    expect(page.get_by_text('CREATED', exact=True).first).to_be_visible()

    page.set_viewport_size({'width': 390, 'height': 844})
    page.get_by_role('link', name='Cameras', exact=True).click()
    expect(camera.get_by_role('img')).to_be_visible()
    assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
    page.once('dialog', lambda dialog: dialog.accept())
    camera.get_by_role('button', name='Delete').click()
    expect(camera).to_have_count(0)

    page.goto(url + '/jobs/missing-browser-test')
    login(page, password)
    expect(page.get_by_text(re.compile(r'400 Camera Error.*Job not found: missing-browser-test'))).to_be_visible()


def run(image, captures, artifacts):
    with isolated_app(image, artifacts) as (url, password), sync_playwright() as browser_tools:
        browser = browser_tools.chromium.launch()
        context = browser.new_context(viewport={'width': 1152, 'height': 810}, locale='en-US')
        page = context.new_page()
        # Start tracing after creating the page. The generated password belongs only to
        # this container. Teardown destroys the database and credentials after each run.
        context.tracing.start(screenshots=True, snapshots=True)
        try:
            exercise(page, url, password, captures)
        except BaseException:
            artifacts.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=artifacts / 'failure.png')
            context.tracing.stop(path=artifacts / 'trace.zip')
            raise
        else:
            context.tracing.stop()
        finally:
            context.close()
            browser.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', help='reuse a locally built Photographer runtime image')
    parser.add_argument('--captures', type=Path, help='also save intentional documentation screenshots')
    parser.add_argument('--artifacts', type=Path, default=ROOT / 'target/browser-smoke')
    args = parser.parse_args()
    # Use the same Dockerfile and Maven build as the packaged product.
    image = args.image
    if not image:
        image = 'photographer-browser-' + secrets.token_hex(6)
        subprocess.run(['docker', 'build', '-t', image, str(ROOT)], check=True)
    try:
        run(image, args.captures, args.artifacts)
    finally:
        if not args.image:
            subprocess.run(['docker', 'image', 'rm', image], check=True)
