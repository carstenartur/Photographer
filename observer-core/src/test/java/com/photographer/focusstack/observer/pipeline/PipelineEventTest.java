package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.pipeline.PipelineBlockerKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineEventTest {

    @Test
    void observe_factory_setsPhaseAndMessage() {
        PipelineEvent e = PipelineEvent.observe("frame in");
        assertEquals(PipelineEvent.Phase.OBSERVE, e.phase());
        assertEquals("frame in", e.message());
        assertNull(e.blocker());
        assertNotNull(e.timestamp());
    }

    @Test
    void decide_factory_setsPhaseAndMessage() {
        PipelineEvent e = PipelineEvent.decide("trigger");
        assertEquals(PipelineEvent.Phase.DECIDE, e.phase());
        assertNull(e.blocker());
    }

    @Test
    void act_factory_setsPhaseAndMessage() {
        PipelineEvent e = PipelineEvent.act("captured");
        assertEquals(PipelineEvent.Phase.ACT, e.phase());
        assertNull(e.blocker());
    }

    @Test
    void blocked_factory_carriesBlockerKind() {
        PipelineEvent e = PipelineEvent.blocked(PipelineBlockerKind.RATE_LIMITED, "too fast");
        assertEquals(PipelineEvent.Phase.BLOCKED, e.phase());
        assertEquals(PipelineBlockerKind.RATE_LIMITED, e.blocker());
        assertEquals("too fast", e.message());
    }
}
