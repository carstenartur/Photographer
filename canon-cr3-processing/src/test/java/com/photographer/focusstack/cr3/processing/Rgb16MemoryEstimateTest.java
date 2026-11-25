package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Rgb16MemoryEstimateTest {
    @Test
    void firstUseEstimateCoversEveryActualEncoderArray() throws Exception {
        // Inspect the owning tables, so adding or resizing an array without
        // updating the exported estimate fails this memory-contract test.
        Class<?> tables = Class.forName(Srgb16Encoder.class.getName() + "$Tables");
        long payload = 0;
        for (var field : tables.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
            int elementBytes = switch (field.getType().componentType().getName()) {
                case "double", "long" -> Long.BYTES;
                case "int", "float" -> Integer.BYTES;
                case "short", "char" -> Short.BYTES;
                case "byte", "boolean" -> Byte.BYTES;
                default -> throw new AssertionError("Unaccounted table storage: " + field);
            };
            field.setAccessible(true);
            payload += (long) Array.getLength(field.get(null)) * elementBytes;
        }
        assertEquals(payload, BayerDemosaicer.estimateSrgb16TableBytes());
    }
}
