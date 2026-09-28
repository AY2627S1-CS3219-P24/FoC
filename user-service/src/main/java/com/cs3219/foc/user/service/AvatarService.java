package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.AvatarException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.infrastructure.storage.AvatarStorage;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
@RequiredArgsConstructor
public class AvatarService {
    private final UserRepository users;
    private final UserMapper mapper;
    private final AvatarStorage storage;
    private final AvatarImageProcessor images;

    @Transactional
    public UserProfileDto upload(UUID userId, MultipartFile file) {
        var image = images.process(file);
        var key = UUID.randomUUID() + ".png";
        // A rolled-back upload should not leave its new file behind when deletion succeeds.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    tryDelete(key);
                }
            }
        });
        try {
            storage.write(key, image);
            var user =
                    users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
            var previous = user.getAvatarKey();
            user.setAvatarKey(key);
            users.saveAndFlush(user);
            deleteAfterCommit(previous);
            return mapper.toUserProfileDto(user);
        } catch (DataAccessException exception) {
            throw new AvatarException(HttpStatus.SERVICE_UNAVAILABLE, "Avatar could not be saved", exception);
        }
    }

    @Transactional
    public byte[] read(UUID userId) {
        // Keep replacements/removal from deleting the file during this bounded read.
        var user = users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (user.getAvatarKey() == null) {
            throw new AvatarException(HttpStatus.NOT_FOUND, "Avatar not found");
        }
        return storage.read(user.getAvatarKey());
    }

    @Transactional
    public void remove(UUID userId) {
        var user = users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        var key = user.getAvatarKey();
        if (key != null) {
            user.setAvatarKey(null);
            users.saveAndFlush(user);
            deleteAfterCommit(key);
        }
    }

    private void deleteAfterCommit(String key) {
        if (key != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    tryDelete(key);
                }
            });
        }
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Avatar file could not be deleted: {}", key, exception);
        }
    }
}
