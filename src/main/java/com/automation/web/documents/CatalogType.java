package com.automation.web.documents;

import java.util.List;

public record CatalogType(
        String id,
        String name,
        String kind,
        String extendsId,
        String description,
        String category,
        List<String> implementedBy,
        List<CatalogField> fields,
        List<CatalogMethod> methods,
        List<String> linkedCollections,
        List<String> linkedRequests
) {}
