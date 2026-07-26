# Security Policy

## Supported versions

Security fixes are developed on `main` and included in the newest published release. Older snapshots, development branches and superseded releases are not maintained separately.

| Version | Supported |
|---|---|
| Latest release | Yes |
| `main` | Best effort until the next release |
| Older releases and snapshots | No |

## Reporting a vulnerability

Please do **not** disclose a suspected vulnerability in a public issue, discussion, pull request or log attachment.

Use GitHub's private vulnerability reporting for this repository when it is available under **Security → Report a vulnerability**. If that channel is unavailable, email [Carsten Hammer](mailto:carsten.hammer@t-online.de) with the subject `Photographer security report`.

Include, where possible:

- the affected revision or release;
- the affected module, endpoint or workflow;
- prerequisites and a minimal reproduction;
- the security impact;
- whether credentials, camera identifiers, images or personal data may have been exposed;
- a suggested mitigation, if known.

Do not include real secrets unless they are essential to reproduce the issue. Revoke or rotate any credential that may already be compromised.

## Security-sensitive areas

Reports are especially useful for:

- authentication, authorization and administrative endpoints;
- camera-control commands, session ownership and unintended capture;
- CCAPI discovery, HTTP transport, redirects and server-side request forgery;
- PTP/USB parsing and device-boundary failures;
- CR3, JPEG and archive parsing, including denial of service and path traversal;
- plugin discovery and loading of untrusted code;
- uploaded files, downloads and generated output paths;
- AI-provider credentials, prompts, images and provider responses;
- secrets, camera serial numbers, GPS coordinates or personal metadata in logs, fixtures, reports or Git history.

## Operational assumptions

Photographer is a self-hosted application, not a hardened public multi-tenant service. Operators should:

- use a unique administrator password and a secrets manager or protected environment file;
- place internet-facing deployments behind TLS and an appropriately configured reverse proxy;
- restrict access to the plugin directory because plugin JARs execute with application privileges;
- restrict camera and USB/network access to trusted hosts and networks;
- keep dependencies and the application updated;
- review logs, exports and camera fixtures before sharing them.

The mock backend is the safe default for demonstrations and automated tests. Real-camera workflows should be enabled deliberately and only on trusted networks or hosts.

## Disclosure process

The maintainer will validate the report, determine affected versions and coordinate a fix and disclosure. Public details should be released only after a mitigation is available or disclosure has otherwise been agreed with the reporter.
