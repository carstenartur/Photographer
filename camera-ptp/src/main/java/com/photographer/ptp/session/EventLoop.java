package com.photographer.ptp.session;

import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background event polling loop for PTP cameras.
 * Reads events from the transport's interrupt endpoint.
 */
public class EventLoop {
    private static final Logger log = LoggerFactory.getLogger(EventLoop.class);

    private final PtpTransport transport;
    private volatile PtpEventListener listener;
    private volatile boolean running;
    private Thread eventThread;

    public EventLoop(PtpTransport transport) {
        this.transport = transport;
    }

    /** Starts the event polling loop in a background thread. */
    public void start() {
        if (running) return;
        running = true;
        eventThread = new Thread(this::pollEvents, "ptp-event-loop");
        eventThread.setDaemon(true);
        eventThread.start();
        log.info("Event loop started");
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

    private void pollEvents() {
        while (running && transport.isOpen()) {
            try {
                PtpContainer event = transport.readEvent(1000);
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
    }
}
