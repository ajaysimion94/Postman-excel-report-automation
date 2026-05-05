package com.automation;

import com.automation.auth.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableResolverTest {
    @Test
    void resolvesPlaceholdersFromVariables() {
        String resolved = VariableResolver.resolve(
                "https://{{host}}/users/{{id}}",
                Map.of("host", "api.example.com", "id", "42")
        );

        assertEquals("https://api.example.com/users/42", resolved);
    }
}