package com.automation.filter;

/**
 * One aggregate expression used in SHAPE AGG clauses.
 */
public record AggregateSpec(String function, String field, String alias) {
}
