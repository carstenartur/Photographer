package com.photographer.focusstack.common.capability;

/**
 * Resolves the {@link CapabilitySet} for a given camera/transport.
 *
 * <p>Implementations typically inspect a freshly opened
 * {@link com.photographer.focusstack.common.transport.CameraConnection},
 * probe the underlying API surface (e.g. CCAPI endpoint catalogue or PTP
 * device-info opcodes), and translate the result into a vendor-neutral
 * {@link CapabilitySet}.
 */
@FunctionalInterface
public interface CapabilityResolver {

    CapabilitySet resolve(com.photographer.focusstack.common.transport.CameraConnection connection);
}
