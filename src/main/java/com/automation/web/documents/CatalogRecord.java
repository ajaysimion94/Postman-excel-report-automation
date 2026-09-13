package com.automation.web.documents;

import com.fasterxml.jackson.databind.JsonNode;

public record CatalogRecord(String id, String name, String typeId, JsonNode values) {}
