package com.automation.postman;

import java.util.List;
import java.util.Map;

public record PostmanCollection(
        String name,
        Map<String, String> variables,
        List<RequestSpec> requests
) {
}