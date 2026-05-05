package com.automation.filter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class FilterLoader {
    public record LoadedFilter(Path path, FilterSpec spec, boolean autoSelected) {
    }

    private FilterLoader() {
    }

    /**
     * Resolves and loads a {@link FilterSpec}, or returns {@code null} if no filter was requested.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code filterArg} is an absolute path or an existing relative path, use it directly.</li>
     *   <li>Otherwise, look for {@code <name>.json} in the directory given by the {@code FILTERS_DIR}
     *       environment / .env variable (read from the raw env map passed in).</li>
     * </ol>
     *
     * @param filterArg  value of {@code --filter} (name or path), or {@code null} if not supplied
     * @param filtersDir value of {@code FILTERS_DIR} from .env, or {@code null}
     */
    public static LoadedFilter load(Path filterArg, String filtersDir) throws IOException {
        if (filterArg != null) {
            Path resolved = resolvePath(filterArg, filtersDir);
            return new LoadedFilter(resolved.toAbsolutePath(), FilterParser.parse(resolved), false);
        }

        // Default daily mode: auto-select only when there is exactly one filter file.
        if (filtersDir == null || filtersDir.isBlank()) {
            return null;
        }
        Path dir = Path.of(filtersDir);
        if (!Files.isDirectory(dir)) {
            return null;
        }

        List<Path> candidates = listFilterFiles(dir);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            String available = candidates.stream()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Multiple filter files found in FILTERS_DIR: " + available + ". " +
                    "Use --filter <name> to choose one, or run --list-filters.");
        }

        Path only = candidates.get(0);
        return new LoadedFilter(only.toAbsolutePath(), FilterParser.parse(only), true);
    }

    public static List<Path> listFilters(String filtersDir) throws IOException {
        if (filtersDir == null || filtersDir.isBlank()) {
            return List.of();
        }
        Path dir = Path.of(filtersDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        return listFilterFiles(dir).stream()
                .map(Path::toAbsolutePath)
                .collect(Collectors.toList());
    }

    private static List<Path> listFilterFiles(Path dir) throws IOException {
        try (var paths = Files.list(dir)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .collect(Collectors.toList());
        }
    }

    private static Path resolvePath(Path filterArg, String filtersDir) {
        if (filterArg.isAbsolute() || Files.exists(filterArg)) {
            if (!Files.exists(filterArg)) {
                throw new IllegalArgumentException("Filter file not found: " + filterArg.toAbsolutePath());
            }
            return filterArg;
        }
        if (filtersDir == null || filtersDir.isBlank()) {
            throw new IllegalArgumentException(
                    "FILTERS_DIR is not set in .env. " +
                    "Use an absolute/relative path for --filter, " +
                    "or set FILTERS_DIR to the directory that contains your filter files.");
        }
        String name = filterArg.toString();
        Path resolved = Path.of(filtersDir).resolve(name.endsWith(".json") ? name : name + ".json");
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Filter file not found: " + resolved.toAbsolutePath());
        }
        return resolved;
    }
}
