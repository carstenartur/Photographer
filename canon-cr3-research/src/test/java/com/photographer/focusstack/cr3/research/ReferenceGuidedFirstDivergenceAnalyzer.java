package com.photographer.focusstack.cr3.research;

import java.util.Objects;
import java.util.Optional;

/**
 * Compares the unchanged test-scope lossless CRX decoder trace with a registered
 * full-sensor RAW-linear reference mosaic.
 *
 * <p>The reference values are observations only. They are not used to alter the
 * bitstream parser, predictor, residual mapping or K adaptation. The analyzer
 * stops retaining trace data after the first reconstructed-sample mismatch and
 * reports the independently derived reference context at that position.</p>
 */
final class ReferenceGuidedFirstDivergenceAnalyzer {

    enum Classification {
        PREDICTOR_CONTEXT,
        SYMBOL_OR_RESIDUAL_INTERPRETATION,
        CLAMPING_OR_RECONSTRUCTION,
        STREAM_EXHAUSTED,
        NO_DIVERGENCE
    }

    /** Maps one tile-local CRX plane onto the full-sensor Bayer mosaic. */
    record PlaneMapping(
            int imageWidth,
            int imageHeight,
            int tileSequence,
            int planeIndex,
            int tilePlaneXOffset,
            int planeWidth,
            int planeHeight) {

        PlaneMapping {
            if (imageWidth <= 0 || imageHeight <= 0) {
                throw new IllegalArgumentException("Invalid image geometry");
            }
            if (tileSequence < 0 || planeIndex < 0 || planeIndex > 3) {
                throw new IllegalArgumentException("Invalid tile/plane identity");
            }
            if (tilePlaneXOffset < 0 || planeWidth <= 0 || planeHeight <= 0) {
                throw new IllegalArgumentException("Invalid plane geometry");
            }
            int maximumX = Math.addExact(
                    Math.multiplyExact(tilePlaneXOffset + planeWidth - 1, 2),
                    planeIndex & 1);
            int maximumY = Math.addExact(
                    Math.multiplyExact(planeHeight - 1, 2),
                    planeIndex >>> 1);
            if (maximumX >= imageWidth || maximumY >= imageHeight) {
                throw new IllegalArgumentException(
                        "Plane mapping exceeds reference geometry: max="
                                + maximumX + "x" + maximumY + " image="
                                + imageWidth + "x" + imageHeight);
            }
        }

        int fullSensorX(int localX) {
            requireLocal(localX, 0);
            return (tilePlaneXOffset + localX) * 2 + (planeIndex & 1);
        }

        int fullSensorY(int localY) {
            requireLocal(0, localY);
            return localY * 2 + (planeIndex >>> 1);
        }

        int referenceIndex(int localX, int localY) {
            requireLocal(localX, localY);
            return fullSensorY(localY) * imageWidth + fullSensorX(localX);
        }

        private void requireLocal(int localX, int localY) {
            if (localX < 0 || localX >= planeWidth || localY < 0 || localY >= planeHeight) {
                throw new IndexOutOfBoundsException(
                        "Local plane coordinate " + localX + "," + localY);
            }
        }
    }

    /** Correct predictor inputs and residual derived solely from reference pixels. */
    record ReferenceContext(
            Integer west,
            Integer north,
            Integer northWest,
            int predictor,
            int referenceValue,
            int expectedSignedResidual,
            int expectedUnsignedSymbol) {
    }

    /** First symbol whose reconstructed sample differs from the reference. */
    record Divergence(
            Classification classification,
            int localX,
            int localY,
            int fullSensorX,
            int fullSensorY,
            CrxNormativeLosslessCandidate.SymbolTrace trace,
            ReferenceContext reference) {

        Divergence {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(trace, "trace");
            Objects.requireNonNull(reference, "reference");
            if (classification == Classification.NO_DIVERGENCE
                    || classification == Classification.STREAM_EXHAUSTED) {
                throw new IllegalArgumentException("Not a sample-divergence classification");
            }
        }

        int signedError() {
            return trace.reconstructed() - reference.referenceValue();
        }
    }

    record AnalysisResult(
            PlaneMapping mapping,
            Classification classification,
            long capacity,
            int symbolsDecoded,
            boolean streamExhausted,
            int consumedBytes,
            Optional<Divergence> firstDivergence) {

        AnalysisResult {
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(firstDivergence, "firstDivergence");
            if (capacity <= 0 || symbolsDecoded < 0 || consumedBytes < 0) {
                throw new IllegalArgumentException("Invalid stream result dimensions");
            }
            if (firstDivergence.isPresent() != isSampleDivergence(classification)) {
                throw new IllegalArgumentException("Classification/divergence mismatch");
            }
        }

        double completion() {
            return (double) symbolsDecoded / capacity;
        }
    }

    private ReferenceGuidedFirstDivergenceAnalyzer() {
    }

    static AnalysisResult analyzePlane(
            byte[] payload,
            PlaneMapping mapping,
            int bitDepth,
            int[] referenceSamples,
            CrxNormativeLosslessCandidate.Options baselineOptions) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(referenceSamples, "referenceSamples");
        Objects.requireNonNull(baselineOptions, "baselineOptions");
        if (bitDepth < 1 || bitDepth > 30) {
            throw new IllegalArgumentException("Unsupported bit depth " + bitDepth);
        }
        int expectedSamples = Math.multiplyExact(mapping.imageWidth(), mapping.imageHeight());
        if (referenceSamples.length != expectedSamples) {
            throw new IllegalArgumentException(
                    "Reference sample length " + referenceSamples.length
                            + " does not match " + expectedSamples);
        }

        FirstMismatchCollector collector = new FirstMismatchCollector(
                mapping, referenceSamples, baselineOptions.firstPixelSeed, bitDepth);
        CrxNormativeLosslessCandidate.Options options = baselineOptions.copy();
        options.traceSink = collector;

        CrxNormativeLosslessCandidate.Result decoded =
                CrxNormativeLosslessCandidate.decodePlane(
                        payload,
                        mapping.planeWidth(),
                        mapping.planeHeight(),
                        bitDepth,
                        options);

        Classification classification;
        if (collector.divergence != null) {
            classification = collector.divergence.classification();
        } else if (decoded.streamExhausted) {
            classification = Classification.STREAM_EXHAUSTED;
        } else {
            classification = Classification.NO_DIVERGENCE;
        }

        return new AnalysisResult(
                mapping,
                classification,
                Math.multiplyExact((long) mapping.planeWidth(), mapping.planeHeight()),
                decoded.symbolsDecoded,
                decoded.streamExhausted,
                decoded.consumedBytes,
                Optional.ofNullable(collector.divergence));
    }

    static ReferenceContext referenceContext(
            PlaneMapping mapping,
            int[] referenceSamples,
            int localX,
            int localY,
            int firstPixelSeed) {
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(referenceSamples, "referenceSamples");
        if (referenceSamples.length
                != Math.multiplyExact(mapping.imageWidth(), mapping.imageHeight())) {
            throw new IllegalArgumentException("Reference geometry mismatch");
        }

        Integer west = localX > 0
                ? sample(mapping, referenceSamples, localX - 1, localY) : null;
        Integer north = localY > 0
                ? sample(mapping, referenceSamples, localX, localY - 1) : null;
        Integer northWest = localX > 0 && localY > 0
                ? sample(mapping, referenceSamples, localX - 1, localY - 1) : null;

        int predictor;
        if (localX == 0 && localY == 0) {
            predictor = firstPixelSeed;
        } else if (localY == 0) {
            predictor = west;
        } else if (localX == 0) {
            predictor = north;
        } else {
            predictor = medPredict(west, north, northWest);
        }

        int referenceValue = sample(mapping, referenceSamples, localX, localY);
        int residual = referenceValue - predictor;
        return new ReferenceContext(
                west,
                north,
                northWest,
                predictor,
                referenceValue,
                residual,
                bit0SignUnsigned(residual));
    }

    static Classification classify(
            CrxNormativeLosslessCandidate.SymbolTrace trace,
            ReferenceContext reference,
            int bitDepth) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(reference, "reference");
        if (trace.predictor() != reference.predictor()) {
            return Classification.PREDICTOR_CONTEXT;
        }
        if (trace.signedResidual() != reference.expectedSignedResidual()
                || trace.unsignedSymbol() != reference.expectedUnsignedSymbol()) {
            return Classification.SYMBOL_OR_RESIDUAL_INTERPRETATION;
        }
        int maximum = (1 << bitDepth) - 1;
        int expectedReconstruction = clamp(
                reference.predictor() + reference.expectedSignedResidual(), maximum);
        if (trace.reconstructed() != expectedReconstruction
                || expectedReconstruction != reference.referenceValue()) {
            return Classification.CLAMPING_OR_RECONSTRUCTION;
        }
        throw new IllegalArgumentException("Trace does not diverge from reference context");
    }

    static int medPredict(int west, int north, int northWest) {
        if (northWest >= Math.max(west, north)) {
            return Math.min(west, north);
        }
        if (northWest <= Math.min(west, north)) {
            return Math.max(west, north);
        }
        return west + north - northWest;
    }

    static int bit0SignUnsigned(int signedResidual) {
        return signedResidual >= 0
                ? Math.multiplyExact(signedResidual, 2)
                : Math.addExact(Math.multiplyExact(-signedResidual, 2), 1);
    }

    private static int sample(
            PlaneMapping mapping,
            int[] referenceSamples,
            int localX,
            int localY) {
        return referenceSamples[mapping.referenceIndex(localX, localY)];
    }

    private static boolean isSampleDivergence(Classification classification) {
        return classification == Classification.PREDICTOR_CONTEXT
                || classification == Classification.SYMBOL_OR_RESIDUAL_INTERPRETATION
                || classification == Classification.CLAMPING_OR_RECONSTRUCTION;
    }

    private static int clamp(int value, int maximum) {
        return value < 0 ? 0 : Math.min(value, maximum);
    }

    private static final class FirstMismatchCollector
            implements CrxNormativeLosslessCandidate.TraceSink {
        private final PlaneMapping mapping;
        private final int[] referenceSamples;
        private final int firstPixelSeed;
        private final int bitDepth;
        private Divergence divergence;

        private FirstMismatchCollector(
                PlaneMapping mapping,
                int[] referenceSamples,
                int firstPixelSeed,
                int bitDepth) {
            this.mapping = mapping;
            this.referenceSamples = referenceSamples;
            this.firstPixelSeed = firstPixelSeed;
            this.bitDepth = bitDepth;
        }

        @Override
        public boolean onSymbol(CrxNormativeLosslessCandidate.SymbolTrace trace) {
            ReferenceContext reference = referenceContext(
                    mapping, referenceSamples, trace.x(), trace.y(), firstPixelSeed);
            if (trace.reconstructed() == reference.referenceValue()) {
                return true;
            }
            Classification classification = classify(trace, reference, bitDepth);
            divergence = new Divergence(
                    classification,
                    trace.x(),
                    trace.y(),
                    mapping.fullSensorX(trace.x()),
                    mapping.fullSensorY(trace.y()),
                    trace,
                    reference);
            return false;
        }
    }
}
