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

/** Valid row-filter operators accepted in filter specs. */

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
            for (Map.Entry<String, List<ColumnSpec>> entry : filter.responseColumns().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidKeys.add(key);
                }
                List<ColumnSpec> columns = entry.getValue();
                if (columns == null || columns.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Filter responseColumns entry for \"" + key + "\" must contain at least one column.");
                }
                for (ColumnSpec col : columns) {
                    if (col == null || col.field() == null || col.field().isBlank()) {
                        throw new IllegalArgumentException(
                                "Filter responseColumns entry for \"" + key + "\" contains a blank column field.");
                    }
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
        Set<String> customTableNames = new java.util.HashSet<>();
        if (filter.customTables() != null) {
            for (int i = 0; i < filter.customTables().size(); i++) {
                CustomTableSpec table = filter.customTables().get(i);
                String tableLabel = "customTables[" + i + "]";
                if (table.name() == null || table.name().isBlank()) {
                    throw new IllegalArgumentException(tableLabel + " is missing a non-blank name.");
                }
                if (!customTableNames.add(table.name())) {
                    throw new IllegalArgumentException(
                            "Filter customTables has duplicate table name: \"" + table.name() + "\".");
                }
                boolean hasSingleSource = table.sourceRequest() != null;
                boolean hasMultiSource  = table.sources() != null && !table.sources().isEmpty();
                boolean hasLookup       = table.lookupRequest() != null;

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
                // Lookup validation
                if (hasLookup) {
                    if (!hasSingleSource) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") lookupRequest requires sourceRequest to be set.");
                    }
                    if (!available.contains(table.lookupRequest())) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") lookupRequest \"" +
                                table.lookupRequest() + "\" is not in the collection. Available: " + available);
                    }
                    if (table.lookupParam() == null || table.lookupParam().isBlank()) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") lookupRequest requires lookupParam to be set.");
                    }
                }
                if (!hasLookup && table.lookupParam() != null) {
                    throw new IllegalArgumentException(
                            tableLabel + " (\"" + table.name() + "\") lookupParam is set but lookupRequest is missing.");
                }
                if (hasMultiSource) {
                    String joinType = table.joinType() == null ? "INNER" : table.joinType().toUpperCase();
                    if (!Set.of("INNER", "LEFT", "RIGHT", "FULL").contains(joinType)) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") has unsupported joinType \"" + table.joinType() +
                                        "\". Use INNER, LEFT, RIGHT, or FULL.");
                    }
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
                    if (table.sources().size() > 2
                            && table.joinOn().size() != 1
                            && table.joinOn().size() != table.sources().size() - 1) {
                        throw new IllegalArgumentException(
                                tableLabel + " (\"" + table.name() + "\") with " + table.sources().size() +
                                        " sources requires joinOn size of 1 (reused per hop) or " +
                                        (table.sources().size() - 1) + " (one condition per hop).");
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

        Set<String> unionNames = new java.util.HashSet<>();
        if (filter.unions() != null) {
            for (int i = 0; i < filter.unions().size(); i++) {
                UnionSpec union = filter.unions().get(i);
                String unionLabel = "unions[" + i + "]";
                if (union.name() == null || union.name().isBlank()) {
                    throw new IllegalArgumentException(unionLabel + " must have a non-blank name.");
                }
                if (!unionNames.add(union.name())) {
                    throw new IllegalArgumentException("Filter unions has duplicate name: \"" + union.name() + "\".");
                }
                if (union.sources() == null || union.sources().size() < 2) {
                    throw new IllegalArgumentException(unionLabel + " (\"" + union.name() + "\") must include at least 2 sources.");
                }
                for (String source : union.sources()) {
                    if (source == null || source.isBlank()) {
                        throw new IllegalArgumentException(unionLabel + " (\"" + union.name() + "\") has a blank source entry.");
                    }
                    if (!available.contains(source)) {
                        throw new IllegalArgumentException(
                                unionLabel + " (\"" + union.name() + "\") source \"" + source +
                                        "\" is not in the collection. Available: " + available);
                    }
                }
            }
        }

        // Validate dataShapes
        if (filter.dataShapes() != null && !filter.dataShapes().isEmpty()) {
            List<String> invalidShapeKeys = new ArrayList<>();
            for (Map.Entry<String, DataShapeSpec> entry : filter.dataShapes().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidShapeKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key) && !customTableNames.contains(key)) {
                    if (!unionNames.contains(key)) {
                    invalidShapeKeys.add(key);
                    }
                }
                DataShapeSpec shape = entry.getValue();
                if (shape == null) {
                    continue;
                }
                if (shape.limit() != null && shape.limit() < 0) {
                    throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" has negative LIMIT.");
                }
                if (shape.offset() != null && shape.offset() < 0) {
                    throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" has negative OFFSET.");
                }
                if (shape.orderBy() != null) {
                    for (int idx = 0; idx < shape.orderBy().size(); idx++) {
                        SortSpec sort = shape.orderBy().get(idx);
                        if (sort == null || sort.field() == null || sort.field().isBlank()) {
                            throw new IllegalArgumentException(
                                    "Filter dataShapes for \"" + key + "\" has ORDER BY term with blank field at index " + idx + ".");
                        }
                    }
                }
                if (shape.groupBy() != null && shape.groupBy().stream().anyMatch(field -> field == null || field.isBlank())) {
                    throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" contains blank GROUP BY fields.");
                }
                if (shape.aggregates() != null) {
                    for (AggregateSpec aggregate : shape.aggregates()) {
                        if (aggregate == null || isBlank(aggregate.function())) {
                            throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" contains aggregate with blank function.");
                        }
                        String fn = aggregate.function().toUpperCase();
                        if (!Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(fn)) {
                            throw new IllegalArgumentException(
                                    "Filter dataShapes for \"" + key + "\" uses unsupported aggregate \"" + aggregate.function() + "\".");
                        }
                        if (isBlank(aggregate.alias())) {
                            throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" contains aggregate with blank alias.");
                        }
                        if (isBlank(aggregate.field())) {
                            throw new IllegalArgumentException("Filter dataShapes for \"" + key + "\" contains aggregate with blank field.");
                        }
                        if (!"COUNT".equals(fn) && "*".equals(aggregate.field())) {
                            throw new IllegalArgumentException(
                                    "Filter dataShapes for \"" + key + "\" can use '*' only with COUNT aggregate.");
                        }
                    }
                }
                if (shape.having() != null) {
                    validateRowFilterGroup(shape.having(), "dataShapes." + key + ".having");
                }
            }
            if (!invalidShapeKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter dataShapes contains unknown keys: " + invalidShapeKeys +
                                ". Use request names, custom table names, or '*' wildcard.");
            }
        }

        // Validate expands
        if (filter.expands() != null && !filter.expands().isEmpty()) {
            List<String> invalidExpandKeys = new ArrayList<>();
            for (Map.Entry<String, com.automation.filter.ExpandSpec> entry : filter.expands().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidExpandKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidExpandKeys.add(key);
                }
                com.automation.filter.ExpandSpec expandSpec = entry.getValue();
                if (expandSpec == null || expandSpec.field() == null || expandSpec.field().isBlank()) {
                    throw new IllegalArgumentException(
                            "Filter expands entry for \"" + key + "\" must specify a non-blank field name.");
                }
            }
            if (!invalidExpandKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter expands contains unknown request keys: " + invalidExpandKeys +
                        ". Use request names from the collection or '*' wildcard.");
            }
        }

        if (filter.summary() != null) {
            Set<String> definedQueries = filter.summary().queries().keySet();
            for (SummaryQuerySpec query : filter.summary().queries().values()) {
                if (query.variableName() == null || query.variableName().isBlank()) {
                    throw new IllegalArgumentException("Summary query variable name cannot be blank.");
                }
                if (query.source() instanceof SummaryQuerySource.FilterRows filterRows) {
                    if (filterRows.requestKey() == null || filterRows.requestKey().isBlank()) {
                        throw new IllegalArgumentException(
                                "Summary query $" + query.variableName() + " is missing a request name.");
                    }
                    if (!available.contains(filterRows.requestKey())) {
                        throw new IllegalArgumentException(
                                "Summary query $" + query.variableName() + " references unknown request \"" +
                                        filterRows.requestKey() + "\". Available: " + available);
                    }
                    if (filterRows.filter() != null) {
                        validateRowFilterGroup(filterRows.filter(), "summary.$" + query.variableName());
                    }
                } else if (query.source() instanceof SummaryQuerySource.NamedTable named) {
                    if (named.tableName() == null || named.tableName().isBlank()) {
                        throw new IllegalArgumentException(
                                "Summary query $" + query.variableName() + " is missing a table name.");
                    }
                    if (!customTableNames.contains(named.tableName())) {
                        throw new IllegalArgumentException(
                                "Summary query $" + query.variableName() + " references unknown table \"" +
                                        named.tableName() + "\". Define LOOKUP_TABLE \"" + named.tableName() + "\" first.");
                    }
                }
            }
            for (SummaryItem item : filter.summary().items()) {
                if (item instanceof SummaryItem.Table table) {
                    if (!definedQueries.contains(table.variableName())) {
                        throw new IllegalArgumentException(
                                "Summary TABLE $" + table.variableName() +
                                        " is not defined. Assign it with $name = FILTER ...;");
                    }
                } else if (item instanceof SummaryItem.KeyValue kv) {
                    validateSummaryTextParts(kv.valueParts(), definedQueries, "KV");
                } else if (item instanceof SummaryItem.LabelValue lv) {
                    validateSummaryTextParts(lv.valueParts(), definedQueries, "LV");
                } else if (item instanceof SummaryItem.Text txt) {
                    validateSummaryTextParts(txt.parts(), definedQueries, "TEXT");
                } else if (item instanceof SummaryItem.QuickTable qt) {
                    // Validate variable references in both classic and multi-column rows
                    for (SummaryItem.InlineTableRow row : qt.rows()) {
                        // Classic 2-column valueParts
                        validateSummaryTextParts(row.valueParts(), definedQueries, "QUICK_TABLE");
                        // Multi-column mode
                        if (row.columns() != null) {
                            for (List<SummaryTextPart> colParts : row.columns()) {
                                validateSummaryTextParts(colParts, definedQueries, "QUICK_TABLE");
                            }
                        }
                    }
                }
                // SummaryItem.Status needs no additional validation — it uses execution results directly
            }
        }
    }

    /** Recursively validates variable references in summary text parts, including IF/ELSE branches. */
    private static void validateSummaryTextParts(List<SummaryTextPart> parts, Set<String> definedQueries, String context) {
        if (parts == null) return;
        for (SummaryTextPart part : parts) {
            if (part instanceof SummaryTextPart.Variable var) {
                if (!definedQueries.contains(var.name())) {
                    throw new IllegalArgumentException(
                            "Summary " + context + " references undefined variable $" + var.name() + ".");
                }
            } else if (part instanceof SummaryTextPart.IfElse ifElse) {
                if (!definedQueries.contains(ifElse.variableName())) {
                    throw new IllegalArgumentException(
                            "Summary " + context + " IF condition references undefined variable $" + ifElse.variableName() + ".");
                }
                String op = ifElse.op();
                if (!Set.of("=", "==", "!=", "<>", ">", ">=", "<", "<=").contains(op)) {
                    throw new IllegalArgumentException(
                            "Summary " + context + " IF has unsupported operator \"" + op + "\". " +
                            "Supported: =, ==, !=, <>, >, >=, <, <=.");
                }
                validateSummaryTextParts(ifElse.thenParts(), definedQueries, context + " IF THEN");
                validateSummaryTextParts(ifElse.elseParts(), definedQueries, context + " IF ELSE");
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
