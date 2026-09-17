package com.cs3219.foc.user.repository;

import com.cs3219.foc.user.model.entity.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    void deleteAllByExpiresAtBefore(OffsetDateTime dateTime);
}
