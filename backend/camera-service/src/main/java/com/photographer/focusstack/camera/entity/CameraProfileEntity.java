package com.photographer.focusstack.camera.entity;

import com.photographer.focusstack.common.model.CameraProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_profiles")
public class CameraProfileEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String backendType;

    private String host;
    private int defaultStepSize;
    private int maxFrames;
    private double movementThreshold;

    public CameraProfileEntity() {
    }

    public CameraProfileEntity(String id, String name, String backendType, String host,
                                int defaultStepSize, int maxFrames, double movementThreshold) {
        this.id = id;
        this.name = name;
        this.backendType = backendType;
        this.host = host;
        this.defaultStepSize = defaultStepSize;
        this.maxFrames = maxFrames;
        this.movementThreshold = movementThreshold;
    }

    public static CameraProfileEntity fromRecord(CameraProfile profile) {
        return new CameraProfileEntity(
                profile.id(), profile.name(), profile.backendType(), profile.host(),
                profile.defaultStepSize(), profile.maxFrames(), profile.movementThreshold()
        );
    }

    public CameraProfile toRecord() {
        return new CameraProfile(id, name, backendType, host, defaultStepSize, maxFrames, movementThreshold);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBackendType() {
        return backendType;
    }

    public void setBackendType(String backendType) {
        this.backendType = backendType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getDefaultStepSize() {
        return defaultStepSize;
    }

    public void setDefaultStepSize(int defaultStepSize) {
        this.defaultStepSize = defaultStepSize;
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    public void setMaxFrames(int maxFrames) {
        this.maxFrames = maxFrames;
    }

    public double getMovementThreshold() {
        return movementThreshold;
    }

    public void setMovementThreshold(double movementThreshold) {
        this.movementThreshold = movementThreshold;
    }
}
