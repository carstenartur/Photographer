package com.photographer.focusstack.cr3.processing;

/** Runs the production boundary and payload tests against a packaged runtime JAR. */
public final class Srgb16Checks {
    public static void main(String[] args) throws Exception {
        var test = new Srgb16EncoderTest();
        test.preservesClippingSpecialValuesAndTheTransferJoin();
        test.preservesEveryNativeFourteenAndSixteenBitInputAndContinuousValues();
        test.preservesAdjacentDoublesAroundEveryOutputRoundingBoundary();
        test.preservesBothSidesOfTheGuardAroundEveryReferenceTransition();
        test.preservesBothSidesOfEveryDirectSeedBucketEdge();
        test.preservesCodesInExceptionBuckets();
        new Rgb16MemoryEstimateTest().firstUseEstimateCoversEveryActualEncoderArray();
        System.out.println("PASS: six production encoder tests and the actual table-payload contract on Java "
                + System.getProperty("java.version"));
        System.out.println("Encoder source: "
                + Srgb16Encoder.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
