package com.oncology.intake.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for the WebClient used to call the WhatsApp Business Cloud API.
 *
 * Defensive defaults:
 *  - <b>Bounded connection pool</b>. Reactor Netty's default {@code elastic} provider
 *    is unbounded — under load the app can exhaust the file descriptor limit before
 *    Meta starts back-pressuring us. A bounded pool surfaces failure earlier as
 *    {@code PendingAcquireTimeoutException} (visible to operators) rather than as
 *    a slow death spiral.
 *  - <b>Short timeouts</b>. Meta's API normally responds in under 1 second; a 10s
 *    response timeout gives plenty of headroom while keeping our threads honest.
 *    The previous 30s was a thread-pool exhaustion vector.
 *  - <b>maxInMemorySize</b> sized just above the upload cap. We download media
 *    bytes through this same client (see {@code WhatsAppClientService}); the cap
 *    must be at least the largest report we accept ({@code app.max-upload-size-mb},
 *    default 25 MB).
 *
 * NOT configured (deliberate):
 *  - <b>Retry on POST /messages</b>. Meta's send-message endpoint is not idempotent
 *    (no idempotency key), so a retry could send a duplicate message to a patient.
 *    Add retry only on idempotent reads (e.g. GET /media/{id}) at the call site
 *    using {@code Retry.backoff(...)} when needed.
 *  - <b>Circuit breaker</b>. Resilience4j would be a good follow-up; not bundled
 *    here to keep the dependency surface small.
 */
@Configuration
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 10;
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    /** Pool size. Plenty for a single-instance deployment talking to one Meta phone number. */
    private static final int MAX_CONNECTIONS = 50;
    /** Maximum number of requests waiting for a connection before we fast-fail. */
    private static final int MAX_PENDING_ACQUIRE = 100;
    /** Time to wait for a connection from the pool before failing the call. */
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    /** Drop idle connections quickly — Meta's edge prefers fresh keep-alives. */
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(30);
    /** Forcibly recycle connections after this so we don't pin to a stale Meta server. */
    private static final Duration MAX_LIFE_TIME = Duration.ofMinutes(5);

    /**
     * Buffer size for response bodies (used when downloading media). Must be at
     * least {@code app.max-upload-size-mb} or media downloads will truncate.
     */
    private static final int MAX_IN_MEMORY_BYTES = 26 * 1024 * 1024;

    @Bean
    public WebClient whatsAppWebClient(WhatsAppConfig whatsAppConfig) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("whatsapp-api")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireMaxCount(MAX_PENDING_ACQUIRE)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .maxIdleTime(MAX_IDLE_TIME)
                .maxLifeTime(MAX_LIFE_TIME)
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(whatsAppConfig.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + whatsAppConfig.getAccessToken())
                .defaultHeader("Content-Type", "application/json")
                .filter(logRequest())
                .filter(logResponse())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }

    /** Log outgoing request method + URL only (never headers — they carry the bearer token). */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            org.slf4j.LoggerFactory.getLogger("WhatsAppClient")
                    .debug("Request: {} {}", clientRequest.method(), clientRequest.url());
            return Mono.just(clientRequest);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            org.slf4j.LoggerFactory.getLogger("WhatsAppClient")
                    .debug("Response status: {}", clientResponse.statusCode());
            return Mono.just(clientResponse);
        });
    }
}
