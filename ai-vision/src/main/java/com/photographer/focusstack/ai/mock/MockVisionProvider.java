package com.photographer.focusstack.ai.mock;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock VisionProvider for tests and development.
 *
 * <p>Returns results from a pre-configured list in a round-robin fashion.
 * Optionally simulates latency and errors.</p>
 */
public class MockVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(MockVisionProvider.class);

    private final List<VisionResult> scheduledResults;
    private final long simulatedLatencyMs;
    private int callIndex = 0;

    /** Creates a provider that returns a single fixed result. */
    public MockVisionProvider(VisionResult fixedResult) {
        this(List.of(fixedResult), 0);
    }

    /** Creates a provider that cycles through the given results. */
    public MockVisionProvider(List<VisionResult> scheduledResults, long simulatedLatencyMs) {
        this.scheduledResults = new ArrayList<>(scheduledResults);
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        simulateLatency();
        VisionResult result = nextResult();
        log.debug("MockVisionProvider.classifyImage → {}", result.primaryLabel());
        return result;
    }

    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        simulateLatency();
        return nextResult();
    }

    @Override
    public VisionResult rankBestImage(List<byte[]> images, VisionContext context) {
        simulateLatency();
        return nextResult();
    }

    @Override
    public boolean isAvailable() {
        return !scheduledResults.isEmpty();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, false, true,
                4 * 1024 * 1024, List.of("jpeg", "png"));
    }

    /** Resets the round-robin counter. */
    public void reset() {
        callIndex = 0;
    }

    /** Returns how many times the provider has been called. */
    public int getCallCount() {
        return callIndex;
    }

    private VisionResult nextResult() {
        if (scheduledResults.isEmpty()) {
            return VisionResult.error("mock", "mock-model", "No results configured");
        }
        VisionResult result = scheduledResults.get(callIndex % scheduledResults.size());
        callIndex++;
        return result;
    }

    private void simulateLatency() {
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
