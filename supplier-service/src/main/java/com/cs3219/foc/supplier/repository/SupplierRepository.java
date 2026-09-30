package com.cs3219.foc.supplier.repository;

import com.cs3219.foc.supplier.model.entity.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {
    // get opening hours in the same query for every supplier, to avoid extra query
    @Override
    @EntityGraph(attributePaths = "openingHours")
    List<Supplier> findAll(Specification<Supplier> spec, Sort sort);

    @Override
    @EntityGraph(attributePaths = "openingHours")
    Optional<Supplier> findById(UUID id);

    @EntityGraph(attributePaths = "openingHours")
    Optional<Supplier> findByIdAndActiveTrue(UUID id);
}
