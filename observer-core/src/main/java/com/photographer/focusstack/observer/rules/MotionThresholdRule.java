package com.photographer.focusstack.observer.rules;

/**
 * Rule: only trigger when motion exceeds a configured threshold.
 */
public class MotionThresholdRule implements ObserverRule {

    private final double threshold;

    public MotionThresholdRule(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return "MotionThresholdRule(threshold=" + threshold + ")";
    }

    @Override
    public boolean shouldTrigger(TriggerContext context) {
        return context.motionScore() >= threshold;
    }

    @Override
    public int priority() {
        return 70;
    }
}
