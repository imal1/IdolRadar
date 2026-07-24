package com.idolradar.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在超时、跳转次数、响应体大小上限内下载 HTTPS RSS。
 *
 * <p>每次跳转均重新校验并解析目标；HTTP 连接仅使用已校验 DNS 结果，
 * 防止校验与连接之间发生 DNS 重绑定，同时保留原主机名供 TLS/Host 校验。
 */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class ApacheFeedDownloader implements FeedDownloader {
    private static final int BUFFER_SIZE = 8192;

    private final FeedUrlGuard guard;
    private final WorkerProperties properties;
    private final ExecutorService dnsExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ApacheFeedDownloader(FeedUrlGuard guard, WorkerProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    @Override
    public byte[] fetch(String rawUrl) {
        URI current = guard.validateUrl(rawUrl);
        for (int redirects = 0; redirects <= properties.getRssMaxRedirects(); redirects++) {
            FeedUrlGuard.ValidatedTarget target = resolveWithTimeout(current.toASCIIString());
            Response response = request(target);
            if (isRedirect(response.status())) {
                if (redirects == properties.getRssMaxRedirects()) {
                    throw new FeedException("TOO_MANY_REDIRECTS", "RSS 重定向次数过多");
                }
                String location = response.location();
                if (location == null || location.isBlank()) {
                    throw new FeedException("HTTP_ERROR", "RSS 重定向地址缺失");
                }
                try {
                    current = guard.validateUrl(current.resolve(location).toASCIIString());
                } catch (IllegalArgumentException error) {
                    throw new FeedException("INVALID_FEED_URL", "RSS 重定向地址无效", error);
                }
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw new FeedException("HTTP_ERROR", "RSS 服务返回异常状态");
            }
            if (response.body().length == 0) {
                throw new FeedException("EMPTY_FEED", "RSS 响应为空");
            }
            return response.body();
        }
        throw new FeedException("TOO_MANY_REDIRECTS", "RSS 重定向次数过多");
    }

    private FeedUrlGuard.ValidatedTarget resolveWithTimeout(String url) {
        Future<FeedUrlGuard.ValidatedTarget> future = dnsExecutor.submit(() -> guard.validateAndResolve(url));
        try {
            return future.get(properties.getRssTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new FeedException("FETCH_TIMEOUT", "RSS 请求超时", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new FeedException("FETCH_TIMEOUT", "RSS 请求被中断", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new FeedException("DNS_LOOKUP_FAILED", "RSS 域名解析失败", error.getCause());
        }
    }

    private Response request(FeedUrlGuard.ValidatedTarget target) {
        InetAddress[] pinned = target.addresses();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                if (!FeedUrlGuard.normalizeHost(host).equals(FeedUrlGuard.normalizeHost(target.uri().getHost()))) {
                    throw new java.net.UnknownHostException("Unvalidated host");
                }
                return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                resolve(host);
                return FeedUrlGuard.normalizeHost(target.uri().getHost());
            }
        };
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .build();
        Timeout timeout = Timeout.ofMilliseconds(properties.getRssTimeout().toMillis());
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .setRedirectsEnabled(false)
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            HttpGet request = new HttpGet(target.uri());
            request.setHeader(HttpHeaders.ACCEPT,
                    "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.2");
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            request.setHeader(HttpHeaders.USER_AGENT, "IdolRadar-RSS/2.0");
            try (CloseableHttpResponse response = client.execute(request)) {
                String encoding = response.getFirstHeader(HttpHeaders.CONTENT_ENCODING) == null
                        ? "identity"
                        : response.getFirstHeader(HttpHeaders.CONTENT_ENCODING).getValue();
                if (!encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
                    throw new FeedException("UNSUPPORTED_ENCODING", "RSS 响应压缩格式不支持");
                }
                long declaredLength = response.getEntity() == null ? 0 : response.getEntity().getContentLength();
                if (declaredLength > properties.getRssMaxResponseBytes()) {
                    throw new FeedException("RESPONSE_TOO_LARGE", "RSS 响应过大");
                }
                String location = response.getFirstHeader(HttpHeaders.LOCATION) == null
                        ? null
                        : response.getFirstHeader(HttpHeaders.LOCATION).getValue();
                byte[] body = response.getEntity() == null
                        ? new byte[0]
                        : readLimited(response.getEntity().getContent(), properties.getRssMaxResponseBytes());
                return new Response(response.getCode(), location, body);
            }
        } catch (FeedException error) {
            throw error;
        } catch (java.net.SocketTimeoutException error) {
            throw new FeedException("FETCH_TIMEOUT", "RSS 请求超时", error);
        } catch (IOException error) {
            if (error instanceof org.apache.hc.client5.http.ConnectTimeoutException
                    || error.getCause() instanceof java.net.SocketTimeoutException) {
                throw new FeedException("FETCH_TIMEOUT", "RSS 请求超时", error);
            }
            throw new FeedException("HTTP_ERROR", "RSS 请求失败", error);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > maxBytes) {
                throw new FeedException("RESPONSE_TOO_LARGE", "RSS 响应过大");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private record Response(int status, String location, byte[] body) {}

    @PreDestroy
    void close() {
        dnsExecutor.shutdownNow();
    }
}
