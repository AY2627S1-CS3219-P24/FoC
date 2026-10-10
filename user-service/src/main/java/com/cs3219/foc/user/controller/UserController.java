package com.cs3219.foc.user.controller;

import com.cs3219.foc.user.model.dto.UpdateEmailRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.service.EmailChangeService;
import com.cs3219.foc.user.service.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final EmailChangeService emailChange;

    @PostMapping("/me/email-change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestEmailChange(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateEmailRequest request) {
        emailChange.requestChange(UUID.fromString(jwt.getSubject()), request.email());
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
