package com.photographer.focusstack;

import com.photographer.focusstack.app.config.CameraConfig;
import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.app.config.CcapiRuntimeProperties;
import com.photographer.focusstack.app.config.PluginProperties;
import com.photographer.focusstack.app.config.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        StorageConfig.class,
        CameraConfig.class,
        CcapiAuthenticationProperties.class,
        CcapiRuntimeProperties.class,
        PluginProperties.class
})
public class FocusStackApplication {

    public static void main(String[] args) {
        SpringApplication.run(FocusStackApplication.class, args);
    }
}
