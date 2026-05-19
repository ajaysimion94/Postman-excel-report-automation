package com.automation.auth;

import com.automation.auth.config.CredentialStore;
import com.automation.cli.CommandLineOptions;
import com.automation.filter.FilterAuthSpec;
import com.automation.filter.FilterSpec;
import com.automation.model.RuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CredentialLoader {
    private CredentialLoader() {
    }

    public static RuntimeConfig load(CommandLineOptions options, FilterSpec filterSpec) {
        Map<String, String> variables = new HashMap<>();
        Path envPath = options.envPath();
        if (envPath != null && Files.exists(envPath)) {
            Path parent = envPath.toAbsolutePath().getParent();
            String directory = parent == null ? "." : parent.toString();
            Dotenv dotenv = Dotenv.configure()
                    .directory(directory)
                    .filename(envPath.getFileName().toString())
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();
            for (DotenvEntry entry : dotenv.entries()) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }

        System.getenv().forEach(variables::putIfAbsent);
        applyCredentialStore(variables);
        applyFilterOverrides(variables, filterSpec);

        // Resolve collection path: explicit --collection wins, then --collection-name + COLLECTIONS_DIR
        Path collectionPath = options.collectionPath();
        if (collectionPath == null) {
            String name = options.collectionName();
            if ((name == null || name.isBlank()) && filterSpec != null && filterSpec.collection() != null) {
                name = filterSpec.collection();
            }
            String dir  = variables.get("COLLECTIONS_DIR");
            if (dir == null || dir.isBlank()) {
                throw new IllegalStateException(
                        "COLLECTIONS_DIR is not set in .env. " +
                        "Set it or use --collection <absolute-path> instead.");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "No collection was provided. Use --collection-name <name>, --collection <path>, " +
                        "or set collection in your filter file.");
            }

            Path resolved = resolveCollectionPath(Path.of(dir), name);
            if (resolved == null || !Files.exists(resolved)) {
                throw new IllegalArgumentException(
                        "Collection not found for selector \"" + name + "\" in " + dir +
                        ". Run --list to see available collections.");
            }
            collectionPath = resolved;
        }

        Path outputPath = options.outputPath();
        if (outputPath == null) {
            String output = variables.getOrDefault("OUTPUT_FILE", "postman-report_{collection}_{timestamp}.xlsx");
            String collectionName = collectionPath.getFileName().toString().replaceFirst("\\.json$", "");
            String prefix = (filterSpec != null && filterSpec.outputPrefix() != null && !filterSpec.outputPrefix().isBlank())
                    ? filterSpec.outputPrefix() : null;
            outputPath = Path.of(resolveTemplate(output, collectionName, prefix));
        }

        return new RuntimeConfig(
                collectionPath.toAbsolutePath(),
                envPath == null ? null : envPath.toAbsolutePath(),
                outputPath.toAbsolutePath(),
                options.includeBody(),
                Map.copyOf(variables),
                filterSpec
        );
    }

    private static void applyCredentialStore(Map<String, String> variables) {
        try {
            CredentialStore store = CredentialStore.system();
            store.getActive().ifPresent(profile -> {
                putIfNotBlank(variables, "API_USERNAME", profile.apiUsername());
                putIfNotBlank(variables, "API_PASSWORD", profile.apiPassword());
                putIfNotBlank(variables, "BEARER_TOKEN", profile.bearerToken());
                putIfNotBlank(variables, "API_KEY",      profile.apiKey());
                putIfNotBlank(variables, "APIKEY_HEADER", profile.apiKeyHeader());
            });
        } catch (Exception e) {
            System.err.println("[WARN] Could not load credential store: " + e.getMessage());
        }
    }

    private static void applyFilterOverrides(Map<String, String> variables, FilterSpec filterSpec) {
        if (filterSpec == null) {
            return;
        }

        FilterAuthSpec auth = filterSpec.auth();
        if (auth != null) {
            putIfNotBlank(variables, "API_USERNAME", auth.username());
            putIfNotBlank(variables, "API_PASSWORD", auth.password());
            putIfNotBlank(variables, "BEARER_TOKEN", auth.bearerToken());
            putIfNotBlank(variables, "API_KEY", auth.apiKey());
            putIfNotBlank(variables, "APIKEY_HEADER", auth.apiKeyHeader());
        }

        if (filterSpec.vars() != null) {
            filterSpec.vars().forEach((key, value) -> putIfNotBlank(variables, key, value));
        }
    }

    private static void putIfNotBlank(Map<String, String> variables, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        variables.put(key, value);
    }

    private static Path resolveCollectionPath(Path collectionsDir, String selector) {
        // 1) File name / stem match first
        Path direct = collectionsDir.resolve(selector.endsWith(".json") ? selector : selector + ".json");
        if (Files.exists(direct)) {
            return direct;
        }

        // 2) Display-name match from collection info.name
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Path> matches = new ArrayList<>();
            try (var files = Files.list(collectionsDir)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                    try {
                        JsonNode root = mapper.readTree(path.toFile());
                        String displayName = root.path("info").path("name").asText("");
                        if (!displayName.isBlank() && displayName.equalsIgnoreCase(selector)) {
                            matches.add(path);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                        "Collection selector \"" + selector + "\" matched multiple files: " + matches);
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
        }
        return null;
    }

    private static String resolveTemplate(String template, String collectionName, String outputPrefix) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String withCollection = template.replace("{collection}", collectionName);
        String resolved;
        if (withCollection.contains("{timestamp}")) {
            resolved = withCollection.replace("{timestamp}", timestamp);
        } else {
            // Insert timestamp before the file extension
            int dot = withCollection.lastIndexOf('.');
            resolved = (dot >= 0)
                    ? withCollection.substring(0, dot) + "_" + timestamp + withCollection.substring(dot)
                    : withCollection + "_" + timestamp;
        }
        if (outputPrefix != null) {
            // Prepend prefix to the filename portion only
            int sep = Math.max(resolved.lastIndexOf('/'), resolved.lastIndexOf('\\'));
            if (sep >= 0) {
                resolved = resolved.substring(0, sep + 1) + outputPrefix + "_" + resolved.substring(sep + 1);
            } else {
                resolved = outputPrefix + "_" + resolved;
            }
        }
        return resolved;
    }
}