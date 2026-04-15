package com.photographer.ptp.wpd;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpException;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Low-level JNA bridge to the Windows Portable Devices (WPD) COM API.
 * <p>
 * Uses the MTP vendor extension commands to send raw PTP operations through
 * the standard Windows MTP driver — no WinUSB/Zadig needed.
 * <p>
 * COM interfaces used (from PortableDeviceApi.dll / PortableDeviceTypes.dll):
 * <ul>
 *   <li>{@code IPortableDeviceManager} — enumerate devices</li>
 *   <li>{@code IPortableDevice} — open device, send commands</li>
 *   <li>{@code IPortableDeviceValues} — parameter bags for commands</li>
 *   <li>{@code IPortableDevicePropVariantCollection} — parameter arrays</li>
 * </ul>
 */
final class WpdNative {

    private WpdNative() {}

    private static final Logger log = LoggerFactory.getLogger(WpdNative.class);

    // ──────────────────────────────────────────────────────────────────────
    // CLSIDs
    // ──────────────────────────────────────────────────────────────────────
    static final Guid.CLSID CLSID_PortableDeviceManager =
            new Guid.CLSID("0af10cec-2ecd-4b92-9581-34f6ae0637f3");
    static final Guid.CLSID CLSID_PortableDevice =
            new Guid.CLSID("728a21c5-3d9e-48d7-9810-864848f0f404");
    static final Guid.CLSID CLSID_PortableDeviceValues =
            new Guid.CLSID("0c15d503-d017-47ce-9016-7b3f978721cc");
    static final Guid.CLSID CLSID_PortableDevicePropVariantCollection =
            new Guid.CLSID("08a99e2f-6d6d-4b80-af5a-baf2bcbe4cb9");

    // ──────────────────────────────────────────────────────────────────────
    // IIDs
    // ──────────────────────────────────────────────────────────────────────
    static final Guid.IID IID_IPortableDeviceManager =
            new Guid.IID("a1567595-4c2f-4574-a6fa-ecef917b9a40");
    static final Guid.IID IID_IPortableDevice =
            new Guid.IID("625e2df8-6392-4cf0-9ad1-3cfa5f17775c");
    static final Guid.IID IID_IPortableDeviceValues =
            new Guid.IID("6848f6f2-3155-4f86-b6f5-263eeeab3143");
    static final Guid.IID IID_IPortableDevicePropVariantCollection =
            new Guid.IID("89b2e422-4f1b-4316-bcef-a44afea83eb3");

    // ──────────────────────────────────────────────────────────────────────
    // WPD Property Keys for MTP Extension commands
    // ──────────────────────────────────────────────────────────────────────
    // WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITHOUT_DATA_PHASE
    static final Guid.GUID WPD_CATEGORY_MTP_EXT =
            new Guid.GUID("4d545058-1a2e-4106-a357-771e0819fc56");
    static final int WPD_MTP_OPCODE_PID = 1001;          // WPD_PROPERTY_MTP_EXT_OPERATION_CODE
    static final int WPD_MTP_PARAMS_PID = 1002;          // WPD_PROPERTY_MTP_EXT_OPERATION_PARAMS
    static final int WPD_MTP_RESP_CODE_PID = 1003;       // WPD_PROPERTY_MTP_EXT_RESPONSE_CODE
    static final int WPD_MTP_RESP_PARAMS_PID = 1004;     // WPD_PROPERTY_MTP_EXT_RESPONSE_PARAMS
    static final int WPD_MTP_TRANSFER_DATA_PID = 1005;   // WPD_PROPERTY_MTP_EXT_TRANSFER_DATA
    static final int WPD_MTP_TRANSFER_SIZE_PID = 1006;   // WPD_PROPERTY_MTP_EXT_TRANSFER_TOTAL_DATA_SIZE
    static final int WPD_MTP_OPTIMAL_BUFFER_PID = 1007;  // WPD_PROPERTY_MTP_EXT_OPTIMAL_TRANSFER_BUFFER_SIZE
    static final int WPD_MTP_NUM_BYTES_READ_PID = 1008;  // WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_READ
    static final int WPD_MTP_NUM_BYTES_WRITTEN_PID = 1009; // WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_WRITTEN
    static final int WPD_MTP_TRANSFER_CONTEXT_PID = 1010; // WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT

    // Command IDs (fmtid + pid pairs packed into PROPERTYKEY)
    static final int CMD_NO_DATA = 12;          // WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITHOUT_DATA_PHASE
    static final int CMD_READ_DATA = 13;        // WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_READ
    static final int CMD_WRITE_DATA = 14;       // WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_WRITE
    static final int CMD_READ_DATA_END = 15;    // WPD_COMMAND_MTP_EXT_READ_DATA
    static final int CMD_WRITE_DATA_END = 16;   // WPD_COMMAND_MTP_EXT_WRITE_DATA
    static final int CMD_END_TRANSFER = 17;     // WPD_COMMAND_MTP_EXT_END_DATA_TRANSFER

    // WPD_PROPERTY_COMMON_COMMAND_CATEGORY / WPD_PROPERTY_COMMON_COMMAND_ID
    static final Guid.GUID WPD_COMMAND_CATEGORY =
            new Guid.GUID("f0422a9c-5dc8-4440-b5bd-5df28835658a");
    static final int WPD_COMMON_CMD_CATEGORY_PID = 1001;
    static final int WPD_COMMON_CMD_ID_PID = 1002;

    // ──────────────────────────────────────────────────────────────────────
    // IPortableDeviceValues VTable offsets (IUnknown has 3, then custom)
    // ──────────────────────────────────────────────────────────────────────
    // IPortableDeviceValues vtable (inherits IUnknown: QI=0, AddRef=1, Release=2)
    static final int PDV_GetCount                = 3;
    static final int PDV_GetAt                   = 4;
    static final int PDV_SetValue                = 5;
    static final int PDV_GetValue                = 6;
    static final int PDV_SetStringValue          = 7;
    static final int PDV_GetStringValue          = 8;
    static final int PDV_SetUnsignedIntegerValue = 9;
    static final int PDV_GetUnsignedIntegerValue = 10;
    static final int PDV_SetSignedIntegerValue   = 11;
    static final int PDV_GetSignedIntegerValue   = 12;
    static final int PDV_SetUnsignedLargeIntegerValue = 13;
    static final int PDV_GetUnsignedLargeIntegerValue = 14;
    static final int PDV_SetSignedLargeIntegerValue   = 15;
    static final int PDV_GetSignedLargeIntegerValue   = 16;
    static final int PDV_SetFloatValue           = 17;
    static final int PDV_GetFloatValue           = 18;
    static final int PDV_SetErrorValue           = 19;
    static final int PDV_GetErrorValue           = 20;
    static final int PDV_SetKeyValue             = 21;
    static final int PDV_GetKeyValue             = 22;
    static final int PDV_SetBoolValue            = 23;
    static final int PDV_GetBoolValue            = 24;
    static final int PDV_SetIUnknownValue        = 25;
    static final int PDV_GetIUnknownValue        = 26;
    static final int PDV_SetGuidValue            = 27;
    static final int PDV_GetGuidValue            = 28;
    static final int PDV_SetBufferValue          = 29;
    static final int PDV_GetBufferValue          = 30;
    static final int PDV_SetIPortableDeviceValuesCollectionValue = 31;
    static final int PDV_GetIPortableDeviceValuesCollectionValue = 32;
    static final int PDV_SetIPortableDevicePropVariantCollectionValue = 33;
    static final int PDV_GetIPortableDevicePropVariantCollectionValue = 34;

    // IPortableDevice vtable
    static final int PD_Open       = 3;
    static final int PD_SendCommand = 4;
    static final int PD_Content    = 5;
    static final int PD_Capabilities = 6;
    static final int PD_Cancel     = 7;
    static final int PD_Close      = 8;

    // IPortableDeviceManager vtable
    static final int PDM_GetDevices = 3;
    static final int PDM_RefreshDeviceList = 4;
    static final int PDM_GetDeviceDescription = 7;
    static final int PDM_GetDeviceFriendlyName = 5;
    static final int PDM_GetDeviceManufacturer = 6;

    // IPortableDevicePropVariantCollection vtable
    static final int PDPVC_GetCount = 3;
    static final int PDPVC_GetAt    = 4;
    static final int PDPVC_Add      = 5;

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Ensures COM is initialized on the current thread.
     * Safe to call multiple times — repeated calls are harmless.
     */
    static void ensureComInitialized() {
        WinNT.HRESULT hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        // S_OK (0) = success, S_FALSE (1) = already initialized — both fine
        int hri = hr.intValue();
        if (hri != 0 && hri != 1) {
            // RPC_E_CHANGED_MODE means STA was already initialized — also OK for our purposes
            if (hri != 0x80010106) {
                COMUtils.checkRC(hr);
            }
        }
    }

    /**
     * Finds all WPD device IDs for Canon cameras.
     */
    static String[] findCanonDeviceIds() {
        ensureComInitialized();
        Pointer pManager = createInstance(CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
        try {
            // Refresh
            vtableCall(pManager, PDM_RefreshDeviceList);

            // Get count
            IntByReference count = new IntByReference(0);
            vtableCallHR(pManager, PDM_GetDevices, Pointer.NULL, count);
            int n = count.getValue();
            if (n == 0) return new String[0];

            // Allocate array of LPWSTRs
            Pointer[] deviceIds = new Pointer[n];
            Memory pArr = new Memory((long) n * Native.POINTER_SIZE);
            count.setValue(n);
            vtableCallHR(pManager, PDM_GetDevices, pArr, count);

            String[] result = new String[n];
            for (int i = 0; i < n; i++) {
                Pointer p = pArr.getPointer((long) i * Native.POINTER_SIZE);
                result[i] = p.getWideString(0);
                Ole32.INSTANCE.CoTaskMemFree(p);
            }
            return result;
        } finally {
            release(pManager);
        }
    }

    /**
     * Gets the friendly name for a WPD device.
     */
    static String getDeviceFriendlyName(String deviceId) {
        Pointer pManager = createInstance(CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
        try {
            WString wDeviceId = new WString(deviceId);
            IntByReference nameLen = new IntByReference(0);
            // First call to get length
            int hr = vtableCallInt(pManager, PDM_GetDeviceFriendlyName, wDeviceId, Pointer.NULL, nameLen);
            if (W32Errors.FAILED(hr)) return deviceId;
            int len = nameLen.getValue();
            if (len == 0) return deviceId;
            Memory buf = new Memory((long) len * 2);
            nameLen.setValue(len);
            hr = vtableCallInt(pManager, PDM_GetDeviceFriendlyName, wDeviceId, buf, nameLen);
            if (W32Errors.FAILED(hr)) return deviceId;
            return buf.getWideString(0);
        } finally {
            release(pManager);
        }
    }

    /**
     * Gets the manufacturer for a WPD device.
     */
    static String getDeviceManufacturer(String deviceId) {
        Pointer pManager = createInstance(CLSID_PortableDeviceManager, IID_IPortableDeviceManager);
        try {
            WString wDeviceId = new WString(deviceId);
            IntByReference nameLen = new IntByReference(0);
            int hr = vtableCallInt(pManager, PDM_GetDeviceManufacturer, wDeviceId, Pointer.NULL, nameLen);
            if (W32Errors.FAILED(hr)) return "";
            int len = nameLen.getValue();
            if (len == 0) return "";
            Memory buf = new Memory((long) len * 2);
            nameLen.setValue(len);
            hr = vtableCallInt(pManager, PDM_GetDeviceManufacturer, wDeviceId, buf, nameLen);
            if (W32Errors.FAILED(hr)) return "";
            return buf.getWideString(0);
        } finally {
            release(pManager);
        }
    }

    /**
     * Opens a WPD device and returns the IPortableDevice pointer.
     */
    static Pointer openDevice(String deviceId) {
        ensureComInitialized();
        Pointer pDevice = createInstance(CLSID_PortableDevice, IID_IPortableDevice);
        Pointer pValues = createValues();
        try {
            // Open the device with the client info values
            WString wDeviceId = new WString(deviceId);
            int hr = vtableCallInt(pDevice, PD_Open, wDeviceId, pValues);
            COMUtils.checkRC(new WinNT.HRESULT(hr));
            return pDevice;
        } catch (Exception e) {
            release(pDevice);
            throw new PtpException("Failed to open WPD device: " + deviceId, e);
        } finally {
            release(pValues);
        }
    }

    /**
     * Closes a WPD device.
     */
    static void closeDevice(Pointer pDevice) {
        if (pDevice != null) {
            vtableCall(pDevice, PD_Close);
            release(pDevice);
        }
    }

    /**
     * Sends a PTP/MTP command through WPD.
     *
     * @param pDevice   opened IPortableDevice pointer
     * @param opCode    PTP operation code
     * @param params    PTP parameters (up to 5)
     * @param dataOut   data to send (for write commands), null for no-data
     * @param readData  true if expecting data back from the camera
     * @return response bytes: [4-byte resp code][4-byte param count][params...][data if readData]
     */
    static byte[] sendCommand(Pointer pDevice, int opCode, int[] params,
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
    private static byte[] sendCommandNoData(Pointer pDevice, int opCode, int[] params) {
        Pointer pInput = createValues();
        Pointer pResult = null;
        Pointer pParamCollection = null;
        try {
            // Set command category + id
            setGuidValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                    WPD_CATEGORY_MTP_EXT);
            setUnsignedIntegerValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                    CMD_NO_DATA);

            // Set MTP opcode
            setUnsignedIntegerValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_OPCODE_PID, opCode);

            // Set parameters collection
            pParamCollection = createParamCollection(params);
            setIUnknownValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_PARAMS_PID, pParamCollection);

            // Send
            pResult = sendDeviceCommand(pDevice, pInput);

            // Extract response
            return extractResponse(pResult);
        } finally {
            releaseAll(pInput, pResult, pParamCollection);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MTP Extension: read data phase
    // ──────────────────────────────────────────────────────────────────────
    private static byte[] sendCommandWithDataToRead(Pointer pDevice, int opCode, int[] params) {
        Pointer pInput = createValues();
        Pointer pResult = null;
        Pointer pParamCollection = null;
        try {
            // Phase 1: initiate read
            setGuidValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                    WPD_CATEGORY_MTP_EXT);
            setUnsignedIntegerValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                    CMD_READ_DATA);
            setUnsignedIntegerValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_OPCODE_PID, opCode);
            pParamCollection = createParamCollection(params);
            setIUnknownValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_PARAMS_PID, pParamCollection);

            pResult = sendDeviceCommand(pDevice, pInput);

            // Get transfer buffer size
            int bufferSize = getUnsignedIntegerValue(pResult, WPD_CATEGORY_MTP_EXT,
                    WPD_MTP_TRANSFER_SIZE_PID);
            log.debug("WPD data read: buffer size = {}", bufferSize);
            if (bufferSize <= 0) bufferSize = 512 * 1024; // default 512KB

            // Get the context string for the transfer
            String transferContext = getStringValue(pResult, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID);
            log.debug("WPD data read: transfer context = '{}'", transferContext);

            release(pResult);
            pResult = null;

            // Phase 2: read data in chunks
            byte[] allData = new byte[0];
            boolean done = false;

            while (!done) {
                Pointer pRead = createValues();
                try {
                    setGuidValue(pRead, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                            WPD_CATEGORY_MTP_EXT);
                    setUnsignedIntegerValue(pRead, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                            CMD_READ_DATA_END);

                    // Set transfer context
                    setStringValue(pRead, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                    // Provide a buffer
                    int chunkSize = Math.min(bufferSize, 512 * 1024);
                    byte[] chunk = new byte[chunkSize];
                    setBufferValue(pRead, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_DATA_PID, chunk);
                    setUnsignedIntegerValue(pRead, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_NUM_BYTES_READ_PID, chunkSize);

                    pResult = sendDeviceCommand(pDevice, pRead);

                    // Get actual bytes read
                    byte[] readData = getBufferValue(pResult, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_TRANSFER_DATA_PID);
                    int bytesRead = getUnsignedIntegerValue(pResult, WPD_CATEGORY_MTP_EXT,
                            WPD_MTP_NUM_BYTES_READ_PID);

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
                    pResult = null;
                } finally {
                    release(pRead);
                }
            }

            log.debug("WPD data read complete: {} bytes total", allData.length);

            // Phase 3: end transfer — get the MTP response code
            Pointer pEnd = createValues();
            try {
                setGuidValue(pEnd, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(pEnd, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                        CMD_END_TRANSFER);
                setStringValue(pEnd, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                pResult = sendDeviceCommand(pDevice, pEnd);

                byte[] responseHeader = extractResponse(pResult);

                // If EndTransfer returned 0x0 (no explicit response code) but we read data
                // successfully, treat it as RC_OK
                ByteBuffer respBuf = ByteBuffer.wrap(responseHeader).order(ByteOrder.LITTLE_ENDIAN);
                int respCode = respBuf.getInt();
                if (respCode == 0 && allData.length > 0) {
                    log.debug("WPD EndTransfer had no response code, assuming RC_OK for {} bytes of data",
                            allData.length);
                    respBuf.putInt(0, PtpConstants.RC_OK);
                }

                // Combine: response header + data
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

    // ──────────────────────────────────────────────────────────────────────
    // MTP Extension: write data phase
    // ──────────────────────────────────────────────────────────────────────
    private static byte[] sendCommandWithDataToWrite(Pointer pDevice, int opCode,
                                                     int[] params, byte[] data) {
        Pointer pInput = createValues();
        Pointer pResult = null;
        Pointer pParamCollection = null;
        try {
            // Phase 1: initiate write
            setGuidValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                    WPD_CATEGORY_MTP_EXT);
            setUnsignedIntegerValue(pInput, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                    CMD_WRITE_DATA);
            setUnsignedIntegerValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_OPCODE_PID, opCode);
            pParamCollection = createParamCollection(params);
            setIUnknownValue(pInput, WPD_CATEGORY_MTP_EXT, WPD_MTP_PARAMS_PID, pParamCollection);
            setUnsignedIntegerValue(pInput, WPD_CATEGORY_MTP_EXT,
                    WPD_MTP_TRANSFER_SIZE_PID, data.length);

            pResult = sendDeviceCommand(pDevice, pInput);

            String transferContext = getStringValue(pResult, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID);
            release(pResult);
            pResult = null;

            // Phase 2: write data
            Pointer pWrite = createValues();
            try {
                setGuidValue(pWrite, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(pWrite, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                        CMD_WRITE_DATA_END);
                setStringValue(pWrite, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);
                setBufferValue(pWrite, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_DATA_PID, data);
                setUnsignedIntegerValue(pWrite, WPD_CATEGORY_MTP_EXT,
                        WPD_MTP_NUM_BYTES_WRITTEN_PID, data.length);

                pResult = sendDeviceCommand(pDevice, pWrite);
                release(pResult);
                pResult = null;
            } finally {
                release(pWrite);
            }

            // Phase 3: end transfer
            Pointer pEnd = createValues();
            try {
                setGuidValue(pEnd, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_CATEGORY_PID,
                        WPD_CATEGORY_MTP_EXT);
                setUnsignedIntegerValue(pEnd, WPD_COMMAND_CATEGORY, WPD_COMMON_CMD_ID_PID,
                        CMD_END_TRANSFER);
                setStringValue(pEnd, WPD_CATEGORY_MTP_EXT, WPD_MTP_TRANSFER_CONTEXT_PID, transferContext);

                pResult = sendDeviceCommand(pDevice, pEnd);
                return extractResponse(pResult);
            } finally {
                release(pEnd);
            }
        } finally {
            releaseAll(pInput, pResult, pParamCollection);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // COM helpers
    // ──────────────────────────────────────────────────────────────────────

    private static Pointer createInstance(Guid.CLSID clsid, Guid.IID iid) {
        PointerByReference pp = new PointerByReference();
        WinNT.HRESULT hr = Ole32.INSTANCE.CoCreateInstance(clsid, null,
                WTypes.CLSCTX_INPROC_SERVER, iid, pp);
        COMUtils.checkRC(hr);
        return pp.getValue();
    }

    static Pointer createValues() {
        return createInstance(CLSID_PortableDeviceValues, IID_IPortableDeviceValues);
    }

    private static Pointer createParamCollection(int[] params) {
        Pointer pColl = createInstance(CLSID_PortableDevicePropVariantCollection,
                IID_IPortableDevicePropVariantCollection);
        if (params != null) {
            for (int param : params) {
                // PROPVARIANT with VT_UI4
                Memory pv = new Memory(24); // PROPVARIANT is 16 or 24 bytes
                pv.clear();
                pv.setShort(0, (short) Variant.VT_UI4);  // vt
                pv.setInt(8, param);                       // uintVal at offset 8
                int hr = vtableCallInt(pColl, PDPVC_Add, pv);
                if (W32Errors.FAILED(hr)) {
                    log.warn("Failed to add param to collection: 0x{}", Integer.toHexString(hr));
                }
            }
        }
        return pColl;
    }

    private static Pointer sendDeviceCommand(Pointer pDevice, Pointer pInput) {
        PointerByReference ppResult = new PointerByReference();
        int flags = 0; // No options
        int hr = vtableCallInt(pDevice, PD_SendCommand, flags, pInput, ppResult);
        COMUtils.checkRC(new WinNT.HRESULT(hr));
        Pointer pResult = ppResult.getValue();
        if (pResult == null) {
            throw new PtpException("WPD SendCommand returned null result");
        }
        // Check for inner HRESULT error (WPD_PROPERTY_COMMON_HRESULT, pid=1003 under WPD_COMMAND_CATEGORY)
        int innerHr = getUnsignedIntegerValue(pResult, WPD_COMMAND_CATEGORY, 1003);
        if (innerHr != 0) {
            log.debug("WPD SendCommand inner HRESULT: 0x{}", Integer.toHexString(innerHr));
            if (W32Errors.FAILED(innerHr)) {
                throw new PtpException("WPD command failed with inner HRESULT: 0x"
                        + Integer.toHexString(innerHr));
            }
        }
        return pResult;
    }

    private static byte[] extractResponse(Pointer pResult) {
        int responseCode = getUnsignedIntegerValue(pResult, WPD_CATEGORY_MTP_EXT,
                WPD_MTP_RESP_CODE_PID);
        log.debug("WPD MTP response code: 0x{}", Integer.toHexString(responseCode));

        // Try to get response params
        int[] respParams = getResponseParams(pResult);

        ByteBuffer buf = ByteBuffer.allocate(8 + respParams.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(responseCode);
        buf.putInt(respParams.length);
        for (int p : respParams) {
            buf.putInt(p);
        }
        return buf.array();
    }

    private static int[] getResponseParams(Pointer pResult) {
        try {
            PointerByReference ppColl = new PointerByReference();
            // PROPERTYKEY for response params
            Memory pk = createPropertyKey(WPD_CATEGORY_MTP_EXT, WPD_MTP_RESP_PARAMS_PID);
            int hr = vtableCallInt(pResult, PDV_GetIUnknownValue, pk, ppColl);
            if (W32Errors.FAILED(hr) || ppColl.getValue() == null) {
                return new int[0];
            }
            Pointer pColl = ppColl.getValue();
            try {
                IntByReference count = new IntByReference();
                vtableCallInt(pColl, PDPVC_GetCount, count);
                int n = count.getValue();
                int[] params = new int[n];
                for (int i = 0; i < n; i++) {
                    Memory pv = new Memory(24);
                    pv.clear();
                    vtableCallInt(pColl, PDPVC_GetAt, i, pv);
                    params[i] = pv.getInt(8);
                }
                return params;
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

    private static void setGuidValue(Pointer pValues, Guid.GUID fmtid, int pid, Guid.GUID value) {
        Memory pk = createPropertyKey(fmtid, pid);
        // Allocate a native GUID struct and write it using JNA's Guid.GUID
        // JNA's GUID stores Data1(4) Data2(2) Data3(2) Data4(8) in native layout
        Guid.GUID.ByReference ref = new Guid.GUID.ByReference();
        ref.Data1 = value.Data1;
        ref.Data2 = value.Data2;
        ref.Data3 = value.Data3;
        ref.Data4 = value.Data4;
        ref.write(); // flush to native memory
        int hr = vtableCallInt(pValues, PDV_SetGuidValue, pk, ref.getPointer());
        if (W32Errors.FAILED(hr)) {
            log.warn("SetGuidValue failed (hr=0x{})", Integer.toHexString(hr));
        }
    }

    private static void setUnsignedIntegerValue(Pointer pValues, Guid.GUID fmtid, int pid, int value) {
        Memory pk = createPropertyKey(fmtid, pid);
        int hr = vtableCallInt(pValues, PDV_SetUnsignedIntegerValue, pk, value);
        if (W32Errors.FAILED(hr)) {
            throw new PtpException("SetUnsignedIntegerValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static int getUnsignedIntegerValue(Pointer pValues, Guid.GUID fmtid, int pid) {
        Memory pk = createPropertyKey(fmtid, pid);
        IntByReference val = new IntByReference();
        int hr = vtableCallInt(pValues, PDV_GetUnsignedIntegerValue, pk, val);
        if (W32Errors.FAILED(hr)) return 0;
        return val.getValue();
    }

    private static void setStringValue(Pointer pValues, Guid.GUID fmtid, int pid, String value) {
        Memory pk = createPropertyKey(fmtid, pid);
        WString wValue = new WString(value);
        int hr = vtableCallInt(pValues, PDV_SetStringValue, pk, wValue);
        if (W32Errors.FAILED(hr)) {
            throw new PtpException("SetStringValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static String getStringValue(Pointer pValues, Guid.GUID fmtid, int pid) {
        Memory pk = createPropertyKey(fmtid, pid);
        PointerByReference pStr = new PointerByReference();
        int hr = vtableCallInt(pValues, PDV_GetStringValue, pk, pStr);
        if (W32Errors.FAILED(hr) || pStr.getValue() == null) return "";
        String result = pStr.getValue().getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(pStr.getValue());
        return result;
    }

    private static void setBufferValue(Pointer pValues, Guid.GUID fmtid, int pid, byte[] data) {
        Memory pk = createPropertyKey(fmtid, pid);
        Memory buf = new Memory(data.length);
        buf.write(0, data, 0, data.length);
        int hr = vtableCallInt(pValues, PDV_SetBufferValue, pk, buf, data.length);
        if (W32Errors.FAILED(hr)) {
            throw new PtpException("SetBufferValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static byte[] getBufferValue(Pointer pValues, Guid.GUID fmtid, int pid) {
        Memory pk = createPropertyKey(fmtid, pid);
        PointerByReference ppBuf = new PointerByReference();
        IntByReference size = new IntByReference();
        int hr = vtableCallInt(pValues, PDV_GetBufferValue, pk, ppBuf, size);
        if (W32Errors.FAILED(hr) || ppBuf.getValue() == null) return new byte[0];
        int len = size.getValue();
        byte[] result = ppBuf.getValue().getByteArray(0, len);
        Ole32.INSTANCE.CoTaskMemFree(ppBuf.getValue());
        return result;
    }

    private static void setIUnknownValue(Pointer pValues, Guid.GUID fmtid, int pid, Pointer pUnk) {
        Memory pk = createPropertyKey(fmtid, pid);
        int hr = vtableCallInt(pValues, PDV_SetIUnknownValue, pk, pUnk);
        if (W32Errors.FAILED(hr)) {
            throw new PtpException("SetIUnknownValue failed: 0x" + Integer.toHexString(hr));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // PROPERTYKEY helper
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Creates a PROPERTYKEY structure (GUID fmtid + DWORD pid) in native memory.
     * Layout: 16 bytes GUID + 4 bytes pid = 20 bytes total.
     */
    private static Memory createPropertyKey(Guid.GUID guid, int pid) {
        Memory pk = new Memory(20);
        // Write GUID as raw bytes
        byte[] guidBytes = guidToBytes(guid);
        pk.write(0, guidBytes, 0, 16);
        pk.setInt(16, pid);
        return pk;
    }

    private static byte[] guidToBytes(Guid.GUID guid) {
        // Parse GUID string to bytes in Windows GUID layout
        // {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}
        String s = guid.toGuidString();
        // Remove braces and dashes
        s = s.replace("{", "").replace("}", "").replace("-", "");
        byte[] raw = hexToBytes(s);

        // Windows GUID layout: Data1 (4 LE), Data2 (2 LE), Data3 (2 LE), Data4 (8 BE)
        byte[] result = new byte[16];
        // Data1 - reverse 4 bytes (LE)
        result[0] = raw[3]; result[1] = raw[2]; result[2] = raw[1]; result[3] = raw[0];
        // Data2 - reverse 2 bytes (LE)
        result[4] = raw[5]; result[5] = raw[4];
        // Data3 - reverse 2 bytes (LE)
        result[6] = raw[7]; result[7] = raw[6];
        // Data4 - as-is (8 bytes)
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
    // VTable call helpers
    // ──────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static int vtableCallInt(Pointer pInterface, int vtableIndex, Object... args) {
        Pointer vtable = pInterface.getPointer(0);
        long funcPtr = Pointer.nativeValue(vtable) + (long) vtableIndex * Native.POINTER_SIZE;
        Pointer func = new Pointer(vtable.getLong((long) vtableIndex * Native.POINTER_SIZE));

        Object[] allArgs = new Object[args.length + 1];
        allArgs[0] = pInterface; // 'this' pointer
        System.arraycopy(args, 0, allArgs, 1, args.length);

        return Function.getFunction(func).invokeInt(allArgs);
    }

    private static void vtableCallHR(Pointer pInterface, int vtableIndex, Object... args) {
        int hr = vtableCallInt(pInterface, vtableIndex, args);
        COMUtils.checkRC(new WinNT.HRESULT(hr));
    }

    private static void vtableCall(Pointer pInterface, int vtableIndex, Object... args) {
        vtableCallInt(pInterface, vtableIndex, args);
    }

    private static void release(Pointer pInterface) {
        if (pInterface != null) {
            try {
                vtableCallInt(pInterface, 2); // IUnknown::Release is vtable index 2
            } catch (Exception e) {
                log.trace("COM Release failed", e);
            }
        }
    }

    private static void releaseAll(Pointer... pointers) {
        for (Pointer p : pointers) {
            release(p);
        }
    }
}
