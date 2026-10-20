package com.cs3219.foc.order.mapper;

import com.cs3219.foc.order.model.dto.ErrandActionResponse;
import com.cs3219.foc.order.model.dto.ErrandDto;
import com.cs3219.foc.order.model.dto.UserBriefDto;
import com.cs3219.foc.order.model.entity.Errand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ErrandMapper {
    @Mapping(target = "id", source = "errand.id")
    @Mapping(target = "requester", source = "requester")
    @Mapping(target = "courier", source = "courier")
    @Mapping(target = "pickup.name", source = "errand.pickupName")
    @Mapping(target = "pickup.address", source = "errand.pickupAddress")
    ErrandDto toDto(Errand errand, UserBriefDto requester, UserBriefDto courier);

    @Mapping(target = "errandId", source = "id")
    ErrandActionResponse toActionResponse(Errand errand);
}
