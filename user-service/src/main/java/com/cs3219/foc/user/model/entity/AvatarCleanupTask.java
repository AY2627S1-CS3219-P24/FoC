package com.cs3219.foc.user.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "avatar_cleanup_tasks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AvatarCleanupTask {
    @Id
    @Column(length = 40)
    private String avatarKey;

    @Column(nullable = false)
    private OffsetDateTime eligibleAt;
}
