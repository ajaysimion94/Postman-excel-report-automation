package com.automation.postman;

import java.util.Map;

public record AuthDefinition(String type, Map<String, String> values) {
    public boolean isNone() {
        return type == null || type.isBlank() || "noauth".equalsIgnoreCase(type);
    }
}