package com.automation.postman;

import java.util.List;

public record RequestBodySpec(
        String mode,
        String raw,
        List<RequestBodyField> urlEncoded,
        List<RequestBodyField> formData,
        String fileSource,
        String graphQlQuery,
        String graphQlVariables
) {
    public RequestBodySpec {
        urlEncoded = urlEncoded == null ? List.of() : List.copyOf(urlEncoded);
        formData = formData == null ? List.of() : List.copyOf(formData);
    }
}