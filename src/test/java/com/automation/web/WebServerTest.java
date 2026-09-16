package com.automation.web;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir Path workspace;
    private WebServer app;
    private HttpServer mock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> inspectedUri = new AtomicReference<>();
    private final AtomicReference<String> inspectedAuth = new AtomicReference<>();
    private final AtomicReference<String> inspectedContentType = new AtomicReference<>();
    private final AtomicReference<String> inspectedBody = new AtomicReference<>();
    private String base;
    private String token;

    @BeforeEach void start() throws Exception {
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/items", exchange -> {
            hits.incrementAndGet();
            byte[] body = "[{\"id\":1,\"title\":\"<script>alert('test')</script>\"},{\"id\":2,\"title\":\"Second\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mock.createContext("/failure", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        mock.createContext("/inspect", exchange -> {
            hits.incrementAndGet();
            inspectedUri.set(exchange.getRequestURI().toString());
            inspectedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            inspectedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            inspectedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mock.start();
        Files.createDirectories(workspace.resolve("collections"));
        String collection = """
                {"info":{"name":"Local test collection","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"List items","request":{"method":"GET","url":"http://127.0.0.1:%d/items"}},
                         {"name":"Failure","request":{"method":"GET","url":"http://127.0.0.1:%d/failure"}}]}
                """.formatted(mock.getAddress().getPort(), mock.getAddress().getPort());
        Files.writeString(workspace.resolve("collections/local.json"), collection);
        app = new WebServer(workspace, workspace.resolve(".env"), 0);
        app.start();
        base = "http://127.0.0.1:" + app.port();
        token = json(request("GET", "/api/session", null)).path("token").asText();
    }

    @AfterEach void stop() {
        if (app != null) app.close();
        if (mock != null) mock.stop(0);
    }

    @Test void servesOfflineInterfaceAndActualWorkspaceFiles() throws Exception {
        var page = request("GET", "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("CEMS Studio"));
        assertTrue(page.body().contains("Guided workspace"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertEquals(200, request("GET", "/app.js", null).statusCode());
        assertEquals(200, request("GET", "/guided-workflow.js", null).statusCode());
        assertEquals(200, request("GET", "/quick-query-assist.js", null).statusCode());
        assertEquals(200, request("GET", "/documents", null).statusCode());
        assertEquals(200, request("GET", "/documents.js", null).statusCode());
        assertEquals(200, request("GET", "/documents/help", null).statusCode());
        var filesResponse = request("GET", "/api/files", null);
        assertTrue(filesResponse.body().contains("collections/local.json"));
        assertFalse(filesResponse.body().contains("documents/opds"));
        assertEquals("no-store, max-age=0", filesResponse.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(404, request("GET", "/.env", null).statusCode());
    }

    @Test void savesWithRevisionsAndRejectsLostUpdates() throws Exception {
        var first = json(request("PUT", "/api/file", Map.of("path", "filters/new.filter", "content", "TITLE \"First\";")));
        assertEquals("TITLE \"First\";", Files.readString(workspace.resolve("filters/new.filter")));
        assertEquals(409, request("PUT", "/api/file", Map.of("path", "filters/new.filter", "content", "overwritten")).statusCode());
        assertEquals(200, request("PUT", "/api/file", Map.of("path", "filters/new.filter", "content", "TITLE \"Second\";", "revision", first.path("revision").asText())).statusCode());
        assertEquals(409, request("PUT", "/api/file", Map.of("path", "filters/new.filter", "content", "lost update", "revision", first.path("revision").asText())).statusCode());
    }

    @Test void createsFoldersMovesFilesAndUsesRecoverableTrash() throws Exception {
        assertEquals(201, request("POST", "/api/folder", Map.of("path", "filters/daily")).statusCode());
        request("PUT", "/api/file", Map.of("path", "filters/old.filter", "content", "METRICS;"));
        assertEquals(200, request("POST", "/api/move", Map.of("from", "filters/old.filter", "to", "filters/daily/report.filter")).statusCode());
        JsonNode result = json(request("POST", "/api/trash", Map.of("path", "filters/daily")));
        assertFalse(Files.exists(workspace.resolve("filters/daily")));
        assertEquals("METRICS;", Files.readString(workspace.resolve(result.path("recoveryPath").asText()).resolve("report.filter")));
        assertEquals(400, request("POST", "/api/trash", Map.of("path", "filters")).statusCode());
    }

    @Test void rejectsTraversalLinksAndCrossOriginWrites() throws Exception {
        assertEquals(400, request("PUT", "/api/file", Map.of("path", "filters/../outside.filter", "content", "x")).statusCode());
        assertEquals(400, request("GET", "/api/file?path=collections%2F..%2F.env", null).statusCode());
        Files.createSymbolicLink(workspace.resolve("filters/link"), workspace.resolve("collections"));
        assertEquals(400, request("PUT", "/api/file", Map.of("path", "filters/link/leak.filter", "content", "x")).statusCode());
        assertFalse(request("GET", "/api/files", null).body().contains("filters/link"));
        HttpRequest foreign = HttpRequest.newBuilder(URI.create(base + "/api/folder")).header("Origin", "https://example.com")
                .header("Content-Type", "application/json").header("X-Workspace-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"filters/foreign\"}")).build();
        assertEquals(403, client.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest missingToken = HttpRequest.newBuilder(URI.create(base + "/api/folder")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"filters/foreign\"}")).build();
        assertEquals(403, client.send(missingToken, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(Files.exists(workspace.resolve("filters/foreign")));
    }

    @Test void rejectsRebindingHostAndMalformedRequestBodies() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", app.port())) {
            socket.getOutputStream().write("GET /api/session HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
        }
        HttpRequest missingContentType = HttpRequest.newBuilder(URI.create(base + "/api/folder"))
                .header("X-Workspace-Token", token).POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        assertEquals(415, client.send(missingContentType, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void validatesUnsavedSourceWithoutExecutingRequests() throws Exception {
        var result = request("POST", "/api/validate", reportBody());
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(1, json(result).path("requests").asInt());
        assertEquals(0, hits.get());
        assertFalse(Files.exists(workspace.resolve("filters/editor.filter")));
        var invalid = request("POST", "/api/validate", Map.of("collection", "collections/local.json", "source", "SUMMARY {\n METRIC \"Rows\" $ROWS;\n}", "filename", "editor.filter"));
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("editor.filter:2:"));
        assertEquals(0, hits.get());
    }

    @Test void validatesCollectionBufferWithoutSavingIt() throws Exception {
        String onDisk = Files.readString(workspace.resolve("collections/local.json"));
        String buffer = onDisk.replace("List items", "Renamed in editor");
        var result = request("POST", "/api/validate", Map.of("collection", "collections/local.json", "source",
                "REQUESTS \"Renamed in editor\"; METRICS;", "collectionSource", buffer));
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(onDisk, Files.readString(workspace.resolve("collections/local.json")));
        assertEquals(0, hits.get());
        var invalid = request("POST", "/api/validate", Map.of("collection", "collections/local.json", "source", "", "collectionSource", "{invalid json"));
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("local.json:1:"));
    }

    @Test void opensACollectionAsAnApiClientAndSendsOneRequest() throws Exception {
        JsonNode collection = json(request("GET", "/api/collection?path=collections%2Flocal.json", null));
        assertEquals("Local test collection", collection.path("name").asText());
        assertEquals(2, collection.path("requests").size());
        JsonNode definition = collection.path("requests").get(0);
        assertEquals("List items", definition.path("name").asText());
        assertEquals("GET", definition.path("method").asText());
        assertTrue(definition.path("url").asText().contains("/items"));

        var response = request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "GET",
                "url", definition.path("url").asText(),
                "headers", List.of(),
                "body", ""));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = json(response);
        assertEquals(200, result.path("statusCode").asInt());
        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("body").asText().contains("Second"));
        assertEquals(1, hits.get());
    }

    @Test void apiClientResolvesVariablesParamsAuthAndFormBodies() throws Exception {
        String collectionSource = """
                {"info":{"name":"API features","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "variable":[{"key":"page","value":"1"}],
                 "item":[{"name":"Inspect","request":{"method":"POST",
                   "url":{"raw":"http://127.0.0.1:%d/inspect/{{ID}}?page={{page}}","query":[{"key":"page","value":"{{page}}"}]},
                   "auth":{"type":"apikey","apikey":[{"key":"key","value":"api_key"},{"key":"value","value":"{{API_TOKEN}}"},{"key":"in","value":"query"}]},
                   "body":{"mode":"urlencoded","urlencoded":[{"key":"user","value":"{{ID}}"}]}}}]}
                """.formatted(mock.getAddress().getPort());
        Files.writeString(workspace.resolve("collections/features.json"), collectionSource);

        JsonNode definition = json(request("GET", "/api/collection?path=collections%2Ffeatures.json", null));
        assertEquals("page", definition.path("requests").get(0).path("params").get(0).path("key").asText());
        assertEquals("apikey", definition.path("requests").get(0).path("auth").path("type").asText());
        assertEquals("urlencoded", definition.path("requests").get(0).path("bodyMode").asText());
        assertEquals("1", definition.path("variables").path("page").asText());

        var response = request("POST", "/api/request", Map.of(
                "collection", "collections/features.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect/{{ID}}?page={{page}}".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "bodyMode", "urlencoded",
                "bodyFields", List.of(Map.of("key", "user", "value", "{{ID}}", "type", "text", "disabled", false),
                        Map.of("key", "label", "value", "hello world", "type", "text", "disabled", false)),
                "auth", Map.of("type", "apikey", "values", Map.of("key", "api_key", "value", "{{API_TOKEN}}", "in", "query")),
                "variables", Map.of("ID", "42", "page", "3", "API_TOKEN", "secret key")));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(inspectedUri.get().startsWith("/inspect/42?page=3"), inspectedUri.get());
        assertTrue(inspectedUri.get().contains("api_key=secret+key"), inspectedUri.get());
        assertNull(inspectedAuth.get());
        assertEquals("application/x-www-form-urlencoded", inspectedContentType.get());
        assertEquals("user=42&label=hello+world", inspectedBody.get());
    }

    @Test void requestAuthWinsOverEnvAndReportsWhereItCameFrom() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\n");

        JsonNode result = json(request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "auth", Map.of("type", "bearer", "values", Map.of("token", "ui-token")))));

        // The request's own auth must be the one actually sent, not the .env fallback.
        assertEquals("Bearer ui-token", inspectedAuth.get());
        assertTrue(result.path("authApplied").asText().contains("request's own auth"),
                "The UI should be told the request auth was used: " + result.path("authApplied").asText());
        assertFalse(result.path("authApplied").asText().contains("ui-token"),
                "The effective-auth note must never include the secret value");
    }

    @Test void requestAuthOverridesEnvEvenWhenEnvAlsoSetsAnApiKey() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\nAPI_KEY=env-key\n");

        json(request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "auth", Map.of("type", "bearer", "values", Map.of("token", "ui-token")))));

        assertEquals("Bearer ui-token", inspectedAuth.get());
    }

    @Test void storedVaultSecretResolvesTemplateInRequestAuth() throws Exception {
        assertEquals(200, request("PUT", "/api/auth/secrets", Map.of(
                "secrets", Map.of("MY_API_TOKEN", "stored-token"))).statusCode());

        JsonNode result = json(request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "auth", Map.of("type", "bearer", "values", Map.of("token", "{{MY_API_TOKEN}}")))));

        assertEquals("Bearer stored-token", inspectedAuth.get(),
                "A {{VAR}} reference in request auth should resolve from the encrypted vault");
        assertFalse(result.toString().contains("stored-token"),
                "The response must not echo the resolved secret back to the browser");
    }

    @Test void vaultSecretSuppliesAuthWhenTheRequestLeavesItBlank() throws Exception {
        request("PUT", "/api/auth/secrets", Map.of("secrets", Map.of("BEARER_TOKEN", "vault-token")));

        JsonNode result = json(request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "auth", Map.of("type", "bearer", "values", Map.of("token", "")))));

        assertEquals("Bearer vault-token", inspectedAuth.get());
        assertTrue(result.path("authApplied").asText().contains("workspace default"),
                "A blank request auth backed by the vault should be reported as a workspace default");
    }

    @Test void secretVaultStoresListsAndRemovesWithoutLeakingValues() throws Exception {
        var empty = json(request("GET", "/api/auth/secrets", null));
        assertEquals(0, empty.path("secrets").size());

        var saved = json(request("PUT", "/api/auth/secrets", Map.of(
                "secrets", Map.of("MY_TOKEN", "a-very-secret-value"))));
        JsonNode entry = saved.path("secrets").get(0);
        assertEquals("MY_TOKEN", entry.path("name").asText());
        assertEquals("a-very-secret-value".length(), entry.path("length").asInt());
        assertTrue(entry.has("preview"));
        assertFalse(saved.toString().contains("a-very-secret-value"),
                "The API must never return the stored secret to the browser");

        // The value is encrypted at rest, so the raw file must not contain it either.
        String stored = Files.readString(workspace.resolve(".web-state/secrets.enc"));
        assertFalse(stored.contains("a-very-secret-value"));

        var listed = json(request("GET", "/api/auth/secrets", null));
        assertEquals(1, listed.path("secrets").size());
        assertFalse(listed.toString().contains("a-very-secret-value"));

        var removed = json(request("DELETE", "/api/auth/secrets?name=MY_TOKEN", null));
        assertEquals(0, removed.path("secrets").size());
    }

    @Test void typedCredentialCanBeSavedAndUsedWithoutLeakingValues() throws Exception {
        var saved = json(request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(Map.of(
                "name", "OPS_BASIC", "type", "basic",
                "values", Map.of("username", "ops-user", "password", "ops-pass"))))));

        JsonNode entry = saved.path("secrets").get(0);
        assertEquals("OPS_BASIC", entry.path("name").asText());
        assertEquals("basic", entry.path("type").asText(), "The stored auth type should be reported back");
        assertFalse(saved.toString().contains("ops-pass"), "The API must never return the stored secret");

        // Bound to the request as {{NAME}} references, the credential must supply both fields.
        json(request("POST", "/api/request", Map.of(
                "collection", "collections/local.json",
                "index", 0,
                "method", "POST",
                "url", "http://127.0.0.1:%d/inspect".formatted(mock.getAddress().getPort()),
                "headers", List.of(),
                "body", "",
                "auth", Map.of("type", "basic", "values", Map.of(
                        "username", "{{OPS_BASIC}}", "password", "{{OPS_BASIC_PASSWORD}}")))));

        String expected = "Basic " + Base64.getEncoder().encodeToString("ops-user:ops-pass".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, inspectedAuth.get(),
                "A typed credential should resolve both its fields from the vault");
    }

    @Test void credentialEndpointRejectsUnknownTypeAndEmptyValues() throws Exception {
        assertEquals(400, request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(Map.of(
                "name", "X", "type", "oauth2", "values", Map.of("token", "v"))))).statusCode(),
                "An auth type the engine cannot send must be refused");
        assertEquals(400, request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(Map.of(
                "name", "X", "type", "bearer", "values", Map.of())))).statusCode(),
                "A credential with no value cannot be saved");
        assertEquals(400, request("PUT", "/api/auth/credentials", Map.of("credentials", "nope")).statusCode());
    }

    @Test void secretVaultRejectsUnusableNamesAndUnknownRemovals() throws Exception {
        assertEquals(400, request("PUT", "/api/auth/secrets", Map.of(
                "secrets", Map.of("NOT A NAME", "value"))).statusCode());
        // A name that collides with an OS environment variable would shadow it, so it is refused.
        assertEquals(400, request("PUT", "/api/auth/secrets", Map.of(
                "secrets", Map.of("PATH", "value"))).statusCode());
        assertEquals(404, request("DELETE", "/api/auth/secrets?name=NEVER_STORED", null).statusCode());
        assertEquals(400, request("PUT", "/api/auth/secrets", Map.of("secrets", "not-an-object")).statusCode());
    }

    @Test void runsRealPipelinePreviewsStylesAndDownloadsWorkbook() throws Exception {
        JsonNode started = json(request("POST", "/api/runs", reportBody()));
        JsonNode finished = awaitRun(started.path("id").asText());
        assertEquals("completed", finished.path("status").asText(), finished.toPrettyString());
        assertEquals(1, hits.get());
        assertEquals(1, finished.path("passed").asInt());
        assertEquals("The request completed successfully.", finished.path("summary").asText());
        String path = finished.path("files").get(0).asText();
        assertTrue(Files.exists(workspace.resolve(path)));
        JsonNode preview = json(request("GET", "/api/workbook?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8), null));
        assertEquals("Summary", preview.path("sheets").get(0).path("name").asText());
        assertTrue(preview.toString().contains("2 items match the filter."));
        assertTrue(preview.toString().contains("#245c50"), preview.toPrettyString());
        assertTrue(preview.path("merges").size() > 0);
        assertTrue(preview.toString().contains("<script>"), "Cell text is data, with escaping applied in the client.");
        assertEquals(400, request("GET", "/api/workbook?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&offset=-1", null).statusCode());
        var download = client.send(HttpRequest.newBuilder(URI.create(base + "/api/download?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals('P', download.body()[0]);
        assertEquals('K', download.body()[1]);
        assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
        assertEquals(200, request("POST", "/api/move", Map.of("from", path, "to", "reports/renamed.xlsx")).statusCode());
        assertEquals("reports/renamed.xlsx", json(request("GET", "/api/run?id=" + started.path("id").asText(), null)).path("files").get(0).asText());
        app.close();
        app = new WebServer(workspace, workspace.resolve(".env"), 0);
        app.start(); base = "http://127.0.0.1:" + app.port();
        JsonNode history = json(request("GET", "/api/runs", null));
        assertEquals(started.path("id").asText(), history.get(0).path("id").asText());
    }

    @Test void acceptsAnOutputFilenamePatternAndRejectsPathsOutsideReports() throws Exception {
        Map<String, String> report = new LinkedHashMap<>(reportBody());
        report.put("outputFile", "daily-{collection}-{timestamp}.xlsx");
        JsonNode started = json(request("POST", "/api/runs", report));
        JsonNode finished = awaitRun(started.path("id").asText());

        assertEquals("completed", finished.path("status").asText(), finished.toPrettyString());
        assertTrue(finished.path("files").get(0).asText()
                .matches("reports/daily-Local-test-collection-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.xlsx"));

        report.put("outputFile", "../outside.xlsx");
        var rejected = request("POST", "/api/runs", report);
        assertEquals(400, rejected.statusCode());
        assertTrue(rejected.body().contains("Reports folder"));
    }

    @Test void reportsFailuresWithoutLosingTheGeneratedReport() throws Exception {
        JsonNode started = json(request("POST", "/api/runs", Map.of("collection", "collections/local.json", "source", "REQUESTS \"Failure\";", "filename", "failure.filter")));
        JsonNode finished = awaitRun(started.path("id").asText());
        assertEquals("completed", finished.path("status").asText(), finished.toPrettyString());
        assertEquals(1, finished.path("failed").asInt());
        assertTrue(finished.path("summary").asText().contains("1 request needs attention."));
        assertEquals(503, finished.path("requests").get(0).path("statusCode").asInt());
        assertEquals(1, finished.path("files").size());
    }

    @Test void runsQuickSourceWithoutACollectionDropdownAndRejectsBadQueriesBeforeExecution() throws Exception {
        var valid = request("POST", "/api/validate", Map.of("source", "@\"Local test collection\" #\"List items\" > title where id > 1;"));
        assertEquals(200, valid.statusCode(), valid.body());
        assertEquals(0, hits.get());
        var invalid = request("POST", "/api/runs", Map.of("source", "@local #\"List items\" > title where id > ;"));
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals(0, hits.get());
        JsonNode started = json(request("POST", "/api/runs", Map.of("source",
                "$items = @local #\"List items\" > title where id > 1;", "filename", "quick-run.filter")));
        JsonNode finished = awaitRun(started.path("id").asText());
        assertEquals("completed", finished.path("status").asText(), finished.toPrettyString());
        assertEquals(1, hits.get());
    }

    @Test void namedQueriesAreSavedSeparatelyAndCanBeReadAndRun() throws Exception {
        String source = "@local #\"List items\" > title where id > 1;";
        String path = "queries/Items over one.filter";
        assertTrue(Files.isDirectory(workspace.resolve("queries")));
        var saved = request("PUT", "/api/file", Map.of("path", path, "content", source));
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(source, Files.readString(workspace.resolve(path)));
        assertEquals(0, hits.get());
        assertFalse(Files.exists(workspace.resolve("filters/Items over one.filter")));
        assertTrue(request("GET", "/api/files", null).body().contains(path));
        assertEquals(source, json(request("GET", "/api/file?path=queries%2FItems%20over%20one.filter", null)).path("content").asText());
        assertEquals(409, request("PUT", "/api/file", Map.of("path", path, "content", "overwrite")).statusCode());
        assertEquals(source, Files.readString(workspace.resolve(path)));
        JsonNode started = json(request("POST", "/api/runs/saved-filter", Map.of("filter", path)));
        assertEquals("completed", awaitRun(started.path("id").asText()).path("status").asText());
        assertEquals(1, hits.get());
    }

    @Test void queryFolderEnforcesWorkspacePathsAndFileTypes() throws Exception {
        assertEquals(400, request("PUT", "/api/file", Map.of("path", "queries/../outside.filter", "content", "x")).statusCode());
        assertEquals(400, request("PUT", "/api/file", Map.of("path", "queries/query.json", "content", "{}")).statusCode());
        assertEquals(400, request("POST", "/api/trash", Map.of("path", "queries")).statusCode());
        Path outside = Files.createTempDirectory("outside-queries");
        Files.createSymbolicLink(workspace.resolve("queries/link"), outside);
        assertEquals(400, request("PUT", "/api/file", Map.of("path", "queries/link/leak.filter", "content", "x")).statusCode());
    }

    @Test void runsASavedFilterWithoutSendingItsSourceFromTheEditor() throws Exception {
        Files.writeString(workspace.resolve("filters/quick.filter"), """
                COLLECTION local;
                REQUESTS "List items";
                $ITEMS = FILTER "List items" WHERE id > 0;
                SUMMARY { METRIC "Items" = $ITEMS; STATUS; }
                """);
        JsonNode started = json(request("POST", "/api/runs/saved-filter", Map.of("filter", "filters/quick.filter")));
        JsonNode finished = awaitRun(started.path("id").asText());
        assertEquals("completed", finished.path("status").asText(), finished.toPrettyString());
        assertEquals("quick.filter", finished.path("name").asText());
        assertEquals("Local test collection", finished.path("collection").asText());
        assertEquals(1, hits.get());
    }

    @Test void savedFilterRequiresAnUnambiguousCollection() throws Exception {
        Files.writeString(workspace.resolve("filters/no-collection.filter"), "METRICS;");
        Files.writeString(workspace.resolve("collections/other.json"), """
                {"info":{"name":"Other","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},"item":[]}
                """);
        var response = request("POST", "/api/runs/saved-filter", Map.of("filter", "filters/no-collection.filter"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("does not select a collection"));
        assertEquals(0, hits.get());
    }

    @Test void authSourcesListEveryAmbientSourceInPrecedenceOrder() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\nAPI_KEY=env-key\nUNRELATED=ignored\n");
        Files.writeString(workspace.resolve("filters/local.filter"), """
                COLLECTION "local";
                REQUESTS "List items";
                """);
        request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(
                Map.of("name", "VAULT_TOKEN", "type", "bearer", "values", Map.of("token", "vault-token")))));

        JsonNode result = json(request("GET",
                "/api/auth/sources?collection=collections%2Flocal.json&index=0", null));

        // The filter that selects this collection is found server-side, so the browser never parses one.
        assertEquals("filters/local.filter", result.path("filterPath").asText());
        JsonNode sources = result.path("sources");
        List<String> ids = new java.util.ArrayList<>();
        List<Integer> ranks = new java.util.ArrayList<>();
        sources.forEach(group -> {
            ids.add(group.path("id").asText());
            ranks.add(group.path("order").asInt());
        });
        assertEquals(List.of("request", "vault", "filter", "cli", "dotenv"), ids);
        // The filter outranks the CLI credential store: CredentialLoader applies it afterwards.
        assertTrue(ranks.get(2) < ranks.get(3), "The filter must rank above the CLI credential store");

        JsonNode vault = sourceById(sources, "vault").path("entries");
        assertEquals(1, vault.size());
        assertEquals("VAULT_TOKEN", vault.get(0).path("name").asText());

        // Ambient sources list only credential-shaped keys, never every variable in the workspace.
        JsonNode dotenv = sourceById(sources, "dotenv").path("entries");
        List<String> envKeys = new java.util.ArrayList<>();
        dotenv.forEach(entry -> envKeys.add(entry.path("name").asText()));
        assertTrue(envKeys.contains("BEARER_TOKEN"));
        assertFalse(envKeys.contains("UNRELATED"), "An unrelated .env variable must not be listed");
    }

    @Test void authSourcesAttributeAReferenceToTheSourceThatResolvesIt() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\n");
        request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(
                Map.of("name", "VAULT_TOKEN", "type", "bearer", "values", Map.of("token", "vault-token")))));
        // The credential picker binds a request as {{VAULT_TOKEN}}, so the vault is what supplies the
        // value even though the request's own auth block is what carries the reference.
        Files.writeString(workspace.resolve("collections/vaulted.json"), """
                {"info":{"name":"Vaulted","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"Secured","request":{"method":"GET","url":"http://127.0.0.1:1/items",
                   "auth":{"type":"bearer","bearer":[{"key":"token","value":"{{VAULT_TOKEN}}"}]}}}]}
                """);

        JsonNode effective = json(request("GET",
                "/api/auth/sources?collection=collections%2Fvaulted.json&index=0", null)).path("effective");
        JsonNode field = effective.path("fields").get(0);

        assertEquals("bearer", effective.path("type").asText());
        assertEquals("vault", field.path("source").asText(),
                "A {{REF}} the vault satisfies must be credited to the vault, not to .env");
        assertEquals("VAULT_TOKEN", field.path("key").asText());
        assertTrue(effective.path("summary").asText().contains("encrypted vault"));
    }

    @Test void authSourcesCreditALiteralRequestValueToTheRequest() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\n");
        Files.writeString(workspace.resolve("collections/literal.json"), """
                {"info":{"name":"Literal","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"Typed","request":{"method":"GET","url":"http://127.0.0.1:1/x",
                   "auth":{"type":"bearer","bearer":[{"key":"token","value":"typed-inline"}]}}}]}
                """);

        JsonNode effective = json(request("GET",
                "/api/auth/sources?collection=collections%2Fliteral.json&index=0", null)).path("effective");

        assertEquals("request", effective.path("fields").get(0).path("source").asText());
        assertTrue(effective.path("summary").asText().contains("This request"));
    }

    @Test void authSourcesFallBackToEnvWhenTheRequestLeavesTheFieldBlank() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=env-token\n");
        Files.writeString(workspace.resolve("collections/blank.json"), """
                {"info":{"name":"Blank","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"Open","request":{"method":"GET","url":"http://127.0.0.1:1/x",
                   "auth":{"type":"bearer","bearer":[{"key":"token","value":""}]}}}]}
                """);

        JsonNode effective = json(request("GET",
                "/api/auth/sources?collection=collections%2Fblank.json&index=0", null)).path("effective");
        JsonNode field = effective.path("fields").get(0);

        assertEquals("dotenv", field.path("source").asText());
        assertEquals("BEARER_TOKEN", field.path("key").asText());
    }

    @Test void authSourcesReportNoAuthRequestsAsSendingNothing() throws Exception {
        JsonNode result = json(request("GET",
                "/api/auth/sources?collection=collections%2Flocal.json&index=0", null));

        assertEquals("noauth", result.path("effective").path("type").asText());
        assertEquals(0, result.path("effective").path("fields").size());
        assertTrue(result.path("effective").path("summary").asText().contains("no Authorization header"));
    }

    @Test void authSourcesNeverLeakValuesOnlyMaskedPreviews() throws Exception {
        Files.writeString(workspace.resolve(".env"), "BEARER_TOKEN=super-secret-env-token\n");
        request("PUT", "/api/auth/credentials", Map.of("credentials", List.of(
                Map.of("name", "VAULT_TOKEN", "type", "bearer", "values", Map.of("token", "super-secret-vault-token")))));
        // A request that carries the secret inline must not have it echoed back either.
        Files.writeString(workspace.resolve("collections/inline.json"), """
                {"info":{"name":"Inline","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"Typed","request":{"method":"GET","url":"http://127.0.0.1:1/x",
                   "auth":{"type":"bearer","bearer":[{"key":"token","value":"super-secret-inline-token"}]}}}]}
                """);

        HttpResponse<String> ambient = request("GET",
                "/api/auth/sources?collection=collections%2Flocal.json&index=0", null);
        HttpResponse<String> inline = request("GET",
                "/api/auth/sources?collection=collections%2Finline.json&index=0", null);

        assertEquals(200, ambient.statusCode());
        for (String body : List.of(ambient.body(), inline.body())) {
            assertFalse(body.contains("super-secret-env-token"),
                    "An ambient value must never reach the browser in clear text");
            assertFalse(body.contains("super-secret-vault-token"),
                    "A vault value must never reach the browser in clear text");
            assertFalse(body.contains("super-secret-inline-token"),
                    "A request's own inline value must never reach the browser in clear text");
            assertTrue(body.contains("preview"), "Rows carry a masked preview instead of the value");
        }
    }

    private JsonNode sourceById(JsonNode sources, String id) {
        for (JsonNode group : sources) {
            if (id.equals(group.path("id").asText())) return group;
        }
        fail("No source named " + id + " in " + sources);
        return null;
    }

    private Map<String, String> reportBody() {
        return Map.of("collection", "collections/local.json", "filename", "editor.filter", "source", """
                REQUESTS "List items";
                $ITEMS = FILTER "List items" WHERE id > 0;
                SUMMARY {
                  TITLE "Local report" COLOR "#245C50";
                  METRIC "Items" = $ITEMS;
                  PARAGRAPH IF $ITEMS = 1 THEN "One item matches the filter."
                    ELSE $ITEMS + " items match the filter.";
                  TABLE $ITEMS TITLE "Items";
                  STATUS;
                }
                """);
    }

    private JsonNode awaitRun(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode result;
        do {
            result = json(request("GET", "/api/run?id=" + id, null));
            if (!Set.of("queued", "running").contains(result.path("status").asText())) return result;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("Run did not complete: " + result);
        return result;
    }

    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("X-Workspace-Token", token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
}
