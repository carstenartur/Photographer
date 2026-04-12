package com.photographer.focusstack.observer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring configuration properties for the Wildlife Observer feature.
 *
 * <p>Configured under the {@code observer} prefix in application.yml.</p>
 */
@ConfigurationProperties(prefix = "observer")
public class ObserverProperties {

    private boolean enabled = false;
    private String defaultProfile = "birdhouse";
    private String preferredAiProvider = "mock";
    private Gemini gemini = new Gemini();
    private Folder folder = new Folder();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDefaultProfile() { return defaultProfile; }
    public void setDefaultProfile(String defaultProfile) { this.defaultProfile = defaultProfile; }

    public String getPreferredAiProvider() { return preferredAiProvider; }
    public void setPreferredAiProvider(String preferredAiProvider) { this.preferredAiProvider = preferredAiProvider; }

    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }

    public Folder getFolder() { return folder; }
    public void setFolder(Folder folder) { this.folder = folder; }

    /**
     * Gemini API configuration.
     */
    public static class Gemini {
        private String apiKey;
        private String model = "gemini-2.5-flash";
        private String endpoint = "https://generativelanguage.googleapis.com/v1beta/models";
        private int timeoutSeconds = 30;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /**
     * Folder camera backend configuration for replay mode.
     */
    public static class Folder {
        private boolean enabled = false;
        private String path = "./data/observer-images";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
