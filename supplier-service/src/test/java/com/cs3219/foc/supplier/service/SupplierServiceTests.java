package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.mapper.SupplierMapperImpl;
import com.cs3219.foc.supplier.model.dto.OpeningHoursDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.dto.SupplierSearchCriteria;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import com.cs3219.foc.supplier.model.entity.SupplierOpeningHours;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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

    private static SupplierRequest request(String name, String building) {
        return new SupplierRequest(
                name,
                SupplierCategory.COFFEE,
                building,
                "1",
                null,
                null,
                null,
                List.of(
                        new OpeningHoursDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(18, 0)),
                        new OpeningHoursDto(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(14, 0))),
                null);
    }

    private Supplier existingSupplier() {
        var supplier = Supplier.builder()
                .id(supplierId)
                .name("Old name")
                .category(SupplierCategory.FOOD)
                .building("COM2")
                .active(false)
                .openingHours(new ArrayList<>())
                .build();
        supplier.replaceOpeningHours(new ArrayList<>(List.of(SupplierOpeningHours.builder()
                .dayOfWeek(DayOfWeek.SUNDAY)
                .opensAt(LocalTime.of(9, 0))
                .closesAt(LocalTime.of(17, 0))
                .build())));
        return supplier;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsSuppliersSortedByName() {
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(existingSupplier()));
        assertThat(service.listSuppliers(SupplierSearchCriteria.activeOnly())).hasSize(1);
        verify(repository).findAll(any(Specification.class), eq(Sort.by("name")));
    }

    @Test
    void createsActiveSupplierWithWeeklyHours() {
        var created = service.createSupplier(request("CoffeeBean", "COM3"));
        assertThat(created.name()).isEqualTo("CoffeeBean");
        assertThat(created.active()).isTrue();
        assertThat(created.openingHours())
                .extracting(OpeningHoursDto::dayOfWeek)
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);
    }

    @Test
    void normalizesSpacingBeforeSaving() {
        var created = service.createSupplier(request("  Cool   Spot ", " COM2  "));
        assertThat(created.name()).isEqualTo("Cool Spot");
        assertThat(created.building()).isEqualTo("COM2");
    }

    @Test
    void updateReplacesDetailsAndHoursButKeepsIdentityAndStatus() {
        when(repository.findById(supplierId)).thenReturn(Optional.of(existingSupplier()));
        var updated = service.updateSupplier(supplierId, request("New name", "COM3"));
        assertThat(updated.id()).isEqualTo(supplierId);
        assertThat(updated.name()).isEqualTo("New name");
        assertThat(updated.category()).isEqualTo(SupplierCategory.COFFEE);
        assertThat(updated.active()).isFalse();
        assertThat(updated.openingHours())
                .extracting(OpeningHoursDto::dayOfWeek)
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);
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
        assertThatThrownBy(() -> service.updateSupplier(supplierId, request("x", "COM3")))
                .isInstanceOf(SupplierNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }
}
