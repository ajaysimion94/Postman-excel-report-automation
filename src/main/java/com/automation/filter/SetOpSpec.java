package com.automation.filter;

import java.util.List;

/**
 * Defines a set operation (INTERSECT or EXCEPT) built from multiple request result sets.
 * Provenance columns (_source, _in_&lt;source&gt;) are always added to the output.
 */
public record SetOpSpec(
        String name,
        String type,
        List<String> sources
) {
}
