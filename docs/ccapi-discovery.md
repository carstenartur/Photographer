# Portable Canon CCAPI discovery

The application contains a platform-independent discovery core for Canon cameras that advertise the Camera Control API through UPnP/SSDP.

## Scope

`CcapiDiscoveryService` performs one bounded local-network discovery pass:

1. enumerate every active, non-loopback, multicast-capable network interface;
2. send Canon's documented SSDP `M-SEARCH` request over each available IPv4 and IPv6 binding;
3. parse Canon service responses and advertisements case-insensitively;
4. validate that the numeric `LOCATION` host equals the datagram source address;
5. fetch each unique device-description document with redirects disabled and a 1 MiB body limit;
6. parse the namespace-aware XML securely;
7. validate that `X_accessURL` refers to the same host as the device description;
8. return cameras deduplicated by UDN, with structured non-fatal warnings.

The caller supplies an overall timeout between a positive duration and five minutes. The service divides the remaining deadline among network bindings and device-description requests.

## Security boundaries

Discovery treats multicast responses and device-description XML as untrusted input.

- No interface name such as `wlan0` is assumed.
- No shell `ping` or `ping6` process is executed.
- Hostnames in SSDP `LOCATION` are rejected; the numeric host must match the datagram source.
- Credential-bearing URLs are rejected.
- HTTP redirects are not followed.
- `X_accessURL` may use a different port, but not a different host.
- XML DTDs, XInclude and external entities are disabled.
- Raw packets and XML bodies are neither logged nor returned in warnings.
- Warnings contain only the processing stage, a bounded source identifier and an exception summary.

These rules deliberately do not copy the Android sample's hard-coded network interface, regular-expression XML parsing, ICMP reachability loop or diagnostic logging of authentication/network values.

## API

```java
CcapiDiscoveryService discovery = new CcapiDiscoveryService();
CcapiDiscoveryResult result = discovery.discover(Duration.ofSeconds(5));

for (CcapiDiscoveredCamera camera : result.cameras()) {
    URI ccapiRoot = camera.accessUrl();
    String model = camera.modelName();
}
```

A discovery result may contain both cameras and warnings. Failure on one interface or one malformed device description does not discard cameras found through other bindings.

## Deliberate non-goals of this slice

- no REST or UI endpoint yet;
- no automatic profile creation;
- no connection attempt or digest authentication;
- no background reachability monitor;
- no changes to capture, live view, media or focus workflows.

Those integrations must preserve the same explicit timeout, cancellation, redaction and capability rules and are tracked in issue #143.

## Local verification

The discovery tests use synthetic SSDP packets and device-description XML. They do not require a camera, multicast-capable CI environment or GitHub Actions:

```bash
mvn -pl app -Dtest='com.photographer.focusstack.camera.discovery.*Test' test
```

Real-camera diagnostics should be optional and tagged. They may record normalized identity/capability data, but must not persist credentials, authorization headers, raw Wi-Fi settings or unredacted packets.
