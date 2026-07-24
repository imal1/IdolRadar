package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class FeedUrlGuardTest {
    @Test
    void acceptsHttpsAndPinsOnlyPublicAddresses() throws Exception {
        FeedUrlGuard guard = new FeedUrlGuard(host -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8")
        });

        FeedUrlGuard.ValidatedTarget target = guard.validateAndResolve("https://feeds.example.com/rss#fragment");

        assertThat(target.uri().toASCIIString()).isEqualTo("https://feeds.example.com/rss");
        assertThat(target.addresses()).extracting(InetAddress::getHostAddress).containsExactly("8.8.8.8");
    }

    @Test
    void rejectsNonHttpsAndLocalHostnames() {
        FeedUrlGuard guard = new FeedUrlGuard(host -> new InetAddress[0]);

        assertThatThrownBy(() -> guard.validateUrl("http://example.com/rss"))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("INVALID_FEED_URL");
        assertThatThrownBy(() -> guard.validateUrl("https://metadata.google.internal/feed"))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("UNSAFE_FEED_URL");
    }

    @Test
    void rejectsDnsAnswerSetWhenAnyAddressIsPrivate() throws Exception {
        FeedUrlGuard guard = new FeedUrlGuard(host -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1")
        });

        assertThatThrownBy(() -> guard.validateAndResolve("https://example.com/rss"))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("UNSAFE_FEED_URL");
    }

    @Test
    void rejectsDocumentationAndMappedPrivateAddresses() throws Exception {
        assertThat(FeedUrlGuard.isUnsafeAddress(InetAddress.getByName("203.0.113.4"))).isTrue();
        assertThat(FeedUrlGuard.isUnsafeAddress(InetAddress.getByName("::ffff:192.168.1.2"))).isTrue();
        assertThat(FeedUrlGuard.isUnsafeAddress(InetAddress.getByName("2001:db8::1"))).isTrue();
    }
}
