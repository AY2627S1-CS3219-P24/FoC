package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.mapper.SupplierMapperImpl;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.dto.SupplierSearchCriteria;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

class SupplierServiceTests {
    private final UUID supplierId = UUID.randomUUID();
    // SG 23:00
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T15:00:00Z"), ZoneId.of("Asia/Singapore"));
    private SupplierRepository repository;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        repository = mock(SupplierRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new SupplierService(repository, new SupplierMapperImpl(), clock);
    }

    private static SupplierRequest request(String name) {
        return new SupplierRequest(
                name,
                SupplierCategory.COFFEE,
                "COM3",
                "1",
                null,
                null,
                null,
                LocalTime.of(8, 0),
                LocalTime.of(18, 0),
                null);
    }

    private Supplier existingSupplier() {
        return Supplier.builder()
                .id(supplierId)
                .name("Old name")
                .category(SupplierCategory.FOOD)
                .building("COM2")
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(17, 0))
                .active(false)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsSuppliersSortedByName() {
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(existingSupplier()));
        assertThat(service.listSuppliers(SupplierSearchCriteria.activeOnly())).hasSize(1);
        verify(repository).findAll(any(Specification.class), eq(Sort.by("name")));
    }

    @Test
    void createsActiveSupplier() {
        var created = service.createSupplier(request("CoffeeBean"));
        assertThat(created.name()).isEqualTo("CoffeeBean");
        assertThat(created.active()).isTrue();
    }

    @Test
    void updateReplacesDetailsButKeepsIdentityAndStatus() {
        when(repository.findById(supplierId)).thenReturn(Optional.of(existingSupplier()));
        var updated = service.updateSupplier(supplierId, request("New name"));
        assertThat(updated.id()).isEqualTo(supplierId);
        assertThat(updated.name()).isEqualTo("New name");
        assertThat(updated.category()).isEqualTo(SupplierCategory.COFFEE);
        assertThat(updated.active()).isFalse();
    }

    @Test
    void togglesActiveFlag() {
        when(repository.findById(supplierId)).thenReturn(Optional.of(existingSupplier()));
        assertThat(service.setSupplierActive(supplierId, true).active()).isTrue();
    }

    @Test
    void inactiveSupplierIsHiddenFromNonAdmins() {
        when(repository.findByIdAndActiveTrue(supplierId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSupplier(supplierId, false)).isInstanceOf(SupplierNotFoundException.class);
    }

    @Test
    void updatingMissingSupplierFails() {
        when(repository.findById(supplierId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateSupplier(supplierId, request("x")))
                .isInstanceOf(SupplierNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }
}
