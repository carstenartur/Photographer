package com.photographer.ptp.usb;

import com.photographer.ptp.core.PtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.usb.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans USB bus for Canon PTP cameras.
 */
public class UsbDeviceScanner {
    private static final Logger log = LoggerFactory.getLogger(UsbDeviceScanner.class);

    private UsbDeviceScanner() {}

    /**
     * Finds the first Canon PTP camera on the USB bus.
     * @return UsbPtpTransport for the found camera
     * @throws PtpException if no camera is found
     */
    public static UsbPtpTransport findCanon() throws PtpException {
        List<UsbDevice> cameras = findCanonCameras();
        if (cameras.isEmpty()) {
            throw new PtpException("No Canon PTP camera found on USB bus");
        }
        return new UsbPtpTransport(cameras.get(0));
    }

    /**
     * Lists all Canon PTP cameras on the USB bus.
     */
    public static List<UsbDevice> findCanonCameras() throws PtpException {
        try {
            UsbServices services = UsbHostManager.getUsbServices();
            UsbHub rootHub = services.getRootUsbHub();
            List<UsbDevice> result = new ArrayList<>();
            scanHub(rootHub, result);
            return result;
        } catch (UsbException e) {
            throw new PtpException("Failed to scan USB bus", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanHub(UsbHub hub, List<UsbDevice> result) {
        for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices()) {
            if (device.isUsbHub()) {
                scanHub((UsbHub) device, result);
            } else {
                UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
                if (desc.idVendor() == UsbConstants.VENDOR_ID_CANON && isPtpDevice(device)) {
                    log.info("Found Canon PTP camera: vendor=0x{}, product=0x{}",
                            String.format("%04X", desc.idVendor() & 0xFFFF),
                            String.format("%04X", desc.idProduct() & 0xFFFF));
                    result.add(device);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isPtpDevice(UsbDevice device) {
        try {
            UsbConfiguration config = device.getActiveUsbConfiguration();
            if (config == null) return false;
            for (UsbInterface iface : (List<UsbInterface>) config.getUsbInterfaces()) {
                UsbInterfaceDescriptor desc = iface.getUsbInterfaceDescriptor();
                if (desc.bInterfaceClass() == UsbConstants.INTERFACE_CLASS_STILL_IMAGE
                        && desc.bInterfaceSubClass() == UsbConstants.INTERFACE_SUBCLASS_PTP) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Error checking device for PTP interface", e);
        }
        return false;
    }
}
