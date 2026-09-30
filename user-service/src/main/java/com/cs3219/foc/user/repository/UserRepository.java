package com.cs3219.foc.user.repository;

import com.cs3219.foc.user.model.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query(value = """
        select *
        from users u
        where (
            :search = ''
            or position(:search in lower(u.name)) > 0
            or position(:search in lower(u.email)) > 0
        )
        and (
            :role is null
            or :role = any(u.roles)
        )
        """, nativeQuery = true)
    Page<User> findAllBySearchAndRolePaginated(String search, String role, Pageable pageable);

    @Query(value = "select count(*) from users u where :role = any(u.roles)", nativeQuery = true)
    long countAllByRolesContains(String role);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdateById(@Param("id") UUID id);
}
