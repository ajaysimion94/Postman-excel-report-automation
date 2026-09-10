package com.automation.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

/** Evaluates a shorthand query against the original JSON, preserving array item identity. */
public final class QuickQueryEvaluator {
    private QuickQueryEvaluator() {}

    public static List<ObjectNode> evaluate(JsonNode root, SummaryQuerySource.QuickRows query,
                                            Map<String, String> variables) {
        if (root == null) return List.of();
        Set<String> fields = new LinkedHashSet<>();
        query.columns().forEach(column -> fields.add(column.field()));
        if (query.filter() != null) {
            if (query.filter().rules() != null) query.filter().rules().forEach(rule -> fields.add(rule.field()));
            collectFields(query.filter().expression(), fields);
        }
        // The deepest qualified parent identifies the row object. Ancestor scalar
        // fields remain available to predicates while arrays are traversed per item.
        String anchor = fields.stream().filter(field -> field.contains("."))
                .map(field -> field.substring(0, field.lastIndexOf('.')))
                .max(Comparator.comparingInt(path -> path.split("\\.").length)).orElse("");
        List<ObjectNode> candidates = new ArrayList<>();
        ObjectNode context = JsonNodeFactory.instance.objectNode();
        if (anchor.isEmpty() && root.isObject()) {
            JsonNode array = null;
            for (JsonNode child : root) {
                if (child.isArray() && !child.isEmpty() && child.get(0).isObject()) { array = child; break; }
            }
            if (array != null) root = array;
        }
        visit(root, anchor.isEmpty() ? new String[0] : anchor.split("\\."), 0, "", context, candidates);
        List<ObjectNode> result = new ArrayList<>();
        Instant now = Instant.now();
        for (ObjectNode candidate : candidates) {
            ObjectNode row = candidate.deepCopy();
            for (String field : fields) {
                if (row.has(field) || field.equals("*")) continue;
                List<String> matches = new ArrayList<>();
                candidate.fieldNames().forEachRemaining(key -> {
                    if (key.endsWith("." + field)) matches.add(key);
                });
                if (matches.size() > 1) throw new IllegalArgumentException(
                        "Ambiguous quick query field '" + field + "'. Use its full path.");
                if (matches.size() == 1) row.set(field, candidate.get(matches.get(0)));
            }
            if (query.filter() != null && !RowConditionEvaluator.evaluate(row, query.filter(), Map.of(), now, variables)) continue;
            ObjectNode projected = JsonNodeFactory.instance.objectNode();
            for (ColumnSpec column : query.columns()) {
                if (column.field().equals("*")) projected.setAll(row);
                else projected.set(column.field(), row.path(column.field()));
            }
            result.add(projected);
        }
        return result;
    }

    private static void visit(JsonNode node, String[] path, int index, String prefix,
                              ObjectNode inherited, List<ObjectNode> rows) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) visit(item, path, index, prefix, inherited, rows);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode context = inherited.deepCopy();
        flatten(node, prefix, context);
        if (index == path.length) {
            // Relative names address this row, even when a parent also has 'name'.
            flatten(node, "", context);
            rows.add(context);
        } else {
            String key = path[index];
            visit(node.get(key), path, index + 1, prefix.isEmpty() ? key : prefix + "." + key, context, rows);
        }
    }

    private static void flatten(JsonNode node, String prefix, ObjectNode target) {
        if (node.isObject()) node.fields().forEachRemaining(entry ->
                flatten(entry.getValue(), prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), target));
        else if (!node.isArray()) target.set(prefix, node);
    }

    private static void collectFields(RowFilterExpression expr, Set<String> fields) {
        if (expr instanceof RowFilterExpression.Predicate p) fields.add(p.rule().field());
        else if (expr instanceof RowFilterExpression.And a) { collectFields(a.left(), fields); collectFields(a.right(), fields); }
        else if (expr instanceof RowFilterExpression.Or o) { collectFields(o.left(), fields); collectFields(o.right(), fields); }
        else if (expr instanceof RowFilterExpression.Not n) collectFields(n.expr(), fields);
        else if (expr instanceof RowFilterExpression.IfElse i) {
            collectFields(i.condition(), fields); collectFields(i.thenExpr(), fields); collectFields(i.elseExpr(), fields);
        }
    }
}
