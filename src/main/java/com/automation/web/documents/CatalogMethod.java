package com.automation.web.documents;

public record CatalogMethod(
        String name,
        String description,
        String linkedRequest,
        String inputMapping,
        String outputMapping,
        String returnType
) {}
