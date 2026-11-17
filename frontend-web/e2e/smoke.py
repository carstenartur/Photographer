"""Exercise the packaged web UI in an isolated H2/mock-camera container."""

import argparse
import base64
import json
from contextlib import contextmanager
from io import BytesIO
import os
from pathlib import Path
import re
import secrets
import subprocess
from tempfile import TemporaryDirectory
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
        '-e', 'FOCUSSTACK_FRESNEL_ENABLED=true', '-e', 'FOCUSSTACK_FRESNEL_TRUST_PRIVATE_NETWORK=true',
        image,
    ], text=True, env=env).strip()
    try:
        check_packaged_cr3_guide(container)
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


def check_packaged_cr3_guide(container):
    with TemporaryDirectory() as directory:
        app_jar = Path(directory) / 'app.jar'
        subprocess.run(['docker', 'cp', f'{container}:/app/app.jar', str(app_jar)], check=True)
        with zipfile.ZipFile(app_jar) as application:
            (imageio_jar,) = (name for name in application.namelist()
                              if re.fullmatch(r'BOOT-INF/lib/cr3-imageio-.+\.jar', name))
            with zipfile.ZipFile(BytesIO(application.read(imageio_jar))) as imageio:
                guide = imageio.read('META-INF/cr3/imageio.md').decode('utf-8')
                assert 'This product includes DNG technology under license by Adobe.' in guide
                assert imageio.read('META-INF/cr3/import.md')


def login(page, password):
    page.get_by_label('Username').fill('admin')
    page.get_by_label('Password').fill(password)
    page.get_by_role('button', name='Sign in').click()


def capture(view, directory, filename):
    if directory:
        directory.mkdir(parents=True, exist_ok=True)
        view.screenshot(path=directory / filename, animations='disabled')


def socket_headers(origin):
    return {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest',
            'Origin': origin, 'Upgrade': 'websocket', 'Connection': 'Upgrade',
            'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ=='}


def check_fresnel_pairing(page, url, captures):
    page.get_by_role('link', name='Fresnel', exact=True).click()
    page.get_by_role('button', name='Create pairing code').click()
    code = page.locator('.form-card [role=status] code').text_content()
    page.get_by_role('button', name='Hide code').click()
    service = url + '/api/v1/integrations/fresnel'
    pair = urllib.request.Request(service + '/pair', headers={
        'Content-Type': 'application/json', 'Idempotency-Key': 'browser-pair'}, data=json.dumps({
            'pairingCode': code, 'fresnelInstanceId': 'optics-lab', 'fresnelDisplayName': 'Optics lab',
            'fresnelApplicationVersion': '1.0', 'fresnelProtocolVersion': 1}).encode())
    with urllib.request.urlopen(pair, timeout=10) as response:
        bearer = json.load(response)['bearerToken']
    health = urllib.request.Request(service + '/health', headers={'Authorization': 'Bearer ' + bearer})
    with urllib.request.urlopen(health, timeout=10) as response:
        assert json.load(response)['state'] == 'CONNECTED'
    card = page.locator('section').filter(has=page.get_by_role('heading', name='Optics lab'))
    expect(card.get_by_text('CONNECTED', exact=True)).to_be_visible(timeout=20000)
    capture(page, captures, 'fresnel-pairing.png')
    card.get_by_role('button', name='Revoke access').click()
    expect(card.get_by_text('REVOKED', exact=True)).to_be_visible()


def check_socket_boundary(page, url):
    headers = {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}
    # The socket grant alone cannot authorize ordinary REST reads or another origin's upgrade.
    assert page.request.get(url + '/api/v1/cameras', headers=headers, max_redirects=0).status == 401
    assert page.request.get(url + '/ws/plain', headers=socket_headers('https://outside.invalid'),
                            max_redirects=0).status == 401


def revoke_open_socket(page, url, password):
    page.evaluate("""() => new Promise((resolve, reject) => {
      const stream = new WebSocket(location.origin.replace(/^http/, 'ws') + '/ws/plain');
      window.revokedSocketClosed = new Promise(done => { stream.onclose = done });
      stream.onopen = resolve;
      stream.onerror = reject;
    })""")
    authorization = base64.b64encode(('admin:' + password).encode()).decode()
    reply = page.request.delete(url + '/api/v1/browser/socket-session', headers={
        'Authorization': 'Basic ' + authorization, 'X-Requested-With': 'XMLHttpRequest'})
    assert reply.status == 204
    page.evaluate("""() => Promise.race([window.revokedSocketClosed.then(() => true),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Socket still open')), 3000))])""")
    assert page.request.get(url + '/ws/plain', headers=socket_headers(url), max_redirects=0).status == 401


def exercise(page, url, password, captures):
    assert page.request.get(url + '/ws/plain', headers=socket_headers(url), max_redirects=0).status == 401
    page.goto(url)
    login(page, 'invalid')
    expect(page.get_by_role('alert')).to_have_text('Invalid username or password')
    login(page, password)
    expect(page.get_by_role('heading', name='Dashboard', exact=True)).to_be_visible()

    check_socket_boundary(page, url)

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
    expect(page.get_by_text("Job completed with 2 frames", exact=True)).to_be_visible(timeout=5000)
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
    page.get_by_label('Camera Profile').select_option(label='Mock studio camera')
    page.get_by_label('Total Intervals').fill('1')
    capture(page.locator('form'), captures, 'hdr-session.png')
    page.get_by_role('button', name='Create Session').click()
    expect(page.get_by_role('heading', name=re.compile('Session:'))).to_be_visible()
    expect(page.get_by_text('CREATED', exact=True).first).to_be_visible()
    page.get_by_role('button', name='Start', exact=True).click()
    expect(page.get_by_text('COMPLETED', exact=True).first).to_be_visible(timeout=20_000)
    expect(page.get_by_role('heading', name='Brackets (1)', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='Generate Timelapse Video')).to_have_count(0)

    check_fresnel_pairing(page, url, captures)

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
    revoke_open_socket(page, url, password)


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
