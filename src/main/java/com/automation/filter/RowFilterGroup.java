package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

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
public final class RowFilterGroup {
        private final String logic;
        private final List<RowFilterRule> rules;
        private final RowFilterExpression expression;

        @JsonCreator
        public RowFilterGroup(
                        @JsonAlias({"mode", "operator"}) @JsonProperty("logic") String logic,
                        @JsonProperty("rules") List<RowFilterRule> rules
        ) {
                this(logic, rules, null);
        }

        public RowFilterGroup(String logic, List<RowFilterRule> rules, RowFilterExpression expression) {
                this.logic = logic;
                this.rules = rules;
                this.expression = expression;
        }

        public String logic() {
                return logic;
        }

        public List<RowFilterRule> rules() {
                return rules;
        }

        @JsonIgnore
        public RowFilterExpression expression() {
                return expression;
        }

        @Override
        public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof RowFilterGroup other)) return false;
                return Objects.equals(logic, other.logic)
                                && Objects.equals(rules, other.rules)
                                && Objects.equals(expression, other.expression);
        }

        @Override
        public int hashCode() {
                return Objects.hash(logic, rules, expression);
        }
}
