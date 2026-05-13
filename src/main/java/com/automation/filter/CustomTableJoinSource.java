package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * A single source request in a multi-request custom table join.
 *
 * <pre>
 * { "request": "List all posts", "as": "p" }
 * { "request": "List users",     "as": "u" }
 * </pre>
 *
 * <p>The {@code as} alias is used to prefix column references when two sources have
 * overlapping field names, e.g., {@code "p.id"}, {@code "u.id"}.
 * If {@code as} is omitted, the request name is used as the alias.
 */
public record CustomTableJoinSource(
        /** Exact request name as it appears in the Postman collection. */
        String request,

        /** Short alias to prefix columns from this source. Optional. */
        @JsonAlias("alias") String as
) {}
