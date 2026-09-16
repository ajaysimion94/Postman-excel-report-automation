package com.automation.http;

import com.automation.postman.AuthDefinition;
import com.automation.postman.RequestSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in who wins when a request carries its own auth and the workspace also supplies a default.
 *
 * <p>Before this was separated, {@code .env} / filter variables were consulted first, so anything
 * configured on a request — including auth set in the UI client — was silently discarded whenever a
 * workspace-level credential existed. The request's own auth must win; workspace defaults are only a
 * fallback for fields the request leaves blank.</p>
 */
class RequestExecutorAuthPrecedenceTest {

    private static final Map<String, String> WORKSPACE_DEFAULT =
            Map.of("BEARER_TOKEN", "ENV_TOKEN", "API_KEY", "ENV_KEY", "API_USERNAME", "env-user",
                    "API_PASSWORD", "env-pass", "APIKEY_HEADER", "X-Env-Key");

    private RequestSpec bearerRequest(String token) {
        return request(new AuthDefinition("bearer", Map.of("token", token == null ? "" : token)));
    }

    private RequestSpec apiKeyRequest(String value) {
        return request(new AuthDefinition("apikey", Map.of("key", "X-Api-Key", "value", value, "in", "header")));
    }

    private RequestSpec apiKeyQueryRequest(String value) {
        return request(new AuthDefinition("apikey", Map.of("key", "api_key", "value", value, "in", "query")));
    }

    private RequestSpec basicRequest(String username, String password) {
        return request(new AuthDefinition("basic", Map.of("username", username, "password", password)));
    }

    private RequestSpec request(AuthDefinition auth) {
        return new RequestSpec("", "Test", "GET", "https://example.test/api", List.of(), "", auth,
                null, false, null, null, null);
    }

    private String describe(RequestSpec spec) {
        return new RequestExecutor().describeAppliedAuth(spec, Map.of(), Map.of(), WORKSPACE_DEFAULT);
    }

    // ---- Request auth wins -----------------------------------------------------------

    @Test
    void requestBearerTokenWinsOverWorkspaceDefault() {
        String applied = describe(bearerRequest("UI_TOKEN"));
        assertTrue(applied.contains("the request's own auth"), "Expected the request auth to be reported as the source: " + applied);
        assertFalse(applied.contains("ENV_TOKEN"), "The effective auth description must not name a workspace default");
    }

    @Test
    void requestApiKeyWinsOverWorkspaceDefault() {
        assertTrue(describe(apiKeyRequest("UI_KEY")).contains("the request's own auth"));
    }

    @Test
    void requestBasicAuthWinsOverWorkspaceDefault() {
        assertTrue(describe(basicRequest("ui-user", "ui-pass")).contains("the request's own auth"));
    }

    @Test
    void partialRequestAuthFallsBackOnlyForTheMissingField() {
        // Password comes from the workspace default; the username is the request's own.
        String applied = describe(basicRequest("ui-user", ""));
        assertTrue(applied.contains("the request auth and a workspace default"),
                "A partially configured request should draw the missing field from the workspace: " + applied);
    }

    // ---- Fallback still works --------------------------------------------------------

    @Test
    void blankRequestAuthFallsBackToWorkspaceDefault() {
        String applied = describe(bearerRequest(""));
        assertTrue(applied.contains("workspace default"), "A blank request auth should fall back: " + applied);
    }

    @Test
    void unresolvedTemplateFallsBackInsteadOfSendingALiteralPlaceholder() {
        // {{MISSING}} cannot be resolved, so the workspace default must be used rather than the raw string.
        String applied = describe(bearerRequest("{{MISSING}}"));
        assertTrue(applied.contains("workspace default"),
                "An unresolved template must not be sent as a literal credential: " + applied);
    }

    @Test
    void noAuthTypeIsReportedAsNothingApplied() {
        assertEquals("", describe(request(new AuthDefinition("noauth", Map.of()))));
    }

    @Test
    void blankRequestAndNoWorkspaceDefaultReportsMissingConfiguration() {
        String applied = new RequestExecutor().describeAppliedAuth(bearerRequest(""), Map.of(), Map.of(), Map.of());
        assertTrue(applied.contains("no token is configured"), "Expected a readable hint: " + applied);
    }

    // ---- Description never leaks the secret -----------------------------------------

    @Test
    void descriptionNeverContainsTheSecretValue() {
        String token = "SUPER_SECRET_TOKEN_VALUE";
        Map<String, String> workspace = new LinkedHashMap<>(WORKSPACE_DEFAULT);
        workspace.put("BEARER_TOKEN", token);

        assertFalse(describe(bearerRequest(token)).contains(token),
                "The auth description is shown in the UI and must never include the secret");
        assertFalse(describe(bearerRequest("")).contains(token),
                "The auth description must not print a workspace default secret");
    }

    @Test
    void apiKeyQueryAuthIsDescribedWithoutRevealingTheKey() {
        String applied = describe(apiKeyQueryRequest("SECRET_QUERY_KEY"));
        assertTrue(applied.contains("query parameter"), "Expected the query location to be named: " + applied);
        assertFalse(applied.contains("SECRET_QUERY_KEY"));
    }
}
