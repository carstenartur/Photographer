# Canon CCAPI authentication

Canon CCAPI can require HTTP Digest authentication when an account is enabled in the camera's **Camera Control API → Account settings** menu. The application supports this without adding credentials to camera profiles, database rows or public API payloads.

## Configuration boundary

Credentials are resolved by the exact `CameraProfile.id` and are used only while constructing that profile's internal HTTP transport.

```yaml
focusstack:
  camera:
    ccapi:
      credentials:
        powershot-g5:
          username: ${CCAPI_G5_USERNAME:}
          password: ${CCAPI_G5_PASSWORD:}
```

Set `CCAPI_G5_USERNAME` and `CCAPI_G5_PASSWORD` in the process/container secret environment. Do not put real values in checked-in YAML, Dockerfiles, command-line arguments, screenshots or support bundles.

An entry is optional. Cameras with authentication disabled continue to use an unauthenticated CCAPI transport. Supplying only a username or only a password is rejected as incomplete configuration.

## Runtime behavior

- each cached Canon backend owns an independent HTTP client and authentication state;
- credentials are scoped to the configured camera host and port;
- the first request is sent without an `Authorization` header and answers a camera-provided Digest challenge;
- redirects are disabled, preventing credentials from being forwarded to another origin;
- Apache HttpClient handles the Digest nonce count and stale-nonce challenge exchange;
- a final `401` becomes a sanitized `CREDENTIALS_REQUIRED` or `CREDENTIALS_REJECTED` camera error;
- challenge headers, realm, nonce, opaque value, user name, password and generated Authorization response are never copied into public error details.

After rotating or removing a camera account password, evict the cached camera backend or restart the application so a fresh profile-scoped transport is created.

## Security note

Digest authentication avoids sending the password itself in each request, but plain HTTP does not provide transport confidentiality. Operate CCAPI on a trusted or isolated local network. Use a camera-supported HTTPS configuration when one is available and validated for the specific model and firmware.

## Troubleshooting

- `CREDENTIALS_REQUIRED`: the camera returned a Digest challenge but the exact profile id has no configured account.
- `CREDENTIALS_REJECTED`: credentials were configured, but the camera still returned `401` after the bounded challenge exchange. Verify the on-camera account, exact profile id and environment secret values.
- Connection timeout/refused: this is a network/session failure, not an authentication rejection.

Never enable HTTP wire/header logging in production diagnostics; it can expose Digest response material even though application logs are redacted.
