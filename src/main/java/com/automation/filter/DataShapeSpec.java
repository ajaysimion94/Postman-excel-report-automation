package com.automation.filter;

import java.util.List;

/**
 * Output shaping options applied after filtering: DISTINCT, ORDER BY, LIMIT/OFFSET.
 */
public record DataShapeSpec(
        boolean distinct,
        List<SortSpec> orderBy,
        Integer limit,
                Integer offset,
                List<String> groupBy,
                List<AggregateSpec> aggregates,
                RowFilterGroup having
) {
        public DataShapeSpec(boolean distinct, List<SortSpec> orderBy, Integer limit, Integer offset) {
                this(distinct, orderBy, limit, offset, null, null, null);
        }
}
