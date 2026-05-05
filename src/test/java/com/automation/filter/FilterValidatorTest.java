package com.automation.filter;

import com.automation.postman.AuthDefinition;
import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterValidatorTest {

    @Test
    void rejectsUnknownRequestNames() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null,
                List.of("Unknown request"),
                null,
                null,
                null,
                null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsInvalidResponseColumnsKeys() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null,
                null,
                Map.of("Does not exist", List.of("id")),
                null,
                null,
                null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsPartialApiKeyAuth() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null,
                null,
                null,
                null,
                new FilterAuthSpec(null, null, null, "secret", null),
                null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void acceptsValidFilter() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                "demo",
                List.of("List users"),
                Map.of("*", List.of("id", "name")),
                "daily",
                new FilterAuthSpec("u", "p", null, null, null),
                Map.of("TEAM", "qa"));

        assertDoesNotThrow(() -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    private static PostmanCollection sampleCollection() {
        RequestSpec request = new RequestSpec(
                "Users",
                "List users",
                "GET",
                "https://example.com/users",
                List.of(),
                null,
                new AuthDefinition("noauth", Map.of()));
        return new PostmanCollection("Demo", Map.of(), List.of(request));
    }
}
