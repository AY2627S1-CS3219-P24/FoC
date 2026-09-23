package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTests {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserService service =
            new UserService(repository, mock(PasswordEncoder.class), Mappers.getMapper(UserMapper.class));
    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(userId)
                .name("Alex Tan")
                .email("alex@example.com")
                .passwordHash("stored-hash")
                .roles(List.of(UserRole.USER))
                .build();
    }

    @Test
    void retrievesProfileWithExistingMapper() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        var result = service.getUserProfile(userId);
        assertThat(result.id()).isEqualTo(userId.toString());
        assertThat(result.name()).isEqualTo("Alex Tan");
        assertThat(result.email()).isEqualTo("alex@example.com");
        assertThat(result.roles()).containsExactly("USER");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void missingUserCannotBeReadOrUpdated() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUserProfile(userId)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex", "alex@example.com", null, null)))
                .isInstanceOf(UserNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updatesNormalizedProfileWithoutChangingIdentityOrCredentials() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);
        var result = service.updateUserProfile(
                userId,
                new UpdateUserProfileRequest("  Jamie Tan  ", "  JAMIE@EXAMPLE.COM  ", "+65 9123 5436", " Computing "));
        assertThat(result.name()).isEqualTo("Jamie Tan");
        assertThat(result.email()).isEqualTo("jamie@example.com");
        assertThat(result.phoneNumber()).isEqualTo("+6591235436");
        assertThat(result.faculty()).isEqualTo("Computing");
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
        assertThat(user.getRoles()).containsExactly(UserRole.USER);
        verify(repository).existsByEmailAndIdNot("jamie@example.com", userId);
    }

    @Test
    void unchangedEmailAndSharedRealNameAreAllowed() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);
        var result = service.updateUserProfile(
                userId, new UpdateUserProfileRequest("Alex Tan", "alex@example.com", null, null));
        assertThat(result.name()).isEqualTo("Alex Tan");
        verify(repository).existsByEmailAndIdNot("alex@example.com", userId);
        verify(repository).saveAndFlush(user);
    }

    @Test
    void rejectsDuplicateEmailBeforeMutatingUser() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.existsByEmailAndIdNot("taken@example.com", userId)).thenReturn(true);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("New Name", "taken@example.com", null, null)))
                .isInstanceOf(EntityAlreadyExistsException.class);
        assertThat(user.getName()).isEqualTo("Alex Tan");
        assertThat(user.getEmail()).isEqualTo("alex@example.com");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void translatesEmailConstraintViolationAfterConcurrentUpdate() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        var cause = new ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), "users_email_key");
        when(repository.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("duplicate", cause));
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex", "taken@example.com", null, null)))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessage("Email is already associated with another account");
    }

    @Test
    void doesNotMislabelOtherDatabaseFailuresAsDuplicateEmail() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        var cause = new ConstraintViolationException("other", new SQLException("other", "23505"), "other_key");
        var failure = new DataIntegrityViolationException("other", cause);
        when(repository.saveAndFlush(user)).thenThrow(failure);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex", "alex@example.com", null, null)))
                .isSameAs(failure);
    }
}
