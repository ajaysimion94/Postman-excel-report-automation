package com.automation.filter;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * Resolves relative date preset names into concrete {@link DateWindow} ranges based on the
 * current instant and a configurable timezone.
 *
 * <p>All range boundaries are inclusive at the day level: {@code from} = start of first day
 * (00:00:00.000), {@code to} = end of last day (23:59:59.999999999).
 *
 * <p>Week boundaries use Monday as the first day of the week.
 *
 * <p>Supported preset names (case-insensitive):
 * <ul>
 *   <li>{@code TODAY}</li>
 *   <li>{@code YESTERDAY}</li>
 *   <li>{@code THIS_WEEK}</li>
 *   <li>{@code LAST_WEEK}</li>
 *   <li>{@code THIS_MONTH}</li>
 *   <li>{@code LAST_MONTH}</li>
 *   <li>{@code THIS_QUARTER}</li>
 *   <li>{@code LAST_QUARTER}</li>
 *   <li>{@code THIS_YEAR}</li>
 *   <li>{@code LAST_YEAR}</li>
 * </ul>
 */
public final class DateWindowResolver {

    /** All valid preset names, for validation. */
    public static final java.util.Set<String> VALID_PRESETS = java.util.Set.of(
            "TODAY", "YESTERDAY",
            "THIS_WEEK", "LAST_WEEK",
            "THIS_MONTH", "LAST_MONTH",
            "THIS_QUARTER", "LAST_QUARTER",
            "THIS_YEAR", "LAST_YEAR"
    );

    /** An inclusive date range expressed as {@link Instant} boundaries. */
    public record DateWindow(Instant from, Instant to) {
        public boolean contains(Instant instant) {
            return !instant.isBefore(from) && !instant.isAfter(to);
        }
    }

    private DateWindowResolver() {}

    /**
     * Resolves a preset name to a {@link DateWindow} relative to {@code now} in the given zone.
     *
     * @param preset case-insensitive preset name
     * @param zone   timezone to use for day/week/month calculations
     * @param now    the reference instant (typically {@code Instant.now()})
     * @return the date window
     * @throws IllegalArgumentException if the preset name is not recognised
     */
    public static DateWindow resolve(String preset, ZoneId zone, Instant now) {
        if (preset == null) {
            throw new IllegalArgumentException("Date preset cannot be null.");
        }
        ZonedDateTime zdt = now.atZone(zone);
        LocalDate today = zdt.toLocalDate();

        return switch (preset.toUpperCase()) {
            case "TODAY"         -> dayWindow(today, zone);
            case "YESTERDAY"     -> dayWindow(today.minusDays(1), zone);
            case "THIS_WEEK"     -> weekWindow(today, 0, zone);
            case "LAST_WEEK"     -> weekWindow(today, -1, zone);
            case "THIS_MONTH"    -> monthWindow(today, 0, zone);
            case "LAST_MONTH"    -> monthWindow(today, -1, zone);
            case "THIS_QUARTER"  -> quarterWindow(today, 0, zone);
            case "LAST_QUARTER"  -> quarterWindow(today, -1, zone);
            case "THIS_YEAR"     -> yearWindow(today, 0, zone);
            case "LAST_YEAR"     -> yearWindow(today, -1, zone);
            default -> throw new IllegalArgumentException(
                    "Unknown date preset: \"" + preset + "\". Valid presets: " + VALID_PRESETS);
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static DateWindow dayWindow(LocalDate date, ZoneId zone) {
        return new DateWindow(
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static DateWindow weekWindow(LocalDate today, int weekOffset, ZoneId zone) {
        LocalDate startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(weekOffset);
        LocalDate endOfWeek = startOfWeek.plusDays(6);
        return new DateWindow(
                startOfWeek.atStartOfDay(zone).toInstant(),
                endOfWeek.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static DateWindow monthWindow(LocalDate today, int monthOffset, ZoneId zone) {
        LocalDate base = today.plusMonths(monthOffset);
        LocalDate start = base.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end   = base.with(TemporalAdjusters.lastDayOfMonth());
        return new DateWindow(
                start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static DateWindow quarterWindow(LocalDate today, int quarterOffset, ZoneId zone) {
        int currentQuarter = today.get(IsoFields.QUARTER_OF_YEAR);
        int targetQuarter  = currentQuarter + quarterOffset;
        int yearAdjust     = 0;
        if (targetQuarter < 1) { targetQuarter += 4; yearAdjust = -1; }
        if (targetQuarter > 4) { targetQuarter -= 4; yearAdjust = +1; }
        int year = today.getYear() + yearAdjust;
        int startMonth = (targetQuarter - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end   = start.plusMonths(3).minusDays(1);
        return new DateWindow(
                start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static DateWindow yearWindow(LocalDate today, int yearOffset, ZoneId zone) {
        LocalDate start = LocalDate.of(today.getYear() + yearOffset, 1, 1);
        LocalDate end   = start.with(TemporalAdjusters.lastDayOfYear());
        return new DateWindow(
                start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }
}
