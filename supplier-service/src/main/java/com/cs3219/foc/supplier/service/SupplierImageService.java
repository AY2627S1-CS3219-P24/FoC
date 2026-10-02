package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.exception.SupplierImageException;
import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.infrastructure.storage.SupplierImageStorage;
import com.cs3219.foc.supplier.mapper.SupplierMapper;
import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads, serves and removes supplier images.
 * Files are only deleted once the database change has committed, so failed save will never miss file
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupplierImageService {
    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final SupplierImageStorage storage;
    private final SupplierImageProcessor processor;

    @Transactional
    public SupplierDto upload(UUID supplierId, MultipartFile file) {
        var supplier = findSupplier(supplierId);
        var image = processor.process(file);
        var key = UUID.randomUUID() + ".jpg";
        afterCompletion(key, true);
        storage.write(key, image);

        var previous = supplier.getImageKey();
        supplier.setImageKey(key);
        supplierRepository.saveAndFlush(supplier);
        afterCompletion(previous, false);
        return supplierMapper.toSupplierDto(supplier);
    }

    @Transactional(readOnly = true)
    public byte[] read(UUID supplierId) {
        var key = findSupplier(supplierId).getImageKey();
        if (key == null) {
            throw new SupplierImageException(HttpStatus.NOT_FOUND, "Image not found");
        }
        return storage.read(key);
    }

    // remove the uploaded image, leaving the supplier without one
    @Transactional
    public SupplierDto remove(UUID supplierId) {
        var supplier = findSupplier(supplierId);
        var previous = supplier.getImageKey();
        supplier.setImageKey(null);
        supplierRepository.saveAndFlush(supplier);
        afterCompletion(previous, false);
        return supplierMapper.toSupplierDto(supplier);
    }

    private Supplier findSupplier(UUID id) {
        return supplierRepository.findById(id).orElseThrow(() -> new SupplierNotFoundException(id));
    }

    /**
     * delete {@code key} once the transaction finishes
     */
    private void afterCompletion(String key, boolean onRollback) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if ((status == STATUS_ROLLED_BACK) == onRollback) {
                    tryDelete(key);
                }
            }
        });
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Supplier image could not be deleted: {}", key, exception);
        }
    }
}
