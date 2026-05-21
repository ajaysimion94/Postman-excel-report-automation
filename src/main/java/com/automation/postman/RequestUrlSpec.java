package com.automation.postman;

import java.util.List;

public record RequestUrlSpec(
        String raw,
        String protocol,
        List<String> host,
        List<String> path,
        List<RequestQueryParam> query
) {
    public RequestUrlSpec {
        host = host == null ? List.of() : List.copyOf(host);
        path = path == null ? List.of() : List.copyOf(path);
        query = query == null ? List.of() : List.copyOf(query);
    }
}