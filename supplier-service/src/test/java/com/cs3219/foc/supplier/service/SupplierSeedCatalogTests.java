package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierSeedCatalogTests {
    @Test
    void readsQuotedFieldsAndInactiveSuppliers() {
        var suppliers = SupplierSeedCatalog.load();
        var coop = suppliers.stream()
                .filter(supplier -> supplier.id().equals(UUID.fromString("d20db68d-b147-53ea-a588-d347550859f2")))
                .findFirst()
                .orElseThrow();

        assertThat(coop.locationDescription()).isEqualTo("Inside Central Library, on the right");
        assertThat(coop.openingHours()).hasSize(7);
        assertThat(suppliers).anyMatch(supplier -> !supplier.active());
    }

    @Test
    void rejectsAnUnterminatedQuote() {
        assertThatThrownBy(() -> SupplierSeedCatalog.parse("name\n\"open"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated quote");
    }
}
