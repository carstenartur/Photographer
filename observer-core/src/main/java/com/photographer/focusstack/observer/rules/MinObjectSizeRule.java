package com.photographer.focusstack.observer.rules;

/**
 * Rule: only trigger when the detected object occupies at least a minimum
 * fraction of the region of interest.
 */
public class MinObjectSizeRule implements ObserverRule {

    private final double minSize;

    public MinObjectSizeRule(double minSize) {
        this.minSize = minSize;
    }

    @Override
    public String name() {
        return "MinObjectSizeRule(min=" + minSize + ")";
    }

    @Override
    public boolean shouldTrigger(TriggerContext context) {
        return context.objectSize() >= minSize;
    }

    @Override
    public int priority() {
        return 80;
    }
}
