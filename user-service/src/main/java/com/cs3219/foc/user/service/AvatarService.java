package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.AvatarException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.infrastructure.storage.AvatarStorage;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.AvatarCleanupTask;
import com.cs3219.foc.user.repository.AvatarCleanupRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarService {
    private final UserRepository users;
    private final UserMapper mapper;
    private final AvatarStorage storage;
    private final AvatarImageProcessor images;
    private final AvatarCleanupRepository tasks;
    private final AvatarCleanupService cleanup;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AvatarService(
            UserRepository users,
            UserMapper mapper,
            AvatarStorage storage,
            AvatarImageProcessor images,
            AvatarCleanupRepository tasks,
            AvatarCleanupService cleanup,
            Clock clock,
            PlatformTransactionManager manager) {
        this.users = users;
        this.mapper = mapper;
        this.storage = storage;
        this.images = images;
        this.tasks = tasks;
        this.cleanup = cleanup;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UserProfileDto upload(UUID userId, MultipartFile file) {
        var image = images.process(file);
        var key = UUID.randomUUID() + ".png";
        try {
            cleanup.reserve(key);
            var update = transactions.execute(status -> {
                // Reserve only this upload's task while doing file I/O; no account lock yet.
                var reservation = tasks.findForUpdateByKey(key)
                        .orElseThrow(
                                () -> new AvatarException(HttpStatus.SERVICE_UNAVAILABLE, "Please retry the upload"));
                storage.write(key, image);
                var user =
                        users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
                var previous = user.getAvatarKey();
                user.setAvatarKey(key);
                users.saveAndFlush(user);
                tasks.delete(reservation);
                enqueue(previous);
                return new AvatarUpdate(mapper.toUserProfileDto(user), previous);
            });
            cleanup.tryDelete(update.previousKey());
            return update.profile();
        } catch (RuntimeException exception) {
            cleanup.tryDelete(key);
            if (exception instanceof DataAccessException) {
                throw new AvatarException(HttpStatus.SERVICE_UNAVAILABLE, "Avatar could not be saved", exception);
            }
            throw exception;
        }
    }

    public byte[] read(UUID userId) {
        return transactions.execute(status -> {
            // Keep replacements/removal from deleting the file during this bounded read.
            var user = users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
            if (user.getAvatarKey() == null) {
                throw new AvatarException(HttpStatus.NOT_FOUND, "Avatar not found");
            }
            return storage.read(user.getAvatarKey());
        });
    }

    public void remove(UUID userId) {
        var previous = transactions.execute(status -> {
            var user = users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
            var key = user.getAvatarKey();
            if (key != null) {
                user.setAvatarKey(null);
                users.saveAndFlush(user);
                enqueue(key);
            }
            return key;
        });
        cleanup.tryDelete(previous);
    }

    private void enqueue(String key) {
        if (key != null) {
            tasks.save(new AvatarCleanupTask(key, OffsetDateTime.now(clock)));
        }
    }

    private record AvatarUpdate(UserProfileDto profile, String previousKey) {}
}
