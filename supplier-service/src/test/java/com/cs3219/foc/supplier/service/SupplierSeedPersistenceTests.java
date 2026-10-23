package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs3219.foc.supplier.integration.AbstractIntegrationTest;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SupplierSeedPersistenceTests extends AbstractIntegrationTest {
    @Autowired
    private SupplierSeedService supplierSeedService;

    @Autowired
    private SupplierRepository supplierRepository;

    @Test
    void storesCatalogIdsAndLeavesExistingSuppliersUnchanged() {
        var inserted = supplierSeedService.seedMissingSuppliers();
        var id = UUID.fromString("438f630d-70f1-519b-a271-a0ec74f0409b");
        var supplier = supplierRepository.findById(id).orElseThrow();

        assertThat(inserted).isEqualTo(SupplierSeedCatalog.load().size());
        assertThat(supplier.getName()).isEqualTo("Anna's x Soup Union");
        assertThat(supplier.getOpeningHours()).hasSize(7);
        assertThat(supplierSeedService.seedMissingSuppliers()).isZero();
        assertThat(supplierRepository.findById(id).orElseThrow().getName()).isEqualTo("Anna's x Soup Union");
    }
}
