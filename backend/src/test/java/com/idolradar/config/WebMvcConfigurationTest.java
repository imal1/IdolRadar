package com.idolradar.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebMvcConfigurationTest {
    @Test
    void accountDeletionAndItsPublicReceiptUseTheExpectedInterceptorLayers() {
        assertTrue(WebMvcConfiguration.PROTECTED_ENDPOINTS.contains("/v1/me"));
        assertTrue(WebMvcConfiguration.PROTECTED_ENDPOINTS
                .contains("/v1/me/account-deletion-requests"));
        assertTrue(WebMvcConfiguration.PUBLIC_IP_RATE_LIMITED_ENDPOINTS
                .contains("/v1/account-deletions/*"));
        assertTrue(WebMvcConfiguration.PUBLIC_IP_RATE_LIMITED_ENDPOINTS
                .contains("/v1/account-deletions/*/retry"));
        assertFalse(WebMvcConfiguration.PROTECTED_ENDPOINTS
                .contains("/v1/account-deletions/*"));
        assertFalse(WebMvcConfiguration.PROTECTED_ENDPOINTS
                .contains("/v1/account-deletions/*/retry"));
    }
}
