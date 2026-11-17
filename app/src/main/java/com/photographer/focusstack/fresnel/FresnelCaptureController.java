package com.photographer.focusstack.fresnel;

import com.photographer.focusstack.app.api.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@Tag(name = "Fresnel capture")
@ConditionalOnProperty(name = "focusstack.fresnel.enabled", havingValue = "true")
@RequestMapping(ApiPaths.V1 + "/integrations/fresnel")
class FresnelCaptureController {
    private static final String READ = "fresnel:descriptor:read";
    private static final String CAMERA = "fresnel:cameras:read";
    private static final String CONTROL = "fresnel:capture-sessions:write";
    private static final String MEDIA = "fresnel:media:read";
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]+)-([0-9]+)");
    private final FresnelCaptures captures;
    private final FresnelPairings pairings;

    FresnelCaptureController(FresnelCaptures captures, FresnelPairings pairings) {
        this.captures = captures;
        this.pairings = pairings;
    }

    @Operation(summary = "Describe supported capture operations")
    @GetMapping("/descriptor")
    Map<String,Object> descriptor(@RequestHeader(value="Authorization", required=false) String token) {
        pairings.authorize(token, READ);
        return captures.descriptor();
    }

    @Operation(summary = "Check the paired connection")
    @GetMapping("/health")
    Map<String,Object> health(@RequestHeader(value="Authorization", required=false) String token) {
        pairings.authorize(token, READ);
        captures.checkAvailable();
        return Map.of("state", "CONNECTED", "checkedAt", Instant.now().toString(), "messageCode", "HEALTH_OK");
    }

    @Operation(summary = "List capture-capable mock profiles")
    @GetMapping("/cameras")
    List<Map<String,Object>> cameras(@RequestHeader(value="Authorization", required=false) String token) {
        pairings.authorize(token, CAMERA);
        return captures.cameras();
    }

    @Operation(summary = "Create a bounded capture session")
    @PostMapping("/capture-sessions")
    ResponseEntity<?> create(@RequestHeader(value="Authorization", required=false) String token,
                             @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody byte[] body) {
        return ResponseEntity.status(201).body(pairings.whileAuthorized(token, CONTROL, owner -> captures.create(owner, key, body)));
    }

    @Operation(summary = "Read durable session status")
    @GetMapping("/capture-sessions/{id}")
    Object session(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id) {
        return captures.status(pairings.authorize(token, CONTROL), id);
    }

    @Operation(summary = "Poll events after a committed cursor")
    @GetMapping("/capture-sessions/{id}/events")
    Object events(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id,
                  @RequestParam(defaultValue="0") long afterEventId, @RequestParam(defaultValue="100") int limit) {
        return captures.events(pairings.authorize(token, CONTROL), id, afterEventId, limit);
    }

    @Operation(summary = "Trigger a presented step once")
    @PostMapping("/capture-sessions/{id}/steps/{stepId}/trigger")
    ResponseEntity<?> trigger(@RequestHeader(value="Authorization", required=false) String token,
                              @RequestHeader(value="Idempotency-Key", required=false) String key,
                              @PathVariable String id, @PathVariable String stepId, @RequestBody byte[] body) {
        var result = pairings.whileAuthorized(token, CONTROL, owner -> captures.trigger(owner, id, stepId, key, body));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @Operation(summary = "Cancel pending steps")
    @PostMapping("/capture-sessions/{id}/cancel")
    ResponseEntity<Void> cancel(@RequestHeader(value="Authorization", required=false) String token,
                                @RequestHeader(value="Idempotency-Key", required=false) String key,
                                @PathVariable String id, @RequestBody(required=false) byte[] body) {
        pairings.whileAuthorized(token, CONTROL, owner -> {
            captures.cancel(owner, id, key, body);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Read verified capture metadata")
    @GetMapping("/capture-sessions/{id}/assets/{assetId}/metadata")
    Object metadata(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id,
                    @PathVariable String assetId) {
        return captures.asset(pairings.authorize(token, MEDIA), id, assetId).metadata();
    }

    @Operation(summary = "Read capture bytes or headers")
    @RequestMapping(path="/capture-sessions/{id}/assets/{assetId}", method={RequestMethod.GET, RequestMethod.HEAD})
    ResponseEntity<StreamingResponseBody> asset(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id,
                            @PathVariable String assetId, HttpServletRequest request) {
        var asset = captures.asset(pairings.authorize(token, MEDIA), id, assetId);
        var meta = asset.metadata();
        var headers = new HttpHeaders();
        String etag = "\"" + meta.sha256() + "\"";
        headers.setETag(etag);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        String condition = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (condition != null && Arrays.stream(condition.split(",")).map(String::trim)
                .anyMatch(value -> value.equals("*") || value.equals(etag) || value.equals("W/" + etag)))
            return ResponseEntity.status(304).headers(headers).build();
        long length = meta.byteLength();
        String range = request.getHeader(HttpHeaders.RANGE);
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        boolean head = "HEAD".equals(request.getMethod());
        long start = 0, end = length - 1;
        int status = 200;
        if (!head && range != null && (ifRange == null || ifRange.equals(etag))) {
            var match = RANGE.matcher(range);
            if (range.length() > 1024 || !match.matches()) throw new FresnelProblem(400, "INVALID_REQUEST");
            java.math.BigInteger first = new java.math.BigInteger(match.group(1));
            java.math.BigInteger last = new java.math.BigInteger(match.group(2));
            if (first.compareTo(last) > 0) throw new FresnelProblem(400, "INVALID_REQUEST");
            if (first.compareTo(java.math.BigInteger.valueOf(length)) >= 0) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
                return ResponseEntity.status(416).headers(headers).body(out ->
                        FresnelPlan.JSON.writeValue(out, new FresnelProblem(416, "RANGE_NOT_SATISFIABLE").body()));
            }
            start = first.longValueExact();
            end = last.min(java.math.BigInteger.valueOf(length - 1)).longValueExact();
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            status = 206;
        }
        headers.set(HttpHeaders.CONTENT_TYPE, meta.mediaType());
        headers.setContentLength(end - start + 1);
        if (head) return ResponseEntity.status(status).headers(headers).build();
        long offset = start, count = end - start + 1;
        StreamingResponseBody response = out -> {
            try (var channel = FileChannel.open(asset.path(), StandardOpenOption.READ)) {
                channel.position(offset);
                var in = Channels.newInputStream(channel);
                byte[] buffer = new byte[8192];
                long remaining = count;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (n < 0) throw new IOException("Incomplete stored capture");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        };
        return ResponseEntity.status(status).headers(headers).body(response);
    }
}
