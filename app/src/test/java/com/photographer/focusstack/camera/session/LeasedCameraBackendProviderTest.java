package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.session.CameraBackendSession;
import com.photographer.focusstack.session.CameraSessionPurpose;
import com.photographer.focusstack.session.SessionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeasedCameraBackendProviderTest {

    @Test
    void equivalentProfilesConflictBeforeSecondBackendResolution() {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackend backend = mock(CameraBackend.class);
        CameraProfile first = profile("first", "Camera.Example");
        CameraProfile alias = profile("alias", "http://camera.example:8080/ccapi");
        when(profileService.getProfile("first")).thenReturn(first);
        when(profileService.getProfile("alias")).thenReturn(alias);
        when(backendFactory.getBackend(first)).thenReturn(backend);
        when(backendFactory.getBackend(alias)).thenReturn(backend);
        InMemoryCameraSessionLeaseManager manager = manager();
        LeasedCameraBackendProvider provider = provider(
                backendFactory, profileService, manager);

        try (CameraBackendSession owned = provider.openSession(
                "first", CameraSessionPurpose.HDR_TIMELAPSE, "hdr-1")) {
            assertThat(owned.backend()).isSameAs(backend);
            assertThatThrownBy(() -> provider.openSession(
                    "alias", CameraSessionPurpose.FACE_ORBIT, "face-1"))
                    .isInstanceOf(CameraSessionConflictException.class);
            verify(backendFactory, never()).getBackend(alias);
            assertThat(manager.metrics().activeLeases()).isEqualTo(1);
        }

        try (CameraBackendSession retry = provider.openSession(
                "alias", CameraSessionPurpose.FACE_ORBIT, "face-2")) {
            assertThat(retry.backend()).isSameAs(backend);
        }
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void sameLogicalWorkflowReacquiresReentrantlyAcrossProfileAliases() {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackend backend = mock(CameraBackend.class);
        CameraProfile first = profile("first", "camera.example");
        CameraProfile alias = profile("alias", "HTTP://CAMERA.EXAMPLE:8080/other");
        when(profileService.getProfile("first")).thenReturn(first);
        when(profileService.getProfile("alias")).thenReturn(alias);
        when(backendFactory.getBackend(first)).thenReturn(backend);
        when(backendFactory.getBackend(alias)).thenReturn(backend);
        InMemoryCameraSessionLeaseManager manager = manager();
        LeasedCameraBackendProvider provider = provider(
                backendFactory, profileService, manager);

        CameraBackendSession firstHandle = provider.openSession(
                "first", CameraSessionPurpose.HDR_TIMELAPSE, "same-hdr-session");
        CameraBackendSession secondHandle = provider.openSession(
                "alias", CameraSessionPurpose.HDR_TIMELAPSE, "same-hdr-session");
        try {
            assertThat(firstHandle.backend()).isSameAs(backend);
            assertThat(secondHandle.backend()).isSameAs(backend);
            assertThat(manager.metrics().activeLeases()).isEqualTo(1);

            firstHandle.close();
            assertThat(manager.metrics().activeLeases()).isEqualTo(1);
            assertThatThrownBy(() -> provider.openSession(
                    "alias", CameraSessionPurpose.HDR_TIMELAPSE, "different-session"))
                    .isInstanceOf(CameraSessionConflictException.class);
        } finally {
            firstHandle.close();
            secondHandle.close();
        }
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void backendInitializationFailureReleasesLeaseAndSanitizesFailure() {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraProfile profile = profile(
                "private-profile-id", "http://private-camera.example:8080/ccapi");
        when(profileService.getProfile("private-profile-id")).thenReturn(profile);
        when(backendFactory.getBackend(profile))
                .thenThrow(new IllegalStateException("private-camera.example initialization failed"));
        InMemoryCameraSessionLeaseManager manager = manager();
        LeasedCameraBackendProvider provider = provider(
                backendFactory, profileService, manager);

        assertThatThrownBy(() -> provider.openSession(
                "private-profile-id", CameraSessionPurpose.HDR_TIMELAPSE, "hdr-secret"))
                .isInstanceOf(SessionException.class)
                .hasMessage("Cannot resolve camera backend")
                .hasNoCause();

        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void unmanagedBackendDoesNotCreateNetworkLease() {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackend backend = mock(CameraBackend.class);
        CameraProfile mockProfile = new CameraProfile(
                "mock", "Mock", "mock", null, 10, 30, 0.1);
        when(profileService.getProfile("mock")).thenReturn(mockProfile);
        when(backendFactory.getBackend(mockProfile)).thenReturn(backend);
        InMemoryCameraSessionLeaseManager manager = manager();
        LeasedCameraBackendProvider provider = provider(
                backendFactory, profileService, manager);

        try (CameraBackendSession session = provider.openSession(
                "mock", CameraSessionPurpose.HDR_TIMELAPSE, "hdr-mock")) {
            assertThat(session.backend()).isSameAs(backend);
            assertThat(manager.metrics().activeLeases()).isZero();
        }
    }

    private static LeasedCameraBackendProvider provider(
            CameraBackendFactory backendFactory,
            CameraProfileService profileService,
            InMemoryCameraSessionLeaseManager manager) {
        return new LeasedCameraBackendProvider(
                backendFactory,
                profileService,
                manager,
                new DefaultCameraLeaseIdentityProvider(),
                Map.of(
                        CameraSessionPurpose.HDR_TIMELAPSE, Duration.ofHours(6),
                        CameraSessionPurpose.FACE_ORBIT, Duration.ofHours(2),
                        CameraSessionPurpose.ASTRO, Duration.ofHours(6)));
    }

    private static InMemoryCameraSessionLeaseManager manager() {
        return new InMemoryCameraSessionLeaseManager(Duration.ofHours(6), 1024);
    }

    private static CameraProfile profile(String id, String host) {
        return new CameraProfile(id, "Camera " + id, "canon-ccapi", host,
                10, 30, 0.1);
    }
}
