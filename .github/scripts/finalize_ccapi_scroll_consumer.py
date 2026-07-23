from pathlib import Path


def replace_once(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected one anchor, found {count}: {old[:120]!r}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


transport = (
    "app/src/main/java/com/photographer/focusstack/camera/backend/"
    "CcapiScrollFrameTransport.java"
)
replace_once(
    transport,
    "final class CcapiScrollFrameTransport {",
    "class CcapiScrollFrameTransport {",
)

client = (
    "app/src/main/java/com/photographer/focusstack/camera/backend/"
    "CcapiLiveViewClient.java"
)
replace_once(
    client,
    """ * <p>The client maps every exact advertised Canon transport to a vendor-neutral mode,
 * but currently implements frame retrieval only for {@link Mode#SINGLE_FRAME}. An
 * advertised endpoint with incompatible method metadata is never called, and an
 * unimplemented stream mode is never reported as a usable frame source.</p>""",
    """ * <p>The client maps every exact advertised Canon transport to a vendor-neutral mode.
 * It implements direct {@link Mode#SINGLE_FRAME} retrieval and a bounded one-frame pull
 * from Canon's long-lived {@link Mode#CURSOR_STREAM}. An advertised endpoint with
 * incompatible method metadata is never called, and an unimplemented stream mode is
 * never reported as a usable frame source.</p>""",
)
replace_once(
    client,
    """        CURSOR_STREAM(
                "cursor-stream",
                "/shooting/liveview/scroll",
                Set.of(HttpMethod.GET, HttpMethod.DELETE),
                false),""",
    """        CURSOR_STREAM(
                "cursor-stream",
                "/shooting/liveview/scroll",
                Set.of(HttpMethod.GET, HttpMethod.DELETE),
                true),""",
)
replace_once(
    client,
    """    private final Waiter waiter;
    private final Object lifecycleMonitor = new Object();""",
    """    private final Waiter waiter;
    private final CcapiScrollFrameTransport scrollTransport;
    private final Object lifecycleMonitor = new Object();""",
)
replace_once(
    client,
    """    CcapiLiveViewClient(
            String profileId,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier,
            Poster poster,
            Duration startDelay,
            Waiter waiter) {
        this.profileId = requireProfileId(profileId);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.endpointSupplier = Objects.requireNonNull(endpointSupplier, "endpointSupplier");
        this.methodSupplier = Objects.requireNonNull(methodSupplier, "methodSupplier");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.startDelay = requireNonNegative(startDelay, "startDelay");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }""",
    """    CcapiLiveViewClient(
            String profileId,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier,
            Poster poster,
            Duration startDelay,
            Waiter waiter) {
        this(
                profileId,
                restTemplate,
                endpointSupplier,
                methodSupplier,
                poster,
                startDelay,
                waiter,
                new CcapiScrollFrameTransport(restTemplate));
    }

    CcapiLiveViewClient(
            String profileId,
            RestTemplate restTemplate,
            Supplier<Map<String, String>> endpointSupplier,
            Supplier<Map<String, Set<HttpMethod>>> methodSupplier,
            Poster poster,
            Duration startDelay,
            Waiter waiter,
            CcapiScrollFrameTransport scrollTransport) {
        this.profileId = requireProfileId(profileId);
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.endpointSupplier = Objects.requireNonNull(endpointSupplier, "endpointSupplier");
        this.methodSupplier = Objects.requireNonNull(methodSupplier, "methodSupplier");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.startDelay = requireNonNegative(startDelay, "startDelay");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.scrollTransport = Objects.requireNonNull(scrollTransport, "scrollTransport");
    }""",
)
replace_once(
    client,
    """    private Endpoint requireFrameEndpoint() {
        Optional<Endpoint> selected = selectFrameEndpoint();
        if (selected.isPresent()) {
            return selected.orElseThrow();
        }

        Optional<Endpoint> advertisedSingleFrame = highestExact(Mode.SINGLE_FRAME.suffix());
        if (advertisedSingleFrame.isPresent()
                && advertisedSingleFrame.orElseThrow().methodMetadataKnown()) {
            throw operation(METHOD_NOT_ALLOWED);
        }
        throw operation(ENDPOINT_NOT_FOUND);
    }""",
    """    private Endpoint requireFrameEndpoint() {
        Optional<Endpoint> selected = selectFrameEndpoint();
        if (selected.isPresent()) {
            return selected.orElseThrow();
        }

        for (Mode mode : Mode.values()) {
            if (!mode.frameRetrievalImplemented()) {
                continue;
            }
            Optional<Endpoint> advertised = highestExact(mode.suffix());
            if (advertised.isPresent()
                    && advertised.orElseThrow().methodMetadataKnown()) {
                throw operation(METHOD_NOT_ALLOWED);
            }
        }
        throw operation(ENDPOINT_NOT_FOUND);
    }""",
)
replace_once(
    client,
    """    private LiveViewFrame readFrame(Endpoint endpoint) {
        byte[] data = restTemplate.getForObject(endpoint.url(), byte[].class);
        return new LiveViewFrame(
                data == null ? new byte[0] : data,
                960,
                640,
                Instant.now(),
                MediaType.IMAGE_JPEG_VALUE);
    }""",
    """    private LiveViewFrame readFrame(Endpoint endpoint) {
        if (endpoint.path().endsWith(Mode.CURSOR_STREAM.suffix())) {
            return readCursorFrame(endpoint);
        }
        byte[] data = restTemplate.getForObject(endpoint.url(), byte[].class);
        return frame(data);
    }

    private LiveViewFrame readCursorFrame(Endpoint endpoint) {
        RuntimeException primaryFailure = null;
        try {
            byte[] data = readCursorBytes(endpoint);
            return frame(data);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                stopCursor(endpoint);
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw liveViewFailure(
                            "Could not stop the Canon scroll live-view stream",
                            cleanupFailure);
                }
            }
        }
    }

    private byte[] readCursorBytes(Endpoint endpoint) {
        try {
            return scrollTransport.readFirstFrame(endpoint.url());
        } catch (RuntimeException failure) {
            if (reason(failure) != ALREADY_STARTED) {
                throw failure;
            }
            stopCursor(endpoint);
            return scrollTransport.readFirstFrame(endpoint.url());
        }
    }

    private void stopCursor(Endpoint endpoint) {
        try {
            scrollTransport.stop(endpoint.url());
        } catch (RuntimeException failure) {
            CameraOperationException.Reason typedReason = reason(failure);
            if (typedReason == LIVE_VIEW_NOT_STARTED || typedReason == NOT_STARTED) {
                return;
            }
            throw failure;
        }
    }

    private static LiveViewFrame frame(byte[] data) {
        return new LiveViewFrame(
                data == null ? new byte[0] : data,
                960,
                640,
                Instant.now(),
                MediaType.IMAGE_JPEG_VALUE);
    }""",
)

test = (
    "app/src/test/java/com/photographer/focusstack/camera/backend/"
    "CcapiLiveViewClientTest.java"
)
replace_once(
    test,
    "import static org.mockito.Mockito.never;\nimport static org.mockito.Mockito.verify;",
    "import static org.mockito.Mockito.never;\n"
    "import static org.mockito.Mockito.times;\n"
    "import static org.mockito.Mockito.verify;",
)
replace_once(
    test,
    "    private static final String FLIP_100 = CONTROL_100 + \"/flip\";",
    "    private static final String FLIP_100 = CONTROL_100 + \"/flip\";\n"
    "    private static final String SCROLL_110 =\n"
    "            BASE + \"/ccapi/ver110/shooting/liveview/scroll\";",
)
replace_once(
    test,
    """        assertThat(fixture.client.advertisedModes())
                .filteredOn(CcapiLiveViewClient.AdvertisedMode::frameRetrievalImplemented)
                .singleElement()
                .satisfies(mode -> {
                    assertThat(mode.mode()).isEqualTo(CcapiLiveViewClient.Mode.SINGLE_FRAME);
                    assertThat(mode.methodCompatible()).isTrue();
                });""",
    """        assertThat(fixture.client.advertisedModes())
                .filteredOn(CcapiLiveViewClient.AdvertisedMode::frameRetrievalImplemented)
                .extracting(CcapiLiveViewClient.AdvertisedMode::mode)
                .containsExactly(
                        CcapiLiveViewClient.Mode.SINGLE_FRAME,
                        CcapiLiveViewClient.Mode.CURSOR_STREAM);""",
)
replace_once(
    test,
    """    @Test
    void mapsScrollOnlyCatalogButDoesNotClaimFrameRetrieval() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver110/shooting/liveview", BASE + "/ccapi/ver110/shooting/liveview",
                        "/ccapi/ver110/shooting/liveview/scroll",
                        BASE + "/ccapi/ver110/shooting/liveview/scroll"),
                methods(
                        "/ccapi/ver110/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver110/shooting/liveview/scroll",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));

        assertThat(fixture.client.advertisedModes())
                .singleElement()
                .satisfies(mode -> {
                    assertThat(mode.mode()).isEqualTo(CcapiLiveViewClient.Mode.CURSOR_STREAM);
                    assertThat(mode.methodCompatible()).isTrue();
                    assertThat(mode.frameRetrievalImplemented()).isFalse();
                });
        assertThat(fixture.client.supportsFrameRetrieval()).isFalse();
        assertThatThrownBy(fixture.client::getFrame)
                .isInstanceOfSatisfying(CameraOperationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ENDPOINT_NOT_FOUND));
    }""",
    """    @Test
    void consumesOneBoundedFrameFromScrollOnlyCatalogAndDeletesTheStream() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver110/shooting/liveview", BASE + "/ccapi/ver110/shooting/liveview",
                        "/ccapi/ver110/shooting/liveview/scroll", SCROLL_110),
                methods(
                        "/ccapi/ver110/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver110/shooting/liveview/scroll",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 4, (byte) 0xff, (byte) 0xd9};
        when(fixture.scrollTransport.readFirstFrame(SCROLL_110)).thenReturn(jpeg);

        LiveViewFrame frame = fixture.client.getFrame();

        assertThat(frame.data()).containsExactly(jpeg);
        assertThat(fixture.client.supportsFrameRetrieval()).isTrue();
        assertThat(fixture.client.advertisedModes())
                .singleElement()
                .satisfies(mode -> {
                    assertThat(mode.mode()).isEqualTo(CcapiLiveViewClient.Mode.CURSOR_STREAM);
                    assertThat(mode.methodCompatible()).isTrue();
                    assertThat(mode.frameRetrievalImplemented()).isTrue();
                });
        verify(fixture.scrollTransport).readFirstFrame(SCROLL_110);
        verify(fixture.scrollTransport).stop(SCROLL_110);
    }

    @Test
    void closesStaleScrollAndRetriesOnceWhenCameraReportsAlreadyStarted() {
        Fixture fixture = fixture(
                endpoints(
                        "/ccapi/ver110/shooting/liveview", BASE + "/ccapi/ver110/shooting/liveview",
                        "/ccapi/ver110/shooting/liveview/scroll", SCROLL_110),
                methods(
                        "/ccapi/ver110/shooting/liveview", Set.of(HttpMethod.POST),
                        "/ccapi/ver110/shooting/liveview/scroll",
                        Set.of(HttpMethod.GET, HttpMethod.DELETE)));
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 8, (byte) 0xff, (byte) 0xd9};
        when(fixture.scrollTransport.readFirstFrame(SCROLL_110))
                .thenThrow(operation(ALREADY_STARTED))
                .thenReturn(jpeg);

        assertThat(fixture.client.getFrame().data()).containsExactly(jpeg);

        verify(fixture.scrollTransport, times(2)).readFirstFrame(SCROLL_110);
        verify(fixture.scrollTransport, times(2)).stop(SCROLL_110);
    }""",
)
replace_once(
    test,
    """        RestTemplate restTemplate = mock(RestTemplate.class);
        RecordingPoster poster = new RecordingPoster();
        CcapiLiveViewClient client = new CcapiLiveViewClient(
                PROFILE,
                restTemplate,
                () -> endpoints,
                () -> methods,
                poster,
                Duration.ZERO,
                ignored -> { });
        return new Fixture(restTemplate, poster, client);""",
    """        RestTemplate restTemplate = mock(RestTemplate.class);
        CcapiScrollFrameTransport scrollTransport = mock(CcapiScrollFrameTransport.class);
        RecordingPoster poster = new RecordingPoster();
        CcapiLiveViewClient client = new CcapiLiveViewClient(
                PROFILE,
                restTemplate,
                () -> endpoints,
                () -> methods,
                poster,
                Duration.ZERO,
                ignored -> { },
                scrollTransport);
        return new Fixture(restTemplate, scrollTransport, poster, client);""",
)
replace_once(
    test,
    """    private record Fixture(
            RestTemplate restTemplate,
            RecordingPoster poster,
            CcapiLiveViewClient client) { }""",
    """    private record Fixture(
            RestTemplate restTemplate,
            CcapiScrollFrameTransport scrollTransport,
            RecordingPoster poster,
            CcapiLiveViewClient client) { }""",
)

production_test = (
    "app/src/test/java/com/photographer/focusstack/camera/backend/"
    "CanonCcapiProductionLiveViewTest.java"
)
replace_once(
    production_test,
    "import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.ADVERTISED_NOT_IMPLEMENTED;",
    "import static com.photographer.focusstack.common.camera.CameraCapabilitySnapshot.Availability.AVAILABLE;",
)
replace_once(
    production_test,
    "void productionSnapshotExposesAdvertisedButUnimplementedScrollMode()",
    "void productionSnapshotExposesImplementedScrollMode()",
)
replace_once(
    production_test,
    """        assertThat(scroll.advertised()).isTrue();
        assertThat(scroll.implemented()).isFalse();
        assertThat(scroll.availability()).isEqualTo(ADVERTISED_NOT_IMPLEMENTED);
        assertThat(scroll.requiredMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.advertisedMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.methodMetadataKnown()).isTrue();
        assertThat(scroll.condition()).isEqualTo("APPLICATION_NOT_IMPLEMENTED");""",
    """        assertThat(scroll.advertised()).isTrue();
        assertThat(scroll.implemented()).isTrue();
        assertThat(scroll.availability()).isEqualTo(AVAILABLE);
        assertThat(scroll.requiredMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.advertisedMethods()).containsExactlyInAnyOrder("GET", "DELETE");
        assertThat(scroll.methodMetadataKnown()).isTrue();
        assertThat(scroll.condition()).isNull();""",
)

docs = "docs/ccapi-live-view.md"
replace_once(
    docs,
    "| `cursor-stream` | `/shooting/liveview/scroll` | `GET`, `DELETE` | Advertised and diagnosed; consumer not implemented |",
    "| `cursor-stream` | `/shooting/liveview/scroll` | `GET`, `DELETE` | Implemented as a bounded one-frame pull with mandatory DELETE cleanup |",
)
replace_once(
    docs,
    "The client chooses the least complex implemented mode. It currently selects the highest-version exact `single-frame` endpoint that either advertises `GET` or comes from a legacy catalog without method metadata. It never constructs a fallback frame URL and never invokes an endpoint that explicitly advertises an incompatible method.",
    "The client chooses the least complex implemented mode. It first selects the highest-version exact `single-frame` endpoint that either advertises `GET` or comes from a legacy catalog without method metadata. When no compatible single-frame endpoint exists, it selects the highest compatible `cursor-stream` endpoint. It never constructs a fallback frame URL and never invokes an endpoint that explicitly advertises an incompatible method.",
)
replace_once(
    docs,
    """The implemented single-frame path inherits the production CCAPI connection and response timeouts. Start and stop requests inherit the existing bounded `Device busy` retry policy.

Scroll and multipart readers are intentionally not implemented until they have:

- a bounded read loop;
- explicit `DELETE` cancellation;
- deterministic close behavior on interruption, timeout and application shutdown;
- tests proving no duplicate start/stop/delete requests.

RTP remains unimplemented until SDP acquisition, target-address selection, receiver shutdown and bounded socket cancellation are owned by one transport component.

An advertised-but-unimplemented stream is reported as such; it is never silently treated as a usable live-view frame source.""",
    """The single-frame and cursor-stream paths inherit the production CCAPI connection and 30-second response timeout. Start and global stop requests inherit the existing bounded `Device busy` retry policy.

The cursor consumer:

- scans at most 64 KiB before JPEG SOI;
- retains at most one 4 MiB JPEG;
- has no producer queue or unbounded buffering;
- returns after the first JPEG EOI marker;
- sends `DELETE` after success, malformed data, interruption, timeout, or typed camera failure;
- accepts both Canon's documented `image/octet-stream` and firmware-compatible `application/octet-stream`;
- closes one stale cursor and retries once for typed `Already started`.

Multipart remains unimplemented until equivalent MIME-boundary, part-size, cancellation, and close guarantees are tested. RTP remains unimplemented until SDP acquisition, target-address selection, receiver shutdown, and bounded socket cancellation are owned by one transport component.

Advertised-but-unimplemented modes remain diagnostic-only and are never silently treated as usable frame sources.""",
)
