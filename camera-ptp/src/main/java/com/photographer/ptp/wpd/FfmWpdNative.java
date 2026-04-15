package com.photographer.ptp.wpd;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Low-level bridge to the Windows Portable Devices (WPD) COM API using the
 * <b>Foreign Function &amp; Memory API</b> (JEP 454, finalized in Java 22).
 * <p>
 * Replaces the former JNA-based {@link WpdNative} implementation, removing the
 * runtime dependency on JNA while providing:
 * <ul>
 *   <li>Deterministic native memory management via {@link Arena}</li>
 *   <li>Type-safe function descriptors instead of reflective vtable calls</li>
 *   <li>Better performance — no JNA reflection overhead</li>
 * </ul>
 * <p>
 * COM interfaces used (from PortableDeviceApi.dll / PortableDeviceTypes.dll):
 * <ul>
 *   <li>{@code IPortableDeviceManager} — enumerate devices</li>
 *   <li>{@code IPortableDevice} — open device, send commands</li>
 *   <li>{@code IPortableDeviceValues} — parameter bags for commands</li>
 *   <li>{@code IPortableDevicePropVariantCollection} — parameter arrays</li>
 * </ul>
 */
final class FfmWpdNative {

    private FfmWpdNative() {}

    private static final Logger log = LoggerFactory.getLogger(FfmWpdNative.class);

    // ──────────────────────────────────────────────────────────────────────
    // Native layouts
    // ──────────────────────────────────────────────────────────────────────

    /** Size of a native pointer (4 on 32-bit, 8 on 64-bit). */
    private static final long PTR_SIZE = ValueLayout.ADDRESS.byteSize();

    /** A GUID in memory: Data1(4) + Data2(2) + Data3(2) + Data4(8) = 16 bytes. */
    static final MemoryLayout GUID_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Data1"),
            ValueLayout.JAVA_SHORT.withName("Data2"),
            ValueLayout.JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4")
    );

    /** PROPERTYKEY: GUID(16) + pid(4) = 20 bytes. */
    static final MemoryLayout PROPERTYKEY_LAYOUT = MemoryLayout.structLayout(
            GUID_LAYOUT.withName("fmtid"),
            ValueLayout.JAVA_INT.withName("pid")
    );

    /** PROPVARIANT is 24 bytes on x64 (vt(2) + pad(6) + value(16)). */
    static final long PROPVARIANT_SIZE = 24;
    private static final short VT_UI4 = 19;  // Variant.VT_UI4
    private static final short VT_LPWSTR = 31;

    // ──────────────────────────────────────────────────────────────────────
    // CLSIDs & IIDs (GUID bytes in Windows memory layout)
    // ──────────────────────────────────────────────────────────────────────
    private static final byte[] CLSID_PortableDeviceManager =
            guidBytes("0af10cec-2ecd-4b92-9581-34f6ae0637f3");
    private static final byte[] CLSID_PortableDevice =
            guidBytes("728a21c5-3d9e-48d7-9810-864848f0f404");
    private static final byte[] CLSID_PortableDeviceValues =
            guidBytes("0c15d503-d017-47ce-9016-7b3f978721cc");
    private static final byte[] CLSID_PortableDevicePropVariantCollection =
            guidBytes("08a99e2f-6d6d-4b80-af5a-baf2bcbe4cb9");

    private static final byte[] IID_IPortableDeviceManager =
            guidBytes("a1567595-4c2f-4574-a6fa-ecef917b9a40");
    private static final byte[] IID_IPortableDevice =
            guidBytes("625e2df8-6392-4cf0-9ad1-3cfa5f17775c");
    private static final byte[] IID_IPortableDeviceValues =
            guidBytes("6848f6f2-3155-4f86-b6f5-263eeeab3143");
    private static final byte[] IID_IPortableDevicePropVariantCollection =
            guidBytes("89b2e422-4f1b-4316-bcef-a44afea83eb3");

    // ──────────────────────────────────────────────────────────────────────
    // WPD Property Keys for MTP Extension commands
    // ──────────────────────────────────────────────────────────────────────
    private static final byte[] WPD_CATEGORY_MTP_EXT =
            guidBytes("4d545058-1a2e-4106-a357-771e0819fc56");
    // PID values from WpdMtpExtDecl.h (Windows SDK), all under WPD_CATEGORY_MTP_EXT GUID
    private static final int WPD_MTP_OPCODE_PID                    = 1001;
    private static final int WPD_MTP_PARAMS_PID                    = 1002;
    private static final int WPD_MTP_RESP_CODE_PID                 = 1003;
    private static final int WPD_MTP_RESP_PARAMS_PID               = 1004;
    // 1005 = WPD_PROPERTY_MTP_EXT_VENDOR_OPERATION_CODES (not used here)
    private static final int WPD_MTP_TRANSFER_CONTEXT_PID          = 1006;
    private static final int WPD_MTP_TRANSFER_TOTAL_DATA_SIZE_PID  = 1007;
    private static final int WPD_MTP_NUM_BYTES_TO_READ_PID         = 1008;
    private static final int WPD_MTP_NUM_BYTES_READ_PID            = 1009;
    private static final int WPD_MTP_NUM_BYTES_TO_WRITE_PID        = 1010;
    private static final int WPD_MTP_NUM_BYTES_WRITTEN_PID         = 1011;
    private static final int WPD_MTP_TRANSFER_DATA_PID             = 1012;
    private static final int WPD_MTP_OPTIMAL_TRANSFER_BUFFER_SIZE_PID = 1013;

    // WPD MTP Extension command IDs (WPD_PROPERTY_COMMON_COMMAND_ID values)
    private static final int CMD_NO_DATA             = 12; // EXECUTE_COMMAND_WITHOUT_DATA_PHASE
    private static final int CMD_INITIATE_READ_DATA  = 13; // EXECUTE_COMMAND_WITH_DATA_TO_READ
    private static final int CMD_INITIATE_WRITE_DATA = 14; // EXECUTE_COMMAND_WITH_DATA_TO_WRITE
    private static final int CMD_READ_DATA           = 15; // READ_DATA (chunk)
    private static final int CMD_WRITE_DATA          = 16; // WRITE_DATA (chunk)
    private static final int CMD_END_TRANSFER        = 17; // END_DATA_TRANSFER

    private static final byte[] WPD_COMMAND_CATEGORY =
            guidBytes("f0422a9c-5dc8-4440-b5bd-5df28835658a");
    private static final int WPD_COMMON_CMD_CATEGORY_PID = 1001;
    private static final int WPD_COMMON_CMD_ID_PID       = 1002;

    // ──────────────────────────────────────────────────────────────────────
    // VTable indices (IUnknown: QI=0, AddRef=1, Release=2, then custom)
    // ──────────────────────────────────────────────────────────────────────
    // IPortableDeviceValues
    private static final int PDV_SetValue                = 5;
    private static final int PDV_GetValue                = 6;
    private static final int PDV_SetStringValue          = 7;
    private static final int PDV_GetStringValue          = 8;
    private static final int PDV_SetUnsignedIntegerValue = 9;
    private static final int PDV_GetUnsignedIntegerValue = 10;
    private static final int PDV_SetGuidValue            = 27;
    private static final int PDV_SetBufferValue          = 29;
    private static final int PDV_GetBufferValue          = 30;
    private static final int PDV_SetIUnknownValue        = 25;
    private static final int PDV_GetIUnknownValue        = 26;

    // IPortableDevice
    private static final int PD_Open        = 3;
    private static final int PD_SendCommand = 4;
    private static final int PD_Close       = 8;

    // IPortableDeviceManager
    private static final int PDM_GetDevices            = 3;
    private static final int PDM_RefreshDeviceList     = 4;
    private static final int PDM_GetDeviceFriendlyName = 5;
    private static final int PDM_GetDeviceManufacturer = 6;

    // IPortableDevicePropVariantCollection
    private static final int PDPVC_GetCount = 3;
    private static final int PDPVC_GetAt    = 4;
    private static final int PDPVC_Add      = 5;

    // ──────────────────────────────────────────────────────────────────────
    // COM initialization (Ole32)
    // ──────────────────────────────────────────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OLE32 =
            SymbolLookup.libraryLookup("ole32.dll", Arena.global());

    private static final MethodHandle CoInitializeEx;
    private static final MethodHandle CoCreateInstance;
    private static final MethodHandle CoTaskMemFree;

    static {
        try {
            CoInitializeEx = LINKER.downcallHandle(
                    OLE32.find("CoInitializeEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // pvReserved
                            ValueLayout.JAVA_INT)); // dwCoInit

            CoCreateInstance = LINKER.downcallHandle(
                    OLE32.find("CoCreateInstance").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // rclsid
                            ValueLayout.ADDRESS,   // pUnkOuter
                            ValueLayout.JAVA_INT,  // dwClsContext
                            ValueLayout.ADDRESS,   // riid
                            ValueLayout.ADDRESS));  // ppv

            CoTaskMemFree = LINKER.downcallHandle(
                    OLE32.find("CoTaskMemFree").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int COINIT_MULTITHREADED = 0x0;
    private static final int CLSCTX_INPROC_SERVER = 0x1;

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /** Ensures COM is initialized on the current thread. */
    static void ensureComInitialized() {
        try {
            int hr = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            // S_OK=0, S_FALSE=1, RPC_E_CHANGED_MODE=0x80010106 — all acceptable
            if (hr != 0 && hr != 1 && hr != 0x80010106) {
                checkHR(hr, "CoInitializeEx");
            }
        } catch (Throwable t) {
            throw new PtpException("CoInitializeEx failed", asException(t));
        }
    }

    /**
     * Refreshes the WPD device list, causing Windows to re-enumerate USB devices.
     * This USB bus activity can wake cameras that have entered sleep/standby.
     */
    static void refreshDeviceList() {
        ensureComInitialized();
        try (var arena = Arena.ofConfined()) {
            MemorySegment pManager = createInstance(arena,
                    CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
            try {
                vtableCallHR(pManager, PDM_RefreshDeviceList, arena);
                log.debug("WPD device list refreshed (USB bus wake signal sent)");
            } finally {
                release(pManager);
            }
        }
    }

    /** Finds all WPD device IDs. */
    static String[] findCanonDeviceIds() {
        ensureComInitialized();
        try (var arena = Arena.ofConfined()) {
            MemorySegment pManager = createInstance(arena,
                    CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
            try {
                // Refresh device list
                vtableCallHR(pManager, PDM_RefreshDeviceList, arena);

                // Get count
                MemorySegment countSeg = arena.allocate(ValueLayout.JAVA_INT);
                vtableCallHR(pManager, PDM_GetDevices, arena,
                        MemorySegment.NULL, countSeg);
                int n = countSeg.get(ValueLayout.JAVA_INT, 0);
                if (n == 0) return new String[0];

                // Allocate array of LPWSTRs
                MemorySegment pArr = arena.allocate(PTR_SIZE * n);
                countSeg.set(ValueLayout.JAVA_INT, 0, n);
                vtableCallHR(pManager, PDM_GetDevices, arena, pArr, countSeg);

                String[] result = new String[n];
                for (int i = 0; i < n; i++) {
                    MemorySegment pStr = pArr.get(ValueLayout.ADDRESS, i * PTR_SIZE)
                            .reinterpret(65536); // max device-id length
                    result[i] = readWideString(pStr);
                    coTaskMemFree(pStr);
                }
                return result;
            } finally {
                release(pManager);
            }
        }
    }

    /** Gets the friendly name for a WPD device. */
    static String getDeviceFriendlyName(String deviceId) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment pManager = createInstance(arena,
                    CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
            try {
                MemorySegment wDeviceId = toWideString(arena, deviceId);
                MemorySegment lenSeg = arena.allocate(ValueLayout.JAVA_INT);

                int hr = vtableCallInt(pManager, PDM_GetDeviceFriendlyName, arena,
                        wDeviceId, MemorySegment.NULL, lenSeg);
                if (failed(hr)) return deviceId;

                int len = lenSeg.get(ValueLayout.JAVA_INT, 0);
                if (len == 0) return deviceId;

                MemorySegment buf = arena.allocate((long) len * 2);
                lenSeg.set(ValueLayout.JAVA_INT, 0, len);
                hr = vtableCallInt(pManager, PDM_GetDeviceFriendlyName, arena,
                        wDeviceId, buf, lenSeg);
                if (failed(hr)) return deviceId;
                return readWideString(buf);
            } finally {
                release(pManager);
            }
        }
    }

    /** Gets the manufacturer name for a WPD device. */
    static String getDeviceManufacturer(String deviceId) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment pManager = createInstance(arena,
                    CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
            try {
                MemorySegment wDeviceId = toWideString(arena, deviceId);
                MemorySegment lenSeg = arena.allocate(ValueLayout.JAVA_INT);

                int hr = vtableCallInt(pManager, PDM_GetDeviceManufacturer, arena,
                        wDeviceId, MemorySegment.NULL, lenSeg);
                if (failed(hr)) return "";

                int len = lenSeg.get(ValueLayout.JAVA_INT, 0);
                if (len == 0) return "";

                MemorySegment buf = arena.allocate((long) len * 2);
                lenSeg.set(ValueLayout.JAVA_INT, 0, len);
                hr = vtableCallInt(pManager, PDM_GetDeviceManufacturer, arena,
                        wDeviceId, buf, lenSeg);
                if (failed(hr)) return "";
                return readWideString(buf);
            } finally {
                release(pManager);
            }
        }
    }

    /** Opens a WPD device and returns the IPortableDevice pointer. */
    static MemorySegment openDevice(String deviceId) {
        ensureComInitialized();
        var arena = Arena.ofShared(); // caller manages lifetime
        MemorySegment pDevice = createInstance(arena,
                CLSID_PortableDevice, IID_IPortableDevice);
        MemorySegment pValues = createValues(arena);
        try {
            MemorySegment wDeviceId = toWideString(arena, deviceId);
            int hr = vtableCallInt(pDevice, PD_Open, arena, wDeviceId, pValues);
            checkHR(hr, "IPortableDevice::Open");
            return pDevice;
        } catch (Exception e) {
            release(pDevice);
            throw new PtpException("Failed to open WPD device: " + deviceId, e);
        } finally {
            release(pValues);
        }
    }

    /** Closes a WPD device. */
    static void closeDevice(MemorySegment pDevice) {
        if (pDevice != null && !MemorySegment.NULL.equals(pDevice)) {
            try (var arena = Arena.ofConfined()) {
                vtableCallVoid(pDevice, PD_Close, arena);
            }
            release(pDevice);
        }
    }

    /**
     * Sends a PTP/MTP command through WPD.
     *
     * @param pDevice   opened IPortableDevice segment
     * @param opCode    PTP operation code
     * @param params    PTP parameters (up to 5)
     * @param dataOut   data to send (for write commands), null for no-data
     * @param readData  true if expecting data back from the camera
     * @return response bytes: [4-byte resp code][4-byte param count][params...][data if readData]
     */
    static byte[] sendCommand(MemorySegment pDevice, int opCode, int[] params,
                              byte[] dataOut, boolean readData) {
        if (dataOut != null && dataOut.length > 0) {
            return sendCommandWithDataToWrite(pDevice, opCode, params, dataOut);
        } else if (readData) {
            return sendCommandWithDataToRead(pDevice, opCode, params);
        } else {
            return sendCommandNoData(pDevice, opCode, params);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MTP Extension: no-data phase
    // ──────────────────────────────────────────────────────────────────────
    private static byte[] sendCommandNoData(MemorySegment pDevice, int opCode, int[] params) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment pInput = createValues(arena);
            MemorySegment pResult = MemorySegment.NULL;
            MemorySegment pParamCollection = MemorySegment.NULL;
            try {
                setGuidValue(arena, pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(arena, pInput, WPD_COMMAND_CATEGORY,
                        WPD_COMMON_CMD_ID_PID, CMD_NO_DATA);
                setUnsignedIntegerValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_OPCODE_PID, opCode);

                pParamCollection = createParamCollection(arena, params);
                setIUnknownValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_PARAMS_PID, pParamCollection);

                pResult = sendDeviceCommand(arena, pDevice, pInput);
                return extractResponse(arena, pResult);
            } finally {
                releaseAll(pInput, pResult, pParamCollection);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MTP Extension: read data phase
    // ──────────────────────────────────────────────────────────────────────
    private static byte[] sendCommandWithDataToRead(MemorySegment pDevice, int opCode, int[] params) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment pInput = createValues(arena);
            MemorySegment pResult = MemorySegment.NULL;
            MemorySegment pParamCollection = MemorySegment.NULL;
            try {
                // Phase 1: initiate read
                setGuidValue(arena, pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(arena, pInput, WPD_COMMAND_CATEGORY,
                        WPD_COMMON_CMD_ID_PID, CMD_INITIATE_READ_DATA);
                setUnsignedIntegerValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_OPCODE_PID, opCode);
                pParamCollection = createParamCollection(arena, params);
                setIUnknownValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_PARAMS_PID, pParamCollection);

                pResult = sendDeviceCommand(arena, pDevice, pInput);

                int bufferSize = getUnsignedIntegerValue(arena, pResult,
                        WPD_CATEGORY_MTP_EXT, WPD_MTP_OPTIMAL_TRANSFER_BUFFER_SIZE_PID);
                log.debug("WPD data read: optimal buffer size = {}", bufferSize);
                if (bufferSize <= 0) bufferSize = 512 * 1024;

                String transferContext = getStringValue(arena, pResult,
                        WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID);
                log.debug("WPD data read: transfer context = '{}'", transferContext);

                release(pResult);
                pResult = MemorySegment.NULL;

                // Phase 2: read data in chunks
                byte[] allData = new byte[0];
                boolean done = false;

                while (!done) {
                    MemorySegment pRead = createValues(arena);
                    try {
                        setGuidValue(arena, pRead, WPD_COMMAND_CATEGORY,
                                WPD_COMMON_CMD_CATEGORY_PID, WPD_CATEGORY_MTP_EXT);
                        setUnsignedIntegerValue(arena, pRead, WPD_COMMAND_CATEGORY,
                                WPD_COMMON_CMD_ID_PID, CMD_READ_DATA);
                        setStringValue(arena, pRead, WPD_CATEGORY_MTP_EXT,
                                WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                        int chunkSize = Math.min(bufferSize, 512 * 1024);
                        byte[] chunk = new byte[chunkSize];
                        setBufferValue(arena, pRead, WPD_CATEGORY_MTP_EXT,
                                WPD_MTP_TRANSFER_DATA_PID, chunk);
                        setUnsignedIntegerValue(arena, pRead, WPD_CATEGORY_MTP_EXT,
                                WPD_MTP_NUM_BYTES_TO_READ_PID, chunkSize);

                        pResult = sendDeviceCommand(arena, pDevice, pRead);

                        byte[] readData = getBufferValue(arena, pResult,
                                WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_DATA_PID);
                        int bytesRead = getUnsignedIntegerValue(arena, pResult,
                                WPD_CATEGORY_MTP_EXT, WPD_MTP_NUM_BYTES_READ_PID);

                        log.debug("WPD data read chunk: {} bytes (buffer data len={})",
                                bytesRead, readData != null ? readData.length : 0);

                        if (readData != null && bytesRead > 0) {
                            byte[] newAll = new byte[allData.length + bytesRead];
                            System.arraycopy(allData, 0, newAll, 0, allData.length);
                            System.arraycopy(readData, 0, newAll, allData.length, bytesRead);
                            allData = newAll;
                        }

                        if (bytesRead < chunkSize || bytesRead == 0) {
                            done = true;
                        }

                        release(pResult);
                        pResult = MemorySegment.NULL;
                    } finally {
                        release(pRead);
                    }
                }

                log.debug("WPD data read complete: {} bytes total", allData.length);

                // Phase 3: end transfer
                MemorySegment pEnd = createValues(arena);
                try {
                    setGuidValue(arena, pEnd, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_CATEGORY_PID, WPD_CATEGORY_MTP_EXT);
                    setUnsignedIntegerValue(arena, pEnd, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_ID_PID, CMD_END_TRANSFER);
                    setStringValue(arena, pEnd, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                    pResult = sendDeviceCommand(arena, pDevice, pEnd);

                    byte[] responseHeader = extractResponse(arena, pResult);
                    ByteBuffer respBuf = ByteBuffer.wrap(responseHeader).order(ByteOrder.LITTLE_ENDIAN);
                    int respCode = respBuf.getInt();
                    if (respCode == 0 && allData.length > 0) {
                        log.debug("WPD EndTransfer had no response code, assuming RC_OK for {} bytes",
                                allData.length);
                        respBuf.putInt(0, PtpConstants.RC_OK);
                    }

                    byte[] combined = new byte[responseHeader.length + allData.length];
                    System.arraycopy(responseHeader, 0, combined, 0, responseHeader.length);
                    System.arraycopy(allData, 0, combined, responseHeader.length, allData.length);
                    return combined;
                } finally {
                    release(pEnd);
                }
            } finally {
                releaseAll(pInput, pResult, pParamCollection);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MTP Extension: write data phase
    // ──────────────────────────────────────────────────────────────────────
    private static byte[] sendCommandWithDataToWrite(MemorySegment pDevice, int opCode,
                                                     int[] params, byte[] data) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment pInput = createValues(arena);
            MemorySegment pResult = MemorySegment.NULL;
            MemorySegment pParamCollection = MemorySegment.NULL;
            try {
                // Phase 1: initiate write
                setGuidValue(arena, pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(arena, pInput, WPD_COMMAND_CATEGORY,
                        WPD_COMMON_CMD_ID_PID, CMD_INITIATE_WRITE_DATA);
                setUnsignedIntegerValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_OPCODE_PID, opCode);
                pParamCollection = createParamCollection(arena, params);
                setIUnknownValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_PARAMS_PID, pParamCollection);
                setUnsignedIntegerValue(arena, pInput, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_TRANSFER_TOTAL_DATA_SIZE_PID, data.length);

                pResult = sendDeviceCommand(arena, pDevice, pInput);

                String transferContext = getStringValue(arena, pResult,
                        WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID);
                release(pResult);
                pResult = MemorySegment.NULL;

                // Phase 2: write data
                MemorySegment pWrite = createValues(arena);
                try {
                    setGuidValue(arena, pWrite, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_CATEGORY_PID, WPD_CATEGORY_MTP_EXT);
                    setUnsignedIntegerValue(arena, pWrite, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_ID_PID, CMD_WRITE_DATA);
                    setStringValue(arena, pWrite, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);
                    setBufferValue(arena, pWrite, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_TRANSFER_DATA_PID, data);
                    setUnsignedIntegerValue(arena, pWrite, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_NUM_BYTES_TO_WRITE_PID, data.length);

                    pResult = sendDeviceCommand(arena, pDevice, pWrite);
                    release(pResult);
                    pResult = MemorySegment.NULL;
                } finally {
                    release(pWrite);
                }

                // Phase 3: end transfer
                MemorySegment pEnd = createValues(arena);
                try {
                    setGuidValue(arena, pEnd, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_CATEGORY_PID, WPD_CATEGORY_MTP_EXT);
                    setUnsignedIntegerValue(arena, pEnd, WPD_COMMAND_CATEGORY,
                            WPD_COMMON_CMD_ID_PID, CMD_END_TRANSFER);
                    setStringValue(arena, pEnd, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                    pResult = sendDeviceCommand(arena, pDevice, pEnd);
                    return extractResponse(arena, pResult);
                } finally {
                    release(pEnd);
                }
            } finally {
                releaseAll(pInput, pResult, pParamCollection);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // COM object creation
    // ──────────────────────────────────────────────────────────────────────

    private static MemorySegment createInstance(Arena arena, byte[] clsidBytes, byte[] iidBytes) {
        MemorySegment clsid = arena.allocate(16);
        clsid.copyFrom(MemorySegment.ofArray(clsidBytes));
        MemorySegment iid = arena.allocate(16);
        iid.copyFrom(MemorySegment.ofArray(iidBytes));
        MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);

        try {
            int hr = (int) CoCreateInstance.invokeExact(clsid, MemorySegment.NULL,
                    CLSCTX_INPROC_SERVER, iid, ppv);
            checkHR(hr, "CoCreateInstance");
            return ppv.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable t) {
            throw new PtpException("CoCreateInstance failed", asException(t));
        }
    }

    static MemorySegment createValues(Arena arena) {
        return createInstance(arena, CLSID_PortableDeviceValues, IID_IPortableDeviceValues);
    }

    private static MemorySegment createParamCollection(Arena arena, int[] params) {
        MemorySegment pColl = createInstance(arena,
                CLSID_PortableDevicePropVariantCollection,
                IID_IPortableDevicePropVariantCollection);
        if (params != null) {
            for (int param : params) {
                MemorySegment pv = arena.allocate(PROPVARIANT_SIZE);
                pv.fill((byte) 0);
                pv.set(ValueLayout.JAVA_SHORT, 0, VT_UI4);
                pv.set(ValueLayout.JAVA_INT, 8, param);
                int hr = vtableCallInt(pColl, PDPVC_Add, arena, pv);
                if (failed(hr)) {
                    log.warn("Failed to add param to collection: 0x{}", Integer.toHexString(hr));
                }
            }
        }
        return pColl;
    }

    private static MemorySegment sendDeviceCommand(Arena arena, MemorySegment pDevice,
                                                    MemorySegment pInput) {
        MemorySegment ppResult = arena.allocate(ValueLayout.ADDRESS);
        int flags = 0;
        int hr = vtableCallInt(pDevice, PD_SendCommand, arena,
                flags, pInput, ppResult);
        checkHR(hr, "IPortableDevice::SendCommand");
        MemorySegment pResult = ppResult.get(ValueLayout.ADDRESS, 0);
        if (MemorySegment.NULL.equals(pResult)) {
            throw new PtpException("WPD SendCommand returned null result");
        }
        // Check inner HRESULT (pid=1003 under WPD_COMMAND_CATEGORY)
        int innerHr = getUnsignedIntegerValue(arena, pResult, WPD_COMMAND_CATEGORY, 1003);
        if (innerHr != 0) {
            log.debug("WPD SendCommand inner HRESULT: 0x{}", Integer.toHexString(innerHr));
            if (failed(innerHr)) {
                throw new PtpException("WPD command failed with inner HRESULT: 0x"
                        + Integer.toHexString(innerHr));
            }
        }
        return pResult;
    }

    private static byte[] extractResponse(Arena arena, MemorySegment pResult) {
        int responseCode = getUnsignedIntegerValue(arena, pResult,
                WPD_CATEGORY_MTP_EXT, WPD_MTP_RESP_CODE_PID);
        log.debug("WPD MTP response code: 0x{}", Integer.toHexString(responseCode));

        int[] respParams = getResponseParams(arena, pResult);

        ByteBuffer buf = ByteBuffer.allocate(8 + respParams.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(responseCode);
        buf.putInt(respParams.length);
        for (int p : respParams) {
            buf.putInt(p);
        }
        return buf.array();
    }

    private static int[] getResponseParams(Arena arena, MemorySegment pResult) {
        try {
            MemorySegment ppColl = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment pk = createPropertyKey(arena, WPD_CATEGORY_MTP_EXT, WPD_MTP_RESP_PARAMS_PID);
            int hr = vtableCallInt(pResult, PDV_GetIUnknownValue, arena, pk, ppColl);
            MemorySegment pColl = ppColl.get(ValueLayout.ADDRESS, 0);
            if (failed(hr) || MemorySegment.NULL.equals(pColl)) {
                return new int[0];
            }
            try {
                MemorySegment countSeg = arena.allocate(ValueLayout.JAVA_INT);
                vtableCallInt(pColl, PDPVC_GetCount, arena, countSeg);
                int n = countSeg.get(ValueLayout.JAVA_INT, 0);
                int[] resultParams = new int[n];
                for (int i = 0; i < n; i++) {
                    MemorySegment pv = arena.allocate(PROPVARIANT_SIZE);
                    pv.fill((byte) 0);
                    vtableCallInt(pColl, PDPVC_GetAt, arena, i, pv);
                    resultParams[i] = pv.get(ValueLayout.JAVA_INT, 8);
                }
                return resultParams;
            } finally {
                release(pColl);
            }
        } catch (Exception e) {
            return new int[0];
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // IPortableDeviceValues helpers
    // ──────────────────────────────────────────────────────────────────────

    private static void setGuidValue(Arena arena, MemorySegment pValues,
                                     byte[] fmtid, int pid, byte[] value) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment guidSeg = arena.allocate(16);
        guidSeg.copyFrom(MemorySegment.ofArray(value));
        int hr = vtableCallInt(pValues, PDV_SetGuidValue, arena, pk, guidSeg);
        if (failed(hr)) {
            log.warn("SetGuidValue failed (hr=0x{})", Integer.toHexString(hr));
        }
    }

    private static void setUnsignedIntegerValue(Arena arena, MemorySegment pValues,
                                                 byte[] fmtid, int pid, int value) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        int hr = vtableCallInt(pValues, PDV_SetUnsignedIntegerValue, arena, pk, value);
        if (failed(hr)) {
            throw new PtpException("SetUnsignedIntegerValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static int getUnsignedIntegerValue(Arena arena, MemorySegment pValues,
                                                byte[] fmtid, int pid) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment val = arena.allocate(ValueLayout.JAVA_INT);
        int hr = vtableCallInt(pValues, PDV_GetUnsignedIntegerValue, arena, pk, val);
        if (failed(hr)) return 0;
        return val.get(ValueLayout.JAVA_INT, 0);
    }

    private static void setStringValue(Arena arena, MemorySegment pValues,
                                        byte[] fmtid, int pid, String value) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment wValue = toWideString(arena, value);
        int hr = vtableCallInt(pValues, PDV_SetStringValue, arena, pk, wValue);
        if (failed(hr)) {
            throw new PtpException("SetStringValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static String getStringValue(Arena arena, MemorySegment pValues,
                                          byte[] fmtid, int pid) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment pStr = arena.allocate(ValueLayout.ADDRESS);
        int hr = vtableCallInt(pValues, PDV_GetStringValue, arena, pk, pStr);
        MemorySegment strPtr = pStr.get(ValueLayout.ADDRESS, 0);
        if (failed(hr) || MemorySegment.NULL.equals(strPtr)) return "";
        String result = readWideString(strPtr.reinterpret(65536));
        coTaskMemFree(strPtr);
        return result;
    }

    private static void setBufferValue(Arena arena, MemorySegment pValues,
                                        byte[] fmtid, int pid, byte[] data) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment buf = arena.allocate(data.length);
        buf.copyFrom(MemorySegment.ofArray(data));
        int hr = vtableCallInt(pValues, PDV_SetBufferValue, arena, pk, buf, data.length);
        if (failed(hr)) {
            throw new PtpException("SetBufferValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static byte[] getBufferValue(Arena arena, MemorySegment pValues,
                                          byte[] fmtid, int pid) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        MemorySegment ppBuf = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment sizeSeg = arena.allocate(ValueLayout.JAVA_INT);
        int hr = vtableCallInt(pValues, PDV_GetBufferValue, arena, pk, ppBuf, sizeSeg);
        MemorySegment bufPtr = ppBuf.get(ValueLayout.ADDRESS, 0);
        if (failed(hr) || MemorySegment.NULL.equals(bufPtr)) return new byte[0];
        int len = sizeSeg.get(ValueLayout.JAVA_INT, 0);
        byte[] result = bufPtr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
        coTaskMemFree(bufPtr);
        return result;
    }

    private static void setIUnknownValue(Arena arena, MemorySegment pValues,
                                          byte[] fmtid, int pid, MemorySegment pUnk) {
        MemorySegment pk = createPropertyKey(arena, fmtid, pid);
        int hr = vtableCallInt(pValues, PDV_SetIUnknownValue, arena, pk, pUnk);
        if (failed(hr)) {
            throw new PtpException("SetIUnknownValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // PROPERTYKEY helper
    // ──────────────────────────────────────────────────────────────────────

    private static MemorySegment createPropertyKey(Arena arena, byte[] guidBytes, int pid) {
        MemorySegment pk = arena.allocate(PROPERTYKEY_LAYOUT);
        pk.copyFrom(MemorySegment.ofArray(guidBytes));
        pk.set(ValueLayout.JAVA_INT, 16, pid);
        return pk;
    }

    // ──────────────────────────────────────────────────────────────────────
    // GUID encoding helper
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Converts a GUID string (e.g. "0af10cec-2ecd-4b92-9581-34f6ae0637f3")
     * to 16 bytes in Windows native GUID memory layout (Data1 LE, Data2 LE, Data3 LE, Data4 BE).
     */
    private static byte[] guidBytes(String guidString) {
        String hex = guidString.replace("-", "");
        byte[] raw = hexToBytes(hex);

        byte[] result = new byte[16];
        // Data1 (4 bytes, little-endian)
        result[0] = raw[3]; result[1] = raw[2]; result[2] = raw[1]; result[3] = raw[0];
        // Data2 (2 bytes, little-endian)
        result[4] = raw[5]; result[5] = raw[4];
        // Data3 (2 bytes, little-endian)
        result[6] = raw[7]; result[7] = raw[6];
        // Data4 (8 bytes, as-is)
        System.arraycopy(raw, 8, result, 8, 8);
        return result;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Wide-string helpers (LPWSTR)
    // ──────────────────────────────────────────────────────────────────────

    /** Allocates a null-terminated UTF-16LE string in the given arena. */
    private static MemorySegment toWideString(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L); // +2 for null terminator
        seg.copyFrom(MemorySegment.ofArray(utf16));
        seg.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0);
        return seg;
    }

    /** Reads a null-terminated UTF-16LE string from a MemorySegment. */
    private static String readWideString(MemorySegment seg) {
        // Find the null terminator
        long offset = 0;
        while (seg.get(ValueLayout.JAVA_SHORT, offset) != 0) {
            offset += 2;
        }
        byte[] bytes = seg.asSlice(0, offset).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    // ──────────────────────────────────────────────────────────────────────
    // COM vtable call infrastructure (FFM-based)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Standard COM vtable function: HRESULT __stdcall Method(IInterface* pThis, ...).
     * We build the FunctionDescriptor dynamically based on the argument types.
     */
    private static int vtableCallInt(MemorySegment pInterface, int vtableIndex,
                                      Arena arena, Object... args) {
        try {
            // Read vtable pointer from the COM object
            MemorySegment vtable = pInterface.reinterpret(PTR_SIZE)
                    .get(ValueLayout.ADDRESS, 0);
            // Read function pointer at vtableIndex
            MemorySegment funcPtr = vtable.reinterpret(PTR_SIZE * (vtableIndex + 1))
                    .get(ValueLayout.ADDRESS, (long) vtableIndex * PTR_SIZE);

            // Build the FunctionDescriptor: return JAVA_INT, first arg is ADDRESS (this),
            // then one layout per extra argument
            MemoryLayout[] paramLayouts = new MemoryLayout[args.length + 1];
            paramLayouts[0] = ValueLayout.ADDRESS; // 'this' pointer
            Object[] callArgs = new Object[args.length + 1];
            callArgs[0] = pInterface;

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                switch (arg) {
                    case MemorySegment ms -> {
                        paramLayouts[i + 1] = ValueLayout.ADDRESS;
                        callArgs[i + 1] = ms;
                    }
                    case Integer iv -> {
                        paramLayouts[i + 1] = ValueLayout.JAVA_INT;
                        callArgs[i + 1] = iv;
                    }
                    case Long lv -> {
                        paramLayouts[i + 1] = ValueLayout.JAVA_LONG;
                        callArgs[i + 1] = lv;
                    }
                    default -> throw new IllegalArgumentException(
                            "Unsupported vtable arg type: " + arg.getClass());
                }
            }

            FunctionDescriptor desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, paramLayouts);
            MethodHandle mh = LINKER.downcallHandle(funcPtr, desc);
            return (int) mh.invokeWithArguments(callArgs);
        } catch (Throwable t) {
            throw new PtpException("COM vtable call failed (index=" + vtableIndex + ")", asException(t));
        }
    }

    private static void vtableCallHR(MemorySegment pInterface, int vtableIndex,
                                      Arena arena, Object... args) {
        int hr = vtableCallInt(pInterface, vtableIndex, arena, args);
        checkHR(hr, "COM vtable[" + vtableIndex + "]");
    }

    private static void vtableCallVoid(MemorySegment pInterface, int vtableIndex,
                                        Arena arena, Object... args) {
        vtableCallInt(pInterface, vtableIndex, arena, args);
    }

    /** IUnknown::Release (vtable index 2). */
    private static void release(MemorySegment pInterface) {
        if (pInterface != null && !MemorySegment.NULL.equals(pInterface)) {
            try {
                MemorySegment vtable = pInterface.reinterpret(PTR_SIZE)
                        .get(ValueLayout.ADDRESS, 0);
                MemorySegment funcPtr = vtable.reinterpret(PTR_SIZE * 3)
                        .get(ValueLayout.ADDRESS, 2 * PTR_SIZE);
                FunctionDescriptor desc = FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                MethodHandle mh = LINKER.downcallHandle(funcPtr, desc);
                mh.invokeExact(pInterface);
            } catch (Throwable t) {
                log.trace("COM Release failed", t);
            }
        }
    }

    private static void releaseAll(MemorySegment... segments) {
        for (MemorySegment s : segments) {
            release(s);
        }
    }

    private static void coTaskMemFree(MemorySegment ptr) {
        try {
            CoTaskMemFree.invokeExact(ptr);
        } catch (Throwable t) {
            log.trace("CoTaskMemFree failed", t);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // HRESULT helpers
    // ──────────────────────────────────────────────────────────────────────

    private static boolean failed(int hr) {
        return hr < 0;
    }

    private static void checkHR(int hr, String context) {
        if (failed(hr)) {
            throw new PtpException(String.format("%s failed: HRESULT 0x%08X", context, hr));
        }
    }

    private static Exception asException(Throwable t) {
        return (t instanceof Exception e) ? e : new RuntimeException(t);
    }
}
