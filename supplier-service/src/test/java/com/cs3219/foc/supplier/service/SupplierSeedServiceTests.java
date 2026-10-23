package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierSeedServiceTests {
    @Test
    void skipsSuppliersThatAlreadyExist() {
        var repository = mock(SupplierRepository.class);
        var existingId = UUID.fromString("438f630d-70f1-519b-a271-a0ec74f0409b");
        when(repository.insertIfAbsent(
                        any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> existingId.equals(invocation.getArgument(0)) ? 0 : 1);
        var service = new SupplierSeedService(repository);

        var inserted = service.seedMissingSuppliers();

        assertThat(inserted).isEqualTo(SupplierSeedCatalog.load().size() - 1);
        verify(repository, never()).insertOpeningHours(eq(existingId), anyShort(), any(LocalTime.class), any());
    }
}
