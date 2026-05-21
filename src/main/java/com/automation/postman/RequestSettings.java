package com.automation.postman;

import java.util.Map;

public record RequestSettings(
        Integer timeoutMillis,
        Boolean followRedirects,
        Boolean disableUrlEncoding,
        Boolean strictSsl,
        Map<String, String> protocolProfileBehavior
) {
    public RequestSettings {
        protocolProfileBehavior = protocolProfileBehavior == null
                ? Map.of()
                : Map.copyOf(protocolProfileBehavior);
    }
}