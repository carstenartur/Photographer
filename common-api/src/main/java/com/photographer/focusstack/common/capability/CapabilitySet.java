package com.photographer.focusstack.common.capability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable per-camera capability snapshot.
 *
 * <p>For every supported {@link Capability} the set records an
 * {@link Access} mode (read / write / both) plus optional vendor metadata
 * (e.g. supported parameter ranges, mode lists).  The UI consults this set
 * to decide what to render — it never inspects the underlying transport.
 *
 * <p>Use {@link #builder()} to assemble one in a backend's
 * {@code getCapabilities()} method.
 */
public final class CapabilitySet {

    /** Read / write semantics of a capability for a given camera. */
    public enum Access { READ, WRITE, READ_WRITE }

    private final Map<Capability, Access> access;
    private final Map<Capability, Map<String, Object>> details;

    private CapabilitySet(Map<Capability, Access> access,
                          Map<Capability, Map<String, Object>> details) {
        this.access = Collections.unmodifiableMap(new EnumMap<>(access));
        this.details = Collections.unmodifiableMap(new EnumMap<>(details));
    }

    public static CapabilitySet empty() {
        return new CapabilitySet(new EnumMap<>(Capability.class), new EnumMap<>(Capability.class));
    }

    public boolean has(Capability c) {
        return access.containsKey(c);
    }

    public boolean canWrite(Capability c) {
        Access a = access.get(c);
        return a == Access.WRITE || a == Access.READ_WRITE;
    }

    public Set<Capability> capabilities() {
        return access.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(access.keySet());
    }

    public Access accessFor(Capability c) {
        return access.get(c);
    }

    /** Vendor metadata for a capability (e.g. supported ISO range), never {@code null}. */
    public Map<String, Object> details(Capability c) {
        Map<String, Object> d = details.get(c);
        return d == null ? Map.of() : d;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; not thread-safe. */
    public static final class Builder {
        private final EnumMap<Capability, Access> access = new EnumMap<>(Capability.class);
        private final EnumMap<Capability, Map<String, Object>> details = new EnumMap<>(Capability.class);

        public Builder add(Capability c, Access a) {
            access.put(c, a);
            return this;
        }

        public Builder add(Capability c) {
            return add(c, Access.READ);
        }

        public Builder addRw(Capability c) {
            return add(c, Access.READ_WRITE);
        }

        public Builder details(Capability c, Map<String, Object> d) {
            if (d != null && !d.isEmpty()) {
                details.put(c, Map.copyOf(d));
            }
            return this;
        }

        public CapabilitySet build() {
            return new CapabilitySet(access, details);
        }
    }
}
