package com.cs3219.foc.user.mapper;

import com.cs3219.foc.user.model.dto.UserDto;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper
public interface UserMapper {
    @Mapping(target = "avatarUrl", source = ".", qualifiedByName = "avatarUrl")
    UserProfileDto toUserProfileDto(User user);

    @Mapping(target = "avatarUrl", source = ".", qualifiedByName = "avatarUrl")
    UserDto toUserDto(User user);

    @Named("avatarUrl")
    default String avatarUrl(User user) {
        if (user == null || user.getAvatarKey() == null) {
            return null;
        }
        return "/api/users/" + user.getId() + "/avatar?v=" + user.getAvatarKey();
    }
}
