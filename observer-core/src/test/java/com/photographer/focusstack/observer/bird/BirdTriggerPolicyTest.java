package com.photographer.focusstack.observer.bird;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BirdTriggerPolicyTest {
    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void triggersImmediatelyAndRepeatsOnlyAfterConfiguredInterval() {
        BirdTriggerPolicy policy = new BirdTriggerPolicy(BirdTriggerSettings.defaults());
        assertTrue(policy.shouldCapture(START, true));
        policy.captureCommitted(START);
        assertFalse(policy.shouldCapture(START.plusMillis(500), true));
        assertFalse(policy.shouldCapture(START.plusSeconds(4), true));
        assertTrue(policy.shouldCapture(START.plusSeconds(5), true));
    }

    @Test
    void zeroRepeatMeansOncePerVisitUntilContinuouslyObservedAbsence() {
        BirdTriggerPolicy policy = new BirdTriggerPolicy(settings(0));
        assertTrue(policy.shouldCapture(START, true));
        policy.captureCommitted(START);
        assertFalse(policy.shouldCapture(START.plusSeconds(20), true));
        for (int second = 21; second <= 24; second++) {
            assertFalse(policy.shouldCapture(START.plusSeconds(second), false));
        }
        assertTrue(policy.shouldCapture(START.plusSeconds(25), true));
    }

    @Test
    void errorsDuplicatesAndBlackoutsDoNotCountAsObservedAbsence() {
        BirdTriggerPolicy policy = new BirdTriggerPolicy(settings(0));
        policy.captureCommitted(START);
        policy.shouldCapture(START.plusSeconds(1), false);
        policy.shouldCapture(START.plusSeconds(2), false);
        policy.gap();
        policy.shouldCapture(START.plusSeconds(3), false);
        policy.shouldCapture(START.plusSeconds(4), false);
        assertFalse(policy.shouldCapture(START.plusSeconds(5), true));
        policy.shouldCapture(START.plusSeconds(6), false);
        policy.shouldCapture(START.plusSeconds(30), false);
        assertFalse(policy.shouldCapture(START.plusSeconds(31), true));
    }

    @Test
    void repeatedOrBackwardsFrameTimestampsCannotTriggerOrAdvanceAbsence() {
        BirdTriggerPolicy policy = new BirdTriggerPolicy(settings(0));
        assertTrue(policy.shouldCapture(START, true));
        policy.captureCommitted(START);
        assertFalse(policy.shouldCapture(START, true));
        assertFalse(policy.shouldCapture(START.minusSeconds(1), true));
        assertFalse(policy.shouldCapture(START.plusSeconds(1), true));
    }

    @Test
    void rejectsNonFiniteAndOutOfFrameSettings() {
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                new RegionOfInterest(.9, 0, .2, 1, "outside"), .5, .01, 500, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                new RegionOfInterest(Double.NaN, 0, .2, 1, "nan"), .5, .01, 500, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                RegionOfInterest.fullFrame(), Double.NaN, .01, 500, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                RegionOfInterest.fullFrame(), .5, Double.POSITIVE_INFINITY, 500, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                RegionOfInterest.fullFrame(), .5, .01, 0, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new BirdTriggerSettings(
                RegionOfInterest.fullFrame(), .5, .01, 500, -1, 3));
    }

    private static BirdTriggerSettings settings(int repeatSeconds) {
        return new BirdTriggerSettings(RegionOfInterest.fullFrame(), .5, .0025, 500, repeatSeconds, 3);
    }
}
