package com.automation.postman;

public record RequestQueryParam(
        String key,
        String value,
        boolean disabled
) {
}