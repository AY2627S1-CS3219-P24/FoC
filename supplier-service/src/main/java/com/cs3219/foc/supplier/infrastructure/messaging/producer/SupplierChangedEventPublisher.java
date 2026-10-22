package com.cs3219.foc.supplier.infrastructure.messaging.producer;

import com.cs3219.foc.supplier.model.dto.event.SupplierChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SupplierChangedEventPublisher {
    public static final String BINDING = "supplierChanged-out-0";
    private final StreamBridge streamBridge;

    @ApplicationModuleListener(id = "supplier-changed")
    public void publish(SupplierChangedEvent event) {
        if (!streamBridge.send(BINDING, event)) {
            throw new IllegalStateException("Supplier-changed message was not accepted for publication");
        }
    }
}
