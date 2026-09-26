package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.mapper.SupplierMapper;
import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;

    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers(boolean includeInactive) {
        var suppliers = includeInactive
                ? supplierRepository.findAllByOrderByNameAsc()
                : supplierRepository.findAllByActiveTrueOrderByNameAsc();
        return suppliers.stream().map(supplierMapper::toSupplierDto).toList();
    }

    @Transactional(readOnly = true)
    public SupplierDto getSupplier(UUID id, boolean includeInactive) {
        var supplier = includeInactive ? supplierRepository.findById(id) : supplierRepository.findByIdAndActiveTrue(id);
        return supplier.map(supplierMapper::toSupplierDto).orElseThrow(() -> new SupplierNotFoundException(id));
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequest request) {
        var supplier = supplierMapper.toSupplier(request);
        return supplierMapper.toSupplierDto(supplierRepository.saveAndFlush(supplier));
    }

    @Transactional
    public SupplierDto updateSupplier(UUID id, SupplierRequest request) {
        var supplier = findSupplier(id);
        supplierMapper.updateSupplier(request, supplier);
        return supplierMapper.toSupplierDto(supplierRepository.saveAndFlush(supplier));
    }

    /** Since there might have other data may refer to supplier, so no deletion, but set if active */
    @Transactional
    public SupplierDto setSupplierActive(UUID id, boolean active) {
        var supplier = findSupplier(id);
        supplier.setActive(active);
        return supplierMapper.toSupplierDto(supplierRepository.saveAndFlush(supplier));
    }

    private Supplier findSupplier(UUID id) {
        return supplierRepository.findById(id).orElseThrow(() -> new SupplierNotFoundException(id));
    }
}
