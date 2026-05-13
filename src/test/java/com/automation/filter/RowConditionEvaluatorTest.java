package com.automation.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RowConditionEvaluatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode row(Object... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object val = keyValues[i + 1];
            if (val instanceof String s)  node.put(key, s);
            else if (val instanceof Boolean b) node.put(key, b);
            else if (val instanceof Integer n) node.put(key, n);
            else if (val instanceof Long l)    node.put(key, l);
            else if (val instanceof Double d)  node.put(key, d);
        }
        return node;
    }

    private static RowFilterGroup and(RowFilterRule... rules) {
        return new RowFilterGroup("AND", List.of(rules));
    }

    private static RowFilterGroup or(RowFilterRule... rules) {
        return new RowFilterGroup("OR", List.of(rules));
    }

    private static RowFilterRule rule(String field, String op, String value) {
        return new RowFilterRule(field, op, value, null, null);
    }

    private static RowFilterRule dateRule(String field, String op, String value, String from, String to) {
        return new RowFilterRule(field, op, value, from, to);
    }

    // ── null group ────────────────────────────────────────────────────────────────

    @Test
    void nullGroupMatchesAllRows() {
        ObjectNode r = row("id", 1);
        assertTrue(RowConditionEvaluator.evaluate(r, null, Collections.emptyMap(), Instant.now()));
    }

    @Test
    void emptyRulesGroupMatchesAllRows() {
        ObjectNode r = row("id", 1);
        RowFilterGroup empty = new RowFilterGroup("AND", List.of());
        assertTrue(RowConditionEvaluator.evaluate(r, empty, Collections.emptyMap(), Instant.now()));
    }

    // ── EQ / NEQ ──────────────────────────────────────────────────────────────────

    @Test
    void eqStringMatch() {
        ObjectNode r = row("status", "active");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("status", "EQ", "active")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("status", "EQ", "inactive")), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void eqCaseInsensitive() {
        ObjectNode r = row("status", "Active");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("status", "EQ", "active")), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void eqNumericComparison() {
        ObjectNode r = row("score", 42);
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("score", "EQ", "42")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("score", "EQ", "43")), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void neqWorks() {
        ObjectNode r = row("status", "active");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("status", "NEQ", "inactive")), Collections.emptyMap(), Instant.now()));
    }

    // ── GT / GTE / LT / LTE ───────────────────────────────────────────────────────

    @Test
    void numericComparisonOperators() {
        ObjectNode r = row("score", 50);
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("score", "GT", "49")),  Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("score", "GTE", "50")), Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("score", "LT", "51")),  Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("score", "LTE", "50")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("score", "GT", "50")),  Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("score", "LT", "50")),  Collections.emptyMap(), Instant.now()));
    }

    // ── IS_TRUE / IS_FALSE ────────────────────────────────────────────────────────

    @Test
    void isTrueWithBooleanField() {
        ObjectNode enabled = row("enabled", true);
        ObjectNode disabled = row("enabled", false);
        assertTrue(RowConditionEvaluator.evaluate(enabled, and(rule("enabled", "IS_TRUE", null)), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(disabled, and(rule("enabled", "IS_TRUE", null)), Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(disabled, and(rule("enabled", "IS_FALSE", null)), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void isTrueWithStringField() {
        ObjectNode r = row("active", "true");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("active", "IS_TRUE", null)), Collections.emptyMap(), Instant.now()));
    }

    // ── CONTAINS / NOT_CONTAINS ───────────────────────────────────────────────────

    @Test
    void containsOperator() {
        ObjectNode r = row("email", "user@example.com");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("email", "CONTAINS", "@example.com")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("email", "CONTAINS", "@other.com")), Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("email", "NOT_CONTAINS", "@other.com")), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void startsWithAndEndsWith() {
        ObjectNode r = row("name", "John Smith");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("name", "STARTS_WITH", "john")), Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("name", "ENDS_WITH", "smith")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("name", "STARTS_WITH", "smith")), Collections.emptyMap(), Instant.now()));
    }

    // ── IN / NOT_IN ───────────────────────────────────────────────────────────────

    @Test
    void inOperatorWithCommaSeparatedList() {
        ObjectNode r = row("status", "pending");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("status", "IN", "active,pending,review")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("status", "IN", "active,done")), Collections.emptyMap(), Instant.now()));
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("status", "NOT_IN", "active,done")), Collections.emptyMap(), Instant.now()));
    }

    // ── IS_NULL / IS_NOT_NULL ─────────────────────────────────────────────────────

    @Test
    void isNullForMissingField() {
        ObjectNode r = row("id", 1);
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("missingField", "IS_NULL", null)), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("missingField", "IS_NOT_NULL", null)), Collections.emptyMap(), Instant.now()));
    }

    @Test
    void isNotNullForPresentField() {
        ObjectNode r = row("id", 1);
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("id", "IS_NOT_NULL", null)), Collections.emptyMap(), Instant.now()));
    }

    // ── REGEX ─────────────────────────────────────────────────────────────────────

    @Test
    void regexMatch() {
        ObjectNode r = row("code", "ABC-123");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rule("code", "REGEX", "^[A-Z]+-\\d+$")), Collections.emptyMap(), Instant.now()));
        assertFalse(RowConditionEvaluator.evaluate(r, and(rule("code", "REGEX", "^\\d+")), Collections.emptyMap(), Instant.now()));
    }

    // ── AND / OR logic ────────────────────────────────────────────────────────────

    @Test
    void andLogicRequiresAllRules() {
        ObjectNode r = row("a", "1", "b", "2");
        RowFilterGroup group = and(rule("a", "EQ", "1"), rule("b", "EQ", "99"));
        assertFalse(RowConditionEvaluator.evaluate(r, group, Collections.emptyMap(), Instant.now()));
    }

    @Test
    void orLogicRequiresAnyRule() {
        ObjectNode r = row("a", "1", "b", "2");
        RowFilterGroup group = or(rule("a", "EQ", "1"), rule("b", "EQ", "99"));
        assertTrue(RowConditionEvaluator.evaluate(r, group, Collections.emptyMap(), Instant.now()));
    }

    @Test
    void orLogicFalseWhenNoneMatch() {
        ObjectNode r = row("a", "X", "b", "Y");
        RowFilterGroup group = or(rule("a", "EQ", "1"), rule("b", "EQ", "2"));
        assertFalse(RowConditionEvaluator.evaluate(r, group, Collections.emptyMap(), Instant.now()));
    }

    // ── missing field warning ─────────────────────────────────────────────────────

    @Test
    void missingFieldExcludesRowFromAndGroup() {
        ObjectNode r = row("id", 1);
        // "nonExistent" not in row; rule should be treated as non-matching
        RowFilterGroup group = and(rule("nonExistent", "EQ", "foo"));
        assertFalse(RowConditionEvaluator.evaluate(r, group, Collections.emptyMap(), Instant.now()));
    }

    // ── DATE_PRESET ───────────────────────────────────────────────────────────────

    @Test
    void datePresetYesterdayMatchesIsoDate() {
        // Reference now = 2026-05-13 noon UTC
        Instant now = ZonedDateTime.of(2026, 5, 13, 12, 0, 0, 0, UTC).toInstant();
        ObjectNode r = row("modifiedDate", "2026-05-12T15:00:00Z");
        RowFilterRule dateRule = dateRule("modifiedDate", "DATE_PRESET", "YESTERDAY", null, null);
        RowFilterGroup group = and(dateRule);
        assertTrue(RowConditionEvaluator.evaluate(r, group, Collections.emptyMap(), now));
    }

    @Test
    void datePresetYesterdayRejectsToday() {
        Instant now = ZonedDateTime.of(2026, 5, 13, 12, 0, 0, 0, UTC).toInstant();
        ObjectNode r = row("modifiedDate", "2026-05-13T08:00:00Z");
        RowFilterRule dateRule = dateRule("modifiedDate", "DATE_PRESET", "YESTERDAY", null, null);
        assertFalse(RowConditionEvaluator.evaluate(r, and(dateRule), Collections.emptyMap(), now));
    }

    @Test
    void datePresetWithCustomFormat() {
        Instant now = ZonedDateTime.of(2026, 5, 13, 12, 0, 0, 0, UTC).toInstant();
        ObjectNode r = row("createdAt", "12/05/2026");
        RowFilterRule dateRule = dateRule("createdAt", "DATE_PRESET", "YESTERDAY", null, null);
        DateFieldConfig cfg = new DateFieldConfig("dd/MM/yyyy", "UTC");
        Map<String, DateFieldConfig> dateConfig = Map.of("createdAt", cfg);
        assertTrue(RowConditionEvaluator.evaluate(r, and(dateRule), dateConfig, now));
    }

    // ── DATE_RANGE ────────────────────────────────────────────────────────────────

    @Test
    void dateRangeInclusive() {
        Instant now = Instant.now();
        ObjectNode r = row("ts", "2026-03-15T00:00:00Z");
        RowFilterRule rangeRule = dateRule("ts", "DATE_RANGE", null, "2026-03-01", "2026-03-31");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rangeRule), Collections.emptyMap(), now));
    }

    @Test
    void dateRangeExcludesOutside() {
        Instant now = Instant.now();
        ObjectNode r = row("ts", "2026-04-01T00:00:00Z");
        RowFilterRule rangeRule = dateRule("ts", "DATE_RANGE", null, "2026-03-01", "2026-03-31");
        assertFalse(RowConditionEvaluator.evaluate(r, and(rangeRule), Collections.emptyMap(), now));
    }

    @Test
    void dateRangeOpenEndedFrom() {
        Instant now = Instant.now();
        ObjectNode r = row("ts", "2026-01-01T00:00:00Z");
        RowFilterRule rangeRule = dateRule("ts", "DATE_RANGE", null, null, "2026-06-30");
        assertTrue(RowConditionEvaluator.evaluate(r, and(rangeRule), Collections.emptyMap(), now));
    }
}
