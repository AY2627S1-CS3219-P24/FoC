package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.mapper.SupplierMapper;
import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.dto.SupplierSearchCriteria;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import com.cs3219.foc.supplier.repository.SupplierSpecifications;
import java.time.Clock;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final Clock clock;

    /** list suppliers that match filters in {@code criteria}, sorted by name. */
    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers(SupplierSearchCriteria criteria) {
        var filters = new ArrayList<Specification<Supplier>>();
        if (!criteria.includeInactive()) {
            filters.add(SupplierSpecifications.isActive());
        }
        if (criteria.query() != null && !criteria.query().isBlank()) {
            filters.add(SupplierSpecifications.matchesText(criteria.query().strip()));
        }
        if (criteria.categories() != null && !criteria.categories().isEmpty()) {
            filters.add(SupplierSpecifications.inCategories(criteria.categories()));
        }
        if (criteria.openNow()) {
            filters.add(SupplierSpecifications.openAt(LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES)));
        }

        return supplierRepository.findAll(Specification.allOf(filters), Sort.by("name")).stream()
                .map(supplierMapper::toSupplierDto)
                .toList();
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
