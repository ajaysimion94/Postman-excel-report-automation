package com.automation.filter;

import java.util.List;

/**
 * Defines a UNION/UNION ALL output built from multiple request result sets.
 */
public record UnionSpec(
        String name,
        List<String> sources,
        boolean all
) {
}
