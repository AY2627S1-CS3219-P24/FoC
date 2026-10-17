package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.model.dto.SupplierDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SupplierImageService {
    private final SupplierService supplierService;
    private final SupplierImageStorageService supplierImageStorageService;
    private final SupplierImageProcessor supplierImageProcessor;

    public SupplierDto uploadSupplierImage(UUID supplierId, MultipartFile file) {
        var bytes = supplierImageProcessor.processSupplierImage(file);
        var key = supplierImageStorageService.storeSupplierImage(supplierId, bytes);
        try {
            return supplierService.updateImageKey(supplierId, key);
        } catch (RuntimeException exception) {
            supplierImageStorageService.deleteSupplierImage(key);
            throw exception;
        }
    }

    public SupplierDto removeSupplierImage(UUID supplierId) {
        return supplierService.clearImageKey(supplierId);
    }
}
