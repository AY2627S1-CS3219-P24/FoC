package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.config.SupplierImageStorageProperties;
import com.cs3219.foc.supplier.infrastructure.storage.ObjectStorage;
import com.cs3219.foc.supplier.model.dto.event.SupplierImageDeletionRequestedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierImageStorageService {
    private static final String PREFIX = "supplier-images/";

    private final ObjectStorage objectStorage;
    private final SupplierImageStorageProperties supplierImageStorageProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void deleteSupplierImage(String key) {
        eventPublisher.publishEvent(new SupplierImageDeletionRequestedEvent(key));
    }

    public String storeSupplierImage(UUID supplierId, byte[] bytes) {
        var key = buildSupplierImageObjectKey(supplierId);
        objectStorage.putObject(key, bytes, "image/jpeg");

        return key;
    }

    public String createPresignedUrl(String key) {
        if (key == null) return null;

        return objectStorage.createPresignedUrl(key, supplierImageStorageProperties.readUrlTtl());
    }

    private static String buildSupplierImageObjectKey(UUID supplierId) {
        return PREFIX + supplierId + "/" + UUID.randomUUID() + ".jpg";
    }
}
