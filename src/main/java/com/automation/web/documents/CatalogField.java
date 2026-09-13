package com.automation.web.documents;

public record CatalogField(
        String name,
        String type,
        boolean required,
        String defaultValue,
        String visibility,
        String validationRules,
        String referenceTypeId,
        String arrayItemType
) {}
