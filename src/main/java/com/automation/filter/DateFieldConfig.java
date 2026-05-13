package com.automation.filter;

/**
 * Date parsing hints for a single field in a response body.
 *
 * <p>These hints are required whenever a {@code DATE_PRESET} or {@code DATE_RANGE} rule
 * targets a field whose value is not standard ISO-8601 ({@code yyyy-MM-dd} or
 * {@code yyyy-MM-dd'T'HH:mm:ssX} etc.). If the field is ISO-8601, the config is optional —
 * the evaluator will try common ISO formats automatically.
 *
 * <pre>
 * Examples:
 *   { "format": "yyyy-MM-dd'T'HH:mm:ss'Z'", "timezone": "UTC"            }
 *   { "format": "dd/MM/yyyy",                "timezone": "Asia/Kolkata"   }
 *   { "format": "yyyy-MM-dd HH:mm:ss",       "timezone": "America/New_York" }
 * </pre>
 *
 * <p>The {@code timezone} field accepts any {@link java.time.ZoneId} name (e.g., {@code UTC},
 * {@code Asia/Kolkata}). When omitted the JVM default timezone is used.
 */
public record DateFieldConfig(
        /**
         * A {@link java.time.format.DateTimeFormatter} pattern string.
         * Examples: {@code "yyyy-MM-dd"}, {@code "dd/MM/yyyy HH:mm:ss"}.
         * {@code null} means auto-detect using standard ISO-8601 patterns.
         */
        String format,

        /**
         * The timezone id that the date strings in the response are expressed in.
         * {@code null} defaults to the JVM system timezone.
         */
        String timezone
) {}
