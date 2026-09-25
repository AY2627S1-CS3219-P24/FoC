package com.cs3219.foc.user.mapper;

import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper
public interface UserMapper {
    @Mapping(target = "avatarUrl", source = "avatarKey", qualifiedByName = "avatarUrl")
    UserProfileDto toUserProfileDto(User user);

    @Named("avatarUrl")
    default String avatarUrl(String key) {
        return key == null ? null : "/users/me/avatar?v=" + key;
    }
}
