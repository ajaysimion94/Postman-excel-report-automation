package com.automation.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates a response data row ({@link ObjectNode}) against a {@link RowFilterGroup},
 * returning {@code true} if the row satisfies the conditions.
 *
 * <p>Type coercion rules:
 * <ul>
 *   <li>Numeric operators ({@code GT}, {@code GTE}, {@code LT}, {@code LTE}): attempt
 *       double-precision numeric parse on both sides; fall back to lexicographic comparison.</li>
 *   <li>Boolean operators ({@code IS_TRUE}, {@code IS_FALSE}, {@code EQ} against "true"/"false"):
 *       case-insensitive string match.</li>
 *   <li>String operators ({@code CONTAINS}, {@code STARTS_WITH}, {@code ENDS_WITH}):
 *       case-insensitive.</li>
 *   <li>Date operators ({@code DATE_PRESET}, {@code DATE_RANGE}): parse using
 *       {@link DateFieldConfig} when provided; otherwise attempt standard ISO-8601 formats
 *       automatically. Emits a warning if parsing fails and treats the row as <em>not</em>
 *       matching that rule.</li>
 * </ul>
 *
 * <p>Missing-field policy: when a rule references a field absent from the row, a warning is
 * printed and the rule is treated as <em>not matching</em> (the row is excluded unless the
 * group logic is OR and another rule matches).
 */
public final class RowConditionEvaluator {

    /** Common ISO-8601 fallback parsers tried in order when no explicit format is configured. */
    private static final List<DateTimeFormatter> ISO_FALLBACKS = List.of(
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
                    .toFormatter(),
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    private RowConditionEvaluator() {}

    // ── public API ────────────────────────────────────────────────────────────────

    /**
     * Evaluates {@code row} against {@code group} using the supplied date config and reference time.
     *
     * @param row        the response data row to test
     * @param group      the rule group (AND / OR combination)
     * @param dateConfig per-field date parsing hints for the owning request
     * @param now        reference instant for date preset resolution
     * @return {@code true} if the row satisfies the group's conditions
     */
    public static boolean evaluate(ObjectNode row, RowFilterGroup group,
                                   Map<String, DateFieldConfig> dateConfig, Instant now) {
        return evaluate(row, group, dateConfig, now, Map.of());
    }

    /**
     * Same as {@link #evaluate(ObjectNode, RowFilterGroup, Map, Instant)} but additionally
     * resolves {@code $name} variable references in rule values against {@code vars}
     * (typically the runtime/env variable map). A value written as {@code $foo} is replaced
     * with {@code vars.get("foo")} before comparison; unknown variables emit a warning and
     * fall back to the literal text.
     */
    public static boolean evaluate(ObjectNode row, RowFilterGroup group,
                                   Map<String, DateFieldConfig> dateConfig, Instant now,
                                   Map<String, String> vars) {
        if (group == null) {
            return true;
        }

        if (group.expression() != null) {
            return evaluateExpression(row, group.expression(), dateConfig, now, vars);
        }

        if (group.rules() == null || group.rules().isEmpty()) {
            return true;
        }

        boolean isAnd = group.logic() == null || !"OR".equalsIgnoreCase(group.logic());

        for (RowFilterRule rule : group.rules()) {
            boolean result = evaluateRule(row, rule, dateConfig, now, vars);
            if (isAnd && !result) return false;   // AND short-circuit fail
            if (!isAnd && result) return true;    // OR short-circuit pass
        }
        return isAnd; // AND: all passed; OR: none passed
    }

    private static boolean evaluateExpression(ObjectNode row, RowFilterExpression expr,
                                              Map<String, DateFieldConfig> dateConfig, Instant now,
                                              Map<String, String> vars) {
        if (expr instanceof RowFilterExpression.Predicate predicate) {
            return evaluateRule(row, predicate.rule(), dateConfig, now, vars);
        }
        if (expr instanceof RowFilterExpression.And andExpr) {
            return evaluateExpression(row, andExpr.left(), dateConfig, now, vars)
                    && evaluateExpression(row, andExpr.right(), dateConfig, now, vars);
        }
        if (expr instanceof RowFilterExpression.Or orExpr) {
            return evaluateExpression(row, orExpr.left(), dateConfig, now, vars)
                    || evaluateExpression(row, orExpr.right(), dateConfig, now, vars);
        }
        if (expr instanceof RowFilterExpression.Not notExpr) {
            return !evaluateExpression(row, notExpr.expr(), dateConfig, now, vars);
        }
        if (expr instanceof RowFilterExpression.IfElse ifElse) {
            boolean conditionResult = evaluateExpression(row, ifElse.condition(), dateConfig, now, vars);
            if (conditionResult) {
                return evaluateExpression(row, ifElse.thenExpr(), dateConfig, now, vars);
            } else {
                return ifElse.elseExpr() != null
                        ? evaluateExpression(row, ifElse.elseExpr(), dateConfig, now, vars)
                        : true; // No ELSE clause → condition was false but nothing excludes the row
            }
        }
        return true;
    }

    // ── rule dispatch ─────────────────────────────────────────────────────────────

    private static boolean evaluateRule(ObjectNode row, RowFilterRule rule,
                                        Map<String, DateFieldConfig> dateConfig, Instant now,
                                        Map<String, String> vars) {
        if (rule == null || rule.field() == null || rule.op() == null) {
            System.err.println("[WARN] Row filter rule is missing field or op — rule skipped.");
            return true;
        }

        String op    = rule.op().toUpperCase();
        String field = rule.field();
        String value = resolveVars(rule.value(), vars);
        String from  = resolveVars(rule.from(), vars);
        String to    = resolveVars(rule.to(), vars);

        // IS_NULL / IS_NOT_NULL do not need the node to be present
        if ("IS_NULL".equals(op)) {
            JsonNode node = row.get(field);
            return node == null || node.isNull();
        }
        if ("IS_NOT_NULL".equals(op)) {
            JsonNode node = row.get(field);
            return node != null && !node.isNull();
        }

        JsonNode node = row.get(field);
        if (node == null || node.isNull()) {
            System.err.printf("[WARN] Row filter: field \"%s\" not found in row — rule skipped (row treated as non-matching).%n", field);
            return false;
        }

        return switch (op) {
            case "IS_TRUE"      -> isTrueCheck(node);
            case "IS_FALSE"     -> isFalseCheck(node);
            case "EQ"           -> compareValues(node, value) == 0;
            case "NEQ"          -> compareValues(node, value) != 0;
            case "GT"           -> compareValues(node, value) >  0;
            case "GTE"          -> compareValues(node, value) >= 0;
            case "LT"           -> compareValues(node, value) <  0;
            case "LTE"          -> compareValues(node, value) <= 0;
            case "CONTAINS"     -> stringContains(node, value, true);
            case "NOT_CONTAINS" -> !stringContains(node, value, true);
            case "STARTS_WITH"  -> stringStartsWith(node, value);
            case "ENDS_WITH"    -> stringEndsWith(node, value);
            case "IN"           -> inCheck(node, value);
            case "NOT_IN"       -> !inCheck(node, value);
            case "REGEX"        -> regexCheck(node, value, field);
            case "DATE_PRESET"  -> datePresetCheck(node, value, field, dateConfig, now);
            case "DATE_RANGE"   -> dateRangeCheck(node, from, to, field, dateConfig, now);
            default -> {
                System.err.printf("[WARN] Unknown row filter operator \"%s\" for field \"%s\" — rule skipped.%n", op, field);
                yield true;
            }
        };
    }

    // ── variable resolution ───────────────────────────────────────────────────────

    /**
     * Resolves a {@code $name} variable reference in a rule value against {@code vars}.
     * A value of the form {@code $identifier} is replaced with the variable's value;
     * unknown variables emit a warning and fall back to the literal text. Any other value
     * (including literals that merely start with {@code $} but are not valid identifiers)
     * is returned unchanged.
     */
    private static String resolveVars(String value, Map<String, String> vars) {
        if (value == null || vars == null || vars.isEmpty()) {
            return value;
        }
        if (value.length() > 1 && value.charAt(0) == '$' && isIdentifier(value.substring(1))) {
            String key = value.substring(1);
            if (vars.containsKey(key)) {
                return vars.get(key);
            }
            System.err.printf("[WARN] Row filter value variable \"$%s\" is not defined — using literal.%n", key);
        }
        return value;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    // ── boolean checks ────────────────────────────────────────────────────────────

    private static boolean isTrueCheck(JsonNode node) {
        if (node.isBoolean()) return node.booleanValue();
        return "true".equalsIgnoreCase(node.asText());
    }

    private static boolean isFalseCheck(JsonNode node) {
        if (node.isBoolean()) return !node.booleanValue();
        return "false".equalsIgnoreCase(node.asText());
    }

    // ── value comparison ──────────────────────────────────────────────────────────

    /**
     * Compares the node's value against a target string.
     * Tries numeric comparison first; falls back to case-insensitive string comparison.
     */
    private static int compareValues(JsonNode node, String target) {
        if (target == null) return 0;
        String nodeStr = node.asText("");
        // Numeric comparison
        try {
            double nodeNum   = Double.parseDouble(nodeStr);
            double targetNum = Double.parseDouble(target);
            return Double.compare(nodeNum, targetNum);
        } catch (NumberFormatException ignored) {
            // fall through to string comparison
        }
        // Boolean-as-string
        if (node.isBoolean()) {
            return Boolean.compare(node.booleanValue(), Boolean.parseBoolean(target));
        }
        return nodeStr.compareToIgnoreCase(target);
    }

    // ── string checks ─────────────────────────────────────────────────────────────

    private static boolean stringContains(JsonNode node, String value, boolean ignoreCase) {
        if (value == null) return false;
        String s = node.asText("");
        return ignoreCase ? s.toLowerCase().contains(value.toLowerCase()) : s.contains(value);
    }

    private static boolean stringStartsWith(JsonNode node, String value) {
        if (value == null) return false;
        return node.asText("").toLowerCase().startsWith(value.toLowerCase());
    }

    private static boolean stringEndsWith(JsonNode node, String value) {
        if (value == null) return false;
        return node.asText("").toLowerCase().endsWith(value.toLowerCase());
    }

    // ── IN / NOT_IN ───────────────────────────────────────────────────────────────

    private static boolean inCheck(JsonNode node, String value) {
        if (value == null || value.isBlank()) return false;
        String nodeStr = node.asText("").trim();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .anyMatch(v -> v.equalsIgnoreCase(nodeStr));
    }

    // ── REGEX ─────────────────────────────────────────────────────────────────────

    private static boolean regexCheck(JsonNode node, String pattern, String field) {
        if (pattern == null) return false;
        try {
            return Pattern.compile(pattern).matcher(node.asText("")).find();
        } catch (PatternSyntaxException e) {
            System.err.printf("[WARN] Row filter: invalid regex \"%s\" for field \"%s\" — rule skipped.%n", pattern, field);
            return true;
        }
    }

    // ── date checks ───────────────────────────────────────────────────────────────

    private static boolean datePresetCheck(JsonNode node, String preset, String field,
                                           Map<String, DateFieldConfig> dateConfig, Instant now) {
        if (preset == null) {
            System.err.printf("[WARN] DATE_PRESET rule for field \"%s\" has no value — rule skipped.%n", field);
            return true;
        }
        DateFieldConfig cfg    = dateConfig != null ? dateConfig.get(field) : null;
        ZoneId zone            = resolveZone(cfg);
        Instant fieldInstant   = parseDate(node.asText(""), field, cfg, zone);
        if (fieldInstant == null) return false;

        DateWindowResolver.DateWindow window = DateWindowResolver.resolve(preset, zone, now);
        return window.contains(fieldInstant);
    }

    private static boolean dateRangeCheck(JsonNode node, String from, String to, String field,
                                          Map<String, DateFieldConfig> dateConfig, Instant now) {
        DateFieldConfig cfg  = dateConfig != null ? dateConfig.get(field) : null;
        ZoneId zone          = resolveZone(cfg);
        Instant fieldInstant = parseDate(node.asText(""), field, cfg, zone);
        if (fieldInstant == null) return false;

        Instant fromInstant = from != null ? parseDate(from, field + ".from", cfg, zone) : null;
        Instant toInstant   = to   != null ? parseDate(to,   field + ".to",   cfg, zone) : null;

        if (fromInstant != null && fieldInstant.isBefore(fromInstant)) return false;
        if (toInstant   != null && fieldInstant.isAfter(toInstant))    return false;
        return true;
    }

    // ── date parsing helpers ──────────────────────────────────────────────────────

    private static ZoneId resolveZone(DateFieldConfig cfg) {
        if (cfg != null && cfg.timezone() != null && !cfg.timezone().isBlank()) {
            try {
                return ZoneId.of(cfg.timezone());
            } catch (Exception e) {
                System.err.printf("[WARN] Invalid timezone \"%s\" in dateConfig — using system default.%n", cfg.timezone());
            }
        }
        return ZoneId.systemDefault();
    }

    /**
     * Parses {@code rawValue} into an {@link Instant}.
     * Uses the configured formatter first; falls back to common ISO-8601 patterns.
     * Emits a warning and returns {@code null} on parse failure.
     */
    static Instant parseDate(String rawValue, String fieldLabel, DateFieldConfig cfg, ZoneId zone) {
        if (rawValue == null || rawValue.isBlank()) return null;

        // 1. Try configured format
        if (cfg != null && cfg.format() != null && !cfg.format().isBlank()) {
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(cfg.format());
                return parseWithFormatter(rawValue, fmt, zone);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                System.err.printf("[WARN] dateConfig format \"%s\" could not parse \"%s\" for field \"%s\". " +
                        "Falling back to ISO-8601 auto-detection.%n", cfg.format(), rawValue, fieldLabel);
            }
        } else if (cfg != null && cfg.format() == null) {
            // dateConfig present but no format → auto ISO detection; no extra warning needed
        } else {
            // No dateConfig at all → auto ISO detection + informational warning on failure
        }

        // 2. Try ISO-8601 fallbacks
        for (DateTimeFormatter fmt : ISO_FALLBACKS) {
            try {
                return parseWithFormatter(rawValue, fmt, zone);
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // try next
            }
        }

        // 3. Give up
        System.err.printf("[WARN] Could not parse date value \"%s\" for field \"%s\". " +
                "Add a dateConfig entry with the correct format pattern to suppress this warning.%n",
                rawValue, fieldLabel);
        return null;
    }

    /** Attempts to parse {@code raw} with {@code fmt} and convert to {@link Instant}. */
    private static Instant parseWithFormatter(String raw, DateTimeFormatter fmt, ZoneId zone) {
        // Try full datetime with zone
        try {
            return ZonedDateTime.parse(raw, fmt).toInstant();
        } catch (DateTimeException ignored) {}
        // Try instant directly
        try {
            return Instant.from(fmt.parse(raw));
        } catch (DateTimeException ignored) {}
        // Try local datetime → assume provided zone
        try {
            return LocalDateTime.parse(raw, fmt).atZone(zone).toInstant();
        } catch (DateTimeException ignored) {}
        // Try local date only → start of day in provided zone
        try {
            return LocalDate.parse(raw, fmt).atStartOfDay(zone).toInstant();
        } catch (DateTimeException ignored) {}
        throw new DateTimeParseException("Cannot parse '" + raw + "'", raw, 0);
    }
}
