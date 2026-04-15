package com.photographer.ptp.session;

import com.photographer.ptp.canon.CanonConstants;
import com.photographer.ptp.canon.CanonEventParser;
import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Background event polling loop for PTP cameras.
 * <p>
 * For standard PTP cameras, reads events from the USB interrupt endpoint.
 * For Canon cameras, periodically calls GetEvent (0x9116) to retrieve
 * vendor-specific events, since Canon cameras use bulk data rather than
 * the interrupt endpoint for most event reporting.
 */
public class EventLoop {
    private static final Logger log = LoggerFactory.getLogger(EventLoop.class);

    private static final long CANON_GET_EVENT_INTERVAL_MS = 500;
    private static final long STANDARD_READ_EVENT_TIMEOUT_MS = 1000;

    private final PtpSession session;
    private final PtpTransport transport;
    /** Canon property values cached from GetEvent responses. Thread-safe. */
    private final ConcurrentMap<Integer, Integer> propertyCache = new ConcurrentHashMap<>();
    private volatile PtpEventListener listener;
    private volatile boolean running;
    private volatile boolean canonMode;
    private volatile boolean suspended;
    private Thread eventThread;

    public EventLoop(PtpSession session) {
        this.session = session;
        this.transport = session.getTransport();
    }

    /**
     * Enables Canon GetEvent polling mode.
     * Must be called before {@link #start()} or at least before the first
     * iteration completes.
     */
    public void setCanonMode(boolean canonMode) {
        this.canonMode = canonMode;
    }

    /** Starts the event polling loop in a background thread. */
    public void start() {
        if (running) return;
        running = true;
        eventThread = new Thread(this::pollEvents, "ptp-event-loop");
        eventThread.setDaemon(true);
        eventThread.start();
        log.info("Event loop started (canon={})", canonMode);
    }

    /** Stops the event polling loop. */
    public void stop() {
        running = false;
        if (eventThread != null) {
            eventThread.interrupt();
            eventThread = null;
        }
        log.info("Event loop stopped");
    }

    public void setListener(PtpEventListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Temporarily suspends event polling.
     * Use this before performing a capture or other PTP operation to avoid
     * competing GetEvent calls on the WPD transport.
     * Waits briefly for the current poll iteration to finish.
     */
    public void suspend() {
        suspended = true;
        // Give the event loop thread time to finish its current poll cycle
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resumes event polling after a {@link #suspend()} call. */
    public void resume() {
        suspended = false;
    }

    /**
     * Returns the last known value for a Canon property code, or 0 if unknown.
     * Values are populated from Canon GetEvent (0x9116) property-changed events.
     */
    public int getPropertyValue(int propertyCode) {
        return propertyCache.getOrDefault(propertyCode, 0);
    }

    /**
     * Stores a Canon property value in the cache.
     * Called during initialization to seed the cache before the event loop starts.
     */
    void cacheProperty(int propertyCode, int value) {
        propertyCache.put(propertyCode, value);
    }

    private void pollEvents() {
        while (running && transport.isOpen()) {
            if (suspended) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            if (canonMode) {
                pollCanonEvents();
            } else {
                pollStandardEvents();
            }
        }
    }

    private void pollStandardEvents() {
        try {
            PtpContainer event = transport.readEvent(STANDARD_READ_EVENT_TIMEOUT_MS);
            if (event != null && listener != null) {
                listener.onEvent(event);
            }
        } catch (PtpException e) {
            if (running) {
                log.debug("Event poll error (may be timeout): {}", e.getMessage());
            }
        } catch (Exception e) {
            if (running) {
                log.warn("Unexpected error in event loop", e);
            }
        }
    }

    private void pollCanonEvents() {
        try {
            PtpTransport.DataResponse dr = session.executeReceiveData(CanonConstants.OC_EOS_GET_EVENT);
            if (dr.response().code() != com.photographer.ptp.core.PtpConstants.RC_OK) {
                if (running) {
                    log.debug("Canon GetEvent returned non-OK response: 0x{}",
                            Integer.toHexString(dr.response().code()));
                }
                return;
            }
            List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(dr.data());
            for (CanonEventParser.CanonEvent event : events) {
                if (event.isPropertyChanged()) {
                    propertyCache.put(event.getPropertyCode(), event.getPropertyValue());
                }
            }
            if (listener != null) {
                for (CanonEventParser.CanonEvent event : events) {
                    listener.onCanonEvent(event);
                }
            }
        } catch (PtpException e) {
            if (running) {
                log.debug("Canon GetEvent poll error: {}", e.getMessage());
            }
        } catch (Exception e) {
            if (running) {
                log.warn("Unexpected error in Canon event polling", e);
            }
        }
        try {
            Thread.sleep(CANON_GET_EVENT_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
