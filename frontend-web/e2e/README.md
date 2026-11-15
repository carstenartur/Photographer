# Packaged browser smoke test

Run from the repository root on a machine with Docker BuildKit, Python 3.10 or later, and enough space for the product image and a Chromium installation.

```sh
python3 -m venv target/browser-venv
target/browser-venv/bin/pip install -r frontend-web/e2e/requirements.txt
target/browser-venv/bin/python -m playwright install --with-deps chromium
target/browser-venv/bin/python frontend-web/e2e/smoke.py
```

The first run downloads build dependencies, the base images and the pinned browser. The script builds the product with its Dockerfile, starts Spring Boot and its Vite bundle on a random loopback port, and runs against an in-memory H2 database with a mock camera. It generates an admin password for each run and removes the container and disposable image on exit. The scenarios cover sign-in, profile creation/editing/deletion, a two-frame capture and ZIP export, session creation, a job bookmark, a backend error and a phone viewport. The test changes only the disposable instance.

CI reuses the Docker smoke image by passing `--image focusstack:test`. A failed run writes a screenshot, Playwright trace and application log in `target/browser-smoke/`. Open the trace with `target/browser-venv/bin/python -m playwright show-trace target/browser-smoke/trace.zip`. Pass `--captures target/browser-guide` to refresh the source screenshots for the user guide. The ordinary unit suite continues to run through `npm test` in `frontend-web`.
