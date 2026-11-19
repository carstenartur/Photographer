# Packaged browser smoke test

Run from the repository root on a machine with Docker BuildKit, Python 3.10 or later, and enough space for the product image and a Chromium installation.

```sh
python3 -m venv target/browser-venv
target/browser-venv/bin/pip install -r frontend-web/e2e/requirements.txt
target/browser-venv/bin/python -m playwright install --with-deps chromium
target/browser-venv/bin/python frontend-web/e2e/smoke.py
```

The first run downloads build dependencies, the base images and the pinned browser. The script builds the product with its Dockerfile, starts Spring Boot and its Vite bundle on a random loopback port, and runs against an in-memory H2 database with a mock camera. It generates an admin password for each run and removes the container and disposable image on exit. The scenarios cover sign-in, profile creation/editing/deletion, a two-frame capture and ZIP export, session creation, Fresnel pairing/revocation, local bird preview and its explicit arm/disarm/stop lifecycle, a job bookmark, a backend error and a phone viewport. The test changes only the disposable instance.

CI reuses the Docker smoke image by passing `--image focusstack:test`. A failed run writes a screenshot, Playwright trace and application log in `target/browser-smoke/`. The trace can contain the generated credentials for the disposable test instance. Open the trace with `target/browser-venv/bin/python -m playwright show-trace target/browser-smoke/trace.zip`. Pass `--captures target/browser-guide` to refresh the source screenshots for the user guide. The ordinary unit suite continues to run through `npm test` in `frontend-web`.

## Bird observer screenshots and assertions

The bird scenario uses the packaged React interface, Spring Boot endpoints, bundled local bird detector, and built-in mock camera. It does not intercept or replace API responses, inject detection boxes, or edit the rendered interface. No hardware camera is contacted.

It starts in **Preview only**, waits for a decoded image and a completed local inference, draws the observation region with pointer input, refines its coordinates, and checks the applied region through the authenticated backend. It then restarts preview to analyse that crop. After further frames, it checks that preview has created neither captures nor observation events. It explicitly arms and disarms automatic capture, stops the observer, and checks a subsequent UI status poll to confirm that the worker counters remain stopped.

The mock camera supplies a synthetic color gradient, not a bird photograph. Its unchanged frame bytes intentionally exercise duplicate-frame rejection. The **Duplicate camera frame; waiting for a new image** message is expected; the test does not hide it. There are no bird boxes or capture events in this scenario, and arming leaves the capture count at zero. These screenshots demonstrate the controls and lifecycle, not successful bird recognition, a hardware capture, or field performance. A real bird-image detection test and a connected-camera acceptance run are separate evidence.

With `--captures`, the scenario writes the following unmodified application-panel screenshots:

| File | Verified state |
| --- | --- |
| `bird-preview.png` | Preview-only mode, selected observation region, analysed mock frame, zero captures. |
| `bird-armed.png` | Automatic capture explicitly armed; repeated mock frames do not trigger a photo. |
| `bird-stopped.png` | Observer stopped and disarmed; counters remain unchanged on the next status poll. |

The test requires the bundled model to initialize in the product image. Missing models or incompatible native inference libraries fail the scenario instead of producing a placeholder screenshot.
