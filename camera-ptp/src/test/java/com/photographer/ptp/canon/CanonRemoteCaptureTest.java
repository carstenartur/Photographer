package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.mock.MockPtpTransport;
import com.photographer.ptp.usb.PtpTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CanonRemoteCapture} 3-phase shutter sequence.
 */
class CanonRemoteCaptureTest {

    /**
     * Records all containers passed to {@link #execute(PtpContainer)} for later inspection.
     */
    private static class RecordingTransport extends MockPtpTransport {
        final List<PtpContainer> executed = new ArrayList<>();

        @Override
        public PtpContainer execute(PtpContainer request) {
            executed.add(request);
            return super.execute(request);
        }
    }

    @Test
    void captureSequence_allThreePhasesInOrder() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        // Use zero delays so the test completes instantly
        CanonRemoteCapture capture = new CanonRemoteCapture(transport, 0, 0, 0);
        AtomicInteger txId = new AtomicInteger(1);

        capture.capture(txId::getAndIncrement);

        // Should have recorded exactly 3 commands: half-press, full-press, release
        assertEquals(3, captureCommandCount(transport.executed),
                "Expected exactly 3 shutter commands");
    }

    @Test
    void captureSequence_phase1_isHalfPress() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        CanonRemoteCapture capture = new CanonRemoteCapture(transport, 0, 0, 0);
        AtomicInteger txId = new AtomicInteger(1);

        capture.capture(txId::getAndIncrement);

        List<PtpContainer> cmds = shutterCommands(transport.executed);
        PtpContainer phase1 = cmds.get(0);
        assertEquals(CanonConstants.OC_EOS_REMOTE_RELEASE_ON, phase1.code(),
                "Phase 1 should be REMOTE_RELEASE_ON");
        assertEquals(CanonConstants.RELEASE_PRESS_HALF, phase1.getParam1(),
                "Phase 1 parameter should be RELEASE_PRESS_HALF");
    }

    @Test
    void captureSequence_phase2_isFullPress() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        CanonRemoteCapture capture = new CanonRemoteCapture(transport, 0, 0, 0);
        AtomicInteger txId = new AtomicInteger(1);

        capture.capture(txId::getAndIncrement);

        List<PtpContainer> cmds = shutterCommands(transport.executed);
        PtpContainer phase2 = cmds.get(1);
        assertEquals(CanonConstants.OC_EOS_REMOTE_RELEASE_ON, phase2.code(),
                "Phase 2 should be REMOTE_RELEASE_ON");
        assertEquals(CanonConstants.RELEASE_PRESS_FULL, phase2.getParam1(),
                "Phase 2 parameter should be RELEASE_PRESS_FULL");
    }

    @Test
    void captureSequence_phase3_isRelease() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        CanonRemoteCapture capture = new CanonRemoteCapture(transport, 0, 0, 0);
        AtomicInteger txId = new AtomicInteger(1);

        capture.capture(txId::getAndIncrement);

        List<PtpContainer> cmds = shutterCommands(transport.executed);
        PtpContainer phase3 = cmds.get(2);
        assertEquals(CanonConstants.OC_EOS_REMOTE_RELEASE_OFF, phase3.code(),
                "Phase 3 should be REMOTE_RELEASE_OFF");
        assertEquals(CanonConstants.RELEASE_RELEASE, phase3.getParam1(),
                "Phase 3 parameter should be RELEASE_RELEASE");
    }

    @Test
    void delayConstants_haveExpectedValues() {
        assertEquals(300, CanonRemoteCapture.DELAY_AFTER_HALF_PRESS);
        assertEquals(250, CanonRemoteCapture.DELAY_AFTER_FULL_PRESS);
        assertEquals(1200, CanonRemoteCapture.DELAY_AFTER_RELEASE);
    }

    @Test
    void captureSequence_transactionIdsAreUnique() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        CanonRemoteCapture capture = new CanonRemoteCapture(transport, 0, 0, 0);
        AtomicInteger txId = new AtomicInteger(10);

        capture.capture(txId::getAndIncrement);

        List<PtpContainer> cmds = shutterCommands(transport.executed);
        assertEquals(3, cmds.size());
        assertNotEquals(cmds.get(0).transactionId(), cmds.get(1).transactionId(),
                "Transaction IDs for phases 1 and 2 must differ");
        assertNotEquals(cmds.get(1).transactionId(), cmds.get(2).transactionId(),
                "Transaction IDs for phases 2 and 3 must differ");
    }

    // ── Helpers ──

    /** Returns only the shutter-related commands (excludes open/close session etc.). */
    private static List<PtpContainer> shutterCommands(List<PtpContainer> all) {
        return all.stream()
                .filter(c -> c.code() == CanonConstants.OC_EOS_REMOTE_RELEASE_ON
                        || c.code() == CanonConstants.OC_EOS_REMOTE_RELEASE_OFF)
                .toList();
    }

    private static int captureCommandCount(List<PtpContainer> all) {
        return shutterCommands(all).size();
    }
}
