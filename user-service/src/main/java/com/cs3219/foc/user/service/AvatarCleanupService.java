package com.cs3219.foc.user.service;

import com.cs3219.foc.user.infrastructure.storage.AvatarStorage;
import com.cs3219.foc.user.model.entity.AvatarCleanupTask;
import com.cs3219.foc.user.repository.AvatarCleanupRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class AvatarCleanupService {
    private final AvatarCleanupRepository tasks;
    private final UserRepository users;
    private final AvatarStorage storage;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AvatarCleanupService(
            AvatarCleanupRepository tasks,
            UserRepository users,
            AvatarStorage storage,
            Clock clock,
            PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.users = users;
        this.storage = storage;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reserve(String key) {
        // Commit before writing the file so a crash/rollback leaves a durable cleanup task.
        transactions.executeWithoutResult(status -> tasks.saveAndFlush(
                new AvatarCleanupTask(key, OffsetDateTime.now(clock).plusMinutes(10))));
    }

    public void cleanPending() {
        var now = OffsetDateTime.now(clock);
        for (var task : tasks.findTop50ByEligibleAtLessThanEqualOrderByEligibleAtAsc(now)) {
            tryDelete(task.getAvatarKey());
        }
    }

    public void tryDelete(String key) {
        if (key == null) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                var task = tasks.findForUpdateByKey(key);
                if (task.isEmpty()) {
                    return;
                }
                // Upload holds this task's lock through writing and linking the image.
                // Never remove an image still referenced by a current profile.
                if (!users.existsByAvatarKey(key)) {
                    storage.delete(key);
                }
                tasks.delete(task.get());
            });
        } catch (RuntimeException exception) {
            // Keep the task for a later retry; cleanup cannot undo a successful upload.
            log.warn("Avatar cleanup will be retried for {}", key, exception);
        }
    }
}
