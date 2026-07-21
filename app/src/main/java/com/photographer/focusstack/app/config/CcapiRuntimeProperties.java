package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Runtime-only requirements used to prove that external CCAPI secrets were bound. */
@ConfigurationProperties(prefix = "focusstack.camera.ccapi.runtime")
public final class CcapiRuntimeProperties {

    private List<String> requiredProfileIds = new ArrayList<>();

    public List<String> getRequiredProfileIds() {
        return List.copyOf(requiredProfileIds);
    }

    public void setRequiredProfileIds(List<String> requiredProfileIds) {
        this.requiredProfileIds = requiredProfileIds == null
                ? new ArrayList<>()
                : new ArrayList<>(requiredProfileIds);
    }
}
