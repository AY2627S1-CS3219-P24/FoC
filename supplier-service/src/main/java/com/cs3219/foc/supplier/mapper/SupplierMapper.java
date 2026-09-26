package com.cs3219.foc.supplier.mapper;

import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.entity.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper
public interface SupplierMapper {
    SupplierDto toSupplierDto(Supplier supplier);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Supplier toSupplier(SupplierRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateSupplier(SupplierRequest request, @MappingTarget Supplier supplier);
}
