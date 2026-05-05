package com.automation.filter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public final class FilterParser {
    private final ObjectMapper mapper = new ObjectMapper();

    private FilterParser() {
    }

    /**
     * Parses a filter JSON file into a {@link FilterSpec}. Missing fields are null.
     */
    public static FilterSpec parse(Path filterPath) throws IOException {
        return new FilterParser().mapper.readValue(filterPath.toFile(), FilterSpec.class);
    }
}
