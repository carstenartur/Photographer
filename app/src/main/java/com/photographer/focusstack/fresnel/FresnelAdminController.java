package com.photographer.focusstack.fresnel;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.photographer.focusstack.app.api.ApiPaths;

@RestController
@Tag(name = "Fresnel capture")
@RequestMapping(ApiPaths.V1)
class FresnelAdminController {
    static final String ADMIN = "/admin/integrations/fresnel";
    private final ObjectProvider<FresnelPairings> pairings;
    private final ObjectProvider<FresnelCaptures> captures;
    private final boolean trustNetwork;

    FresnelAdminController(ObjectProvider<FresnelPairings> pairings, ObjectProvider<FresnelCaptures> captures,
            @Value("${focusstack.fresnel.trust-private-network:false}") boolean trustNetwork) {
        this.pairings = pairings;
        this.captures = captures;
        this.trustNetwork = trustNetwork;
    }
    record CodeRequest(String baseUrl, List<String> scopes) {}

    @Operation(summary = "List paired instances and active captures")
    @GetMapping(ADMIN + "/pairings")
    ResponseEntity<?> list() {
        var service = pairings.getIfAvailable();
        if (service == null) return privateOk(Map.of("enabled", false, "pairings", List.of()));
        var engine = captures.getObject();
        return privateOk(Map.of("enabled", true, "descriptor", engine.descriptor(), "pairings", service.list().stream()
                .map(p -> Map.of("pairing", p, "sessions", engine.activeSessions(p.pairingId()))).toList()));
    }

    @Operation(summary = "Get an instance and its active captures")
    @GetMapping(ADMIN + "/pairings/{id}")
    ResponseEntity<?> one(@PathVariable String id) {
        var pairing = service().list().stream().filter(p -> p.pairingId().equals(id)).findFirst()
                .orElseThrow(() -> new FresnelProblem(404, "PAIRING_NOT_FOUND"));
        return privateOk(Map.of("pairing", pairing, "sessions", captures.getObject().activeSessions(id)));
    }

    @Operation(summary = "Read the aggregate Fresnel connection status")
    @GetMapping("/fresnel/status")
    ResponseEntity<?> indicator() {
        var service = pairings.getIfAvailable();
        List<FresnelPairings.Summary> list = service == null ? List.of() : service.list();
        String state = java.util.stream.Stream.of("CONNECTED", "PAIRING", "DISCONNECTED", "REVOKED")
                .filter(value -> list.stream().anyMatch(p -> value.equals(p.state()))).findFirst().orElse("NOT_CONFIGURED");
        return privateOk(Map.of("state", state));
    }

    @Operation(summary = "Issue a one-time pairing code")
    @PostMapping(ADMIN + "/pairing-codes")
    ResponseEntity<?> issue(@RequestBody CodeRequest request) {
        URI base;
        try { base = URI.create(request.baseUrl()); }
        catch (IllegalArgumentException | NullPointerException e) { throw new FresnelProblem(400, "INVALID_REQUEST"); }
        if ("http".equalsIgnoreCase(base.getScheme()) && !trustNetwork && !FresnelIngress.loopback(base.getHost())) {
            throw new FresnelProblem(400, "SECURE_TRANSPORT_REQUIRED");
        }
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service().issue(request.baseUrl(), request.scopes() == null ? FresnelPairings.SCOPES : request.scopes()));
    }

    @Operation(summary = "Revoke an instance and stop its captures")
    @DeleteMapping(ADMIN + "/pairings/{id}")
    ResponseEntity<Void> revoke(@PathVariable String id) {
        service().revoke(id, () -> captures.getObject().revoke(id));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    @Operation(summary = "Abort one capture session")
    @PostMapping(ADMIN + "/pairings/{id}/sessions/{session}/abort")
    ResponseEntity<Void> abort(@PathVariable String id, @PathVariable String session) {
        service().whilePaired(id, () -> captures.getObject().cancel(id, session, "admin-abort-" + session));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    private FresnelPairings service() {
        var service = pairings.getIfAvailable();
        if (service == null) throw new FresnelProblem(409, "NOT_CONFIGURED");
        return service;
    }
    private static ResponseEntity<?> privateOk(Object body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }
}
