package com.photographer.focusstack.fresnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Run the canonical BOS sequence over the actual servlet and security chains. */
final class FresnelCaptureHttpChecks {
    private FresnelCaptureHttpChecks() {}

    static void verify(String origin, HttpClient http, String admin) throws Exception {
        String root = origin + "/api/v1/integrations/fresnel";
        String admins = origin + "/api/v1/admin/integrations/fresnel";
        JsonNode fixtures = FresnelPlan.JSON.readTree(Path.of("..","docs","integrations","fresnel-v1",
                "photographer-capture-v1.fixtures.json").toFile()).required("values");
        var code = json(send(http, "POST", admins + "/pairing-codes", admin,
                Map.of("baseUrl",origin), null, Map.of()), 201).required("pairingCode").asText();
        ObjectNode pair = fixtures.required("pair").deepCopy();
        pair.put("pairingCode",code);
        var grant = json(send(http, "POST", root + "/pair", null, pair, "exchange-bos", Map.of()), 201);
        String bearer = "Bearer " + grant.required("bearerToken").asText();
        var cameras = json(send(http,"GET",root + "/cameras", bearer, null, null, Map.of()),200);
        ObjectNode plan = fixtures.required("bosPlan").deepCopy();
        plan.put("cameraProfileId",cameras.get(0).required("cameraProfileId").asText());
        String collection = root + "/capture-sessions";
        var created = json(send(http,"POST",collection,bearer,plan,"bos-create",Map.of()),201);
        assertEquals(created, json(send(http,"POST",collection,bearer,plan,"bos-create",Map.of()),201));
        String sid = created.required("sessionId").asText();
        String session = collection + "/" + sid;
        for (String step : new String[]{"reference","disturbed"}) {
            String trigger = session + "/steps/" + step + "/trigger";
            var body = fixtures.required(step.equals("reference") ? "bosTrigger" : "disturbedTrigger");
            assertEquals(202, send(http, "POST",trigger,bearer,body,step,Map.of()).statusCode());
            JsonNode result = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline && result == null) {
                var replay = send(http, "POST",trigger,bearer,body,step,Map.of());
                if (replay.statusCode() == 200) result = json(replay,200);
                else { assertEquals(202,replay.statusCode()); Thread.sleep(10); }
            }
            assertNotNull(result, "The committed capture was unavailable");
            assertEquals(step,result.required("stepId").asText());
            var metadata=result.required("asset");
            String asset = session + "/assets/" + metadata.required("assetId").asText();
            var full = send(http,"GET",asset,bearer,null,null,Map.of());
            assertEquals(200,full.statusCode());
            assertEquals(metadata.required("sha256").asText(), FresnelPlan.hash(full.body()));
            String etag = full.headers().firstValue("ETag").orElseThrow();
            var first = send(http,"GET",asset,bearer,null,null,Map.of("Range","bytes=0-7","If-Range",etag));
            var rest = send(http,"GET",asset,bearer,null,null,Map.of("Range","bytes=8-999999","If-Range",etag));
            assertEquals(206,first.statusCode()); assertEquals(206,rest.statusCode());
            assertArrayEquals(Arrays.copyOfRange(full.body(),0,8),first.body());
            assertArrayEquals(Arrays.copyOfRange(full.body(),8,full.body().length),rest.body());
            assertEquals(304,send(http,"GET",asset,bearer,null,null,Map.of("If-None-Match","W/"+etag)).statusCode());
            assertEquals(200,send(http,"HEAD",asset,bearer,null,null,Map.of("Range","bytes=2-5")).statusCode());
        }
        assertEquals("COMPLETED", json(send(http,"GET",session,bearer,null,null,Map.of()),200).required("state").asText());
        String id = grant.required("pairingId").asText();
        assertEquals(204,send(http,"DELETE",admins+"/pairings/"+id,admin,null,null,Map.of()).statusCode());
        assertEquals(401,send(http,"GET",session,bearer,null,null,Map.of()).statusCode());
    }

    private static HttpResponse<byte[]> send(HttpClient client, String method, String url, String auth,
                                             Object body, String key, Map<String,String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (auth != null) b.header("Authorization",auth);
        if (key != null) b.header("Idempotency-Key",key);
        headers.forEach(b::header);
        if (body != null) b.header("Content-Type","application/json");
        return client.send(b.method(method,body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofByteArray(FresnelPlan.JSON.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonNode json(HttpResponse<byte[]> resp, int expected) throws Exception {
        assertEquals(expected,resp.statusCode(),()->resp.request().method()+" "+resp.request().uri().getPath());
        return FresnelPlan.JSON.readTree(resp.body());
    }
}
