package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class CameraToneMemoryEstimateTest {
    @Test
    void firstUseEstimateCoversEveryActualToneArray() throws Exception {
        // Inspect the owning arrays, so a new or enlarged table cannot escape the estimate.
        Class<?> tables = Class.forName(CameraToneCurve.class.getName() + "$Tables");
        long payload = 0;
        for (var field : tables.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
            int elementBytes = switch (field.getType().componentType().getName()) {
                case "double", "long" -> Long.BYTES;
                case "int", "float" -> Integer.BYTES;
                case "short", "char" -> Short.BYTES;
                case "byte", "boolean" -> Byte.BYTES;
                default -> throw new AssertionError("Unaccounted tone storage: " + field);
            };
            field.setAccessible(true);
            payload += (long) Array.getLength(field.get(null)) * elementBytes;
        }
        assertTrue(payload > 0, "The owning tone tables must be inspected");
        assertEquals(payload, CameraToneCurve.estimateTableBytes());
    }

    @Test
    void estimateAndCoverageQueriesDoNotLoadToneTablesAtStartup() throws Exception {
        String owner = CameraToneCurve.class.getName();
        URL location = CameraToneCurve.class.getProtectionDomain().getCodeSource().getLocation();
        // A fresh loader keeps this independent of test order and already initialized tables.
        try (var loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(owner + "$Tables")) {
                    throw new AssertionError("Estimation and coverage queries must not initialize tone storage");
                }
                return super.findClass(name);
            }
        }) {
            Class<?> isolated = Class.forName(owner, true, loader);
            assertNotSame(CameraToneCurve.class, isolated);
            assertEquals(CameraToneCurve.estimateTableBytes(),
                    (long) isolated.getMethod("estimateTableBytes").invoke(null));
            assertEquals(Boolean.TRUE, isolated.getMethod("supports", String.class, int.class)
                    .invoke(null, "Canon PowerShot G5 X Mark II", 0x85));
            assertEquals(Boolean.FALSE, isolated.getMethod("supports", String.class, int.class)
                    .invoke(null, "Unprofiled camera", 0x85));
        }
    }
}
