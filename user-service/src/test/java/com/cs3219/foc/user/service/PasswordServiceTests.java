package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.exception.InvalidPasswordException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.model.dto.ChangePasswordRequest;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.RefreshTokenRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordService service = new PasswordService(users, tokens, encoder);
    private final UUID userId = UUID.randomUUID();
    private User user;
    private String previousHash;

    @BeforeEach
    void setUp() {
        previousHash = encoder.encode("CurrentPassword1");
        user = User.builder()
                .id(userId)
                .name("Alex Tan")
                .email("alex@example.com")
                .phoneNumber("+6591235436")
                .faculty("Computing")
                .roles(List.of(UserRole.USER))
                .passwordHash(previousHash)
                .build();
        when(users.findForUpdateById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void hashesExactNewPasswordAndRevokesOnlyThisUsersTokens() {
        service.changePassword(userId, new ChangePasswordRequest("CurrentPassword1", " NewPassword2 "));
        assertThat(encoder.matches(" NewPassword2 ", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("NewPassword2", user.getPasswordHash())).isFalse();
        assertThat(encoder.matches("CurrentPassword1", user.getPasswordHash())).isFalse();
        assertThat(user.getPasswordHash()).isNotEqualTo(" NewPassword2 ");
        assertThat(user.getName()).isEqualTo("Alex Tan");
        assertThat(user.getEmail()).isEqualTo("alex@example.com");
        assertThat(user.getPhoneNumber()).isEqualTo("+6591235436");
        assertThat(user.getFaculty()).isEqualTo("Computing");
        assertThat(user.getRoles()).containsExactly(UserRole.USER);
        verify(users).saveAndFlush(user);
        verify(tokens).deleteAllByUserId(userId);
        verifyNoMoreInteractions(tokens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IncorrectPassword", " CurrentPassword1", "CurrentPassword1 "})
    void rejectsWrongCurrentPasswordWithoutChangingAnything(String current) {
        assertThatThrownBy(() -> service.changePassword(userId, new ChangePasswordRequest(current, "NewPassword2")))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessage("Current password is incorrect");
        assertUnchanged();
    }

    @Test
    void rejectsMissingUser() {
        when(users.findForUpdateById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                        service.changePassword(userId, new ChangePasswordRequest("CurrentPassword1", "NewPassword2")))
                .isInstanceOf(UserNotFoundException.class);
        assertUnchanged();
    }

    @Test
    void rejectsPasswordReuse() {
        assertThatThrownBy(() -> service.changePassword(
                        userId, new ChangePasswordRequest("CurrentPassword1", "CurrentPassword1")))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("differ");
        assertUnchanged();
    }

    @Test
    void acceptsPasswordAtBcryptByteLimit() {
        // Each accented e uses two UTF-8 bytes: 36 characters reach the 72-byte limit.
        var password = "\u00e9".repeat(36);
        service.changePassword(userId, new ChangePasswordRequest("CurrentPassword1", password));
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
    }

    @Test
    void requestStringDoesNotRevealPasswords() {
        assertThat(new ChangePasswordRequest("CurrentPassword1", "NewPassword2").toString())
                .doesNotContain("CurrentPassword1", "NewPassword2");
    }

    private void assertUnchanged() {
        assertThat(user.getPasswordHash()).isEqualTo(previousHash);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(tokens);
    }
}
