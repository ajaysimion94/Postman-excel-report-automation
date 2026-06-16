package com.automation.filter;

import java.util.List;

/**
 * One fragment of a {@link SummaryItem.Text} expression.
 *
 * <p>Three kinds of fragments:
 * <ul>
 *   <li>{@link Literal} — plain text string</li>
 *   <li>{@link Variable} — reference to a {@code $name} query variable or filter {@code vars} entry</li>
 *   <li>{@link IfElse} — conditional: evaluates condition, then renders either the THEN
 *       or ELSE branch's text parts (which can themselves contain Variables, Literals,
 *       or nested IfElse parts)</li>
 * </ul>
 *
 * <p>DSL syntax for IF/ELSE inside summary text expressions:
 * <pre>
 *   TEXT IF $count > 0 THEN $count + " found" ELSE "none found";
 *   KV "Status" IF $success = true THEN "All passed" ELSE "Some failed";
 *   QT "Results" HEADERS Name, Value
 *     ROW "Count", IF $count > 100 THEN "High" ELSE "Low";
 * </pre>
 *
 * <p>Condition syntax is a simplified predicate: {@code $variable op value} where
 * {@code op} is one of =, !=, &gt;, &gt;=, &lt;, &lt;=.
 * The variable is resolved at render time using the same logic as
 * {@link SummaryTextPart.Variable} (query row count or single-cell scalar).
 */
public sealed interface SummaryTextPart permits SummaryTextPart.Literal, SummaryTextPart.Variable, SummaryTextPart.IfElse {
    record Literal(String value) implements SummaryTextPart {
    }

    /** References a summary query variable ({@code $name}) or a filter {@code vars} entry. */
    record Variable(String name) implements SummaryTextPart {
    }

    /**
     * Conditional text: when the condition evaluates to true, the {@code thenParts}
     * are rendered; otherwise the {@code elseParts} are rendered.
     *
     * <p>The condition is a simple predicate on a summary variable:
     * <ul>
     *   <li>{@code variableName} — the {@code $name} to resolve (row count or scalar)</li>
     *   <li>{@code op} — comparison operator (=, !=, &gt;, &gt;=, &lt;, &lt;=)</li>
     *   <li>{@code value} — the comparison target (numeric or string)</li>
     * </ul>
     *
     * <p>Resolution rules for the condition variable:
     * <ul>
     *   <li>If the variable resolves to a single-cell scalar, that value is compared.</li>
     *   <li>If the variable resolves to a row set, the row count is compared.</li>
     * </ul>
     *
     * <p>Both THEN and ELSE branches are lists of {@link SummaryTextPart} so they can
     * contain nested variables, literals, or even nested IF/ELSE.
     */
    record IfElse(
            /** The first condition term's $variable name (kept for single-term backward compatibility). */
            String variableName,
            /** The first condition term's operator: =, !=, >, >=, <, <= */
            String op,
            /** The first condition term's comparison value. */
            String value,
            /** The full (possibly compound) condition tree. Never null. */
            Condition condition,
            /** Text parts to render when condition is true. */
            List<SummaryTextPart> thenParts,
            /** Text parts to render when condition is false. Null means empty string. */
            List<SummaryTextPart> elseParts
    ) implements SummaryTextPart {
        /** Single-term condition (backward compatible). */
        public IfElse(String variableName, String op, String value,
                      List<SummaryTextPart> thenParts, List<SummaryTextPart> elseParts) {
            this(variableName, op, value, new Condition.Term(variableName, op, value), thenParts, elseParts);
        }

        /** Single-term condition without an ELSE branch (backward compatible). */
        public IfElse(String variableName, String op, String value,
                      List<SummaryTextPart> thenParts) {
            this(variableName, op, value, thenParts, List.of());
        }

        /** Compound condition tree; the legacy variableName/op/value mirror the first term. */
        public IfElse(Condition condition, List<SummaryTextPart> thenParts, List<SummaryTextPart> elseParts) {
            this(firstVar(condition), firstOp(condition), firstValue(condition), condition, thenParts, elseParts);
        }

        private static Condition.Term firstTerm(Condition c) {
            if (c instanceof Condition.Term t) return t;
            if (c instanceof Condition.And a) return firstTerm(a.left());
            if (c instanceof Condition.Or o) return firstTerm(o.left());
            return null;
        }

        private static String firstVar(Condition c) {
            Condition.Term t = firstTerm(c);
            return t == null ? null : t.variableName();
        }

        private static String firstOp(Condition c) {
            Condition.Term t = firstTerm(c);
            return t == null ? null : t.op();
        }

        private static String firstValue(Condition c) {
            Condition.Term t = firstTerm(c);
            return t == null ? null : t.value();
        }
    }

    /**
     * A boolean condition for a summary {@code IF}. Either a single comparison {@link Term}
     * or {@link And}/{@link Or} combinations, allowing conditions such as
     * {@code IF $a > 0 AND $b > 0 THEN ...}.
     */
    sealed interface Condition permits Condition.Term, Condition.And, Condition.Or {
        /** A single {@code $variable op value} comparison. */
        record Term(String variableName, String op, String value) implements Condition {
        }

        record And(Condition left, Condition right) implements Condition {
        }

        record Or(Condition left, Condition right) implements Condition {
        }
    }
}
