package com.photographer.ptp.integration;

import com.photographer.ptp.canon.CanonConstants;
import com.photographer.ptp.canon.CanonPropertyDecoder;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.session.CameraCapabilities;
import com.photographer.ptp.session.ImageDownload;
import com.photographer.ptp.session.PtpCamera;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cleanroom CR3 decoder fixture-capture IT.
 *
 * <p>Drives the connected Canon PowerShot G5 X Mark II over USB and captures
 * a curated panel of CR3 frames whose camera-side settings (ISO, shutter
 * speed, aperture) are <em>known and recorded</em>. The output files and a
 * companion manifest are written to:
 *
 * <pre>
 * canon-cr3-imageio/src/test/resources/samples/research/
 *     g5xm2-iso&lt;label&gt;-ss&lt;label&gt;-f&lt;label&gt;-&lt;idx&gt;.cr3
 *     manifest.tsv
 * </pre>
 *
 * <p>The intent is to give the cleanroom CR3 decoder research (see
 * {@code canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/research})
 * a wider set of fixtures with controlled metadata so that the relationship
 * between {@code CMP1.quantValue}, the per-plane K-init seed and the
 * predictor/k-update rules can be cross-validated against real samples that
 * span the full ISO range of the sensor.
 *
 * <h3>Sweep</h3>
 * <ol>
 *   <li><b>ISO sweep</b> at the camera's currently-selected aperture and a
 *       moderate shutter (1/30s default). Eight ISO values from ISO 100 to
 *       ISO 12800. This is the most useful sweep for the decoder because
 *       Canon's wavelet quantiser scales with analog gain.</li>
 *   <li><b>Repeat shot</b> at ISO 400 — a second exposure with identical
 *       settings, used to verify decoder determinism (per-tile bitstream
 *       structure must be reproducible across captures).</li>
 *   <li><b>Long exposure</b> at base ISO — yields a noise-reduced frame that
 *       is useful for sanity-checking the predictor seed at the start of
 *       each tile (no read-noise speckle near the seam).</li>
 * </ol>
 *
 * <h3>Run</h3>
 * <pre>
 * mvn -B verify -pl camera-ptp -am ^
 *     -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true ^
 *     -Dit.test=CleanroomCr3FixtureCaptureIT
 * </pre>
 *
 * <p>Tagged {@code real-camera} so it is skipped in CI by default. The whole
 * IT becomes a no-op (assumption-aborted) if the camera is not connected /
 * the PC connection mode is not enabled.
 */
@Tag("real-camera")
@EnabledIfSystemProperty(named = "ptp.test.usb", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CleanroomCr3FixtureCaptureIT {

    private static final Logger log = LoggerFactory.getLogger(CleanroomCr3FixtureCaptureIT.class);

    /**
     * Output directory, resolved relative to the {@code camera-ptp} module
     * working directory used by Surefire/Failsafe.
     */
    private static final Path OUT_DIR = Path.of(
            "..", "canon-cr3-imageio", "src", "test", "resources", "samples", "research")
            .toAbsolutePath().normalize();

    /**
     * Canon EOS raw values for ISO speeds — see CanonPropertyDecoder.ISO_VALUES.
     * Restricted to the PowerShot G5 X Mark II's native range (ISO 125 – 12 800).
     * ISO 100 (0x48) is rejected by this body because the sensor's native floor
     * is ISO 125.
     */
    private static final int[] ISO_RAW_SWEEP = {
            0x4B, // ISO 125  (native floor on G5X II)
            0x50, // ISO 200
            0x58, // ISO 400
            0x60, // ISO 800
            0x68, // ISO 1600
            0x70, // ISO 3200
            0x78, // ISO 6400
            0x80  // ISO 12800 (native ceiling on G5X II)
    };

    /** Number of identical-settings captures in Phase A. */
    private static final int PHASE_A_BURST = 5;

    /** Settle interval after a property change before tripping the shutter. */
    private static final long SETTLE_AFTER_PROP_MS = 3_000;

    /** Settle interval between consecutive same-settings captures. */
    private static final long SETTLE_BETWEEN_CAPTURES_MS = 2_000;

    /** Stop a phase after this many consecutive remote-release failures. */
    private static final int MAX_CONSEC_FAILURES = 3;

    /**
     * Scene tag applied to the {@code asis} burst. Set with
     * {@code -Dfixture.scene=<name>}; defaults to {@code asis}.
     *
     * <p>Recommended scenes for the cleanroom CR3 decoder:</p>
     * <ul>
     *   <li>{@code dark} — lens cap on. Verifies the OB pedestal and that
     *       the wavelet HH/HL/LH subbands collapse to ≈0; the rice-golomb
     *       decoder should see almost only short unary runs.</li>
     *   <li>{@code flat} — defocused white wall. Uniform LL, near-zero
     *       high-freq content; exposes lens shading.</li>
     *   <li>{@code detail} — high-detail real-world scene (foliage,
     *       textured fabric). Exercises the full k-update range.</li>
     *   <li>{@code clip} — over-exposed (look at a bright window). Pins
     *       the upper saturation branch in the predictor.</li>
     *   <li>{@code asis} (default) — whatever the operator dialled in.</li>
     * </ul>
     */
    private static final String SCENE_TAG =
            System.getProperty("fixture.scene", "asis").replaceAll("[^A-Za-z0-9_+-]", "");

    /**
     * Optional: when set to a non-zero hex value (e.g. {@code -Dfixture.imageQualityRaw=0x10130004}),
     * the IT attempts a single best-effort write of {@code DPC_EOS_IMAGE_QUALITY (0xD108)}
     * before Phase A. The current PowerShot G5X II PSREC firmware is known to reject most
     * EOS-property writes with {@code Device_Busy 0x2019} (see README in the research
     * fixture directory) — this hook lets us measure whether {@code 0xD108} happens to be
     * an exception. The cached value is re-read after a 3 s settle and the difference is
     * logged either way; capture proceeds regardless of outcome.
     *
     * <p>The original value is recorded at startup and a best-effort restore is attempted
     * after the panel completes.</p>
     */
    private static final int FIXTURE_IMAGE_QUALITY_OVERRIDE = parseHexProperty(
            "fixture.imageQualityRaw", 0);

    private static int parseHexProperty(String key, int defVal) {
        String s = System.getProperty(key, "");
        if (s == null || s.isBlank()) return defVal;
        s = s.trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    private PtpCamera camera;

    @BeforeAll
    void openCamera() throws IOException {
        Files.createDirectories(OUT_DIR);
        try {
            camera = PtpCamera.openUsb();
            log.info("Camera opened for cleanroom CR3 fixture capture");
        } catch (PtpException e) {
            String causeMsg = e.getCause() != null ? e.getCause().getMessage() : "";
            if (causeMsg != null && (causeMsg.contains("Access denied")
                    || causeMsg.contains("insufficient permissions"))) {
                Assumptions.abort("USB claim denied — install WinUSB via Zadig. See "
                        + "UsbCanonCameraIT for the full instructions.");
            }
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("No Canon")) {
                Assumptions.abort("No Canon camera connected via USB — skipping fixture capture.");
            }
            throw e;
        }
        Assumptions.assumeTrue(camera.isCanon(),
                "Connected camera is not Canon EOS — fixture sweep skipped");
        CameraCapabilities caps = camera.getCapabilities();
        Assumptions.assumeTrue(caps.capture(),
                "Camera does not advertise remote-capture support");
        Assumptions.assumeTrue(caps.propertiesWrite(),
                "Camera does not advertise property-write support");
    }

    @AfterAll
    void closeCamera() {
        if (camera != null) {
            try { camera.close(); } catch (RuntimeException e) {
                log.warn("Camera close failed: {}", e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Capture a known-settings CR3 fixture panel for cleanroom decoder research")
    void captureFixturePanel() throws IOException, InterruptedException {
        int origIso      = safeRead(camera::getIsoRaw);
        int origShutter  = safeRead(camera::getShutterSpeedRaw);
        int origAperture = safeRead(camera::getApertureRaw);
        int origImageQuality = safeRead(() -> camera.getCanonPropertyRaw(CanonConstants.DPC_EOS_IMAGE_QUALITY));
        log.info("═══ Original settings (will be restored if possible) ═══");
        log.info("  ISO          : {} (raw=0x{})", camera.getIso(), Integer.toHexString(origIso));
        log.info("  Shutter      : {} (raw=0x{})", camera.getShutterSpeed(), Integer.toHexString(origShutter));
        log.info("  Aperture     : {} (raw=0x{})", camera.getAperture(), Integer.toHexString(origAperture));
        log.info("  ImageQuality : raw=0x{} (0 means camera has not yet emitted DPC 0xD108)",
                Integer.toHexString(origImageQuality));

        // ── Phase 0 — best-effort ImageQuality probe ───────────────────────────
        if (FIXTURE_IMAGE_QUALITY_OVERRIDE != 0) {
            log.info("═══ Phase 0 — attempting ImageQuality probe write 0x{} ═══",
                    Integer.toHexString(FIXTURE_IMAGE_QUALITY_OVERRIDE));
            boolean took = tryProbeImageQuality(FIXTURE_IMAGE_QUALITY_OVERRIDE);
            log.info("ImageQuality probe write {} (cache now reads 0x{})",
                    took ? "ACCEPTED" : "REJECTED",
                    Integer.toHexString(safeRead(() -> camera.getCanonPropertyRaw(CanonConstants.DPC_EOS_IMAGE_QUALITY))));
        }

        List<CapturedFixture> manifest = new ArrayList<>();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        // Allow the EOS event-mode handshake to settle before the first shutter trip.
        Thread.sleep(2_000);

        // ── Phase A — current-settings burst ───────────────────────────────────
        log.info("═══ Phase A — capturing {} '{}' frames at current settings ═══",
                PHASE_A_BURST, SCENE_TAG);
        int phaseAFails = 0;
        for (int i = 1; i <= PHASE_A_BURST; i++) {
            CapturedFixture f = captureAndSave(SCENE_TAG, i, runId);
            if (f != null) {
                manifest.add(f);
                phaseAFails = 0;
            } else if (++phaseAFails >= MAX_CONSEC_FAILURES) {
                log.warn("Phase A: aborting after {} consecutive failures", phaseAFails);
                break;
            }
            Thread.sleep(SETTLE_BETWEEN_CAPTURES_MS);
        }

        // ── Phase B — best-effort ISO sweep ─────────────────────────────────────
        if (!manifest.isEmpty()) {
            log.info("═══ Phase B — best-effort ISO sweep ═══");
            int consecFail = 0;
            int idx = 0;
            for (int isoRaw : ISO_RAW_SWEEP) {
                idx++;
                if (consecFail >= MAX_CONSEC_FAILURES) {
                    log.warn("Phase B: stopping after {} consecutive failures", consecFail);
                    break;
                }
                if (!trySetIso(isoRaw)) { consecFail++; continue; }
                Thread.sleep(SETTLE_AFTER_PROP_MS);
                CapturedFixture f = captureAndSave("isosweep", idx, runId);
                if (f == null) {
                    consecFail++;
                } else {
                    consecFail = 0;
                    manifest.add(f);
                }
                Thread.sleep(SETTLE_BETWEEN_CAPTURES_MS);
            }
        } else {
            log.warn("Skipping Phase B because Phase A captured no frames");
        }

        // ── Restore originals (best effort) ────────────────────────────────────
        if (origIso != 0) trySetIso(origIso);
        if (origShutter != 0) {
            try { camera.setShutterSpeed(origShutter); }
            catch (RuntimeException e) { log.debug("Shutter restore skipped: {}", e.getMessage()); }
        }
        if (FIXTURE_IMAGE_QUALITY_OVERRIDE != 0 && origImageQuality != 0
                && origImageQuality != FIXTURE_IMAGE_QUALITY_OVERRIDE) {
            log.info("Restoring ImageQuality to original 0x{}",
                    Integer.toHexString(origImageQuality));
            tryProbeImageQuality(origImageQuality);
        }

        writeManifest(manifest, runId);

        assertFalse(manifest.isEmpty(),
                "Phase A produced zero CR3 frames — see log for capture errors");
        log.info("═══ Captured {} CR3 fixtures into {} ═══", manifest.size(), OUT_DIR);
    }

    // ─────────────────────────────────────────────────────── helpers ──

    @FunctionalInterface
    private interface IntSupplier { int get(); }

    private static int safeRead(IntSupplier s) {
        try { return s.get(); } catch (RuntimeException e) { return 0; }
    }

    private boolean trySetIso(int raw) {
        try {
            camera.setIso(raw);
            return true;
        } catch (RuntimeException e) {
            log.warn("setIso(0x{}) rejected: {}", Integer.toHexString(raw), e.getMessage());
            return false;
        }
    }

    /**
     * Best-effort ImageQuality (0xD108) write. The G5X II rejects most EOS
     * property writes (Device_Busy 0x2019); this method swallows that and
     * reports whether the cache reflects the requested value after a settle.
     */
    private boolean tryProbeImageQuality(int rawValue) {
        try {
            camera.setCanonProperty(CanonConstants.DPC_EOS_IMAGE_QUALITY, rawValue);
        } catch (RuntimeException e) {
            log.warn("setCanonProperty(0xD108=0x{}) rejected: {}",
                    Integer.toHexString(rawValue), e.getMessage());
            return false;
        }
        try { Thread.sleep(SETTLE_AFTER_PROP_MS); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        int back = safeRead(() -> camera.getCanonPropertyRaw(CanonConstants.DPC_EOS_IMAGE_QUALITY));
        return back == rawValue;
    }

    private CapturedFixture captureAndSave(String tag, int seq, String runId)
            throws IOException, InterruptedException {
        // Let the camera settle after the property change before tripping the shutter.
        Thread.sleep(750);

        int isoRaw = camera.getIsoRaw();
        int ssRaw = camera.getShutterSpeedRaw();
        int avRaw = camera.getApertureRaw();
        int iqRaw = safeRead(() -> camera.getCanonPropertyRaw(CanonConstants.DPC_EOS_IMAGE_QUALITY));
        String isoLbl = sanitize(CanonPropertyDecoder.decodeIso(isoRaw));
        String ssLbl = sanitize(CanonPropertyDecoder.decodeShutterSpeed(ssRaw));
        String avLbl = sanitize(CanonPropertyDecoder.decodeAperture(avRaw));
        String iqLbl = iqRaw == 0 ? "x" : Integer.toHexString(iqRaw);

        ImageDownload dl;
        try {
            dl = camera.capture();
        } catch (RuntimeException e) {
            log.error("Capture failed for {}-{}: {}", tag, seq, e.getMessage());
            return null;
        }
        if (dl == null || dl.size() == 0) {
            log.warn("Capture for {}-{} returned no data (timeout?)", tag, seq);
            return null;
        }

        String camFn = dl.fileName() == null ? "object" : dl.fileName();
        String ext = camFn.toLowerCase().endsWith(".cr3") ? ".cr3"
                : camFn.toLowerCase().endsWith(".jpg") ? ".jpg"
                : ".bin";
        // Include runId in the filename so successive runs accumulate fixtures
        // instead of overwriting each other (same-settings bursts naturally
        // produce identical labels).
        String outName = String.format("g5xm2-%s-iso%s-ss%s-f%s-iq%s-%s-%02d%s",
                tag, isoLbl, ssLbl, avLbl, iqLbl, runId, seq, ext);
        Path outPath = OUT_DIR.resolve(outName);
        Files.write(outPath, dl.data(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Saved {} ({} bytes) — iso={} ss={} f={} iq=0x{} (camera filename: {})",
                outName, dl.size(),
                CanonPropertyDecoder.decodeIso(isoRaw),
                CanonPropertyDecoder.decodeShutterSpeed(ssRaw),
                CanonPropertyDecoder.decodeAperture(avRaw),
                iqLbl,
                camFn);

        return new CapturedFixture(outName, runId, tag, seq,
                isoRaw, ssRaw, avRaw, iqRaw, dl.size(), camFn);
    }

    private static String sanitize(String s) {
        if (s == null) return "x";
        return s.replace("\"", "s")
                .replace("/", "")
                .replace(" ", "")
                .replace("(", "")
                .replace(")", "")
                .replace(".", "p")
                .replaceAll("[^A-Za-z0-9_+-]", "");
    }

    private static void writeManifest(List<CapturedFixture> rows, String runId) throws IOException {
        Path manifest = OUT_DIR.resolve("manifest.tsv");
        boolean fresh = !Files.exists(manifest);
        StringBuilder sb = new StringBuilder();
        if (fresh) {
            sb.append("filename\trunId\ttag\tseq\tisoLabel\tisoRaw\tshutterLabel\tshutterRaw")
              .append("\tapertureLabel\tapertureRaw\timageQualityRaw\tbytes\tcameraFilename\n");
        }
        for (CapturedFixture r : rows) {
            sb.append(r.filename).append('\t')
              .append(r.runId).append('\t')
              .append(r.tag).append('\t')
              .append(r.seq).append('\t')
              .append(CanonPropertyDecoder.decodeIso(r.isoRaw)).append('\t')
              .append("0x").append(Integer.toHexString(r.isoRaw)).append('\t')
              .append(CanonPropertyDecoder.decodeShutterSpeed(r.ssRaw)).append('\t')
              .append("0x").append(Integer.toHexString(r.ssRaw)).append('\t')
              .append(CanonPropertyDecoder.decodeAperture(r.avRaw)).append('\t')
              .append("0x").append(Integer.toHexString(r.avRaw)).append('\t')
              .append("0x").append(Integer.toHexString(r.iqRaw)).append('\t')
              .append(r.bytes).append('\t')
              .append(r.cameraFilename).append('\n');
        }
        Files.writeString(manifest, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.info("Manifest updated: {} (added {} rows, runId={})", manifest, rows.size(), runId);
    }

    /** Per-frame manifest record. */
    private record CapturedFixture(String filename, String runId, String tag, int seq,
                                   int isoRaw, int ssRaw, int avRaw, int iqRaw, int bytes,
                                   String cameraFilename) { }
}
