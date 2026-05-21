package com.automation.postman;

public record RequestBodyField(
        String key,
        String value,
        String type,
        String source,
        boolean disabled,
        String contentType
) {
}