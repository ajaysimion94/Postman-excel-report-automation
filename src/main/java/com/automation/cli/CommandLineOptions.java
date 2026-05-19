package com.automation.cli;

import java.nio.file.Path;

public record CommandLineOptions(
        Path collectionPath,
        String collectionName,
        Path envPath,
        Path outputPath,
        boolean includeBody,
        Path filterPath,
        boolean configMode,
        ConfigAction configAction,
        String configTargetUser
) {
}