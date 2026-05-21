package com.automation.postman;

import java.util.List;

public record RequestSpec(
        String folderPath,
        String name,
        String method,
        String url,
        List<RequestHeader> headers,
        String body,
        AuthDefinition auth,
        String description,
        boolean disabled,
        RequestUrlSpec urlSpec,
        RequestBodySpec bodySpec,
        RequestSettings settings
) {
        public RequestSpec(
                        String folderPath,
                        String name,
                        String method,
                        String url,
                        List<RequestHeader> headers,
                        String body,
                        AuthDefinition auth
        ) {
                this(folderPath, name, method, url, headers, body, auth, null, false, null, null, null);
        }
}