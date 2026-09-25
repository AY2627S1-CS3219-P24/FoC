package com.cs3219.foc.user.repository;

import com.cs3219.foc.user.model.entity.AvatarCleanupTask;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AvatarCleanupRepository extends JpaRepository<AvatarCleanupTask, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AvatarCleanupTask t where t.avatarKey = :key")
    Optional<AvatarCleanupTask> findForUpdateByKey(@Param("key") String key);

    List<AvatarCleanupTask> findTop50ByEligibleAtLessThanEqualOrderByEligibleAtAsc(OffsetDateTime time);
}
