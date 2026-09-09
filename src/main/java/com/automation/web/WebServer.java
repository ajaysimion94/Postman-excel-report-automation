package com.automation.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automation.postman.AuthDefinition;
import com.automation.postman.RequestBodyField;
import com.automation.postman.RequestHeader;
import com.sun.net.httpserver.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Same-origin local application; serves the existing Java engine and bundled, offline web UI. */
public final class WebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService connections = Executors.newFixedThreadPool(8);
    private final WorkspaceFiles files;
    private final ReportService reports;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String token = UUID.randomUUID().toString();

    public WebServer(Path workspace, Path env, int port) throws IOException {
        files = new WorkspaceFiles(workspace);
        reports = new ReportService(files, env);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(connections);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    public static void launch(String[] args) throws IOException {
        Path workspace = Path.of(".");
        Path env = null;
        int port = 8080;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--web")) continue;
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("Usage: java -jar target/postman-excel-runner-1.0.0.jar --web [--port 8080] [--workspace .] [--env .env]");
                return;
            }
            if (!Set.of("--workspace", "--env", "--port").contains(arg)) throw new IllegalArgumentException("Unknown web argument: " + arg);
            if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
            switch (arg) {
                case "--workspace" -> workspace = Path.of(args[i]);
                case "--env" -> env = Path.of(args[i]).toAbsolutePath();
                case "--port" -> port = Integer.parseInt(args[i]);
                default -> throw new IllegalArgumentException("Unknown web argument: " + arg);
            }
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Choose a port from 1 to 65535.");
        if (env == null) env = workspace.resolve(".env").toAbsolutePath();
        WebServer app = new WebServer(workspace, env, port);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        app.start();
        System.out.println("Report Studio: http://127.0.0.1:" + app.port());
        System.out.println("Workspace: " + workspace.toAbsolutePath().normalize());
        System.out.println("Press Ctrl+C to stop.");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            checkOrigin(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(exchange, path);
            else staticFile(exchange, path);
        } catch (WebException e) {
            json(exchange, e.status, Map.of("error", e.getMessage()));
        } catch (NoSuchFileException e) {
            json(exchange, 404, Map.of("error", "The requested file no longer exists."));
        } catch (FileAlreadyExistsException e) {
            json(exchange, 409, Map.of("error", "A file or folder already uses that name."));
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", Objects.toString(e.getMessage(), "The request is invalid.")));
        } catch (Exception e) {
            System.err.println("[WARN] Web request failed: " + e.getMessage());
            json(exchange, 500, Map.of("error", "The operation could not be completed. Check the application log for details."));
        } finally { exchange.close(); }
    }

    private void checkOrigin(HttpExchange exchange) {
        Set<String> hosts = Set.of("127.0.0.1:" + port(), "localhost:" + port());
        if (!hosts.contains(exchange.getRequestHeaders().getFirst("Host"))) throw new WebException(403, "Only local workspace requests are accepted.");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !hosts.stream().anyMatch(host -> origin.equals("http://" + host))) {
            throw new WebException(403, "Open Report Studio directly from its local address.");
        }
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equals(fetchSite)) throw new WebException(403, "Cross-site requests are not accepted.");
        if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())
                && !token.equals(exchange.getRequestHeaders().getFirst("X-Workspace-Token"))) {
            throw new WebException(403, "Your workspace session has expired. Refresh this page and try again.");
        }
    }

    private void api(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        Map<String, String> query = query(exchange.getRequestURI());
        switch (method + " " + path) {
            case "GET /api/session" -> json(exchange, 200, Map.of("token", token,
                    "workspace", files.root().getFileName().toString(), "root", files.root().toString()));
            case "GET /api/files" -> json(exchange, 200, files.list());
            case "GET /api/file" -> json(exchange, 200, files.read(query.get("path")));
            case "PUT /api/file" -> {
                JsonNode body = body(exchange);
                json(exchange, 200, files.save(required(body, "path"), required(body, "content"), body.path("revision").isTextual() ? body.get("revision").asText() : null));
            }
            case "POST /api/folder" -> {
                files.mkdir(required(body(exchange), "path"));
                json(exchange, 201, Map.of("message", "Folder created."));
            }
            case "POST /api/move" -> {
                JsonNode body = body(exchange);
                files.move(required(body, "from"), required(body, "to"));
                reports.filesMoved(required(body, "from"), required(body, "to"));
                json(exchange, 200, Map.of("message", "Moved successfully."));
            }
            case "POST /api/trash" -> json(exchange, 200, Map.of("recoveryPath", files.trash(required(body(exchange), "path"))));
            case "GET /api/collection" -> json(exchange, 200, reports.collection(query.get("path")));
            case "POST /api/request" -> {
                JsonNode body = body(exchange);
                if (!body.path("index").canConvertToInt()) throw new WebException(400, "Choose a request from this collection.");
                JsonNode headerNodes = body.path("headers");
                if (!headerNodes.isArray()) throw new WebException(400, "Request headers must be a list.");
                List<RequestHeader> requestHeaders = new ArrayList<>();
                for (JsonNode header : headerNodes) {
                    String key = required(header, "key").trim();
                    String value = required(header, "value");
                    if (key.isEmpty()) continue;
                    if (key.length() > 200 || value.length() > 16_000 || key.contains("\r") || key.contains("\n")
                            || value.contains("\r") || value.contains("\n")) {
                        throw new WebException(400, "A request header is too long or contains a line break.");
                    }
                    requestHeaders.add(new RequestHeader(key, value));
                }
                String requestBody = body.path("body").isTextual() ? body.get("body").asText() : "";
                String bodyMode = body.path("bodyMode").asText("raw");
                List<RequestBodyField> bodyFields = new ArrayList<>();
                JsonNode bodyFieldNodes = body.path("bodyFields");
                if (!bodyFieldNodes.isMissingNode() && !bodyFieldNodes.isArray()) {
                    throw new WebException(400, "Request body fields must be a list.");
                }
                for (JsonNode field : bodyFieldNodes) {
                    String key = field.path("key").asText("");
                    String value = field.path("value").asText("");
                    if (key.length() > 500 || value.length() > 100_000) {
                        throw new WebException(400, "A request body field is too long.");
                    }
                    bodyFields.add(new RequestBodyField(key, value, field.path("type").asText("text"),
                            field.path("source").asText(null), field.path("disabled").asBoolean(false),
                            field.path("contentType").asText(null)));
                }
                Map<String, String> variableOverrides = textMap(body.path("variables"), "Request variables", 200, 100_000);
                AuthDefinition authOverride = null;
                JsonNode authNode = body.path("auth");
                if (!authNode.isMissingNode() && !authNode.isNull()) {
                    if (!authNode.isObject()) throw new WebException(400, "Request auth must be an object.");
                    String authType = authNode.path("type").asText("noauth").toLowerCase(Locale.ROOT);
                    if (!Set.of("noauth", "basic", "bearer", "apikey").contains(authType)) {
                        throw new WebException(400, "Unsupported request auth type: " + authType + ".");
                    }
                    authOverride = new AuthDefinition(authType, textMap(authNode.path("values"), "Auth values", 20, 16_000));
                }
                json(exchange, 200, reports.testRequest(required(body, "collection"), body.get("index").asInt(),
                        required(body, "method"), required(body, "url"), requestHeaders, requestBody,
                        bodyMode, bodyFields, authOverride, variableOverrides));
            }
            case "POST /api/validate", "POST /api/runs" -> {
                JsonNode body = body(exchange);
                String collection = required(body, "collection");
                String source = required(body, "source");
                String filename = body.path("filename").asText("untitled.filter");
                String collectionSource = body.path("collectionSource").isTextual() ? body.get("collectionSource").asText() : null;
                String outputFile = body.path("outputFile").isTextual() ? body.get("outputFile").asText() : null;
                json(exchange, path.endsWith("validate") ? 200 : 202, path.endsWith("validate")
                        ? reports.validate(collection, source, filename, collectionSource) : reports.start(collection, source, filename, collectionSource, outputFile));
            }
            case "POST /api/runs/saved-filter" -> {
                JsonNode body = body(exchange);
                String requestedCollection = body.path("collection").isTextual() ? body.get("collection").asText() : null;
                String outputFile = body.path("outputFile").isTextual() ? body.get("outputFile").asText() : null;
                json(exchange, 202, reports.startSavedFilter(required(body, "filter"), requestedCollection, outputFile));
            }
            case "GET /api/runs" -> json(exchange, 200, reports.history());
            case "GET /api/run" -> json(exchange, 200, reports.get(query.get("id")));
            case "GET /api/workbook" -> json(exchange, 200, WorkbookPreview.read(reportPath(query.get("path")),
                    number(query, "sheet", 0), number(query, "offset", 0), number(query, "limit", 200)));
            case "GET /api/download" -> {
                Path file = reportPath(query.get("path"));
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20"));
                exchange.sendResponseHeaders(200, Files.size(file));
                Files.copy(file, exchange.getResponseBody());
            }
            default -> throw new WebException(404, "This operation is not available.");
        }
    }

    private Path reportPath(String name) throws IOException {
        Path path = files.resolve(name);
        if (!name.startsWith("reports/") || !name.endsWith(".xlsx") || !Files.isRegularFile(path)) throw new WebException(404, "Report not found.");
        if (Files.size(path) > 100 * 1024 * 1024) throw new WebException(413, "This workbook is larger than the 100 MB web preview limit. Open it from the reports folder.");
        return path;
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) throw new WebException(405, "Use GET to load this page.");
        String resource = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.js" -> "app.js";
            case "/guided-workflow.js" -> "guided-workflow.js";
            case "/styles.css" -> "styles.css";
            default -> throw new WebException(404, "Page not found.");
        };
        try (var input = WebServer.class.getResourceAsStream("/web/" + resource)) {
            if (input == null) throw new WebException(404, "Web resources are missing. Rebuild the application.");
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", resource.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : resource.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        if (!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new WebException(415, "Send application/json content.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(WorkspaceFiles.MAX_TEXT_BYTES * 2 + 1);
        if (bytes.length > WorkspaceFiles.MAX_TEXT_BYTES * 2) throw new WebException(413, "The request is too large.");
        try {
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) throw new WebException(400, "Send a JSON object.");
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new WebException(400, "The JSON could not be read."); }
    }

    private static String required(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new WebException(400, "Missing text field: " + key);
        return node.get(key).asText();
    }

    private static Map<String, String> textMap(JsonNode node, String label, int maximumEntries, int maximumValueLength) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) throw new WebException(400, label + " must be an object.");
        if (node.size() > maximumEntries) throw new WebException(400, label + " has too many entries.");
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isValueNode()) throw new WebException(400, label + " values must be text.");
            String key = entry.getKey().trim();
            String value = entry.getValue().asText();
            if (key.isEmpty() || key.length() > 200 || value.length() > maximumValueLength
                    || key.contains("\r") || key.contains("\n")) {
                throw new WebException(400, label + " contains an invalid key or oversized value.");
            }
            values.put(key, value);
        });
        return Map.copyOf(values);
    }

    private static int number(Map<String, String> query, String key, int fallback) {
        return query.containsKey(key) ? Integer.parseInt(query.get(key)) : fallback;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        server.stop(1);
        reports.close();
        connections.shutdownNow();
    }
}
