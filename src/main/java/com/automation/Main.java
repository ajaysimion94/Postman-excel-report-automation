package com.automation;

import com.automation.auth.CredentialLoader;
import com.automation.cli.CliCommand;
import com.automation.cli.CommandLineOptions;
import com.automation.cli.ConfigCommand;
import com.automation.excel.ExcelReportGenerator;
import com.automation.filter.FilterLoader;
import com.automation.filter.FilterSpec;
import com.automation.filter.FilterValidator;
import com.automation.http.RequestExecutor;
import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCompatibilityValidator;
import com.automation.postman.PostmanCollectionParser;
import com.automation.postman.RequestSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        CommandLineOptions options = CliCommand.parse(args);

        // --config mode: manage credential profiles, then exit
        if (options.configMode()) {
            ConfigCommand.run(options.configAction(), options.configTargetUser());
            return;
        }

        // Load filter before CredentialLoader so the outputPrefix can be applied
        FilterLoader.LoadedFilter loadedFilter = FilterLoader.load(
                options.filterPath(),
            readFiltersDir(options),
            preferredCollectionSelector(options));
        FilterSpec filterSpec = loadedFilter == null ? null : loadedFilter.spec();
        RuntimeConfig config = CredentialLoader.load(options, filterSpec);

        PostmanCollection collection = new PostmanCollectionParser().parse(config.collectionPath());

        if (filterSpec != null) {
            FilterValidator.validate(filterSpec, collection, config.collectionPath());
            collection = applyRequestFilter(collection, filterSpec);
            String mode = loadedFilter.autoSelected() ? "Auto-selected" : "Using";
            System.out.println(mode + " filter: " + loadedFilter.path().getFileName());
        }

        PostmanCompatibilityValidator.validate(collection);

        List<ExecutionResult> results = new RequestExecutor(config.variables()).execute(collection, config);
        RequestExecutor executor = new RequestExecutor(config.variables());
        List<Path> outputPaths = new ExcelReportGenerator().generate(collection, results, config, executor);
        if (outputPaths.size() == 1) {
            System.out.println("Excel report written to: " + outputPaths.get(0).toAbsolutePath());
        } else {
            System.out.println("Excel report split into " + outputPaths.size() + " files:");
            for (Path p : outputPaths) {
                System.out.println("  " + p.toAbsolutePath());
            }
        }
    }

    /** Applies the request whitelist from the filter; returns a new collection with only matching requests. */
    private static PostmanCollection applyRequestFilter(PostmanCollection collection, FilterSpec filter) {
        if (filter.requests() == null || filter.requests().isEmpty()) {
            return collection;
        }
        List<RequestSpec> filtered = collection.requests().stream()
                .filter(r -> filter.requests().contains(r.name()))
                .collect(Collectors.toList());
        return new PostmanCollection(collection.name(), collection.variables(), filtered);
    }

    /** Reads FILTERS_DIR from the variables already loaded via CredentialLoader's raw env/dotenv pass. */
    private static String readFiltersDir(CommandLineOptions options) {
        // Quick read from .env to resolve FILTERS_DIR without a full Dotenv initialisation
        java.nio.file.Path envPath = options.envPath();
        if (envPath != null && java.nio.file.Files.exists(envPath)) {
            try {
                for (String line : java.nio.file.Files.readAllLines(envPath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("FILTERS_DIR=")) {
                        return trimmed.substring("FILTERS_DIR=".length()).trim();
                    }
                }
            } catch (java.io.IOException ignored) {
            }
        }
        return System.getenv("FILTERS_DIR");
    }

    private static String preferredCollectionSelector(CommandLineOptions options) {
        if (options.collectionName() != null && !options.collectionName().isBlank()) {
            return options.collectionName();
        }
        if (options.collectionPath() != null) {
            String filename = options.collectionPath().getFileName().toString();
            return filename.replaceFirst("\\.json$", "");
        }
        return null;
    }
}