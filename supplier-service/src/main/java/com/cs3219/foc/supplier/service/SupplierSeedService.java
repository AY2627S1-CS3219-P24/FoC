package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierOpeningHours;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierSeedService {
    private final SupplierRepository supplierRepository;

    /**
     * Inserts catalog suppliers whose ids are not already stored. An existing supplier and its opening hours are left
     * unchanged.
     *
     * @return how many suppliers were inserted
     */
    @Transactional
    public int seedMissingSuppliers() {
        var inserted = 0;
        for (var row : SupplierSeedCatalog.load()) {
            if (supplierRepository.existsById(row.id())) {
                continue;
            }
            var supplier = Supplier.builder()
                    .id(row.id())
                    .name(row.name())
                    .category(row.category())
                    .building(row.building())
                    .floor(row.floor())
                    .locationDescription(row.locationDescription())
                    .latitude(row.latitude())
                    .longitude(row.longitude())
                    .active(row.active())
                    .openingHours(new ArrayList<>())
                    .build();
            supplier.replaceOpeningHours(row.openingHours().stream()
                    .map(hours -> SupplierOpeningHours.builder()
                            .dayOfWeek(hours.dayOfWeek())
                            .opensAt(hours.opensAt())
                            .closesAt(hours.closesAt())
                            .build())
                    .toList());
            supplierRepository.saveAndFlush(supplier);
            inserted++;
        }
        return inserted;
    }
}
