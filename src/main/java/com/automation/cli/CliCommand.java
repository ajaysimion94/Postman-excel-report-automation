package com.automation.cli;

import com.automation.filter.FilterLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class CliCommand {
    private CliCommand() {
    }

    public static CommandLineOptions parse(String[] args) {
        Path collectionPath = null;
        String collectionName = null;
        Path envPath = Path.of(".env");
        Path outputPath = null;
        boolean includeBody = false;
        boolean listCollections = false;
        boolean listFilters = false;
        Path filterPath = null;
        boolean configMode = false;
        ConfigAction configAction = null;
        String configTargetUser = null;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--collection"      -> collectionPath  = Path.of(nextValue(args, ++index, "--collection"));
                case "--collection-name" -> collectionName  = nextValue(args, ++index, "--collection-name");
                case "--env"             -> envPath         = Path.of(nextValue(args, ++index, "--env"));
                case "--output"          -> outputPath      = Path.of(nextValue(args, ++index, "--output"));
                case "--include-body"    -> includeBody     = true;
                case "--filter"          -> filterPath      = Path.of(nextValue(args, ++index, "--filter"));
                case "--list"            -> listCollections = true;
                case "--list-filters"    -> listFilters     = true;
                case "--help", "-h"      -> { printUsage(); System.exit(0); }
                case "--config" -> {
                    configMode   = true;
                    configAction = ConfigAction.ADD; // default — overridden by sub-flags below
                    if (index + 1 < args.length) {
                        switch (args[index + 1]) {
                            case "--show" -> {
                                configAction = ConfigAction.SHOW;
                                index++;
                            }
                            case "--switch" -> {
                                configAction   = ConfigAction.SWITCH;
                                index++;
                                configTargetUser = nextValue(args, ++index, "--switch");
                            }
                            case "--delete" -> {
                                configAction   = ConfigAction.DELETE;
                                index++;
                                configTargetUser = nextValue(args, ++index, "--delete");
                            }
                            default -> { /* no sub-flag — stays as ADD */ }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (listCollections) {
            listAvailableCollections(envPath);
            System.exit(0);
        }

        if (listFilters) {
            listAvailableFilters(envPath);
            System.exit(0);
        }

        // Collection can come from a selected filter in day-to-day usage.
        // Runtime validation happens in CredentialLoader when resolution is finalized.

        return new CommandLineOptions(collectionPath, collectionName, envPath, outputPath, includeBody, filterPath,
                configMode, configAction, configTargetUser);
    }

    private static void listAvailableCollections(Path envPath) {
        String collectionsDir = resolveCollectionsDir(envPath);
        if (collectionsDir == null) {
            System.out.println("COLLECTIONS_DIR is not set in " + envPath + ". Use --collection <path> instead.");
            return;
        }

        Path dir = Path.of(collectionsDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("COLLECTIONS_DIR does not exist or is not a directory: " + dir);
            return;
        }

        try {
            List<String> names = Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());

            if (names.isEmpty()) {
                System.out.println("No .json collection files found in: " + dir);
            } else {
                System.out.println("Collections in " + dir + ":");
                names.forEach(name -> System.out.println("  " + name));
            }
        } catch (IOException e) {
            System.out.println("Could not read COLLECTIONS_DIR: " + e.getMessage());
        }
    }

    private static void listAvailableFilters(Path envPath) {
        String filtersDir = resolveFiltersDir(envPath);
        if (filtersDir == null || filtersDir.isBlank()) {
            System.out.println("FILTERS_DIR is not set in " + envPath + ".");
            return;
        }
        try {
            List<Path> filters = FilterLoader.listFilters(filtersDir);
            if (filters.isEmpty()) {
                System.out.println("No .filter files found in: " + filtersDir);
                return;
            }
            System.out.println("Filters in " + filtersDir + ":");
            for (Path filter : filters) {
                System.out.println("  " + filter.getFileName());
            }
        } catch (IOException e) {
            System.out.println("Could not read FILTERS_DIR: " + e.getMessage());
        }
    }

    /** Reads COLLECTIONS_DIR directly from the .env file without full dotenv initialisation. */
    private static String resolveCollectionsDir(Path envPath) {
        return resolveFromEnvFile(envPath, "COLLECTIONS_DIR");
    }

    private static String resolveFiltersDir(Path envPath) {
        return resolveFromEnvFile(envPath, "FILTERS_DIR");
    }

    private static String resolveFromEnvFile(Path envPath, String key) {
        if (envPath == null || !Files.exists(envPath)) {
            return System.getenv(key);
        }
        try {
            for (String line : Files.readAllLines(envPath)) {
                String trimmed = line.trim();
                String prefix = key + "=";
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length()).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return System.getenv(key);
    }

    private static String nextValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar app.jar");
        System.out.println("  --web [--port 8080]       Open the local Report Studio web workspace");
        System.out.println("    --workspace <path>      Workspace containing collections/, filters/, reports/");
        System.out.println("  --collection <path>       Absolute path to a Postman collection JSON file");
        System.out.println("  --collection-name <name>  Collection filename to load from COLLECTIONS_DIR in .env");
        System.out.println("  --list                    List all .json collections in COLLECTIONS_DIR");
        System.out.println("  --list-filters            List all .filter files in FILTERS_DIR");
        System.out.println("  --env <path>              Path to .env file (default: .env)");
        System.out.println("  --output <path>           Path for the output .xlsx file");
        System.out.println("  --include-body            Include response body in the report");
        System.out.println("  --filter <name|path>      Filter file to apply (.filter; auto-select if exactly one exists)");
        System.out.println();
        System.out.println("Credential management:");
        System.out.println("  --config                  Add or update a credential profile (interactive)");
        System.out.println("  --config --show           List all configured profiles");
        System.out.println("  --config --switch <name>  Switch the active credential profile");
        System.out.println("  --config --delete <name>  Delete a credential profile");
    }
}
