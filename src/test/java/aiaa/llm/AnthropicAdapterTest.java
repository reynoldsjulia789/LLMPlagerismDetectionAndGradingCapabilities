package aiaa.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Claude adapter against a local fake Messages endpoint: request shape, parsing, retries, failures. */
class AnthropicAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["similarity"],
             "properties":{"similarity":{"type":"integer"}}}""";

    private HttpServer server;
    private final Deque<int[]> statuses = new ConcurrentLinkedDeque<>();   // queued HTTP statuses
    private final Deque<String> bodies = new ConcurrentLinkedDeque<>();    // queued response bodies
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> apiKeys = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            synchronized (requests) {
                requests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
                String key = ex.getRequestHeaders().getFirst("x-api-key");
                apiKeys.add(key != null ? key : ex.getRequestHeaders().getFirst("Authorization"));
            }
            int status = statuses.isEmpty() ? 200 : statuses.poll()[0];
            byte[] out = (bodies.isEmpty() ? message("end_turn", "{\"similarity\": 42}") : bodies.poll())
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicAdapter adapter(String effort, int retries) {
        return new AnthropicAdapter(AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key").maxRetries(0).build(), "claude-opus-5-5", effort, retries);
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":%s}],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":30}}""".formatted(quote(text), stopReason);
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ModelRequest request(Double temperature) {
        return new ModelRequest("system text", "user text", 16000, temperature, "code_similarity", SCHEMA);
    }

    @Test
    void sendsModelSchemaAndEffortAndParsesTheReply() throws ModelException {
        ModelResponse r = adapter("high", 0).complete(request(null));
        assertEquals("{\"similarity\": 42}", r.text());
        assertEquals("claude-opus-5-5", r.returnedModel());
        assertEquals(120, r.inputTokens());
        assertEquals(30, r.outputTokens());
        assertEquals(0, r.retries());

        JsonNode sent = requests.getFirst();
        assertEquals("claude-opus-5-5", sent.path("model").asText());
        assertEquals(16000, sent.path("max_tokens").asInt());
        assertEquals("system text", sent.path("system").asText());
        assertEquals("user", sent.path("messages").path(0).path("role").asText());
        assertEquals("json_schema", sent.path("output_config").path("format").path("type").asText());
        assertEquals("integer", sent.path("output_config").path("format").path("schema")
                .path("properties").path("similarity").path("type").asText());
        assertEquals("high", sent.path("output_config").path("effort").asText());
        assertFalse(sent.has("temperature"));
        assertTrue(apiKeys.getFirst() != null && apiKeys.getFirst().contains("test-key"), "key sent: " + apiKeys);
    }

    @Test
    void modelDefaultEffortSendsNoEffort() throws ModelException {
        adapter(null, 0).complete(request(null));
        assertFalse(requests.getFirst().path("output_config").has("effort"));
    }

    @Test
    void rateLimitIsRetriedAndCounted() throws ModelException {
        statuses.add(new int[]{429});
        bodies.add("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        ModelResponse r = adapter(null, 2).complete(request(null));
        assertEquals(1, r.retries());
        assertEquals(2, requests.size());
    }

    @Test
    void badRequestIsNotRetried() {
        statuses.add(new int[]{400});
        bodies.add("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad schema\"}}");
        ModelException e = assertThrows(ModelException.class, () -> adapter(null, 3).complete(request(null)));
        assertEquals(1, requests.size());
        assertTrue(e.getMessage().contains("bad schema"), e.getMessage());
    }

    @Test
    void refusalAndTruncationAreFailuresNotResults() {
        bodies.add(message("refusal", ""));
        assertTrue(assertThrows(ModelException.class, () -> adapter(null, 0).complete(request(null)))
                .getMessage().contains("refused"));
        bodies.add(message("max_tokens", "{\"simil"));
        assertTrue(assertThrows(ModelException.class, () -> adapter(null, 0).complete(request(null)))
                .getMessage().contains("truncated"));
    }

    @Test
    void temperatureIsReportedAsUnsupportedWithoutCalling() {
        assertThrows(ModelException.class, () -> adapter(null, 0).complete(request(0.0)));
        assertTrue(requests.isEmpty());
    }
}
