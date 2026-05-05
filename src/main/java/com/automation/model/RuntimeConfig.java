package com.automation.model;

import com.automation.filter.FilterSpec;

import java.nio.file.Path;
import java.util.Map;

public record RuntimeConfig(
        Path collectionPath,
        Path envPath,
        Path outputPath,
        boolean includeResponseBody,
        Map<String, String> variables,
        FilterSpec filterSpec
) {
}