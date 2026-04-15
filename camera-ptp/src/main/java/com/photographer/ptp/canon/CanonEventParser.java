package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpTypeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Canon EOS event data returned by GetEvent (0x9116).
 * Canon events are a blob of concatenated TLV-like entries.
 */
public class CanonEventParser {
    private static final Logger log = LoggerFactory.getLogger(CanonEventParser.class);

    private CanonEventParser() {}

    /**
     * Parses the raw event data into a list of CanonEvent records.
     */
    public static List<CanonEvent> parse(byte[] data) {
        List<CanonEvent> events = new ArrayList<>();
        if (data == null || data.length < 8) {
            return events;
        }
        PtpTypeReader reader = new PtpTypeReader(data);
        while (reader.remaining() >= 8) {
            int entryLength = reader.readInt32();
            int eventType = reader.readInt32();
            if (entryLength <= 8) {
                break;
            }
            int payloadLength = entryLength - 8;
            if (reader.remaining() < payloadLength) {
                log.warn("Truncated Canon event entry: type=0x{}, payloadLen={}, remaining={}",
                        String.format("%04X", eventType), payloadLength, reader.remaining());
                break;
            }
            byte[] payload = payloadLength > 0 ? reader.readBytes(payloadLength) : new byte[0];
            events.add(new CanonEvent(eventType, payload));
            log.debug("Parsed Canon event: type=0x{}, payloadLen={}",
                    String.format("%04X", eventType), payload.length);
        }
        return events;
    }

    /**
     * Represents a single Canon EOS event entry.
     */
    public record CanonEvent(int type, byte[] payload) {
        /** Checks if this is a property-changed event. */
        public boolean isPropertyChanged() {
            return type == CanonConstants.EVENT_PROP_CHANGED;
        }

        /** Checks if this is an object-added event. */
        public boolean isObjectAdded() {
            return type == CanonConstants.EVENT_OBJ_ADDED;
        }

        /** Checks if this is a request-complete event. */
        public boolean isRequestComplete() {
            return type == CanonConstants.EVENT_REQUEST_COMPLETE;
        }

        /** Extracts the property code from a property-changed event. */
        public int getPropertyCode() {
            if (payload.length >= 4) {
                PtpTypeReader r = new PtpTypeReader(payload);
                return r.readInt32();
            }
            return 0;
        }

        /** Extracts the property value from a property-changed event. */
        public int getPropertyValue() {
            if (payload.length >= 8) {
                PtpTypeReader r = new PtpTypeReader(payload);
                r.readInt32(); // skip property code
                return r.readInt32();
            }
            return 0;
        }

        /** Extracts the object handle from an object-added event. */
        public int getObjectHandle() {
            if (payload.length >= 4) {
                PtpTypeReader r = new PtpTypeReader(payload);
                return r.readInt32();
            }
            return 0;
        }
    }
}
