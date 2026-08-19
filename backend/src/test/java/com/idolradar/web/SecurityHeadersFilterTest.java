package com.idolradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    @Test
    void entryHtmlIsNotCachedButFingerprintedAssetIsImmutable() throws Exception {
        assertEquals("no-store", cacheControlFor("/admin/"));
        assertEquals("no-store", cacheControlFor("/admin/index.html"));
        assertEquals("no-store", cacheControlFor("/v1/home"));
        assertEquals(
                "public, max-age=31536000, immutable",
                cacheControlFor("/admin/assets/index-ZMmhtb77.js"));
    }

    @Test
    void adminPageCarriesNoindexAndFrameAndCspHeaders() throws Exception {
        MockHttpServletResponse response = filter("/admin/");

        assertEquals("noindex, nofollow", response.getHeader("X-Robots-Tag"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        String csp = response.getHeader("Content-Security-Policy");
        // 产物中没有内联 <script>，因此 script-src 不需要放宽到 'unsafe-inline'。
        assertTrue(csp.contains("script-src 'self'"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
    }

    private static String cacheControlFor(String uri) throws Exception {
        return filter(uri).getHeader("Cache-Control");
    }

    private static MockHttpServletResponse filter(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());
        return response;
    }
}
