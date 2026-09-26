package com.cs3219.foc.supplier.repository;

import com.cs3219.foc.supplier.model.entity.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    List<Supplier> findAllByOrderByNameAsc();

    List<Supplier> findAllByActiveTrueOrderByNameAsc();

    Optional<Supplier> findByIdAndActiveTrue(UUID id);
}
