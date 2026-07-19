# Canon CCAPI Digest authentication

Canon cameras can protect CCAPI with HTTP Digest authentication. The camera-side
account is configured under **Camera Control API → Account settings**. The
application keeps those credentials outside camera profiles, database records and
public APIs.

## External configuration

Credentials are resolved by the exact camera profile id:

```yaml
focusstack:
  camera:
    ccapi:
      credentials:
        studio-g5x:
          username: ${CCAPI_STUDIO_G5X_USERNAME}
          password: ${CCAPI_STUDIO_G5X_PASSWORD}
```

Environment variables should supply the secret values at runtime. Do not place
real passwords in committed YAML, Docker images, command-line arguments, issue
attachments or diagnostic bundles.

The configuration key is the profile id, not the display name, host or camera
model. No wildcard or host-based fallback is performed. A profile with no entry
uses an ordinary unauthenticated CCAPI transport. Supplying only a username or
only a password fails safely and identifies only the affected profile id.

## Transport isolation and lifecycle

Every cached Canon backend owns a separate Apache HttpClient and connection pool.
Credentials are scoped to the configured camera host and port and are sent only
after a Digest challenge. Redirects are disabled so authentication state cannot
move to another origin.

The backend cache owns the transport lifetime. Explicit profile eviction and
application shutdown close the HttpClient and connection pool and overwrite the
transport-owned password character array. Rotating or removing credentials still
requires eviction or restart so a new backend is created from the new external
configuration; automatic configuration fingerprinting is tracked separately.

## Failure behavior

A final camera response of HTTP 401 is mapped to one of two sanitized outcomes:

- `CREDENTIALS_REQUIRED`: no credentials were configured for the profile;
- `CREDENTIALS_REJECTED`: configured credentials did not satisfy the camera.

The public problem response contains the safe profile id and stable error code.
It never copies `WWW-Authenticate`, realm, nonce, opaque, username, password or
Authorization data from the camera exchange.

## Rotation

1. Change the account password on the camera.
2. Replace the runtime secret in the deployment environment.
3. Restart the application, or explicitly evict the affected profile backend.
4. Reconnect and verify read-only device information before starting a capture.

No database migration is needed because the credentials are not persisted.

## Troubleshooting

- `CREDENTIALS_REQUIRED`: configure both external values for the exact profile id,
  or disable account protection on the camera if authentication is intentionally
  unused.
- `CREDENTIALS_REJECTED`: verify the camera-side account is enabled and that the
  runtime secret belongs to the same profile.
- Repeated failure after rotation: evict the cached backend or restart the
  application so old transport state is discarded.
- Connection timeout is distinct from an authentication rejection; verify the
  camera URL, Wi-Fi connection and configured CCAPI port.

## Network-security boundary

Digest authentication avoids transmitting the password directly, but plain HTTP
does not provide transport confidentiality for images, metadata or request
paths. Use an isolated or trusted network. HTTPS should be preferred when the
connected camera and its advertised API support it.

Never enable HTTP wire/header logging in production diagnostics; it can expose
Digest response material even though application logs and API errors are redacted.
