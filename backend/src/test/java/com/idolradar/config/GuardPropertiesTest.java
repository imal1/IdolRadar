package com.idolradar.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardPropertiesTest {

    @Test
    void resolvesPerClientLimitAndFallsBackToDefault() {
        GuardProperties properties = new GuardProperties(2, Map.of("wechat-miniprogram", 1, "ios", 5));

        assertEquals(1, properties.limitFor("wechat-miniprogram"));
        assertEquals(1, properties.limitFor("  WeChat-MiniProgram "));
        assertEquals(5, properties.limitFor("ios"));
        // 未知客户端与未自报身份的客户端都走默认上限，不能因为拿不到标识就放开限制。
        assertEquals(2, properties.limitFor("android"));
        assertEquals(2, properties.limitFor(null));
        assertEquals(2, properties.limitFor("   "));
    }

    @Test
    void defaultsToSingleGuardWhenUnconfigured() {
        assertEquals(1, new GuardProperties(null, null).limitFor("wechat-miniprogram"));
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties(0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties(1, Map.of("ios", 0)));
        assertThrows(IllegalArgumentException.class, () -> new GuardProperties(1, Map.of("ios", -1)));
    }
}
