package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.repository.CameraProfileRepository;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.camera.exception.CameraNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraProfileServiceTest {

    @Mock
    private CameraProfileRepository repository;

    private CameraProfileService service;

    @BeforeEach
    void setUp() {
        service = new CameraProfileService(repository);
    }

    @Test
    void createAndGetProfile() {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Camera", "mock", "localhost", 10, 30, 0.1);
        CameraProfileEntity entity = CameraProfileEntity.fromRecord(profile);

        when(repository.save(any(CameraProfileEntity.class))).thenReturn(entity);
        when(repository.findById("cam-1")).thenReturn(Optional.of(entity));

        service.createProfile(profile);

        CameraProfile retrieved = service.getProfile("cam-1");
        assertEquals("cam-1", retrieved.id());
        assertEquals("Test Camera", retrieved.name());
    }

    @Test
    void getAllProfiles_returnsAll() {
        CameraProfile p1 = new CameraProfile("cam-1", "Camera 1", "mock", "localhost", 10, 30, 0.1);
        CameraProfile p2 = new CameraProfile("cam-2", "Camera 2", "mock", "localhost", 10, 30, 0.1);

        when(repository.findAll()).thenReturn(List.of(
                CameraProfileEntity.fromRecord(p1),
                CameraProfileEntity.fromRecord(p2)
        ));

        List<CameraProfile> profiles = service.getAllProfiles();
        assertEquals(2, profiles.size());
    }

    @Test
    void updateProfile_updatesExisting() {
        CameraProfile updated = new CameraProfile("cam-1", "New Name", "ccapi", "192.168.1.2", 20, 60, 0.2);
        CameraProfileEntity updatedEntity = CameraProfileEntity.fromRecord(updated);

        when(repository.existsById("cam-1")).thenReturn(true);
        when(repository.save(any(CameraProfileEntity.class))).thenReturn(updatedEntity);

        CameraProfile result = service.updateProfile("cam-1", updated);
        assertEquals("New Name", result.name());
        assertEquals("cam-1", result.id());
    }

    @Test
    void deleteProfile_removesProfile() {
        when(repository.existsById("cam-1")).thenReturn(true);
        service.deleteProfile("cam-1");
        verify(repository).deleteById("cam-1");
    }

    @Test
    void getProfile_notFound_throwsException() {
        when(repository.findById("nonexistent")).thenReturn(Optional.empty());
        assertThrows(CameraNotFoundException.class, () -> service.getProfile("nonexistent"));
    }

    @Test
    void updateProfile_notFound_throwsException() {
        when(repository.existsById("nonexistent")).thenReturn(false);
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test", "mock", "localhost", 10, 30, 0.1);
        assertThrows(CameraNotFoundException.class,
                () -> service.updateProfile("nonexistent", profile));
    }

    @Test
    void deleteProfile_notFound_throwsException() {
        when(repository.existsById("nonexistent")).thenReturn(false);
        assertThrows(CameraNotFoundException.class,
                () -> service.deleteProfile("nonexistent"));
    }

    @Test
    void createProfile_returnsCreatedProfile() {
        CameraProfile profile = new CameraProfile(
                "cam-1", "Test Camera", "mock", "localhost", 10, 30, 0.1);
        CameraProfileEntity entity = CameraProfileEntity.fromRecord(profile);

        when(repository.save(any(CameraProfileEntity.class))).thenReturn(entity);

        CameraProfile created = service.createProfile(profile);
        assertNotNull(created);
        assertEquals("cam-1", created.id());
        assertEquals("Test Camera", created.name());
    }
}
