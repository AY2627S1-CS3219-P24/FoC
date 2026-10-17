package com.cs3219.foc.user.service;

import com.cs3219.foc.user.config.AvatarStorageProperties;
import com.cs3219.foc.user.infrastructure.storage.ObjectStorage;
import com.cs3219.foc.user.model.dto.event.AvatarDeletionRequestedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AvatarStorageService {
    private static final String PREFIX = "avatars/";

    private final ObjectStorage objectStorage;
    private final AvatarStorageProperties avatarStorageProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void deleteAvatar(String key) {
        eventPublisher.publishEvent(new AvatarDeletionRequestedEvent(key));
    }

    public String storeAvatar(UUID userId, byte[] bytes) {
        var key = buildAvatarObjectKey(userId);
        objectStorage.putObject(key, bytes, "image/png");
        return key;
    }

    public String createPresignedUrl(String key) {
        if (key == null) return null;
        return objectStorage.createPresignedUrl(key, avatarStorageProperties.readUrlTtl());
    }

    private static String buildAvatarObjectKey(UUID userId) {
        return PREFIX + userId + "/" + UUID.randomUUID() + ".png";
    }
}
