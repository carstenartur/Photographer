package com.photographer.focusstack.fresnel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "focusstack.fresnel.enabled", havingValue = "true")
public class FresnelPairings {
    public static final List<String> SCOPES = List.of("fresnel:descriptor:read", "fresnel:cameras:read",
            "fresnel:capture-sessions:write", "fresnel:media:read");
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern UUID_TEXT = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern LABEL = Pattern.compile("[^\\p{Cntrl}]{1,128}");
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private final Cache<String, Pending> codes = Caffeine.newBuilder().maximumSize(128)
            .expireAfterWrite(CODE_TTL).build();
    private final Cache<String, Integer> failures = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    private final Cache<String, Integer> tokenFailures = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final Path directory;
    private final Clock clock;

    @Autowired
    public FresnelPairings(@Value("${focusstack.fresnel.directory:${focusstack.storage.images-dir:./data/images}/fresnel}") String root) {
        this(Path.of(root), Clock.systemUTC());
    }

    FresnelPairings(Path root, Clock clock) {
        this.directory = root.resolve("pairings");
        this.clock = clock;
        try { Files.createDirectories(directory); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public record PairRequest(String pairingCode, String fresnelInstanceId, String fresnelDisplayName,
                              String fresnelApplicationVersion, Integer fresnelProtocolVersion) {}
    public record Grant(String pairingCode, String expiresAt, String baseUrl) {}
    public record Exchange(String pairingId, String bearerToken, List<String> scopes) {}
    public record Summary(String pairingId, String displayName, String applicationVersion, String instanceId,
                          List<String> scopes, String createdAt, String lastSeenAt, String state) {}
    private record Pending(List<String> scopes, Instant expires) {}
    private record Stored(String id, String name, String version, String instanceId, List<String> scopes,
                          String createdAt, String lastSeenAt, String revokedAt, String hash) {
        Stored seen(String now) { return new Stored(id, name, version, instanceId, scopes, createdAt, now, revokedAt, hash); }
        Stored revoked(String now) { return new Stored(id, name, version, instanceId, scopes, createdAt, lastSeenAt, now, null); }
    }

    public synchronized Grant issue(String baseUrl, List<String> scopes) {
        if (scopes == null || scopes.isEmpty() || !SCOPES.containsAll(scopes)
                || scopes.stream().distinct().count() != scopes.size()) throw new FresnelProblem(400, "INVALID_REQUEST");
        URI origin;
        try { origin = URI.create(baseUrl == null ? "" : baseUrl); }
        catch (IllegalArgumentException e) { throw new FresnelProblem(400, "INVALID_REQUEST"); }
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || !List.of("https", "http").contains(origin.getScheme())) {
            throw new FresnelProblem(400, "INVALID_REQUEST");
        }
        String code = secret();
        Instant expiry = clock.instant().plus(CODE_TTL);
        codes.put(digest(code), new Pending(List.copyOf(scopes), expiry));
        return new Grant(code, expiry.toString(), origin.toString());
    }

    public synchronized Exchange exchange(PairRequest request, String peer) {
        int count = failures.asMap().getOrDefault(peer, 0);
        if (count >= 5) throw new FresnelProblem(429, "RATE_LIMITED");
        if (request == null || !matches(ID, request.fresnelInstanceId()) || !matches(LABEL, request.fresnelDisplayName())
                || !matches(LABEL, request.fresnelApplicationVersion()) || request.fresnelProtocolVersion() == null
                || request.fresnelProtocolVersion() < 1 || request.pairingCode() == null
                || request.pairingCode().isEmpty() || request.pairingCode().length() > 512) {
            throw new FresnelProblem(400, "INVALID_REQUEST");
        }
        String key = digest(request.pairingCode());
        Pending pending = codes.getIfPresent(key);
        if (pending == null || !clock.instant().isBefore(pending.expires())) {
            failures.put(peer, count + 1);
            throw new FresnelProblem(403, "PAIRING_CODE_INVALID");
        }
        if (request.fresnelProtocolVersion() != 1) throw new FresnelProblem(409, "INCOMPATIBLE");
        String id = UUID.randomUUID().toString();
        String token = id + "." + secret();
        String now = clock.instant().toString();
        save(new Stored(id, request.fresnelDisplayName(), request.fresnelApplicationVersion(),
                request.fresnelInstanceId(), pending.scopes(), now, null, null, digest(token)));
        codes.invalidate(key);
        return new Exchange(id, token, pending.scopes());
    }

    synchronized String authenticatePeer(String header, String peer) {
        int failed = tokenFailures.asMap().getOrDefault(peer, 0);
        if (failed >= 16) throw new FresnelProblem(429, "RATE_LIMITED");
        try {
            return authorize(header, null);
        } catch (FresnelProblem e) {
            if (e.status() == 401) tokenFailures.put(peer, failed + 1);
            throw e;
        }
    }

    public synchronized String authorize(String header, String scope) {
        if (header == null || header.length() != 7 + 36 + 1 + 43 || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || header.charAt(43) != '.') throw unauthorized();
        String token = header.substring(7);
        Stored p = active(token.substring(0, 36));
        if (!MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),
                p.hash().getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
        if (scope != null && !p.scopes().contains(scope)) throw new FresnelProblem(403, "INSUFFICIENT_SCOPE");
        return p.id();
    }

    synchronized void recordSuccessfulRequest(String pairingId) {
        Stored p = load(pairingId);
        if (p == null || p.revokedAt() != null) return;
        Instant now = clock.instant();
        if (p.lastSeenAt() == null || Instant.parse(p.lastSeenAt()).plusSeconds(5).isBefore(now)) {
            save(p.seen(now.toString()));
        }
    }

    public synchronized <T> T whileAuthorized(String header, String scope, Function<String, T> action) {
        return action.apply(authorize(header, scope));
    }

    public synchronized void whilePaired(String id, Runnable action) {
        active(id);
        action.run();
    }

    public synchronized void revoke(String id, Runnable stop) {
        Stored p = load(id);
        if (p == null) throw new FresnelProblem(404, "PAIRING_NOT_FOUND");
        if (p.revokedAt() == null) save(p.revoked(clock.instant().toString()));
        stop.run();
    }

    public synchronized List<Summary> list() {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json"))
                    .map(name -> load(name.substring(0, name.length() - 5))).map(p -> new Summary(p.id(), p.name(),
                            p.version(), p.instanceId(), p.scopes(), p.createdAt(), p.lastSeenAt(), state(p)))
                    .sorted(java.util.Comparator.comparing(Summary::createdAt).reversed()).toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private String state(Stored p) {
        if (p.revokedAt() != null) return "REVOKED";
        if (p.lastSeenAt() == null) return "PAIRING";
        return Instant.parse(p.lastSeenAt()).plusSeconds(60).isAfter(clock.instant()) ? "CONNECTED" : "DISCONNECTED";
    }

    private Stored active(String id) {
        Stored p = load(id);
        if (p == null || p.revokedAt() != null) throw unauthorized();
        return p;
    }
    private static FresnelProblem unauthorized() { return new FresnelProblem(401, "AUTHENTICATION_FAILED"); }

    private Stored load(String id) {
        if (!matches(UUID_TEXT, id)) return null;
        Path file = directory.resolve(id + ".json");
        try { return json.readValue(Files.readAllBytes(file), Stored.class); }
        catch (java.nio.file.NoSuchFileException e) { return null; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void save(Stored p) {
        try {
            CaptureJournal.writeAtomically(directory, p.id() + ".json", json.writeValueAsBytes(p));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
    private static String digest(String value) {
        return FresnelPlan.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
