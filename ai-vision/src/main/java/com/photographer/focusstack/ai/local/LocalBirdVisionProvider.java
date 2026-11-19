package com.photographer.focusstack.ai.local;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.observer.events.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Local CPU inference for the generic COCO bird class using YOLOX-Nano. */
public final class LocalBirdVisionProvider implements VisionProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalBirdVisionProvider.class);
    private static final String NAME = "local-bird";
    public static final String MODEL_NAME = "yolox-nano-0.1.1rc0";

    private static final String CLOSED_MESSAGE = "The local bird provider is closed";
    private static final String MODEL_RESOURCE = "yolox_nano.onnx";
    private static final String INPUT_NAME = "images";
    private static final String OUTPUT_NAME = "output";
    private static final int MAX_MODEL_BYTES = 64 * 1024 * 1024;
    private static final long[] INPUT_SHAPE = {1, 3, YoloXInput.SIZE, YoloXInput.SIZE};
    private static final long[] OUTPUT_SHAPE = {1, YoloXOutput.BOX_COUNT, YoloXOutput.BOX_VALUES};
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            true, false, true, false, YoloXInput.MAX_ENCODED_BYTES, List.of("jpeg", "png"));

    private final Path modelPath;
    private OrtEnvironment environment;
    private OrtSession session;
    private FloatBuffer inputBuffer;
    private InitializationFailure initializationFailure;
    private boolean closed;

    public LocalBirdVisionProvider() {
        this(null);
    }

    /** Uses the supplied compatible model file, or the bundled model if the path is null. */
    public LocalBirdVisionProvider(Path modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public synchronized boolean isAvailable() {
        if (closed) {
            return false;
        }
        initialize();
        return session != null;
    }

    /** Returns the safe error from the most recent initialization attempt or close. */
    public synchronized String availabilityError() {
        if (closed) {
            return CLOSED_MESSAGE;
        }
        return initializationFailure == null ? null : initializationFailure.message();
    }

    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        return analyze(imageData, "classification");
    }

    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        return failure("description", VisionFailureKind.INVALID_REQUEST,
                "The local bird model does not describe images", System.nanoTime());
    }

    @Override
    public VisionResult detectObjects(byte[] imageData, VisionContext context) {
        return analyze(imageData, "object_detection");
    }

    @Override
    public VisionResult rankBestImage(List<byte[]> images, VisionContext context) {
        return failure("ranking", VisionFailureKind.INVALID_REQUEST,
                "The local bird model does not rank images", System.nanoTime());
    }

    private synchronized VisionResult analyze(byte[] imageData, String task) {
        long started = System.nanoTime();
        if (closed) {
            return failure(task, VisionFailureKind.PERMANENT_PROVIDER_ERROR, CLOSED_MESSAGE, started);
        }
        if (Thread.currentThread().isInterrupted()) {
            return interrupted(task, started);
        }

        BufferedImage image;
        try {
            image = YoloXInput.readImage(imageData);
        } catch (IOException e) {
            return failure(task, VisionFailureKind.INVALID_REQUEST, e.getMessage(), started);
        }

        initialize();
        if (initializationFailure != null) {
            return failure(task, initializationFailure.kind(), initializationFailure.message(), started);
        }
        if (Thread.currentThread().isInterrupted()) {
            return interrupted(task, started);
        }
        YoloXInput.ImageGeometry geometry = YoloXInput.prepare(image, inputBuffer());
        try (var tensor = OnnxTensor.createTensor(environment, inputBuffer, INPUT_SHAPE);
             var result = session.run(Map.of(INPUT_NAME, tensor))) {
            if (Thread.currentThread().isInterrupted()) {
                return interrupted(task, started);
            }
            var output = result.get(OUTPUT_NAME).orElse(null);
            if (!(output instanceof OnnxTensor outputTensor)) {
                return failure(task, VisionFailureKind.MALFORMED_RESPONSE,
                        "The local bird model did not return its detection tensor", started);
            }
            List<BoundingBox> birds = YoloXOutput.decode(outputTensor.getFloatBuffer(), geometry);
            String label = birds.isEmpty() ? "none" : "bird";
            double confidence = birds.isEmpty() ? 0.0 : birds.getFirst().confidence();
            return new VisionResult(NAME, MODEL_NAME, task, label, List.of(), confidence, null, birds,
                    null, elapsedMs(started), true, null);
        } catch (IllegalArgumentException e) {
            return failure(task, VisionFailureKind.MALFORMED_RESPONSE, e.getMessage(), started);
        } catch (OrtException | LinkageError e) {
            log.debug("Local bird inference failed", e);
            return failure(task, VisionFailureKind.PERMANENT_PROVIDER_ERROR,
                    "Local bird inference failed", started);
        }
    }

    private FloatBuffer inputBuffer() {
        if (inputBuffer == null) {
            inputBuffer = YoloXInput.allocateBuffer();
        }
        return inputBuffer;
    }

    private void initialize() {
        if (session != null || initializationFailure != null) {
            return;
        }
        if (modelPath != null && !Files.isRegularFile(modelPath)) {
            initializationFailure = new InitializationFailure(VisionFailureKind.NOT_CONFIGURED,
                    "The configured local bird model file is unavailable");
            return;
        }
        try (InputStream model = modelPath == null
                ? LocalBirdVisionProvider.class.getResourceAsStream(MODEL_RESOURCE)
                : Files.newInputStream(modelPath)) {
            if (model == null) {
                initializationFailure = new InitializationFailure(VisionFailureKind.PERMANENT_PROVIDER_ERROR,
                        "The bundled local bird model is missing");
                return;
            }
            byte[] bytes = model.readNBytes(MAX_MODEL_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_MODEL_BYTES) {
                initializationFailure = new InitializationFailure(VisionFailureKind.PERMANENT_PROVIDER_ERROR,
                        "The local bird model file is empty or exceeds the size limit");
                return;
            }
            environment = OrtEnvironment.getEnvironment();
            session = createSession(bytes);
        } catch (IOException | OrtException | RuntimeException | LinkageError e) {
            closeSession(session);
            session = null;
            log.debug("Local bird model initialization failed", e);
            initializationFailure = new InitializationFailure(VisionFailureKind.PERMANENT_PROVIDER_ERROR,
                    "The local bird model could not be loaded or has incompatible tensors");
        }
    }

    private OrtSession createSession(byte[] bytes) throws OrtException {
        OrtSession candidate = null;
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(2, Runtime.getRuntime().availableProcessors()));
            options.setInterOpNumThreads(1);
            candidate = environment.createSession(bytes, options);
            if (!matches(candidate.getInputInfo(), INPUT_NAME, INPUT_SHAPE)
                    || !matches(candidate.getOutputInfo(), OUTPUT_NAME, OUTPUT_SHAPE)) {
                throw new IllegalArgumentException("The model does not expose the YOLOX-Nano tensor shapes");
            }
            return candidate;
        } catch (OrtException | RuntimeException | LinkageError e) {
            closeSession(candidate);
            throw e;
        }
    }

    private static boolean matches(Map<String, NodeInfo> nodes, String name, long[] shape) {
        NodeInfo node = nodes.get(name);
        return nodes.size() == 1 && node != null && node.getInfo() instanceof TensorInfo tensor
                && tensor.type == OnnxJavaType.FLOAT && Arrays.equals(tensor.getShape(), shape);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeSession(session);
        session = null;
        inputBuffer = null;
    }

    private static void closeSession(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("Could not release the local bird inference session", e);
            }
        }
    }

    private static VisionResult interrupted(String task, long started) {
        return failure(task, VisionFailureKind.INTERRUPTED, "Local bird inference was interrupted", started);
    }

    private static VisionResult failure(String task, VisionFailureKind kind, String message, long started) {
        return VisionResult.failure(NAME, MODEL_NAME, task, kind, false, null, message, elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record InitializationFailure(VisionFailureKind kind, String message) {}
}
