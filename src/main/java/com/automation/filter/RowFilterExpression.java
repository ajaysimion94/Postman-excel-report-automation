package com.automation.filter;

/**
 * Expression tree used for SQL-like WHERE evaluation with nested AND/OR groups, NOT,
 * and IF/ELSE conditional logic.
 *
 * <p>IF/ELSE allows branching: when the condition evaluates to true the {@code thenExpr}
 * is evaluated; otherwise the {@code elseExpr} is evaluated (if present; defaults to true).
 *
 * <p>DSL syntax:
 * <pre>
 *   IF field = value THEN (subExpr) [ELSE (subExpr)]
 * </pre>
 *
 * <p>Example:
 * <pre>
 *   FILTER * WHERE IF status = active THEN (score > 50) ELSE (score > 20) AND category = premium;
 * </pre>
 */
public sealed interface RowFilterExpression
        permits RowFilterExpression.Predicate, RowFilterExpression.And, RowFilterExpression.Or,
        RowFilterExpression.Not, RowFilterExpression.IfElse {

    record Predicate(RowFilterRule rule) implements RowFilterExpression {
    }

    record And(RowFilterExpression left, RowFilterExpression right) implements RowFilterExpression {
    }

    record Or(RowFilterExpression left, RowFilterExpression right) implements RowFilterExpression {
    }

    record Not(RowFilterExpression expr) implements RowFilterExpression {
    }

    /**
     * Conditional expression: if {@code condition} is true, evaluate {@code thenExpr};
     * otherwise evaluate {@code elseExpr} (defaults to a no-op / always-true if null).
     *
     * <p>This allows complex branching logic inside WHERE clauses:
     * <pre>
     *   IF priority = high THEN (severity > 7) ELSE (severity > 3)
     * </pre>
     */
    record IfElse(RowFilterExpression condition, RowFilterExpression thenExpr, RowFilterExpression elseExpr) implements RowFilterExpression {
        public IfElse(RowFilterExpression condition, RowFilterExpression thenExpr) {
            this(condition, thenExpr, null);
        }
    }
}
