package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.exception.UnsupportedEmailDomainException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTests {
    private final UserRepository repository = mock(UserRepository.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);
    private final UserService service =
            new UserService(repository, mock(PasswordEncoder.class), Mappers.getMapper(UserMapper.class), verification);
    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(userId)
                .name("Alex Tan")
                .email("alex@u.nus.edu")
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
        assertThat(result.email()).isEqualTo("alex@u.nus.edu");
        assertThat(result.roles()).containsExactly("USER");
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jamie@u.nus.edu", "jamie@nus.edu.sg"})
    void registersAllowedNusEmails(String email) {
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });
        var result = service.registerUser(new RegisterUserRequest(email, "Jamie", "password123"));
        assertThat(result.email()).isEqualTo(email);
        verify(repository).save(any(User.class));
        verify(verification).createRegistrationVerification(argThat(saved -> !saved.isEmailVerified()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jamie@gmail.com", "jamie@u.nus.edu.example.com"})
    void rejectsRegistrationWithUnsupportedDomain(String email) {
        assertThatThrownBy(() -> service.registerUser(new RegisterUserRequest(email, "Jamie", "password123")))
                .isInstanceOf(UnsupportedEmailDomainException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void missingUserCannotBeReadOrUpdated() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUserProfile(userId)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.updateUserProfile(userId, new UpdateUserProfileRequest("Alex", null, null)))
                .isInstanceOf(UserNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updatesNormalizedProfileWithoutChangingIdentityOrCredentials() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);
        var result = service.updateUserProfile(
                userId, new UpdateUserProfileRequest("  Jamie Tan  ", "+65 9123 5436", " Computing "));
        assertThat(result.name()).isEqualTo("Jamie Tan");
        assertThat(result.email()).isEqualTo("alex@u.nus.edu");
        assertThat(result.phoneNumber()).isEqualTo("+6591235436");
        assertThat(result.faculty()).isEqualTo("Computing");
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
        assertThat(user.getRoles()).containsExactly(UserRole.USER);
    }
}
