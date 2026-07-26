package com.photographer.focusstack.app.api;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Separates the canonical REST contract from temporary compatibility aliases. */
@Configuration
public class ApiOpenApiGroups {

    @Bean
    GroupedOpenApi restV1OpenApi() {
        return GroupedOpenApi.builder()
                .group("rest-v1")
                .pathsToMatch(ApiPaths.V1 + "/**")
                .build();
    }

    @Bean
    GroupedOpenApi legacyCompatibilityOpenApi() {
        return GroupedOpenApi.builder()
                .group("legacy-compat")
                .pathsToMatch(ApiPaths.LEGACY + "/**")
                .pathsToExclude(ApiPaths.V1 + "/**")
                .addOpenApiCustomizer(openApi -> {
                    if (openApi.getPaths() != null) {
                        openApi.getPaths().values().stream()
                                .flatMap(path -> path.readOperations().stream())
                                .forEach(operation -> operation.setDeprecated(true));
                    }
                })
                .build();
    }
}
