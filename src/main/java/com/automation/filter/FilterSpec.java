package com.automation.filter;

import java.util.List;
import java.util.Map;

/**
 * Defines which requests to run and which response columns to include in the report.
 *
 * <pre>
 * {
 *   "collection":      "jsonplaceholder",         // optional: match this collection name (stem)
 *   "requests":        ["List all posts", "..."],  // optional: whitelist of request names
 *   "responseColumns": {                           // optional: per-request column whitelists
 *     "*":               ["id", "title"],          //   "*" = default for unspecified requests
 *     "Get post by ID":  ["id", "title", "body"]
 *   },
 *   "outputPrefix":    "posts-focus"               // optional: prepended to the output filename
 * }
 * </pre>
 */
public record FilterSpec(
        String collection,
        List<String> requests,
        Map<String, List<String>> responseColumns,
        String outputPrefix,
        FilterAuthSpec auth,
        Map<String, String> vars
) {}
