package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * A group of {@link RowFilterRule} conditions combined with a logical operator.
 *
 * <pre>
 * {
 *   "logic": "AND",
 *   "rules": [
 *     { "field": "enabled",      "op": "EQ",         "value": "true"      },
 *     { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code logic} defaults to {@code AND} when omitted.</li>
 *   <li>Supported values: {@code AND} (all rules must match) or {@code OR} (any rule must match).</li>
 * </ul>
 */
public record RowFilterGroup(
        /**
         * Logical combinator: {@code "AND"} or {@code "OR"}.
         * Case-insensitive. Defaults to {@code AND} when {@code null}.
         */
        @JsonAlias({"mode", "operator"}) String logic,

        /** Ordered list of rules to evaluate. */
        List<RowFilterRule> rules
) {}
