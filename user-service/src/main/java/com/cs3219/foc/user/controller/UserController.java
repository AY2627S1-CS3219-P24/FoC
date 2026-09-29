package com.cs3219.foc.user.controller;

import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.PageDto;
import com.cs3219.foc.user.model.dto.UpdateUserRequest;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.service.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    public PageDto<UserProfileDto> listUsers(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UserRole role) {
        return userService.listUsers(pageable, search, role);
    }

    @GetMapping("/{id}")
    public UserProfileDto getUser(@PathVariable UUID id) {
        return userService.getUserProfile(id);
    }

    @PatchMapping("/{id}")
    public UserProfileDto updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }


    @GetMapping("/me")
    public UserProfileDto getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return userService.getUserProfile(UUID.fromString(jwt.getSubject()));
    }
    @PutMapping("/me")
    public UserProfileDto updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateUserProfileRequest request) {
        return userService.updateUserProfile(UUID.fromString(jwt.getSubject()), request);
    }
}
