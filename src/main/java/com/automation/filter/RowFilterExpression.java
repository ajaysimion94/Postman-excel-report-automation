package com.automation.filter;

/**
 * Expression tree used for SQL-like WHERE evaluation with nested AND/OR groups and NOT.
 */
public sealed interface RowFilterExpression
        permits RowFilterExpression.Predicate, RowFilterExpression.And, RowFilterExpression.Or, RowFilterExpression.Not {

    record Predicate(RowFilterRule rule) implements RowFilterExpression {
    }

    record And(RowFilterExpression left, RowFilterExpression right) implements RowFilterExpression {
    }

    record Or(RowFilterExpression left, RowFilterExpression right) implements RowFilterExpression {
    }

    record Not(RowFilterExpression expr) implements RowFilterExpression {
    }
}
