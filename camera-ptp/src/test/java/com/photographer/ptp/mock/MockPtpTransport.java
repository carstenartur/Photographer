package com.photographer.ptp.mock;

import com.photographer.ptp.canon.CanonConstants;
import com.photographer.ptp.core.*;
import com.photographer.ptp.usb.PtpTransport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Simulates a PTP camera without real USB hardware.
 * Responds to standard and Canon PTP opcodes with predefined answers.
 * All unit tests in CI run against this mock — no camera needed!
 */
public class MockPtpTransport implements PtpTransport {

    private final Map<Integer, Function<PtpContainer, DataResponse>> dataHandlers = new HashMap<>();
    private final Map<Integer, Function<PtpContainer, PtpContainer>> handlers = new HashMap<>();
    private final List<Integer> downloadedObjects = new ArrayList<>();
    private final List<Integer> completedTransfers = new ArrayList<>();
    private final AtomicBoolean objectEventPending = new AtomicBoolean();
    private final boolean pairedRaw;
    private final boolean delayFirstRawUntilNextCapture;
    private int rawMetadataUnavailableReads;
    private int captures;
    private boolean open;
    private int sessionId;

    public MockPtpTransport() {
        this(false);
    }

    MockPtpTransport(boolean pairedRaw) {
        this(pairedRaw, 0);
    }

    MockPtpTransport(boolean pairedRaw, int rawMetadataUnavailableReads) {
        this(pairedRaw, rawMetadataUnavailableReads, false);
    }

    MockPtpTransport(boolean pairedRaw, int rawMetadataUnavailableReads, boolean delayFirstRawUntilNextCapture) {
        this.pairedRaw = pairedRaw;
        this.rawMetadataUnavailableReads = rawMetadataUnavailableReads;
        this.delayFirstRawUntilNextCapture = delayFirstRawUntilNextCapture;
        // Standard handlers
        handlers.put(PtpConstants.OP_OPEN_SESSION, this::handleOpenSession);
        handlers.put(PtpConstants.OP_CLOSE_SESSION, this::handleCloseSession);

        // Data-producing handlers
        dataHandlers.put(PtpConstants.OP_GET_DEVICE_INFO, this::handleGetDeviceInfo);
        dataHandlers.put(PtpConstants.OP_GET_STORAGE_IDS, this::handleGetStorageIds);
        dataHandlers.put(PtpConstants.OP_GET_STORAGE_INFO, this::handleGetStorageInfo);
        dataHandlers.put(PtpConstants.OP_GET_OBJECT_HANDLES, this::handleGetObjectHandles);
        dataHandlers.put(PtpConstants.OP_GET_OBJECT_INFO, this::handleGetObjectInfo);
        dataHandlers.put(PtpConstants.OP_GET_OBJECT, this::handleGetObject);
        dataHandlers.put(PtpConstants.OP_GET_THUMB, this::handleGetThumb);
        dataHandlers.put(PtpConstants.OP_GET_DEVICE_PROP_DESC, this::handleGetDevicePropDesc);

        // Canon handlers
        handlers.put(CanonConstants.OC_EOS_SET_REMOTE_MODE, this::okResponse);
        handlers.put(CanonConstants.OC_EOS_SET_EVENT_MODE, this::okResponse);
        handlers.put(CanonConstants.OC_EOS_REMOTE_RELEASE_ON, this::okResponse);
        handlers.put(CanonConstants.OC_EOS_REMOTE_RELEASE_OFF, this::handleShutterRelease);
        handlers.put(CanonConstants.OC_EOS_AF_DRIVE, this::okResponse);
        handlers.put(CanonConstants.OC_EOS_SET_DEVICE_PROP, this::okResponse);
        handlers.put(CanonConstants.OC_EOS_TRANSFER_COMPLETE, this::handleTransferComplete);

        // Canon data handlers
        dataHandlers.put(CanonConstants.OC_EOS_GET_LIVEVIEW_DATA, this::handleGetLiveViewData);
        dataHandlers.put(CanonConstants.OC_EOS_GET_EVENT, this::handleGetEvent);
    }

    List<Integer> downloadedObjects() {
        return List.copyOf(downloadedObjects);
    }

    List<Integer> completedTransfers() {
        return List.copyOf(completedTransfers);
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public PtpContainer execute(PtpContainer request) {
        Function<PtpContainer, PtpContainer> handler = handlers.get(request.code());
        if (handler != null) {
            return handler.apply(request);
        }
        // Check if it's a data handler being called without data
        if (dataHandlers.containsKey(request.code())) {
            DataResponse dr = dataHandlers.get(request.code()).apply(request);
            return dr.response();
        }
        return okResponse(request);
    }

    @Override
    public PtpContainer executeWithData(PtpContainer request, byte[] data) {
        return okResponse(request);
    }

    @Override
    public DataResponse executeReceiveData(PtpContainer request) {
        Function<PtpContainer, DataResponse> handler = dataHandlers.get(request.code());
        if (handler != null) {
            return handler.apply(request);
        }
        return new DataResponse(new byte[0], okResponse(request));
    }

    @Override
    public PtpContainer readEvent(long timeoutMs) {
        throw new PtpException("No events in mock transport");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    // ── Handlers ──

    private PtpContainer handleOpenSession(PtpContainer req) {
        sessionId = req.getParam1();
        return okResponse(req);
    }

    private PtpContainer handleCloseSession(PtpContainer req) {
        sessionId = 0;
        return okResponse(req);
    }

    private PtpContainer handleShutterRelease(PtpContainer req) {
        captures++;
        objectEventPending.set(true);
        return okResponse(req);
    }

    private PtpContainer handleTransferComplete(PtpContainer req) {
        completedTransfers.add(req.getParam1());
        return okResponse(req);
    }

    private DataResponse handleGetDeviceInfo(PtpContainer req) {
        byte[] data = buildMockDeviceInfo();
        return new DataResponse(data, okResponse(req));
    }

    private DataResponse handleGetStorageIds(PtpContainer req) {
        // Return one storage ID: 0x00010001
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1); // count
        buf.putInt(0x00010001); // storage ID
        return new DataResponse(buf.array(), okResponse(req));
    }

    private DataResponse handleGetStorageInfo(PtpContainer req) {
        byte[] data = buildMockStorageInfo();
        return new DataResponse(data, okResponse(req));
    }

    private DataResponse handleGetObjectHandles(PtpContainer req) {
        // Return two mock object handles
        ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2); // count
        buf.putInt(1); // handle 1
        buf.putInt(2); // handle 2
        return new DataResponse(buf.array(), okResponse(req));
    }

    private DataResponse handleGetObjectInfo(PtpContainer req) {
        byte[] data = buildMockObjectInfo(req.getParam1());
        return new DataResponse(data, okResponse(req));
    }

    private DataResponse handleGetObject(PtpContainer req) {
        downloadedObjects.add(req.getParam1());
        if (pairedRaw && req.getParam1() == 43) {
            byte[] cr3Header = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' ', 0, 0, 0, 0};
            return new DataResponse(cr3Header, okResponse(req));
        }
        // Return fake JPEG data
        byte[] jpeg = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, // JPEG header
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            (byte) 0xFF, (byte) 0xD9 // JPEG end
        };
        return new DataResponse(jpeg, okResponse(req));
    }

    private DataResponse handleGetThumb(PtpContainer req) {
        byte[] thumb = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9
        };
        return new DataResponse(thumb, okResponse(req));
    }

    private DataResponse handleGetDevicePropDesc(PtpContainer req) {
        byte[] data = buildMockPropertyDesc(req.getParam1());
        return new DataResponse(data, okResponse(req));
    }

    private DataResponse handleGetLiveViewData(PtpContainer req) {
        // Return mock LiveView data with JPEG markers
        byte[] liveViewData = new byte[100];
        // Header (32 bytes of zeros)
        // Then JPEG SOI + data + EOI
        liveViewData[32] = (byte) 0xFF;
        liveViewData[33] = (byte) 0xD8;
        liveViewData[34] = (byte) 0xFF;
        liveViewData[35] = (byte) 0xE0;
        // Some padding
        liveViewData[98] = (byte) 0xFF;
        liveViewData[99] = (byte) 0xD9;
        return new DataResponse(liveViewData, okResponse(req));
    }

    private DataResponse handleGetEvent(PtpContainer req) {
        // Return mock Canon event blob with property-changed events and an object-added event.
        // Event entry format: entryLength(4) + eventType(4) + payload(N)
        // Property-changed payload: propCode(4) + value(4)
        int entrySize = 16; // 8-byte header + 8-byte payload (propCode + value)
        int objEntrySize = 12; // 8-byte header + 4-byte payload (objectHandle)

        int[][] props = {
            { CanonConstants.DPC_EOS_APERTURE, 0x2B },       // f/4.0
            { CanonConstants.DPC_EOS_SHUTTER_SPEED, 0x68 },  // 1/60
            { CanonConstants.DPC_EOS_ISO, 0x48 },            // ISO 100
            { CanonConstants.DPC_EOS_BATTERY_LEVEL, 3 },     // 75%
        };

        boolean announceObjects = objectEventPending.getAndSet(false);
        boolean announceRaw = pairedRaw && (!delayFirstRawUntilNextCapture || captures == 2);
        int objectEntries = announceObjects ? (announceRaw ? 2 : 1) : 0;
        ByteBuffer buf = ByteBuffer.allocate(props.length * entrySize + objEntrySize * objectEntries)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int[] prop : props) {
            buf.putInt(entrySize);
            buf.putInt(CanonConstants.EVENT_PROP_CHANGED);
            buf.putInt(prop[0]); // propCode
            buf.putInt(prop[1]); // value
        }

        if (announceObjects) {
            if (announceRaw && delayFirstRawUntilNextCapture) {
                addObjectEvent(buf, CanonConstants.EVENT_OBJ_INFO_EX, 43);
            }
            addObjectEvent(buf, CanonConstants.EVENT_OBJ_ADDED, 42 + (captures - 1) * 2);
            if (announceRaw && !delayFirstRawUntilNextCapture) {
                addObjectEvent(buf, CanonConstants.EVENT_OBJ_INFO_EX, 43);
            }
        }

        return new DataResponse(buf.array(), okResponse(req));
    }

    private static void addObjectEvent(ByteBuffer buffer, int eventType, int handle) {
        buffer.putInt(12);
        buffer.putInt(eventType);
        buffer.putInt(handle);
    }

    // ── Data builders ──

    private byte[] buildMockDeviceInfo() {
        ByteBuffer buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);

        buf.putShort((short) 100);  // standardVersion
        buf.putInt(0x0B);           // vendorExtensionId (Canon = 0x0B)
        buf.putShort((short) 100);  // vendorExtensionVersion

        // vendorExtensionDesc (PTP string)
        writePtpString(buf, "canon.com: 1.0;");

        buf.putShort((short) 0);    // functionalMode

        // operationsSupported array
        int[] ops = {
            PtpConstants.OP_GET_DEVICE_INFO, PtpConstants.OP_OPEN_SESSION,
            PtpConstants.OP_CLOSE_SESSION, PtpConstants.OP_GET_STORAGE_IDS,
            PtpConstants.OP_GET_OBJECT_HANDLES, PtpConstants.OP_GET_OBJECT_INFO,
            PtpConstants.OP_GET_OBJECT, PtpConstants.OP_GET_THUMB,
            PtpConstants.OP_INITIATE_CAPTURE, PtpConstants.OP_GET_DEVICE_PROP_DESC,
            PtpConstants.OP_GET_DEVICE_PROP_VALUE, PtpConstants.OP_SET_DEVICE_PROP_VALUE,
            CanonConstants.OC_EOS_SET_REMOTE_MODE, CanonConstants.OC_EOS_SET_EVENT_MODE,
            CanonConstants.OC_EOS_GET_EVENT, CanonConstants.OC_EOS_REMOTE_RELEASE_ON,
            CanonConstants.OC_EOS_REMOTE_RELEASE_OFF, CanonConstants.OC_EOS_AF_DRIVE,
            CanonConstants.OC_EOS_GET_LIVEVIEW_DATA, CanonConstants.OC_EOS_GET_OBJECT
        };
        writeUInt16Array(buf, ops);

        // eventsSupported
        writeUInt16Array(buf, new int[] { PtpConstants.EC_OBJECT_ADDED, PtpConstants.EC_CAPTURE_COMPLETE });

        // devicePropertiesSupported
        writeUInt16Array(buf, new int[] {
            CanonConstants.DPC_EOS_APERTURE, CanonConstants.DPC_EOS_SHUTTER_SPEED,
            CanonConstants.DPC_EOS_ISO, CanonConstants.DPC_EOS_BATTERY_LEVEL
        });

        // captureFormats
        writeUInt16Array(buf, new int[] { PtpConstants.OFC_EXIF_JPEG });

        // imageFormats
        writeUInt16Array(buf, new int[] { PtpConstants.OFC_EXIF_JPEG });

        // manufacturer, model, deviceVersion, serialNumber
        writePtpString(buf, "Canon Inc.");
        writePtpString(buf, "Canon PowerShot G5 X Mark II");
        writePtpString(buf, "1.0.0");
        writePtpString(buf, "MOCK12345678");

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private byte[] buildMockStorageInfo() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0004); // storageType: removable RAM
        buf.putShort((short) 0x0002); // filesystemType: generic flat
        buf.putShort((short) 0x0000); // accessCapability: read-write
        buf.putLong(32L * 1024 * 1024 * 1024); // maxCapacity: 32GB
        buf.putLong(16L * 1024 * 1024 * 1024); // freeSpace: 16GB
        buf.putInt(999);              // freeSpaceInImages
        writePtpString(buf, "SD Card");
        writePtpString(buf, "CANON_DC");
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private byte[] buildMockObjectInfo(int objectHandle) {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00010001);       // storageId
        buf.putShort((short) PtpConstants.OFC_EXIF_JPEG); // objectFormat
        buf.putShort((short) 0);      // protectionStatus
        buf.putInt(5 * 1024 * 1024);  // compressedSize: 5MB
        buf.putShort((short) PtpConstants.OFC_EXIF_JPEG); // thumbFormat
        buf.putInt(50 * 1024);        // thumbCompressedSize
        buf.putInt(160);              // thumbPixWidth
        buf.putInt(120);              // thumbPixHeight
        buf.putInt(5472);             // imagePixWidth
        buf.putInt(3648);             // imagePixHeight
        buf.putInt(24);               // imageBitDepth
        buf.putInt(0);                // parentObject
        buf.putShort((short) 0);      // associationType
        buf.putInt(0);                // associationDesc
        buf.putInt(objectHandle);     // sequenceNumber

        String filename = "IMG_%04d.JPG".formatted(objectHandle);
        if (pairedRaw && objectHandle == 43) {
            if (rawMetadataUnavailableReads > 0) {
                rawMetadataUnavailableReads--;
                filename = " ";
            } else {
                filename = "IMG_0042.CR3";
            }
        }
        writePtpString(buf, filename);
        writePtpString(buf, "20240101T120000");
        writePtpString(buf, "20240101T120000");
        writePtpString(buf, "");

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private byte[] buildMockPropertyDesc(int propertyCode) {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) propertyCode);
        buf.putShort((short) PtpDeviceProperty.TYPE_UINT16); // dataType
        buf.put((byte) 1);            // get/set
        int value = switch (propertyCode) {
            case CanonConstants.DPC_EOS_APERTURE -> 0x2B;       // f/4.0
            case CanonConstants.DPC_EOS_SHUTTER_SPEED -> 0x68;  // 1/60
            case CanonConstants.DPC_EOS_ISO -> 0x48;            // ISO 100
            case CanonConstants.DPC_EOS_BATTERY_LEVEL -> 3;     // 75%
            default -> 0;
        };
        buf.putShort((short) value);   // defaultValue
        buf.putShort((short) value);   // currentValue
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    // ── Helpers ──

    private PtpContainer okResponse(PtpContainer request) {
        return PtpContainer.createResponse(PtpConstants.RC_OK, request.transactionId());
    }

    private static void writePtpString(ByteBuffer buf, String s) {
        if (s == null || s.isEmpty()) {
            buf.put((byte) 0);
            return;
        }
        buf.put((byte) (s.length() + 1)); // length including null terminator
        for (char c : s.toCharArray()) {
            buf.putShort((short) c);
        }
        buf.putShort((short) 0); // null terminator
    }

    private static void writeUInt16Array(ByteBuffer buf, int[] values) {
        buf.putInt(values.length);
        for (int v : values) {
            buf.putShort((short) v);
        }
    }
}
