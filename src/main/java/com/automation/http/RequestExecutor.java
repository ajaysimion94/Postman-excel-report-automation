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
import java.util.Map;
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
        Map<String, String> merged = new LinkedHashMap<>(baseVariables);
        merged.putAll(overrideVars);
        return executeRequest(request, merged, false, timeoutSeconds, maxResponseBytes);
    }

    public List<ExecutionResult> execute(PostmanCollection collection, RuntimeConfig config) {
        List<ExecutionResult> results = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>(collection.variables());
        variables.putAll(config.variables());

        int timeoutSeconds = parseIntVar(config.variables(), "REQUEST_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS);
        int maxResponseBytes = parseMbVar(config.variables(), "MAX_RESPONSE_MB", DEFAULT_MAX_RESPONSE_BYTES);

        for (RequestSpec request : collection.requests()) {
            results.add(executeRequest(request, variables, config.includeResponseBody(), timeoutSeconds, maxResponseBytes));
        }
        return List.copyOf(results);
    }

    private ExecutionResult executeRequest(RequestSpec request, Map<String, String> variables, boolean includeResponseBody, int timeoutSeconds, int maxResponseBytes) {
        String resolvedUrl = appendApiKeyQueryParam(VariableResolver.resolve(request.url(), variables), variables);
        String resolvedBody = VariableResolver.resolve(request.body(), variables);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolvedUrl));

        for (RequestHeader header : request.headers()) {
            builder.header(header.key(), VariableResolver.resolve(header.value(), variables));
        }
        applyAuth(builder, request.auth(), variables);

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

    private void applyAuth(HttpRequest.Builder builder, AuthDefinition auth, Map<String, String> variables) {
        if (auth == null || auth.isNone()) {
            return;
        }

        String type = auth.type().toLowerCase();
        switch (type) {
            case "basic" -> {
                String username = resolveAuthValue(auth, variables, "username", "API_USERNAME", "USERNAME");
                String password = resolveAuthValue(auth, variables, "password", "API_PASSWORD", "PASSWORD");
                String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                builder.header("Authorization", "Basic " + token);
            }
            case "bearer" -> {
                String token = resolveAuthValue(auth, variables, "token", "BEARER_TOKEN", "TOKEN");
                builder.header("Authorization", "Bearer " + token);
            }
            case "apikey" -> {
                String keyName  = resolveAuthValue(auth, variables, "key",   "APIKEY_HEADER", "X-API-Key");
                String keyValue = resolveAuthValue(auth, variables, "value", "API_KEY",        "APIKEY");
                String location = auth.values().getOrDefault("in", "header");
                if ("query".equalsIgnoreCase(location)) {
                    variables.put("__apikey_param_name__",  keyName);
                    variables.put("__apikey_param_value__", keyValue);
                } else {
                    builder.header(keyName, keyValue);
                }
            }
        }
    }

    private String appendApiKeyQueryParam(String url, Map<String, String> variables) {
        String paramName  = variables.remove("__apikey_param_name__");
        String paramValue = variables.remove("__apikey_param_value__");
        if (paramName == null || paramName.isBlank()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + paramName + "=" + paramValue;
    }

    private String resolveAuthValue(AuthDefinition auth, Map<String, String> variables, String authKey, String... fallbackKeys) {
        // .env / filter variables always win over hardcoded collection values
        for (String fallbackKey : fallbackKeys) {
            String value = variables.get(fallbackKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        // Fall back to collection auth block value (may be a {{VAR}} reference or a hardcoded literal)
        String directValue = auth.values().get(authKey);
        if (directValue != null && !directValue.isBlank()) {
            return VariableResolver.resolve(directValue, variables);
        }

        return "";
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