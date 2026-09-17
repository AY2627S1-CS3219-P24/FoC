package com.cs3219.foc.credit.infrastructure.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StreamPublisher {
    private final StreamBridge streamBridge;

    public void publish(String bindingName, Object payload) {
        streamBridge.send(bindingName, payload);
    }
}
