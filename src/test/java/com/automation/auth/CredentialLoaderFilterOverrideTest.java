package com.automation.auth;

import com.automation.cli.CommandLineOptions;
import com.automation.filter.FilterAuthSpec;
import com.automation.filter.FilterSpec;
import com.automation.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialLoaderFilterOverrideTest {

    @Test
    void filterAuthAndVarsOverrideEnvValues() throws Exception {
        Path root = Files.createTempDirectory("automation");
        Path collections = root.resolve("collections");
        Files.createDirectories(collections);
        Files.writeString(collections.resolve("demo.json"), "{\"info\":{\"name\":\"Demo\"},\"item\":[]}");

        Path env = root.resolve(".env");
        Files.write(env, List.of(
                "COLLECTIONS_DIR=" + collections,
                "API_USERNAME=env-user",
                "API_PASSWORD=env-pass",
                "BEARER_TOKEN=env-token",
                "TEAM=env-team",
                "OUTPUT_FILE=" + root.resolve("reports/{collection}_{timestamp}.xlsx")
        ));

        CommandLineOptions options = new CommandLineOptions(null, "demo", env, null, false, null);
        FilterSpec filter = new FilterSpec(
                null, null, null,
                "daily",
                new FilterAuthSpec("filter-user", "filter-pass", "filter-token", null, null),
                Map.of("TEAM", "filter-team"),
                null, null, null);

        RuntimeConfig config = CredentialLoader.load(options, filter);

        assertEquals("filter-user", config.variables().get("API_USERNAME"));
        assertEquals("filter-pass", config.variables().get("API_PASSWORD"));
        assertEquals("filter-token", config.variables().get("BEARER_TOKEN"));
        assertEquals("filter-team", config.variables().get("TEAM"));
    }
}
