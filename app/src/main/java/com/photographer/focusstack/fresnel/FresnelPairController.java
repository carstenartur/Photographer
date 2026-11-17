package com.photographer.focusstack.fresnel;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Fresnel capture")
@RequestMapping(FresnelIngress.ROOT)
@ConditionalOnProperty(name = "focusstack.fresnel.enabled", havingValue = "true")
class FresnelPairController {
    private final FresnelPairings pairings;
    private final FresnelCaptures captures;
    FresnelPairController(FresnelPairings pairings, FresnelCaptures captures) {
        this.pairings = pairings;
        this.captures = captures;
    }

    @Operation(summary = "Exchange a one-time pairing code")
    @PostMapping(value = "/pair", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> pair(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                           HttpServletRequest request) throws IOException {
        if (key == null || !FresnelPairings.ID.matcher(key).matches()) throw new FresnelProblem(400, "INVALID_REQUEST");
        FresnelPairings.PairRequest body;
        try { body = FresnelPlan.JSON.readValue(request.getInputStream(), FresnelPairings.PairRequest.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new FresnelProblem(400, "INVALID_REQUEST"); }
        var descriptor = captures.descriptor();
        var result = pairings.exchange(body, request.getRemoteAddr());
        return ResponseEntity.status(201).body(Map.of("pairingId", result.pairingId(), "bearerToken",
                result.bearerToken(), "scopes", result.scopes(), "descriptor", descriptor));
    }
}
