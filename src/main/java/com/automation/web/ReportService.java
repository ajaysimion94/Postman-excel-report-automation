package com.automation.web;

import com.automation.auth.CredentialLoader;
import com.automation.auth.VariableResolver;
import com.automation.cli.CommandLineOptions;
import com.automation.excel.ExcelReportGenerator;
import com.automation.filter.*;
import com.automation.http.RequestExecutor;
import com.automation.model.*;
import com.automation.postman.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

final class ReportService implements AutoCloseable {
    private final WorkspaceFiles files;
    private final Path envPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> runs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "report-worker"));

    private record Prepared(Path collectionPath, PostmanCollection collection, FilterSpec filter) {}

    ReportService(WorkspaceFiles files, Path envPath) throws IOException {
        this.files = files;
        this.envPath = envPath;
        try (var paths = Files.list(files.internalDirectory(".web-state"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                    .filter(p -> !Files.isSymbolicLink(p)).sorted(Comparator.reverseOrder()).limit(200).toList()) {
                try {
                    Map<String, Object> run = mapper.readValue(path.toFile(), new TypeReference<>() {});
                    if (Set.of("queued", "running").contains(run.get("status"))) {
                        run.put("status", "interrupted");
                        run.put("error", "The application stopped before this run finished. Run the report again.");
                    }
                    runs.put(run.get("id").toString(), Collections.unmodifiableMap(run));
                } catch (IOException | RuntimeException ignored) { /* An incomplete history entry must not block startup. */ }
            }
        }
    }

    Map<String, Object> collection(String name) throws IOException {
        Path path = collectionPath(name);
        PostmanCollection collection = new PostmanCollectionParser().parse(path);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int index = 0; index < collection.requests().size(); index++) {
            RequestSpec request = collection.requests().get(index);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("index", index);
            details.put("name", request.name());
            details.put("method", request.method());
            details.put("url", Objects.toString(request.url(), ""));
            details.put("folder", Objects.toString(request.folderPath(), ""));
            details.put("description", Objects.toString(request.description(), ""));
            details.put("disabled", request.disabled());
            details.put("body", Objects.toString(request.body(), ""));
            details.put("headers", request.headers().stream().map(header -> Map.of(
                    "key", Objects.toString(header.key(), ""), "value", Objects.toString(header.value(), ""))).toList());
            RequestUrlSpec urlSpec = request.urlSpec();
            details.put("params", urlSpec == null ? List.of() : urlSpec.query().stream().map(param -> Map.of(
                    "key", Objects.toString(param.key(), ""),
                    "value", Objects.toString(param.value(), ""),
                    "disabled", param.disabled())).toList());
            AuthDefinition auth = request.auth();
            details.put("auth", Map.of(
                    "type", auth == null || auth.type() == null ? "noauth" : auth.type(),
                    "values", auth == null || auth.values() == null ? Map.of() : auth.values()));
            RequestBodySpec bodySpec = request.bodySpec();
            String bodyMode = bodySpec == null || bodySpec.mode() == null || bodySpec.mode().isBlank()
                    ? (request.body() == null ? "none" : "raw") : bodySpec.mode().toLowerCase(Locale.ROOT);
            details.put("bodyMode", bodyMode);
            List<RequestBodyField> bodyFields = switch (bodyMode) {
                case "urlencoded" -> bodySpec == null ? List.of() : bodySpec.urlEncoded();
                case "formdata" -> bodySpec == null ? List.of() : bodySpec.formData();
                default -> List.of();
            };
            details.put("bodyFields", bodyFields.stream().map(field -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("key", Objects.toString(field.key(), ""));
                value.put("value", Objects.toString(field.value(), ""));
                value.put("type", Objects.toString(field.type(), "text"));
                value.put("source", Objects.toString(field.source(), ""));
                value.put("disabled", field.disabled());
                value.put("contentType", Objects.toString(field.contentType(), ""));
                return value;
            }).toList());
            requests.add(details);
        }
        return Map.of("name", collection.name(), "path", name, "variables", collection.variables(), "requests", requests);
    }

    Map<String, Object> testRequest(String collectionName, int requestIndex, String method, String url,
                                    List<RequestHeader> headers, String body, String bodyMode,
                                    List<RequestBodyField> bodyFields, AuthDefinition authOverride,
                                    Map<String, String> variableOverrides) throws IOException {
        Path path = collectionPath(collectionName);
        PostmanCollection collection = new PostmanCollectionParser().parse(path);
        if (requestIndex < 0 || requestIndex >= collection.requests().size()) {
            throw new WebException(400, "Choose a request from this collection.");
        }
        if (method == null || !method.matches("[A-Za-z]{1,20}")) {
            throw new WebException(400, "Use a valid HTTP method.");
        }
        if (url == null || url.isBlank() || url.length() > 8_000) {
            throw new WebException(400, "Enter a valid request URL.");
        }
        if (headers.size() > 100) throw new WebException(400, "Use no more than 100 request headers.");
        CommandLineOptions options = new CommandLineOptions(path, null, envPath, files.resolve("reports/api-test.xlsx"),
                true, null, false, null, null);
        RuntimeConfig config = CredentialLoader.load(options, null);
        Map<String, String> variables = new LinkedHashMap<>(collection.variables());
        variables.putAll(config.variables());
        variables.putAll(variableOverrides);
        List<RequestHeader> effectiveHeaders = new ArrayList<>(headers);
        String effectiveBody = renderApiBody(bodyMode, body, bodyFields, variables, effectiveHeaders);
        RequestSpec original = collection.requests().get(requestIndex);
        RequestSpec request = new RequestSpec(original.folderPath(), original.name(), method.toUpperCase(Locale.ROOT), url,
                List.copyOf(effectiveHeaders), effectiveBody, authOverride == null ? original.auth() : authOverride,
                original.description(), false, original.urlSpec(), original.bodySpec(), original.settings());
        int timeout = boundedInteger(config.variables().get("REQUEST_TIMEOUT_SECONDS"), 30, 1, 300);
        int responseMb = boundedInteger(config.variables().get("MAX_RESPONSE_MB"), 10, 1, 25);
        ExecutionResult result = new RequestExecutor(config.variables()).executeSingle(request, variables, Map.of(),
                timeout, responseMb * 1024 * 1024);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", result.requestName());
        response.put("method", result.method());
        response.put("url", result.url());
        response.put("statusCode", result.statusCode());
        response.put("durationMs", result.durationMillis());
        response.put("success", result.success());
        response.put("error", Objects.toString(result.errorMessage(), ""));
        response.put("body", Objects.toString(result.responseBody(), ""));
        response.put("executedAt", result.executedAt().toString());
        return response;
    }

    private static String renderApiBody(String requestedMode, String rawBody, List<RequestBodyField> fields,
                                        Map<String, String> variables, List<RequestHeader> headers) {
        String mode = requestedMode == null ? "raw" : requestedMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "none", "" -> null;
            case "raw" -> rawBody;
            case "urlencoded" -> {
                addHeaderIfMissing(headers, "Content-Type", "application/x-www-form-urlencoded");
                yield fields.stream().filter(field -> !field.disabled() && field.key() != null && !field.key().isBlank())
                        .map(field -> encodeFormValue(VariableResolver.resolve(field.key(), variables)) + "="
                                + encodeFormValue(VariableResolver.resolve(Objects.toString(field.value(), ""), variables)))
                        .collect(java.util.stream.Collectors.joining("&"));
            }
            case "formdata" -> {
                if (fields.stream().anyMatch(field -> !field.disabled() && "file".equalsIgnoreCase(field.type()))) {
                    throw new WebException(400, "File uploads in form-data are not supported by the local API client yet. Use a text field or raw body.");
                }
                String boundary = "ReportStudioBoundary" + UUID.randomUUID().toString().replace("-", "");
                addHeaderIfMissing(headers, "Content-Type", "multipart/form-data; boundary=" + boundary);
                StringBuilder multipart = new StringBuilder();
                for (RequestBodyField field : fields) {
                    if (field.disabled() || field.key() == null || field.key().isBlank()) continue;
                    String key = VariableResolver.resolve(field.key(), variables).replace("\"", "").replace("\r", "").replace("\n", "");
                    String value = VariableResolver.resolve(Objects.toString(field.value(), ""), variables);
                    multipart.append("--").append(boundary).append("\r\n")
                            .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                            .append(value).append("\r\n");
                }
                multipart.append("--").append(boundary).append("--\r\n");
                yield multipart.toString();
            }
            default -> throw new WebException(400, "Unsupported request body mode: " + requestedMode + ".");
        };
    }

    private static String encodeFormValue(String value) {
        return java.net.URLEncoder.encode(Objects.toString(value, ""), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void addHeaderIfMissing(List<RequestHeader> headers, String key, String value) {
        if (headers.stream().noneMatch(header -> key.equalsIgnoreCase(header.key()))) {
            headers.add(new RequestHeader(key, value));
        }
    }

    private static int boundedInteger(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isBlank()) return fallback;
        try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    Map<String, Object> validate(String collectionName, String source, String filename, String collectionSource) throws IOException {
        Prepared prepared = prepare(collectionName, source, filename, collectionSource);
        return Map.of("valid", true, "collection", prepared.collection().name(), "requests", prepared.collection().requests().size(),
                "summaryBlocks", prepared.filter() == null || prepared.filter().summary() == null ? 0 : prepared.filter().summary().items().size(),
                "message", "The report definition is valid. Ready to run.");
    }

    synchronized Map<String, Object> start(String collectionName, String source, String filename, String collectionSource) throws IOException {
        Prepared prepared = prepare(collectionName, source, filename, collectionSource);
        return queue(prepared, filename);
    }

    /** Runs a saved report definition without opening its source in the editor. */
    synchronized Map<String, Object> startSavedFilter(String filterName, String requestedCollection) throws IOException {
        Path filterPath = files.resolve(filterName);
        if (!filterName.startsWith("filters/") || !filterName.endsWith(".filter") || !Files.isRegularFile(filterPath)) {
            throw new WebException(400, "Select a saved .filter file from Filters.");
        }
        if (Files.size(filterPath) > WorkspaceFiles.MAX_TEXT_BYTES) {
            throw new WebException(413, "Report definitions must be 5 MB or smaller.");
        }
        String source = Files.readString(filterPath);
        String collection = resolveSavedCollection(source, filterPath, requestedCollection);
        return queue(prepare(collection, source, filterName, null), filterName);
    }

    private Map<String, Object> queue(Prepared prepared, String filename) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", id);
        run.put("name", filename == null || filename.isBlank() ? "Collection report" : Path.of(filename).getFileName().toString());
        run.put("collection", prepared.collection().name());
        run.put("startedAt", Instant.now().toString());
        run.put("status", "queued");
        run.put("phase", "Waiting to run");
        run.put("total", prepared.collection().requests().size());
        run.put("completed", 0);
        run.put("requests", List.of());
        run.put("files", List.of());
        publish(id, run);
        try { worker.execute(() -> execute(id, prepared)); }
        catch (RejectedExecutionException e) {
            update(id, Map.of("status", "failed", "error", "The run queue is full. Try again after an active run finishes."));
            throw new WebException(429, "The run queue is full. Try again after an active run finishes.");
        }
        return runs.get(id);
    }

    private String resolveSavedCollection(String source, Path filterPath, String requestedCollection) throws IOException {
        FilterSpec filter = FilterQueryParser.parseSource(source, filterPath, null);
        String selector = filter.collection();
        if (selector != null && !selector.isBlank()) {
            List<String> matches = collectionCandidates(selector);
            if (matches.size() == 1) return matches.get(0);
            if (matches.isEmpty()) {
                throw new WebException(400, "The filter selects collection \"" + selector +
                        "\", but no matching .json file exists in Collections.");
            }
            throw new WebException(400, "The filter selects collection \"" + selector +
                    "\", but it matches multiple files. Rename a collection or select one in the editor.");
        }
        if (requestedCollection != null && !requestedCollection.isBlank()) {
            collectionPath(requestedCollection);
            return requestedCollection;
        }
        List<String> collections = files.list().stream()
                .filter(entry -> !entry.directory() && entry.path().startsWith("collections/") && entry.path().endsWith(".json"))
                .map(WorkspaceFiles.Entry::path).sorted().toList();
        if (collections.size() == 1) return collections.get(0);
        if (collections.isEmpty()) throw new WebException(400, "Import a collection before running this filter.");
        throw new WebException(400, "This filter does not select a collection. Choose one in the editor, or add COLLECTION <name>; to the filter.");
    }

    private List<String> collectionCandidates(String selector) throws IOException {
        String desired = normalizeCollectionName(selector);
        List<String> matches = new ArrayList<>();
        for (WorkspaceFiles.Entry entry : files.list()) {
            if (entry.directory() || !entry.path().startsWith("collections/") || !entry.path().endsWith(".json")) continue;
            String filename = entry.name().replaceFirst("\\.json$", "");
            if (normalizeCollectionName(filename).equals(desired)) {
                matches.add(entry.path());
                continue;
            }
            try {
                PostmanCollection collection = new PostmanCollectionParser().parse(files.resolve(entry.path()));
                if (normalizeCollectionName(collection.name()).equals(desired)) matches.add(entry.path());
            } catch (Exception ignored) { /* A malformed unrelated collection cannot prevent a clear file-name match. */ }
        }
        return matches;
    }

    private static String normalizeCollectionName(String name) {
        return name == null ? "" : name.replaceFirst("\\.json$", "").trim().toLowerCase(Locale.ROOT);
    }

    List<Map<String, Object>> history() {
        return runs.values().stream().sorted(Comparator.comparing(r -> r.get("startedAt").toString(), Comparator.reverseOrder())).limit(100).toList();
    }

    Map<String, Object> get(String id) {
        Map<String, Object> run = runs.get(id);
        if (run == null) throw new WebException(404, "Run not found.");
        return run;
    }

    private void execute(String id, Prepared prepared) {
        try {
            update(id, Map.of("status", "running", "phase", "Executing collection requests"));
            String timestamp = Instant.now().toString().replace(':', '-').replace('.', '-');
            Path output = files.resolve("reports/" + timestamp + "-" + id.substring(0, 8) + ".xlsx");
            CommandLineOptions options = new CommandLineOptions(prepared.collectionPath(), null, envPath, output,
                    true, null, false, null, null);
            RuntimeConfig config = CredentialLoader.load(options, prepared.filter());
            RequestExecutor executor = new RequestExecutor(config.variables());
            List<Map<String, Object>> progress = new ArrayList<>();
            List<ExecutionResult> results = executor.execute(prepared.collection(), config, result -> {
                progress.add(requestResult(result));
                update(id, Map.of("completed", progress.size(), "requests", List.copyOf(progress)));
            });
            update(id, Map.of("phase", "Building Excel workbook"));
            List<Path> outputs = new ExcelReportGenerator().generate(prepared.collection(), results, config, executor);
            long passed = results.stream().filter(ExecutionResult::success).count();
            long failed = results.size() - passed;
            double average = results.stream().mapToLong(ExecutionResult::durationMillis).average().orElse(0);
            String sentence = results.isEmpty() ? "No requests were executed."
                    : failed == 0 ? (results.size() == 1 ? "The request completed successfully." : "All " + results.size() + " requests completed successfully.")
                    : passed + " of " + results.size() + " requests completed successfully. " + failed
                        + (failed == 1 ? " request needs attention." : " requests need attention.");
            update(id, Map.of("status", "completed", "phase", "Report ready", "finishedAt", Instant.now().toString(),
                    "files", outputs.stream().map(files::relative).toList(), "passed", passed, "failed", failed,
                    "averageMs", Math.round(average), "summary", sentence));
        } catch (Exception e) {
            update(id, Map.of("status", "failed", "phase", "Run failed", "finishedAt", Instant.now().toString(),
                    "error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    private static Map<String, Object> requestResult(ExecutionResult result) {
        return Map.of("name", result.requestName(), "method", result.method(), "statusCode", result.statusCode(),
                "durationMs", result.durationMillis(), "success", result.success(),
                "error", Objects.toString(result.errorMessage(), ""), "assertions", result.assertions() == null ? List.of() : result.assertions());
    }

    private Prepared prepare(String collectionName, String source, String filename, String collectionSource) throws IOException {
        Path path = collectionPath(collectionName);
        PostmanCollection collection;
        try {
            collection = collectionSource == null ? new PostmanCollectionParser().parse(path)
                    : new PostmanCollectionParser().parseSource(collectionSource, path.getFileName().toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new WebException(400, path.getFileName() + ":" + e.getLocation().getLineNr() + ":"
                    + e.getLocation().getColumnNr() + " Invalid collection JSON: " + e.getOriginalMessage());
        }
        FilterSpec spec = source == null || source.isBlank() ? null : FilterQueryParser.parseSource(source,
                Path.of(filename == null || filename.isBlank() ? "untitled.filter" : filename), path.getFileName().toString().replaceFirst("\\.json$", ""));
        if (spec != null) {
            FilterValidator.validate(spec, collection, path);
            if (spec.requests() != null && !spec.requests().isEmpty()) {
                collection = new PostmanCollection(collection.name(), collection.variables(), collection.requests().stream()
                        .filter(request -> spec.requests().contains(request.name())).toList());
            }
        }
        PostmanCompatibilityValidator.validate(collection);
        if (collection.requests().isEmpty()) throw new WebException(400, "Select a collection with at least one request.");
        return new Prepared(path, collection, spec);
    }

    void filesMoved(String from, String to) {
        for (Map<String, Object> run : history()) {
            Object rawFiles = run.get("files");
            if (!(rawFiles instanceof List<?> oldFiles)) continue;
            List<String> next = oldFiles.stream().map(Object::toString)
                    .map(path -> path.equals(from) || path.startsWith(from + "/") ? to + path.substring(from.length()) : path).toList();
            if (!next.equals(oldFiles)) update(run.get("id").toString(), Map.of("files", next));
        }
    }

    private Path collectionPath(String name) {
        Path path = files.resolve(name);
        if (!name.startsWith("collections/") || !name.endsWith(".json") || !Files.isRegularFile(path)) {
            throw new WebException(400, "Select a .json file from Collections.");
        }
        try {
            if (Files.size(path) > WorkspaceFiles.MAX_TEXT_BYTES) throw new WebException(413, "Collections must be 5 MB or smaller.");
        } catch (IOException e) { throw new WebException(400, "The collection could not be read."); }
        return path;
    }

    private void update(String id, Map<String, Object> changes) {
        Map<String, Object> next = new LinkedHashMap<>(runs.get(id));
        next.putAll(changes);
        publish(id, next);
    }

    private void publish(String id, Map<String, Object> run) {
        runs.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(run)));
        try {
            Path directory = files.internalDirectory(".web-state");
            Path temporary = Files.createTempFile(directory, ".run-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), run);
                Files.move(temporary, directory.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) {
            System.err.println("[WARN] Run history could not be saved: " + e.getMessage());
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
