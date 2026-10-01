package com.cs3219.foc.supplier.mapper;

import com.cs3219.foc.supplier.model.dto.OpeningHoursDto;
import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierOpeningHours;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper
public interface SupplierMapper {
    @Mapping(target = "imageUrl", expression = "java(imageUrlOf(supplier))")
    SupplierDto toSupplierDto(Supplier supplier);

    /**
     * Access the image location, will be null if no image
     * The key in the query string will change every upload
     */
    default String imageUrlOf(Supplier supplier) {
        if (supplier.getImageKey() == null) {
            return null;
        }
        return "/api/suppliers/" + supplier.getId() + "/image?v="
                + supplier.getImageKey().substring(0, 8);
    }

    OpeningHoursDto toOpeningHoursDto(SupplierOpeningHours hours);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "openingHours", ignore = true)
    @Mapping(target = "imageKey", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Supplier toSupplier(SupplierRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "openingHours", ignore = true)
    @Mapping(target = "imageKey", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateSupplier(SupplierRequest request, @MappingTarget Supplier supplier);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "supplier", ignore = true)
    SupplierOpeningHours toOpeningHours(OpeningHoursDto hours);

    List<SupplierOpeningHours> toOpeningHours(List<OpeningHoursDto> hours);
}
