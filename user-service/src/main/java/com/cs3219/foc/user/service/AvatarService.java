package com.cs3219.foc.user.service;

import com.cs3219.foc.user.model.dto.UserProfileDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AvatarService {
    private final UserService userService;
    private final AvatarStorageService avatarStorageService;
    private final AvatarImageProcessor avatarImageProcessor;

    public UserProfileDto uploadAvatar(UUID userId, MultipartFile file) {
        var bytes = avatarImageProcessor.processAvatar(file);
        var key = avatarStorageService.storeAvatar(userId, bytes);
        try {
            return userService.updateAvatarKey(userId, key);
        } catch (RuntimeException exception) {
            avatarStorageService.deleteAvatar(key);
            throw exception;
        }
    }

    public void removeAvatar(UUID userId) {
        userService.clearAvatarKey(userId);
    }
}
