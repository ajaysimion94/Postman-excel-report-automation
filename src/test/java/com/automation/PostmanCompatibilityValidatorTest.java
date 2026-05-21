package com.automation;

import com.automation.postman.AuthDefinition;
import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCompatibilityValidator;
import com.automation.postman.RequestBodySpec;
import com.automation.postman.RequestSettings;
import com.automation.postman.RequestSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanCompatibilityValidatorTest {
    @Test
    void acceptsCurrentlySupportedRequestFeatures() {
        PostmanCollection collection = new PostmanCollection(
                "Supported Collection",
                Map.of(),
                List.of(new RequestSpec(
                        "",
                        "List Users",
                        "POST",
                        "https://example.com/users?page=1",
                        List.of(),
                        "page=1",
                        new AuthDefinition("bearer", Map.of("token", "{{BEARER_TOKEN}}")),
                        null,
                        false,
                        null,
                        new RequestBodySpec("urlencoded", null, List.of(), List.of(), null, null, null),
                        null
                ))
        );

        assertDoesNotThrow(() -> PostmanCompatibilityValidator.validate(collection));
    }

    @Test
    void rejectsUnsupportedAuthBodyModesAndSettings() {
        PostmanCollection collection = new PostmanCollection(
                "Unsupported Collection",
                Map.of(),
                List.of(new RequestSpec(
                        "",
                        "Search Users",
                        "POST",
                        "https://example.com/users/search",
                        List.of(),
                        null,
                        new AuthDefinition("oauth2", Map.of()),
                        null,
                        false,
                        null,
                        new RequestBodySpec("graphql", null, List.of(), List.of(), null, "query Users { users { id } }", "{}"),
                        new RequestSettings(1500, Boolean.FALSE, Boolean.TRUE, null, Map.of("followRedirects", "false"))
                ))
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PostmanCompatibilityValidator.validate(collection));

        assertTrue(error.getMessage().contains("unsupported auth type \"oauth2\""));
        assertTrue(error.getMessage().contains("unsupported body mode \"graphql\""));
        assertTrue(error.getMessage().contains("request-level settings that are not executed yet"));
    }
}