package com.photographer.focusstack.app.openapi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiAnnotationCoverageTest {

    @Test
    void everyRestEndpointHasOperationAndControllerTag() throws ClassNotFoundException {
        List<Class<?>> controllers = findRestControllers("com.photographer.focusstack");
        List<String> missingTag = new ArrayList<>();
        List<String> missingOperation = new ArrayList<>();

        for (Class<?> controller : controllers) {
            if (!controller.isAnnotationPresent(Tag.class)) {
                missingTag.add(controller.getName());
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpointMethod(method)) {
                    continue;
                }
                if (!method.isAnnotationPresent(Operation.class)) {
                    missingOperation.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertTrue(missingTag.isEmpty(), "Missing @Tag on controllers: " + missingTag);
        assertTrue(missingOperation.isEmpty(), "Missing @Operation on endpoint methods: " + missingOperation);
    }

    private static List<Class<?>> findRestControllers(String basePackage) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents(basePackage)) {
            controllers.add(Class.forName(bean.getBeanClassName()));
        }
        return controllers;
    }

    private static boolean isEndpointMethod(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
                || method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(PatchMapping.class);
    }
}
