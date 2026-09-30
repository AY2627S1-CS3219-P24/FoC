package com.cs3219.foc.supplier.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.properties.hibernate.default_schema=suppliers")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupplierSpecificationsTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private SupplierRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        repository.saveAll(List.of(
                supplier("Cool Spot", SupplierCategory.FOOD, "COM2", "Opp LT16", "09:00", "21:30", true),
                supplier("TOMORO COFFEE", SupplierCategory.COFFEE, "HSSML", null, "08:15", "18:00", true),
                supplier("Supersnacks", SupplierCategory.FOOD, "PGP", "Block 10", "11:00", "02:00", true),
                supplier("Octobox", SupplierCategory.SHOPPING, "PGP", "Near 100% entrance", "00:00", "23:59", true),
                supplier("Closed Cafe", SupplierCategory.COFFEE, "COM2", null, "09:00", "18:00", false)));
    }

    private static Supplier supplier(
            String name,
            SupplierCategory category,
            String building,
            String description,
            String opening,
            String closing,
            boolean active) {
        return Supplier.builder()
                .name(name)
                .category(category)
                .building(building)
                .locationDescription(description)
                .openingTime(LocalTime.parse(opening))
                .closingTime(LocalTime.parse(closing))
                .active(active)
                .build();
    }

    private List<String> names(Specification<Supplier> spec) {
        return repository.findAll(spec, Sort.by("name")).stream()
                .map(Supplier::getName)
                .toList();
    }

    @Test
    void activeOnlyHidesDeactivatedSuppliers() {
        assertThat(names(SupplierSpecifications.isActive())).doesNotContain("Closed Cafe");
    }

    @Test
    void textMatchesNameBuildingOrDescriptionIgnoringCase() {
        assertThat(names(SupplierSpecifications.matchesText("coffee"))).containsExactly("TOMORO COFFEE");
        assertThat(names(SupplierSpecifications.matchesText("com2"))).containsExactly("Closed Cafe", "Cool Spot");
        assertThat(names(SupplierSpecifications.matchesText("lt16"))).containsExactly("Cool Spot");
    }

    @Test
    void textTreatsLikeWildcardsLiterally() {
        assertThat(names(SupplierSpecifications.matchesText("100%"))).containsExactly("Octobox");
        assertThat(names(SupplierSpecifications.matchesText("%"))).containsExactly("Octobox");
    }

    @Test
    void filtersByAnyOfTheGivenCategories() {
        assertThat(names(SupplierSpecifications.inCategories(
                        List.of(SupplierCategory.COFFEE, SupplierCategory.SHOPPING))))
                .containsExactly("Closed Cafe", "Octobox", "TOMORO COFFEE");
    }

    @Test
    void openAtHandlesSameDayHours() {
        var open = SupplierSpecifications.openAt(LocalTime.of(9, 0)).and(SupplierSpecifications.isActive());
        assertThat(names(open)).containsExactly("Cool Spot", "Octobox", "TOMORO COFFEE");

        var afterClosing = SupplierSpecifications.openAt(LocalTime.of(21, 30));
        assertThat(names(afterClosing)).containsExactly("Octobox", "Supersnacks");
    }

    @Test
    void openAtHandlesSuppliersClosingAfterMidnight() {
        assertThat(names(SupplierSpecifications.openAt(LocalTime.of(1, 30)))).containsExactly("Octobox", "Supersnacks");
        assertThat(names(SupplierSpecifications.openAt(LocalTime.of(2, 0)))).containsExactly("Octobox");
    }

    @Test
    void filtersCombineWithAnd() {
        var spec = Specification.allOf(
                SupplierSpecifications.isActive(),
                SupplierSpecifications.inCategories(List.of(SupplierCategory.FOOD)),
                SupplierSpecifications.openAt(LocalTime.of(23, 0)));
        assertThat(names(spec)).containsExactly("Supersnacks");
    }
}
