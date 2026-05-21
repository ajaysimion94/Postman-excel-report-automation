package com.automation.postman;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PostmanCompatibilityValidator {
    private static final Set<String> SUPPORTED_AUTH_TYPES = Set.of("basic", "bearer", "apikey", "noauth");
    private static final Set<String> SUPPORTED_BODY_MODES = Set.of("", "raw", "urlencoded", "formdata");

    private PostmanCompatibilityValidator() {
    }

    public static void validate(PostmanCollection collection) {
        List<String> incompatibilities = new ArrayList<>();
        for (RequestSpec request : collection.requests()) {
            validateAuth(request, incompatibilities);
            validateBody(request, incompatibilities);
            validateSettings(request, incompatibilities);
        }

        if (!incompatibilities.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected requests use Postman features that this runner does not execute yet:\n - "
                            + String.join("\n - ", incompatibilities));
        }
    }

    private static void validateAuth(RequestSpec request, List<String> incompatibilities) {
        AuthDefinition auth = request.auth();
        if (auth == null || auth.isNone()) {
            return;
        }

        String normalizedType = normalize(auth.type());
        if (!SUPPORTED_AUTH_TYPES.contains(normalizedType)) {
            incompatibilities.add(requestLabel(request)
                    + " uses unsupported auth type \"" + auth.type() + "\".");
        }
    }

    private static void validateBody(RequestSpec request, List<String> incompatibilities) {
        RequestBodySpec bodySpec = request.bodySpec();
        if (bodySpec == null) {
            return;
        }

        String normalizedMode = normalize(bodySpec.mode());
        if (!SUPPORTED_BODY_MODES.contains(normalizedMode)) {
            incompatibilities.add(requestLabel(request)
                    + " uses unsupported body mode \"" + bodySpec.mode() + "\".");
        }
    }

    private static void validateSettings(RequestSpec request, List<String> incompatibilities) {
        RequestSettings settings = request.settings();
        if (settings == null) {
            return;
        }

        LinkedHashSet<String> settingNames = new LinkedHashSet<>();
        if (settings.timeoutMillis() != null) {
            settingNames.add("timeout");
        }
        if (settings.followRedirects() != null) {
            settingNames.add("followRedirects");
        }
        if (settings.disableUrlEncoding() != null) {
            settingNames.add("disableUrlEncoding");
        }
        if (settings.strictSsl() != null) {
            settingNames.add("strictSSL");
        }
        settingNames.addAll(settings.protocolProfileBehavior().keySet());

        if (!settingNames.isEmpty()) {
            incompatibilities.add(requestLabel(request)
                    + " uses request-level settings that are not executed yet: "
                    + String.join(", ", settingNames) + ".");
        }
    }

    private static String requestLabel(RequestSpec request) {
        return "Request \"" + request.name() + "\"";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}