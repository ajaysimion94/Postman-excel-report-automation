package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * A single condition that a response data row must satisfy.
 *
 * <pre>
 * Supported operators:
 *   EQ, NEQ                   – equality / inequality (string, numeric, boolean)
 *   GT, GTE, LT, LTE          – comparison (numeric or lexicographic)
 *   CONTAINS, NOT_CONTAINS    – substring check (case-insensitive)
 *   STARTS_WITH, ENDS_WITH    – prefix / suffix check (case-insensitive)
 *   IN, NOT_IN                – membership; {@code value} is comma-separated list
 *   IS_NULL, IS_NOT_NULL      – presence check; no {@code value} needed
 *   IS_TRUE, IS_FALSE         – boolean check; no {@code value} needed
 *   REGEX                     – Java regex match against string form of field value
 *   DATE_PRESET               – {@code value} is one of the date preset names
 *   DATE_RANGE                – {@code from} and {@code to} are ISO-8601 or configured-format strings
 *
 * Date presets (case-insensitive):
 *   TODAY, YESTERDAY,
 *   THIS_WEEK, LAST_WEEK,
 *   THIS_MONTH, LAST_MONTH,
 *   THIS_QUARTER, LAST_QUARTER,
 *   THIS_YEAR, LAST_YEAR
 * </pre>
 *
 * <p>Example JSON rules:
 * <pre>
 *   { "field": "enabled",      "op": "EQ",          "value": "true"       }
 *   { "field": "modifiedDate", "op": "DATE_PRESET",  "value": "YESTERDAY"  }
 *   { "field": "modifiedDate", "op": "DATE_RANGE",   "from": "2026-01-01", "to": "2026-01-31" }
 *   { "field": "status",       "op": "IN",           "value": "active,pending" }
 *   { "field": "email",        "op": "CONTAINS",     "value": "@example.com"   }
 * </pre>
 */
public record RowFilterRule(
        /** The JSON field name in the response object. */
        String field,

        /** Operator name (case-insensitive). */
        @JsonAlias("operator") String op,

        /**
         * Comparison value.
         * For {@code IN} / {@code NOT_IN}: comma-separated list of values.
         * For {@code DATE_PRESET}: preset name (e.g., {@code YESTERDAY}).
         * Not used for {@code IS_NULL}, {@code IS_NOT_NULL}, {@code IS_TRUE}, {@code IS_FALSE},
         * or {@code DATE_RANGE}.
         */
        String value,

        /** Start of date range (inclusive). Used only with {@code DATE_RANGE} operator. */
        String from,

        /** End of date range (inclusive). Used only with {@code DATE_RANGE} operator. */
        String to
) {}
