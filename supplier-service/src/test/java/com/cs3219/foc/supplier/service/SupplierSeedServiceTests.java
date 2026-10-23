package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierSeedServiceTests {
    @Test
    void skipsSuppliersThatAlreadyExist() {
        var repository = mock(SupplierRepository.class);
        var existingId = UUID.fromString("438f630d-70f1-519b-a271-a0ec74f0409b");
        when(repository.existsById(any())).thenAnswer(invocation -> existingId.equals(invocation.getArgument(0)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new SupplierSeedService(repository);

        var inserted = service.seedMissingSuppliers();

        assertThat(inserted).isEqualTo(SupplierSeedCatalog.load().size() - 1);
        verify(repository, never()).saveAndFlush(argThat(supplier -> existingId.equals(supplier.getId())));
        var saved = ArgumentCaptor.forClass(Supplier.class);
        verify(repository, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(supplier -> assertThat(supplier.getId()).isNotNull());
    }
}
