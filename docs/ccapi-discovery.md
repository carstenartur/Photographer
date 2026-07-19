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

The core caller supplies an overall timeout between a positive duration and five minutes. The service divides the remaining deadline among network bindings and device-description requests.

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

## Java API

```java
CcapiDiscoveryService discovery = new CcapiDiscoveryService();
CcapiDiscoveryResult result = discovery.discover(Duration.ofSeconds(5));

for (CcapiDiscoveredCamera camera : result.cameras()) {
    URI ccapiRoot = camera.accessUrl();
    String model = camera.modelName();
}
```

A discovery result may contain both cameras and warnings. Failure on one interface or one malformed device description does not discard cameras found through other bindings.

## Authenticated HTTP boundary

The application exposes the discovery core without coupling it to camera-profile persistence:

```http
POST /api/cameras/discovery?timeoutSeconds=5
```

- Authentication is required by the application's normal security policy.
- The HTTP timeout policy accepts 1–30 seconds; the default is 5 seconds.
- The operation is read-only: it does not create or update profiles and does not connect to a camera.
- The response contains the normalized CCAPI access URL and camera identity fields required for an explicit user choice.
- The internal device-description URL is deliberately omitted from the HTTP response.
- Non-fatal warnings remain structured and redacted.

Example response:

```json
{
  "cameras": [
    {
      "accessUrl": "http://192.168.1.2:8080/ccapi",
      "udn": "uuid:camera-one",
      "friendlyName": "Canon Camera",
      "modelName": "PowerShot G5 X Mark II",
      "serialNumber": "123456789",
      "nickname": "Studio",
      "serviceConnected": true
    }
  ],
  "warnings": []
}
```

## Deliberate non-goals of this slice

- no automatic profile creation or update;
- no automatic connection attempt or digest authentication;
- no background reachability monitor;
- no claim that SSDP discovery is authoritative session state;
- no changes to capture, live view, media or focus workflows.

Those integrations must preserve the same explicit timeout, cancellation, redaction and capability rules and are tracked in issue #143.

## Local verification

The discovery tests use synthetic SSDP packets and device-description XML. They do not require a camera, multicast-capable CI environment or GitHub Actions:

```bash
mvn -pl app -am -Dtest='com.photographer.focusstack.camera.discovery.*Test,com.photographer.focusstack.camera.controller.CameraDiscoveryControllerTest' test
```

Real-camera diagnostics should be optional and tagged. They may record normalized identity/capability data, but must not persist credentials, authorization headers, raw Wi-Fi settings or unredacted packets.
