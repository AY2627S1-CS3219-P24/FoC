package com.cs3219.foc.order.infrastructure.credit;

import com.cs3219.foc.order.config.OrderCreditProperties;
import com.cs3219.foc.order.model.dto.CreditReservationRequest;
import com.cs3219.foc.order.model.dto.CreditReservationResponse;
import java.net.http.HttpClient;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CreditReservationClient {
    private final RestClient client;

    public CreditReservationClient(OrderCreditProperties properties) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public CreditReservationResponse reserve(CreditReservationRequest request, String token) {
        try {
            var response = client.post()
                    .uri("/credits/reservations")
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CreditReservationResponse.class);
            if (response == null
                    || response.outcome() == null
                    || !Set.of("RESERVED", "INSUFFICIENT_BALANCE").contains(response.outcome())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid Credit reservation response");
            }
            return response;
        } catch (RestClientException ex) {
            if (ex instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(response.getStatusCode(), "Credit reservation request rejected", ex);
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Credit is unavailable; retry with the same Idempotency-Key", ex);
        }
    }
}
