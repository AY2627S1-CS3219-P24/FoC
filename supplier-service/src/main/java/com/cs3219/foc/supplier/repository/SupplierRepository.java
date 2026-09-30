package com.cs3219.foc.supplier.repository;

import com.cs3219.foc.supplier.model.entity.Supplier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {
    Optional<Supplier> findByIdAndActiveTrue(UUID id);
}
