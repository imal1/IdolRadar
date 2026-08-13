package com.idolradar.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class JdbcIdolRadarStoreTest {

    @Test
    void shanghaiDayWindowUsesNaturalDayInsteadOfServerTimezone() {
        JdbcIdolRadarStore.DayWindow window = JdbcIdolRadarStore.shanghaiDayWindow(
                Instant.parse("2026-08-10T03:00:00Z"));

        assertEquals(OffsetDateTime.parse("2026-08-09T16:00:00Z"), window.startInclusive());
        assertEquals(OffsetDateTime.parse("2026-08-10T16:00:00Z"), window.endExclusive());
    }

    @Test
    void requestNameNormalizationMergesCasingAndSpacingButNotDifferentNames() {
        assertEquals("wang yibo", JdbcIdolRadarStore.normalizeRequestName("  Wang   Yibo "));
        assertEquals("王一博", JdbcIdolRadarStore.normalizeRequestName("王一博"));
        assertNotEquals(
                JdbcIdolRadarStore.normalizeRequestName("王一博"),
                JdbcIdolRadarStore.normalizeRequestName("王一"));
    }
}
