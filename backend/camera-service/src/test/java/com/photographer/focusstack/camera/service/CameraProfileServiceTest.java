package com.photographer.focusstack.camera.service;

import com.canon.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.exception.CameraNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CameraProfileServiceTest {

    private CameraProfileService service;

    @BeforeEach
    void setUp() {
        service = new CameraProfileService();
    }

    @Test
    void createAndGetProfile() {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Camera", "mock", "localhost", 10, 30, 0.1);
        service.createProfile(profile);

        CameraProfile retrieved = service.getProfile("cam-1");
        assertEquals("cam-1", retrieved.id());
        assertEquals("Test Camera", retrieved.name());
    }

    @Test
    void getAllProfiles_returnsAll() {
        service.createProfile(new CameraProfile(
                "cam-1", "Camera 1", "mock", "localhost", 10, 30, 0.1));
        service.createProfile(new CameraProfile(
                "cam-2", "Camera 2", "mock", "localhost", 10, 30, 0.1));

        List<CameraProfile> profiles = service.getAllProfiles();
        assertEquals(2, profiles.size());
    }

    @Test
    void updateProfile_updatesExisting() {
        service.createProfile(new CameraProfile(
                "cam-1", "Old Name", "mock", "localhost", 10, 30, 0.1));

        CameraProfile updated = service.updateProfile("cam-1",
                new CameraProfile("cam-1", "New Name", "ccapi", "192.168.1.2", 20, 60, 0.2));

        assertEquals("New Name", updated.name());
        assertEquals("cam-1", updated.id());
    }

    @Test
    void deleteProfile_removesProfile() {
        service.createProfile(new CameraProfile(
                "cam-1", "Camera 1", "mock", "localhost", 10, 30, 0.1));
        service.deleteProfile("cam-1");

        assertThrows(CameraNotFoundException.class, () -> service.getProfile("cam-1"));
    }

    @Test
    void getProfile_notFound_throwsException() {
        assertThrows(CameraNotFoundException.class, () -> service.getProfile("nonexistent"));
    }

    @Test
    void updateProfile_notFound_throwsException() {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test", "mock", "localhost", 10, 30, 0.1);
        assertThrows(CameraNotFoundException.class,
                () -> service.updateProfile("nonexistent", profile));
    }

    @Test
    void deleteProfile_notFound_throwsException() {
        assertThrows(CameraNotFoundException.class,
                () -> service.deleteProfile("nonexistent"));
    }

    @Test
    void createProfile_returnsCreatedProfile() {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Camera", "mock", "localhost", 10, 30, 0.1);
        CameraProfile created = service.createProfile(profile);

        assertNotNull(created);
        assertEquals("cam-1", created.id());
        assertEquals("Test Camera", created.name());
    }
}
