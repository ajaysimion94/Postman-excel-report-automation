package com.automation.http;

import com.automation.auth.VariableResolver;
import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.AuthDefinition;
import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestHeader;
import com.automation.postman.RequestSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class RequestExecutor {
    /** Default per-request read timeout in seconds (overridable via REQUEST_TIMEOUT_SECONDS in .env). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** Default maximum response body size in bytes before it is capped (overridable via MAX_RESPONSE_MB in .env). */
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final HttpClient httpClient;

    public RequestExecutor() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)).build());
    }

    public RequestExecutor(Map<String, String> variables) {
        this(buildHttpClient(variables));
    }

    RequestExecutor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private static HttpClient buildHttpClient(Map<String, String> variables) {
        int connectTimeout = parseIntVar(variables, "REQUEST_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL));

        String disableSsl         = variables.getOrDefault("DISABLE_SSL_VERIFY", "false").trim();
        String trustStorePath     = variables.get("SSL_TRUST_STORE");
        String trustStorePassword = variables.getOrDefault("SSL_TRUST_STORE_PASSWORD", "changeit");

        if ("true".equalsIgnoreCase(disableSsl)) {
            System.err.println("[WARN] DISABLE_SSL_VERIFY=true — SSL certificate validation is disabled. "
                    + "Do NOT use this in production against real APIs.");
            builder.sslContext(buildTrustAllSslContext());
        } else if (trustStorePath != null && !trustStorePath.isBlank()) {
            builder.sslContext(buildCustomTrustStoreSslContext(trustStorePath, trustStorePassword));
        }

        return builder.build();
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create trust-all SSL context: " + e.getMessage(), e);
        }
    }

    private static SSLContext buildCustomTrustStoreSslContext(String trustStorePath, String password) {
        Path path = Path.of(trustStorePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(
                    "SSL_TRUST_STORE file not found: " + path.toAbsolutePath()
                    + ". Fix the path in .env or remove SSL_TRUST_STORE to use the default JVM truststore.");
        }
        try (InputStream is = Files.newInputStream(path)) {
            String type = trustStorePath.toLowerCase().endsWith(".p12")
                    || trustStorePath.toLowerCase().endsWith(".pfx") ? "PKCS12" : "JKS";
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(is, password.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            System.out.println("SSL: using custom trust store: " + path.toAbsolutePath());
            return ctx;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load SSL_TRUST_STORE from " + path.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Executes a single request with an extra variable override map layered on top of the
     * collection and config variables. Used for lookup (nested) joins in custom tables —
     * the caller supplies the per-row parameter value (e.g., {@code {"id": "42"}}).
     *
     * @param request       the {@link RequestSpec} to execute
     * @param baseVariables merged collection + config variables
     * @param overrideVars  per-row variable overrides (e.g., the lookup param value)
     * @param timeoutSeconds per-request read timeout
     * @param maxResponseBytes response body cap
     * @return the {@link ExecutionResult} for this single execution
     */
    public ExecutionResult executeSingle(RequestSpec request,
                                        Map<String, String> baseVariables,
                                        Map<String, String> overrideVars,
                                        int timeoutSeconds,
                                        int maxResponseBytes) {
        return executeSingle(request, baseVariables, overrideVars, timeoutSeconds, maxResponseBytes, baseVariables);
    }

    /**
     * Executes a single request, separating workspace-level defaults from the request's own values.
     *
     * @param ambientVariables workspace defaults (credential store, vault, filter, .env). Used only
     *                         as a fallback for auth that the request itself leaves blank, so that
     *                         auth configured on a request is never silently overridden.
     */
    public ExecutionResult executeSingle(RequestSpec request,
                                        Map<String, String> baseVariables,
                                        Map<String, String> overrideVars,
                                        int timeoutSeconds,
                                        int maxResponseBytes,
                                        Map<String, String> ambientVariables) {
        Map<String, String> merged = new LinkedHashMap<>(baseVariables);
        merged.putAll(overrideVars);
        return executeRequest(request, merged, ambientVariables, false, timeoutSeconds, maxResponseBytes);
    }

    /**
     * Describes which layer supplied a request's credentials, without revealing any secret value.
     * Used by the UI to show whether auth came from the request itself or a workspace default.
     */
    public String describeAppliedAuth(RequestSpec request, Map<String, String> baseVariables,
                                      Map<String, String> overrideVariables, Map<String, String> ambientVariables) {
        Map<String, String> merged = new LinkedHashMap<>(baseVariables == null ? Map.of() : baseVariables);
        if (overrideVariables != null) {
            merged.putAll(overrideVariables);
        }
        return planAuth(request.auth(), merged, ambientVariables).description();
    }

    public List<ExecutionResult> execute(PostmanCollection collection, RuntimeConfig config) {
        return execute(collection, config, result -> {});
    }

    public List<ExecutionResult> execute(PostmanCollection collection, RuntimeConfig config,
                                         java.util.function.Consumer<ExecutionResult> onResult) {
        List<ExecutionResult> results = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>(collection.variables());
        variables.putAll(config.variables());

        int timeoutSeconds = parseIntVar(config.variables(), "REQUEST_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS);
        int maxResponseBytes = parseMbVar(config.variables(), "MAX_RESPONSE_MB", DEFAULT_MAX_RESPONSE_BYTES);

        for (RequestSpec request : collection.requests()) {
            ExecutionResult result = executeRequest(request, variables, config.variables(), config.includeResponseBody(),
                    timeoutSeconds, maxResponseBytes);
            results.add(result);
            onResult.accept(result);
        }
        return List.copyOf(results);
    }

    private ExecutionResult executeRequest(RequestSpec request, Map<String, String> variables,
                                           Map<String, String> ambientVariables, boolean includeResponseBody,
                                           int timeoutSeconds, int maxResponseBytes) {
        AuthPlan authPlan = planAuth(request.auth(), variables, ambientVariables);
        String resolvedUrl = appendApiKeyQueryParam(VariableResolver.resolve(request.url(), variables), authPlan);
        String resolvedBody = VariableResolver.resolve(request.body(), variables);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolvedUrl));

        for (RequestHeader header : request.headers()) {
            builder.header(header.key(), VariableResolver.resolve(header.value(), variables));
        }
        applyAuth(builder, authPlan);

        String method = request.method().toUpperCase();
        if (supportsBody(method) && resolvedBody != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(resolvedBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        Instant startedAt = Instant.now();
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.timeout(Duration.ofSeconds(timeoutSeconds)).build(),
                    HttpResponse.BodyHandlers.ofString());
            long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
            String rawBody = response.body() == null ? "" : response.body();
            String body = capBody(rawBody, maxResponseBytes, request.name());
            String displayBody = includeResponseBody ? truncate(body) : "";
            List<String> assertions = List.of("Status 2xx: " + (success ? "PASS" : "FAIL"));
            return new ExecutionResult(
                    request.folderPath(),
                    request.name(),
                    method,
                    resolvedUrl,
                    response.statusCode(),
                    durationMillis,
                    success,
                    "",
                    body,
                displayBody,
                    startedAt,
                    assertions
            );
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
            String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            return new ExecutionResult(
                    request.folderPath(),
                    request.name(),
                    method,
                    resolvedUrl,
                    0,
                    durationMillis,
                    false,
                    errorMsg,
                    "",
                    "",
                    startedAt,
                    List.of("Status 2xx: FAIL")
            );
        }
    }

    /** A fully resolved auth instruction, plus a secret-free note about where it came from. */
    private record AuthPlan(String headerName, String headerValue, String queryName, String queryValue, String description) {
        static AuthPlan none() {
            return new AuthPlan(null, null, null, null, "");
        }
    }

    /** One credential value and which layer supplied it. */
    private record ResolvedAuthValue(String value, boolean fromRequestAuth) {
    }

    /**
     * Resolves the auth for a request. Auth configured on the request itself (collection, folder,
     * request, or anything edited in the UI client) always wins. Workspace-level defaults — the
     * credential store, the UI vault, filter auth, .env, and system environment variables — are
     * only consulted for fields the request leaves blank.
     */
    private AuthPlan planAuth(AuthDefinition auth, Map<String, String> variables, Map<String, String> ambientVariables) {
        if (auth == null || auth.isNone()) {
            return AuthPlan.none();
        }

        String type = Objects.toString(auth.type(), "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "basic" -> {
                ResolvedAuthValue username = resolveAuthValue(auth, variables, ambientVariables, "username", "API_USERNAME", "USERNAME");
                ResolvedAuthValue password = resolveAuthValue(auth, variables, ambientVariables, "password", "API_PASSWORD", "PASSWORD");
                if (username.value().isBlank() && password.value().isBlank()) {
                    yield new AuthPlan(null, null, null, null, "Basic auth is set, but no credentials are configured.");
                }
                String encoded = Base64.getEncoder().encodeToString((username.value() + ":" + password.value()).getBytes());
                yield new AuthPlan("Authorization", "Basic " + encoded, null, null,
                        "Basic auth " + describeSource(username, password));
            }
            case "bearer" -> {
                ResolvedAuthValue token = resolveAuthValue(auth, variables, ambientVariables, "token", "BEARER_TOKEN", "TOKEN");
                if (token.value().isBlank()) {
                    yield new AuthPlan(null, null, null, null, "Bearer auth is set, but no token is configured.");
                }
                yield new AuthPlan("Authorization", "Bearer " + token.value(), null, null,
                        "Bearer token " + describeSource(token));
            }
            case "apikey" -> {
                ResolvedAuthValue keyName = resolveAuthValue(auth, variables, ambientVariables, "key", "APIKEY_HEADER");
                ResolvedAuthValue keyValue = resolveAuthValue(auth, variables, ambientVariables, "value", "API_KEY", "APIKEY");
                if (keyValue.value().isBlank()) {
                    yield new AuthPlan(null, null, null, null, "API key auth is set, but no key value is configured.");
                }
                String name = keyName.value().isBlank() ? "X-API-Key" : keyName.value();
                boolean inQuery = "query".equalsIgnoreCase(Objects.toString(auth.values() == null ? null
                        : auth.values().get("in"), "header"));
                String location = inQuery ? "query parameter \"" + name + "\"" : "header \"" + name + "\"";
                String description = "API key in " + location + " " + describeSource(keyValue);
                yield inQuery
                        ? new AuthPlan(null, null, name, keyValue.value(), description)
                        : new AuthPlan(name, keyValue.value(), null, null, description);
            }
            default -> {
                System.err.println("[WARN] Auth type \"" + type
                        + "\" is not supported by this runner. The request will be sent without authentication. "
                        + "Supported types: basic, bearer, apikey.");
                yield new AuthPlan(null, null, null, null,
                        "Auth type \"" + type + "\" is not supported. Supported types: basic, bearer, apikey.");
            }
        };
    }

    private static String describeSource(ResolvedAuthValue... values) {
        boolean fromRequest = false;
        boolean fromWorkspace = false;
        for (ResolvedAuthValue value : values) {
            if (value.value().isBlank()) {
                continue;
            }
            if (value.fromRequestAuth()) {
                fromRequest = true;
            } else {
                fromWorkspace = true;
            }
        }
        if (fromRequest && fromWorkspace) {
            return "from the request auth and a workspace default";
        }
        return fromRequest ? "from the request's own auth" : "from the workspace default (vault, store, filter, or .env)";
    }

    /**
     * Resolves a single credential value. A value configured on the request wins; workspace defaults
     * are only a fallback. An unresolved {@code {{VAR}}} template counts as "not configured" so the
     * fallback still applies instead of sending a literal placeholder to the server.
     */
    private ResolvedAuthValue resolveAuthValue(AuthDefinition auth, Map<String, String> variables,
                                               Map<String, String> ambientVariables,
                                               String authKey, String... fallbackKeys) {
        String configured = auth.values() == null ? null : auth.values().get(authKey);
        if (configured != null && !configured.isBlank()) {
            String resolved = VariableResolver.resolve(configured, variables);
            if (resolved != null && !resolved.isBlank() && !resolved.contains("{{")) {
                return new ResolvedAuthValue(resolved, true);
            }
        }

        if (ambientVariables != null) {
            for (String fallbackKey : fallbackKeys) {
                String value = ambientVariables.get(fallbackKey);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String resolved = VariableResolver.resolve(value, variables);
                if (resolved != null && !resolved.isBlank()) {
                    return new ResolvedAuthValue(resolved, false);
                }
            }
        }

        return new ResolvedAuthValue("", configured != null && !configured.isBlank());
    }

    private void applyAuth(HttpRequest.Builder builder, AuthPlan plan) {
        if (plan.headerName() != null) {
            builder.header(plan.headerName(), plan.headerValue());
        }
    }

    private String appendApiKeyQueryParam(String url, AuthPlan plan) {
        if (plan.queryName() == null || plan.queryName().isBlank() || plan.queryValue() == null) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator
                + java.net.URLEncoder.encode(plan.queryName(), java.nio.charset.StandardCharsets.UTF_8)
                + "="
                + java.net.URLEncoder.encode(plan.queryValue(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean supportsBody(String method) {
        return !("GET".equals(method) || "DELETE".equals(method));
    }

    /**
     * Caps the body at {@code maxBytes} chars (approximate; JSON is UTF-8 so char ≈ byte for ASCII).
     * Logs a warning so the user knows data was truncated for the data sheet.
     */
    private String capBody(String body, int maxBytes, String requestName) {
        if (body.length() <= maxBytes) {
            return body;
        }
        System.err.printf(
                "[WARN] Response for \"%s\" is %,d bytes — capped at %,d bytes for data sheet parsing. " +
                "Increase MAX_RESPONSE_MB in .env to raise the limit.%n",
                requestName, body.length(), maxBytes);
        // Find a safe cut point at the last '}' before the cap to keep valid JSON where possible
        int cutAt = body.lastIndexOf('}', maxBytes);
        if (cutAt <= 0) cutAt = maxBytes;
        return body.substring(0, cutAt + 1);
    }

    private static int parseIntVar(Map<String, String> vars, String key, int defaultValue) {
        String val = vars.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static int parseMbVar(Map<String, String> vars, String key, int defaultBytes) {
        String val = vars.get(key);
        if (val == null || val.isBlank()) return defaultBytes;
        try { return (int) (Double.parseDouble(val.trim()) * 1024 * 1024); } catch (NumberFormatException e) { return defaultBytes; }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000) + "...";
    }
}
