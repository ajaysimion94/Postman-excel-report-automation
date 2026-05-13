package com.automation.filter;

import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Valid row-filter operators accepted in filter JSON. */

public final class FilterValidator {

    private static final Set<String> VALID_OPERATORS = Set.of(
            "EQ", "NEQ", "GT", "GTE", "LT", "LTE",
            "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH",
            "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL", "IS_TRUE", "IS_FALSE",
            "REGEX", "DATE_PRESET", "DATE_RANGE"
    );

    private FilterValidator() {
    }

    /**
     * Validates a {@link FilterSpec} against a parsed {@link PostmanCollection}.
     *
     * <p>Checks performed:
     * <ul>
     *   <li>If {@code filter.collection()} is set, it must match the collection's name stem.</li>
     *   <li>Each name in {@code filter.requests()} is checked against the collection's request names;
     *       unknown names produce a warning (not an error) so typos are visible.</li>
     * </ul>
     */
    public static void validate(FilterSpec filter, PostmanCollection collection, Path collectionPath) {
        if (filter == null) {
            return;
        }

        if (filter.outputPrefix() != null && filter.outputPrefix().isBlank()) {
            throw new IllegalArgumentException("Filter outputPrefix cannot be blank.");
        }

        validateAuth(filter.auth());

        if (filter.vars() != null) {
            List<String> blankVarKeys = filter.vars().keySet().stream()
                    .filter(key -> key == null || key.isBlank())
                    .collect(Collectors.toList());
            if (!blankVarKeys.isEmpty()) {
                throw new IllegalArgumentException("Filter vars contains blank variable keys.");
            }
        }

        // Collection name check
        if (filter.collection() != null && !filter.collection().isBlank()) {
            String collectionStem = normalizeCollectionName(collection.name());
            String pathStem = collectionPath == null
                    ? ""
                    : normalizeCollectionName(collectionPath.getFileName().toString().replaceFirst("\\.json$", ""));
            String expected = normalizeCollectionName(filter.collection());
            if (!filter.collection().equalsIgnoreCase(collection.name())
                    && !expected.equals(collectionStem)
                    && !expected.equals(pathStem)) {
                throw new IllegalArgumentException(
                        "Filter targets collection \"" + filter.collection() +
                        "\" but the loaded collection is \"" + collection.name() + "\". " +
                        "Selected file: " + (collectionPath == null ? "<unknown>" : collectionPath.getFileName()) + ".");
            }
        }

        Set<String> available = collection.requests().stream()
                .map(RequestSpec::name)
                .collect(Collectors.toSet());

        // Request name check (strict)
        if (filter.requests() != null && !filter.requests().isEmpty()) {
            List<String> unknown = filter.requests().stream()
                    .filter(name -> !available.contains(name))
                    .collect(Collectors.toList());

            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter has unknown request names: " + unknown + ". Available requests: " + available);
            }
        }

        // Validate responseColumns keys map to requests or wildcard
        if (filter.responseColumns() != null && !filter.responseColumns().isEmpty()) {
            List<String> invalidKeys = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : filter.responseColumns().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidKeys.add(key);
                }
                List<String> columns = entry.getValue();
                if (columns == null || columns.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Filter responseColumns entry for \"" + key + "\" must contain at least one column.");
                }
                if (columns.stream().anyMatch(col -> col == null || col.isBlank())) {
                    throw new IllegalArgumentException(
                            "Filter responseColumns entry for \"" + key + "\" contains blank column names.");
                }
            }
            if (!invalidKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter responseColumns contains unknown request keys: " + invalidKeys +
                        ". Use request names from the collection or '*' wildcard.");
            }
        }

        // Validate rowFilters
        if (filter.rowFilters() != null && !filter.rowFilters().isEmpty()) {
            List<String> invalidRowFilterKeys = new ArrayList<>();
            for (Map.Entry<String, RowFilterGroup> entry : filter.rowFilters().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidRowFilterKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidRowFilterKeys.add(key);
                }
                validateRowFilterGroup(entry.getValue(), key);
            }
            if (!invalidRowFilterKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter rowFilters contains unknown request keys: " + invalidRowFilterKeys +
                        ". Use request names from the collection or '*' wildcard.");
            }
        }

        // Validate dateConfig
        if (filter.dateConfig() != null && !filter.dateConfig().isEmpty()) {
            List<String> invalidDateKeys = new ArrayList<>();
            for (Map.Entry<String, Map<String, DateFieldConfig>> entry : filter.dateConfig().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidDateKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidDateKeys.add(key);
                }
                if (entry.getValue() != null) {
                    for (Map.Entry<String, DateFieldConfig> fieldEntry : entry.getValue().entrySet()) {
                        DateFieldConfig cfg = fieldEntry.getValue();
                        if (cfg != null && cfg.format() != null && !cfg.format().isBlank()) {
                            try {
                                DateTimeFormatter.ofPattern(cfg.format());
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException(
                                        "Filter dateConfig for request \"" + key + "\", field \"" +
                                        fieldEntry.getKey() + "\" has invalid format pattern \"" +
                                        cfg.format() + "\": " + e.getMessage());
                            }
                        }
                        if (cfg != null && cfg.timezone() != null && !cfg.timezone().isBlank()) {
                            try {
                                java.time.ZoneId.of(cfg.timezone());
                            } catch (java.time.zone.ZoneRulesException e) {
                                throw new IllegalArgumentException(
                                        "Filter dateConfig for request \"" + key + "\", field \"" +
                                        fieldEntry.getKey() + "\" has unknown timezone \"" +
                                        cfg.timezone() + "\".");
                            }
                        }
                    }
                }
            }
            if (!invalidDateKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter dateConfig contains unknown request keys: " + invalidDateKeys +
                        ". Use request names from the collection or '*' wildcard.");
            }
        }

        // Validate customTables
        if (filter.customTables() != null) {
            Set<String> tableNames = new java.util.HashSet<>();
            for (int i = 0; i < filter.customTables().size(); i++) {
                CustomTableSpec table = filter.customTables().get(i);
                String tableLabel = "customTables[" + i + "]";
                if (table.name() == null || table.name().isBlank()) {
                    throw new IllegalArgumentException(tableLabel + " is missing a non-blank name.");
                }
                if (!tableNames.add(table.name())) {
                    throw new IllegalArgumentException(
                            "Filter customTables has duplicate table name: \"" + table.name() + "\".");
                }
                boolean hasSingleSource = table.sourceRequest() != null;
                boolean hasMultiSource  = table.sources() != null && !table.sources().isEmpty();
                if (!hasSingleSource && !hasMultiSource) {
                    throw new IllegalArgumentException(
                            tableLabel + " (\"" + table.name() + "\") must specify either sourceRequest or sources.");
                }
                if (hasSingleSource && hasMultiSource) {
                    throw new IllegalArgumentException(
                            tableLabel + " (\"" + table.name() + "\") cannot specify both sourceRequest and sources.");
                }
                if (hasSingleSource && !available.contains(table.sourceRequest())) {
                    throw new IllegalArgumentException(
                            tableLabel + " (\"" + table.name() + "\") sourceRequest \"" +
                            table.sourceRequest() + "\" is not in the collection. Available: " + available);
                }
                if (hasMultiSource) {
                    for (CustomTableJoinSource src : table.sources()) {
                        if (src.request() == null || src.request().isBlank()) {
                            throw new IllegalArgumentException(
                                    tableLabel + " (\"" + table.name() + "\") has a source with a blank request name.");
                        }
                        if (!available.contains(src.request())) {
                            throw new IllegalArgumentException(
                                    tableLabel + " (\"" + table.name() + "\") source request \"" +
                                    src.request() + "\" is not in the collection. Available: " + available);
                        }
                    }
                    if (table.joinOn() == null || table.joinOn().isEmpty()) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") with multiple sources must specify joinOn.");
                    }
                    for (CustomTableJoinCondition cond : table.joinOn()) {
                        if (isBlank(cond.leftField()) || isBlank(cond.rightField())) {
                            throw new IllegalArgumentException(
                                    tableLabel + " (\"" + table.name() + "\") joinOn condition is missing leftField or rightField.");
                        }
                    }
                }
                if (table.where() != null) {
                    validateRowFilterGroup(table.where(), tableLabel + ".where");
                }
            }
        }
    }

    private static void validateRowFilterGroup(RowFilterGroup group, String location) {
        if (group == null) return;
        if (group.logic() != null && !group.logic().isBlank()) {
            String logic = group.logic().toUpperCase();
            if (!"AND".equals(logic) && !"OR".equals(logic)) {
                throw new IllegalArgumentException(
                        "Row filter group at \"" + location + "\" has invalid logic \"" + group.logic() +
                        "\". Use \"AND\" or \"OR\".");
            }
        }
        if (group.rules() == null || group.rules().isEmpty()) {
            System.err.printf("[WARN] Row filter group at \"%s\" has no rules — group will not filter any rows.%n", location);
            return;
        }
        for (int i = 0; i < group.rules().size(); i++) {
            RowFilterRule rule = group.rules().get(i);
            String ruleLabel = location + ".rules[" + i + "]";
            if (rule.field() == null || rule.field().isBlank()) {
                throw new IllegalArgumentException("Row filter rule at \"" + ruleLabel + "\" is missing a field name.");
            }
            if (rule.op() == null || rule.op().isBlank()) {
                throw new IllegalArgumentException("Row filter rule at \"" + ruleLabel + "\" is missing an op (operator).");
            }
            String op = rule.op().toUpperCase();
            if (!VALID_OPERATORS.contains(op)) {
                throw new IllegalArgumentException(
                        "Row filter rule at \"" + ruleLabel + "\" has unknown operator \"" + rule.op() +
                        "\". Valid operators: " + VALID_OPERATORS);
            }
            if ("DATE_PRESET".equals(op)) {
                if (isBlank(rule.value())) {
                    throw new IllegalArgumentException(
                            "Row filter rule at \"" + ruleLabel + "\" with op DATE_PRESET requires a value (preset name).");
                }
                String preset = rule.value().toUpperCase();
                if (!DateWindowResolver.VALID_PRESETS.contains(preset)) {
                    throw new IllegalArgumentException(
                            "Row filter rule at \"" + ruleLabel + "\" references unknown date preset \"" +
                            rule.value() + "\". Valid presets: " + DateWindowResolver.VALID_PRESETS);
                }
            }
            if ("DATE_RANGE".equals(op)) {
                if (isBlank(rule.from()) && isBlank(rule.to())) {
                    throw new IllegalArgumentException(
                            "Row filter rule at \"" + ruleLabel + "\" with op DATE_RANGE requires at least one of from or to.");
                }
            }
        }
    }

    private static void validateAuth(FilterAuthSpec auth) {
        if (auth == null) {
            return;
        }
        if (isBlank(auth.apiKey()) ^ isBlank(auth.apiKeyHeader())) {
            throw new IllegalArgumentException(
                    "Filter auth requires both apiKey and apiKeyHeader when using API key authentication.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeCollectionName(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase()
                .replaceFirst("\\.json$", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
