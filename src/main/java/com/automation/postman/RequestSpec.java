package com.automation.postman;

import java.util.List;

public record RequestSpec(
        String folderPath,
        String name,
        String method,
        String url,
        List<RequestHeader> headers,
        String body,
        AuthDefinition auth
) {
}