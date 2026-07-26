package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FeedUrlGuardTest {
    @Test
    void springUsesTheProductionConstructorWhenTestConstructorsAlsoExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("app.mode", "worker")));
            context.registerBean(WorkerProperties.class);
            context.registerBean(FeedUrlGuard.class);
            context.refresh();

            assertThat(context.getBean(FeedUrlGuard.class)).isNotNull();
        }
    }

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

    @Test
    void allowsPrivateHttpOnlyForExactTrustedRssHubOrigin() throws Exception {
        FeedUrlGuard guard = new FeedUrlGuard(
                host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") },
                List.of(URI.create("http://127.0.0.1:1200")));

        FeedUrlGuard.ValidatedTarget target = guard.validateAndResolve(
                "http://127.0.0.1:1200/weibo/user/5492443184#ignored");

        assertThat(target.uri().toASCIIString())
                .isEqualTo("http://127.0.0.1:1200/weibo/user/5492443184");
        assertThatThrownBy(() -> guard.validateUrl("http://127.0.0.1:1201/feed"))
                .isInstanceOf(FeedException.class)
                .extracting(error -> ((FeedException) error).code())
                .isEqualTo("INVALID_FEED_URL");
    }
}
