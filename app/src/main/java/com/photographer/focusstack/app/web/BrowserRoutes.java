package com.photographer.focusstack.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Serve the React entry point when a bookmark or reload opens a client route. */
@Configuration
public class BrowserRoutes implements WebMvcConfigurer {

    public static final List<String> PATHS = List.of(
            "/cameras", "/jobs", "/jobs/{id}", "/gallery", "/hdr-timelapse", "/face-orbit",
            "/observer", "/observer/events", "/observer/events/{id}", "/observer/gallery",
            "/observer/statistics", "/observer/settings", "/astro", "/plugins", "/integrations/fresnel");

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : PATHS) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
