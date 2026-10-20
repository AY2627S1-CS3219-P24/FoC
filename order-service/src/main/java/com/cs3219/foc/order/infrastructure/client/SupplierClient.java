package com.cs3219.foc.order.infrastructure.client;

import com.cs3219.foc.order.config.ServiceClientProperties;
import com.cs3219.foc.order.model.dto.SupplierPickupDto;
import com.cs3219.foc.order.security.AuthenticationContextService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SupplierClient {
    private final RestClient client;
    private final AuthenticationContextService authenticationContextService;

    public SupplierClient(
            ServiceClientProperties properties, AuthenticationContextService authenticationContextService) {
        this.authenticationContextService = authenticationContextService;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder()
                .baseUrl(properties.supplierUrl())
                .requestFactory(factory)
                .build();
    }

    public SupplierPickupDto get(UUID id) {
        if (!(authenticationContextService.requireAuthentication() instanceof JwtAuthenticationToken authentication)) {
            throw new AuthenticationCredentialsNotFoundException("JWT is required for supplier lookup");
        }
        return client.get()
                .uri("/suppliers/{id}", id)
                .headers(headers ->
                        headers.setBearerAuth(authentication.getToken().getTokenValue()))
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier is not available");
                })
                .body(SupplierPickupDto.class);
    }
}
