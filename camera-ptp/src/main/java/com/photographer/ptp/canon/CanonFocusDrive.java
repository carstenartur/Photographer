package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Canon EOS focus drive commands via PTP.
 */
public class CanonFocusDrive {
    private static final Logger log = LoggerFactory.getLogger(CanonFocusDrive.class);

    private final PtpTransport transport;

    public CanonFocusDrive(PtpTransport transport) {
        this.transport = transport;
    }

    /**
     * Drives the focus towards near (closer) direction.
     * @param magnitude 1=small step, 2=medium step, 3=large step
     * @param transactionId current transaction ID
     */
    public void driveNear(int magnitude, int transactionId) {
        int value = mapNearValue(magnitude);
        drive(value, transactionId);
    }

    /**
     * Drives the focus towards far (infinity) direction.
     * @param magnitude 1=small step, 2=medium step, 3=large step
     * @param transactionId current transaction ID
     */
    public void driveFar(int magnitude, int transactionId) {
        int value = mapFarValue(magnitude);
        drive(value, transactionId);
    }

    private void drive(int driveValue, int transactionId) {
        log.debug("Driving focus: value=0x{}", String.format("%04X", driveValue & 0xFFFF));
        PtpContainer request = PtpContainer.createCommand(
                CanonConstants.OC_EOS_AF_DRIVE, transactionId, driveValue);
        PtpContainer response = transport.execute(request);
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    "Focus drive failed: 0x%04X".formatted(response.code()));
        }
    }

    static int mapNearValue(int magnitude) {
        return switch (magnitude) {
            case 1 -> CanonConstants.FOCUS_NEAR_1;
            case 2 -> CanonConstants.FOCUS_NEAR_2;
            case 3 -> CanonConstants.FOCUS_NEAR_3;
            default -> throw new IllegalArgumentException(
                    "Focus magnitude must be 1-3, got: " + magnitude);
        };
    }

    static int mapFarValue(int magnitude) {
        return switch (magnitude) {
            case 1 -> CanonConstants.FOCUS_FAR_1;
            case 2 -> CanonConstants.FOCUS_FAR_2;
            case 3 -> CanonConstants.FOCUS_FAR_3;
            default -> throw new IllegalArgumentException(
                    "Focus magnitude must be 1-3, got: " + magnitude);
        };
    }
}
