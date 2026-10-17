package com.cs3219.foc.user.mapper;

import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UpdateUserRequest;
import com.cs3219.foc.user.model.dto.UserDto;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import org.mapstruct.BeanMapping;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(collectionMappingStrategy = CollectionMappingStrategy.TARGET_IMMUTABLE)
public interface UserMapper {
    @Mapping(target = "avatarUrl", source = ".", qualifiedByName = "avatarUrl")
    UserProfileDto toUserProfileDto(User user);

    @Mapping(target = "avatarUrl", source = ".", qualifiedByName = "avatarUrl")
    UserDto toUserDto(User user);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "roles", source = "roles")
    @Mapping(
            target = "phoneNumber",
            source = "phoneNumber",
            qualifiedByName = "emptyToNull",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(
            target = "faculty",
            source = "faculty",
            qualifiedByName = "emptyToNull",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateUser(UpdateUserRequest request, @MappingTarget User user);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "phoneNumber", source = "phoneNumber")
    @Mapping(target = "faculty", source = "faculty")
    void updateUserProfile(UpdateUserProfileRequest request, @MappingTarget User user);

    @Named("avatarUrl")
    default String avatarUrl(User user) {
        if (user == null || user.getAvatarKey() == null) {
            return null;
        }
        return "/api/users/" + user.getId() + "/avatar?v=" + user.getAvatarKey();
    }

    @Named("emptyToNull")
    default String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
