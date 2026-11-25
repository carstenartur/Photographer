package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;

/** Runs the production processing regressions against a frozen packaged JAR. */
public final class ToneMemoryChecks {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (Class<?> type : new Class<?>[]{CameraToneMemoryEstimateTest.class,
                OpticalCorrectionRegressionTest.class, CameraToneCurveTest.class,
                Srgb16EncoderTest.class, Rgb16MemoryEstimateTest.class}) {
            Object instance = type.getDeclaredConstructor().newInstance();
            var methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(java.lang.reflect.Method::getName));
            for (var method : methods) {
                if (!method.isAnnotationPresent(Test.class)) continue;
                try {
                    method.invoke(instance);
                } catch (InvocationTargetException failure) {
                    throw new AssertionError(type.getSimpleName() + "." + method.getName(), failure.getCause());
                }
                checks++;
            }
        }
        System.out.println("PASS processing checks=" + checks + ", java=" + System.getProperty("java.runtime.version"));
    }
}
