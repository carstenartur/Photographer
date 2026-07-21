# Runtime-only Canon CCAPI credentials

The packaged application imports Spring Boot config-tree files from `/run/secrets/` when the `docker` profile is active. Credentials are never accepted as Docker build arguments and are not stored in camera profiles, the database, the image, Compose defaults or generated application configuration.

## File names

For a camera profile id `camera-smoke`, mount two files with these exact target names:

```text
/run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.username
/run/secrets/focusstack.camera.ccapi.credentials.camera-smoke.password
```

Each file contains only the corresponding value. Do not add a property name or JSON wrapper.

The target name is a Spring config-tree property key. Replace `camera-smoke` with the exact camera profile id used by the application.

## Compose example

Create two local files outside the repository and restrict their permissions:

```bash
install -m 600 /dev/null "$HOME/.config/focusstack/camera-smoke.username"
install -m 600 /dev/null "$HOME/.config/focusstack/camera-smoke.password"
printf '%s' 'camera-account' > "$HOME/.config/focusstack/camera-smoke.username"
printf '%s' 'camera-password' > "$HOME/.config/focusstack/camera-smoke.password"
```

Then start the normal Compose file with the secret overlay:

```bash
export CCAPI_CAMERA_USERNAME_FILE="$HOME/.config/focusstack/camera-smoke.username"
export CCAPI_CAMERA_PASSWORD_FILE="$HOME/.config/focusstack/camera-smoke.password"
export CCAPI_REQUIRED_PROFILE_IDS='camera-smoke'
docker compose -f docker-compose.yml -f docker-compose.ccapi-secrets.yml up --build
```

`CCAPI_REQUIRED_PROFILE_IDS` is optional. When present, application startup proves that every listed profile has a complete external credential pair. Failure reports only the missing count and never the profile ids or secret values.

## Security properties

- secret values are mounted after image construction;
- the files are not copied into the image and do not appear in `docker history`;
- the application obtains them through `optional:configtree:/run/secrets/`;
- snapshots redact username and password and overwrite temporary password arrays on close;
- the packaged-runtime CI creates fresh sentinels after the image build, mounts them read-only, starts the real packaged JAR, scans logs and the saved image, and then performs a bounded container shutdown.

Do not pass CCAPI credentials with `--build-arg`, bake them into an `ENV` instruction, commit them to `.env`, or put them into a camera-profile REST payload.
