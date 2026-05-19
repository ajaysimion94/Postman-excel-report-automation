package com.automation.filter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class FilterParser {
    private FilterParser() {
    }

    /**
     * Parses a `.filter` script into a {@link FilterSpec}.
     */
    public static FilterSpec parse(Path filterPath) throws IOException {
        return parse(filterPath, null);
    }

    public static FilterSpec parse(Path filterPath, String preferredCollectionSelector) throws IOException {
        String name = filterPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".filter")) {
            throw new IllegalArgumentException(
                    "Unsupported filter format: " + filterPath.getFileName() +
                            ". Use .filter files only.");
        }
        return FilterQueryParser.parse(filterPath, preferredCollectionSelector);
    }
}
