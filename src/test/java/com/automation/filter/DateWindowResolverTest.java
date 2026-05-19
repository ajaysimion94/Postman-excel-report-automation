package com.automation.filter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DateWindowResolverTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Returns an instant at the given date at 12:00 UTC */
    private static Instant noon(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 12, 0, 0, 0, UTC).toInstant();
    }

    /** Returns an instant at the start of the given date in UTC */
    private static Instant startOf(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, UTC).toInstant();
    }

    @Test
    void todayWindowContainsCurrentTime() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("TODAY", UTC, now);
        assertTrue(w.contains(now));
        // start of today must be included
        assertTrue(w.contains(startOf(2026, 5, 13)));
        // yesterday must NOT be included
        assertFalse(w.contains(noon(2026, 5, 12)));
        // tomorrow must NOT be included
        assertFalse(w.contains(noon(2026, 5, 14)));
    }

    @Test
    void yesterdayWindowContainsPreviousDay() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("YESTERDAY", UTC, now);
        assertTrue(w.contains(noon(2026, 5, 12)));
        assertFalse(w.contains(noon(2026, 5, 13)));
        assertFalse(w.contains(noon(2026, 5, 11)));
    }

    @Test
    void thisWeekWindowContainsCorrectMondayToSunday() {
        // 2026-05-13 is a Wednesday
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("THIS_WEEK", UTC, now);
        assertTrue(w.contains(noon(2026, 5, 11)));  // Monday
        assertTrue(w.contains(noon(2026, 5, 13)));  // Wednesday
        assertTrue(w.contains(noon(2026, 5, 17)));  // Sunday
        assertFalse(w.contains(noon(2026, 5, 10))); // previous Sunday
        assertFalse(w.contains(noon(2026, 5, 18))); // next Monday
    }

    @Test
    void lastWeekWindowIsPreviousMonToSun() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("LAST_WEEK", UTC, now);
        assertTrue(w.contains(noon(2026, 5, 4)));   // Monday
        assertTrue(w.contains(noon(2026, 5, 10)));  // Sunday
        assertFalse(w.contains(noon(2026, 5, 11))); // this Monday
    }

    @Test
    void thisMonthWindowContainsCorrectDays() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("THIS_MONTH", UTC, now);
        assertTrue(w.contains(noon(2026, 5, 1)));
        assertTrue(w.contains(noon(2026, 5, 31)));
        assertFalse(w.contains(noon(2026, 4, 30)));
        assertFalse(w.contains(noon(2026, 6, 1)));
    }

    @Test
    void lastMonthWindowIsAprilForMay() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("LAST_MONTH", UTC, now);
        assertTrue(w.contains(noon(2026, 4, 1)));
        assertTrue(w.contains(noon(2026, 4, 30)));
        assertFalse(w.contains(noon(2026, 5, 1)));
        assertFalse(w.contains(noon(2026, 3, 31)));
    }

    @Test
    void thisQuarterWindowIsQ2ForMay() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("THIS_QUARTER", UTC, now);
        assertTrue(w.contains(noon(2026, 4, 1)));   // start of Q2
        assertTrue(w.contains(noon(2026, 6, 30)));  // end of Q2
        assertFalse(w.contains(noon(2026, 3, 31))); // Q1
        assertFalse(w.contains(noon(2026, 7, 1)));  // Q3
    }

    @Test
    void lastQuarterWindowIsQ1ForMay() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("LAST_QUARTER", UTC, now);
        assertTrue(w.contains(noon(2026, 1, 1)));
        assertTrue(w.contains(noon(2026, 3, 31)));
        assertFalse(w.contains(noon(2026, 4, 1)));
    }

    @Test
    void thisYearWindow() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("THIS_YEAR", UTC, now);
        assertTrue(w.contains(noon(2026, 1, 1)));
        assertTrue(w.contains(noon(2026, 12, 31)));
        assertFalse(w.contains(noon(2025, 12, 31)));
        assertFalse(w.contains(noon(2027, 1, 1)));
    }

    @Test
    void lastYearWindow() {
        Instant now = noon(2026, 5, 13);
        DateWindowResolver.DateWindow w = DateWindowResolver.resolve("LAST_YEAR", UTC, now);
        assertTrue(w.contains(noon(2025, 1, 1)));
        assertTrue(w.contains(noon(2025, 12, 31)));
        assertFalse(w.contains(noon(2026, 1, 1)));
    }

    @Test
    void caseInsensitivePreset() {
        Instant now = noon(2026, 5, 13);
        assertDoesNotThrow(() -> DateWindowResolver.resolve("yesterday", UTC, now));
        assertDoesNotThrow(() -> DateWindowResolver.resolve("Yesterday", UTC, now));
        assertDoesNotThrow(() -> DateWindowResolver.resolve("YESTERDAY", UTC, now));
    }

    @Test
    void unknownPresetThrows() {
        Instant now = noon(2026, 5, 13);
        assertThrows(IllegalArgumentException.class,
                () -> DateWindowResolver.resolve("TOMORROW", UTC, now));
        assertThrows(IllegalArgumentException.class,
                () -> DateWindowResolver.resolve("", UTC, now));
    }
}
