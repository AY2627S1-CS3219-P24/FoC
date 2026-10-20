package com.cs3219.foc.order.infrastructure.client;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.order.config.ServiceClientProperties;
import com.cs3219.foc.order.exception.CreditAccountPendingException;
import com.cs3219.foc.order.security.AuthenticationContextService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class CreditClientTests {
    private HttpServer server;
    private CreditClient client;
    private volatile int status;
    private volatile String body;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        status = 200;
        body = "{\"availableBalance\":0,\"reservedBalance\":0}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/credits/me", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        client = new CreditClient(
                new ServiceClientProperties(
                        "http://unused/api",
                        "http://unused/api",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                new AuthenticationContextService());
        var jwt = Jwt.withTokenValue("current-access-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void stop() {
        SecurityContextHolder.clearContext();
        server.stop(0);
    }

    @Test
    void zeroBalanceIsReadyAndForwardsTheCurrentToken() {
        assertThatCode(client::requireAccountReady).doesNotThrowAnyException();
        assertThat(authorization.get()).isEqualTo("Bearer current-access-token");
    }

    @Test
    void explicitPendingAccountIsDistinguishedFromOtherErrors() {
        status = 409;
        body = "{\"code\":\"CREDIT_ACCOUNT_PENDING\",\"message\":\"Preparing\"}";
        assertThatThrownBy(client::requireAccountReady).isInstanceOf(CreditAccountPendingException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 409, 503})
    void otherHttpFailuresAreNotDisguisedAsPendingAccounts(int responseStatus) {
        status = responseStatus;
        body = "{\"code\":\"OTHER_ERROR\",\"message\":\"Request failed\"}";
        assertThatThrownBy(client::requireAccountReady)
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(error -> assertThat(((RestClientResponseException) error)
                                .getStatusCode()
                                .value())
                        .isEqualTo(responseStatus));
    }

    @Test
    void malformedConflictResponseRemainsAnUpstreamError() {
        status = 409;
        body = "not-json";
        assertThatThrownBy(client::requireAccountReady).isInstanceOf(RestClientException.class);
    }
}
