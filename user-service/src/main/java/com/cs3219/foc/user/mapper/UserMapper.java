package com.cs3219.foc.user.mapper;

import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {
    UserProfileDto toUserProfileDto(User user);
}
