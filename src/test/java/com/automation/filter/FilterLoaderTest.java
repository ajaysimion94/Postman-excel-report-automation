package com.automation.filter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterLoaderTest {

    @Test
    void loadsExplicitFilterByNameFromFiltersDir() throws Exception {
        Path dir = Files.createTempDirectory("filters");
        Path filter = dir.resolve("daily.filter");
        Files.writeString(filter, "REQUESTS \"Ping\";");

        FilterLoader.LoadedFilter loaded = FilterLoader.load(Path.of("daily"), dir.toString());

        assertNotNull(loaded);
        assertFalse(loaded.autoSelected());
        assertEquals(filter.toAbsolutePath(), loaded.path());
        assertEquals(1, loaded.spec().requests().size());
        assertEquals("Ping", loaded.spec().requests().get(0));
    }

    @Test
    void autoSelectsSingleFilterWhenNoFilterArgumentProvided() throws Exception {
        Path dir = Files.createTempDirectory("filters");
        Path filter = dir.resolve("only.filter");
        Files.writeString(filter, "OUTPUT_PREFIX daily;");

        FilterLoader.LoadedFilter loaded = FilterLoader.load(null, dir.toString());

        assertNotNull(loaded);
        assertTrue(loaded.autoSelected());
        assertEquals(filter.toAbsolutePath(), loaded.path());
        assertEquals("daily", loaded.spec().outputPrefix());
    }

    @Test
    void throwsWhenMultipleFiltersExistAndNoSelectionIsProvided() throws Exception {
        Path dir = Files.createTempDirectory("filters");
        Files.writeString(dir.resolve("a.filter"), "REQUESTS \"A\";");
        Files.writeString(dir.resolve("b.filter"), "REQUESTS \"B\";");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> FilterLoader.load(null, dir.toString()));

        assertTrue(error.getMessage().contains("Multiple filter files found"));
    }

    @Test
    void returnsNullWhenFiltersDirMissingOrEmpty() throws Exception {
        assertNull(FilterLoader.load(null, null));

        Path emptyDir = Files.createTempDirectory("filters");
        assertNull(FilterLoader.load(null, emptyDir.toString()));
    }

    @Test
    void loadsExplicitFilterFromFilterExtension() throws Exception {
        Path dir = Files.createTempDirectory("filters");
        Path filter = dir.resolve("daily.filter");
        Files.writeString(filter, "REQUESTS \"Ping\";");

        FilterLoader.LoadedFilter loaded = FilterLoader.load(Path.of("daily"), dir.toString());

        assertNotNull(loaded);
        assertEquals(filter.toAbsolutePath(), loaded.path());
        assertEquals(List.of("Ping"), loaded.spec().requests());
    }

    @Test
    void selectsCollectionBlockWhenPreferredSelectorProvided() throws Exception {
        Path dir = Files.createTempDirectory("filters");
        Path filter = dir.resolve("multi.filter");
        Files.writeString(filter, """
                COLLECTION posts;
                REQUESTS \"List posts\";
                COLLECTION users;
                REQUESTS \"List users\";
                """);

        FilterLoader.LoadedFilter loaded = FilterLoader.load(Path.of("multi"), dir.toString(), "users");

        assertNotNull(loaded);
        assertEquals("users", loaded.spec().collection());
        assertEquals(List.of("List users"), loaded.spec().requests());
    }
}
