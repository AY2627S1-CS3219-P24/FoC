package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.UnsupportedEmailDomainException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UpdateUserRequest;
import com.cs3219.foc.user.model.dto.UserSearchCriteria;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

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

    @Test
    void retrievesFullUserDtoWithDatesAndUserSpecificAvatarUrl() {
        var created = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var updated = OffsetDateTime.parse("2026-01-02T00:00:00Z");
        user.setCreatedAt(created);
        user.setUpdatedAt(updated);
        user.setAvatarKey("avatar.png");
        when(repository.findById(userId)).thenReturn(Optional.of(user));

        var result = service.getUserDto(userId);

        assertThat(result.avatarUrl()).isEqualTo("/api/users/" + userId + "/avatar?v=avatar.png");
        assertThat(result.createdAt()).isEqualTo(created);
        assertThat(result.updatedAt()).isEqualTo(updated);
        assertThat(result.email()).isEqualTo(user.getEmail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"createdAt", "updatedAt"})
    void sortsUsersByTimestampPropertiesWithStableIdTieBreak(String property) {
        when(repository.findAll(ArgumentMatchers.<Specification<User>>any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listUsers(
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, property)), new UserSearchCriteria("", null, null));

        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(ArgumentMatchers.<Specification<User>>any(), pageable.capture());
        assertThat(pageable.getValue().getSort().stream().map(Sort.Order::getProperty))
                .containsExactly(property, "id");
        assertThat(pageable.getValue().getSort().getOrderFor(property).getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"School of Computing", "UNASSIGNED"})
    void acceptsSupportedFacultyFilters(String faculty) {
        when(repository.findAll(ArgumentMatchers.<Specification<User>>any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listUsers(PageRequest.of(0, 20, Sort.by("name")), new UserSearchCriteria("", null, faculty));

        verify(repository).findAll(ArgumentMatchers.<Specification<User>>any(), any(Pageable.class));
    }

    @Test
    void rejectsUnknownFacultyFilter() {
        assertThatThrownBy(() -> service.listUsers(
                        PageRequest.of(0, 20, Sort.by("name")), new UserSearchCriteria("", null, "Unknown")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void listsNusFaculties() {
        assertThat(service.getFaculties()).hasSize(15).contains("School of Computing", "Faculty of Science");
    }

    @Test
    void rejectsUnknownAdminFacultyBeforeReadingOrSaving() {
        assertThatThrownBy(() -> service.updateUser(
                        userId,
                        new UpdateUserRequest("Alex Tan", "alex@u.nus.edu", List.of(UserRole.USER), null, "Unknown")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownProfileFacultyBeforeReadingOrSaving() {
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex Tan", "alex@u.nus.edu", null, "Unknown")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void adminCanSetAListedFaculty() {
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);

        var result = service.updateUser(
                userId,
                new UpdateUserRequest(
                        "Alex Tan", "alex@u.nus.edu", List.of(UserRole.USER), null, "School of Computing"));

        assertThat(result.faculty()).isEqualTo("School of Computing");
        verify(repository).saveAndFlush(user);
    }

    @Test
    void adminUpdateNormalizesAndClearsOptionalDetails() {
        user.setPhoneNumber("+6590000000");
        user.setFaculty("Science");
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);

        var result = service.updateUser(
                userId,
                new UpdateUserRequest(
                        " Alex Tan ", " ALEX@U.NUS.EDU ", List.of(UserRole.USER), " +65 9123-5436 ", "   "));

        assertThat(result.phoneNumber()).isEqualTo("+6591235436");
        assertThat(result.faculty()).isNull();
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
    }

    @Test
    void adminUpdateOmittingOptionalDetailsPreservesThem() {
        user.setPhoneNumber("+6591235436");
        user.setFaculty("School of Computing");
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);

        var result = service.updateUser(
                userId, new UpdateUserRequest("Alex Tan", "alex@u.nus.edu", List.of(UserRole.USER), null, null));

        assertThat(result.phoneNumber()).isEqualTo("+6591235436");
        assertThat(result.faculty()).isEqualTo("School of Computing");
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"jamie@gmail.com", "jamie@u.nus.edu.example.com"})
    void rejectsRegistrationWithUnsupportedDomain(String email) {
        assertThatThrownBy(() -> service.registerUser(new RegisterUserRequest(email, "Jamie", "password123")))
                .isInstanceOf(UnsupportedEmailDomainException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnsupportedProfileEmailBeforeMutatingUser() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("New Name", "jamie@gmail.com", null, null)))
                .isInstanceOf(UnsupportedEmailDomainException.class);
        assertThat(user.getName()).isEqualTo("Alex Tan");
        assertThat(user.getEmail()).isEqualTo("alex@u.nus.edu");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void missingUserCannotBeReadOrUpdated() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUserProfile(userId)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex", "alex@u.nus.edu", null, null)))
                .isInstanceOf(UserNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updatesNormalizedProfileWithoutChangingIdentityOrCredentials() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);
        var result = service.updateUserProfile(
                userId,
                new UpdateUserProfileRequest(
                        "  Jamie Tan  ", "  JAMIE@U.NUS.EDU  ", "+65 9123 5436", " School of Computing "));
        assertThat(result.name()).isEqualTo("Jamie Tan");
        assertThat(result.email()).isEqualTo("jamie@u.nus.edu");
        assertThat(result.phoneNumber()).isEqualTo("+6591235436");
        assertThat(result.faculty()).isEqualTo("School of Computing");
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
        assertThat(user.getRoles()).containsExactly(UserRole.USER);
        verify(repository).existsByEmailAndIdNot("jamie@u.nus.edu", userId);
    }

    @Test
    void unchangedEmailAndSharedRealNameAreAllowed() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        when(repository.saveAndFlush(user)).thenReturn(user);
        var result = service.updateUserProfile(
                userId, new UpdateUserProfileRequest("Alex Tan", "alex@u.nus.edu", null, null));
        assertThat(result.name()).isEqualTo("Alex Tan");
        verify(repository).existsByEmailAndIdNot("alex@u.nus.edu", userId);
        verify(repository).saveAndFlush(user);
    }

    @Test
    void rejectsDuplicateEmailBeforeMutatingUser() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        when(repository.existsByEmailAndIdNot("taken@u.nus.edu", userId)).thenReturn(true);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("New Name", "taken@u.nus.edu", null, null)))
                .isInstanceOf(EntityAlreadyExistsException.class);
        assertThat(user.getName()).isEqualTo("Alex Tan");
        assertThat(user.getEmail()).isEqualTo("alex@u.nus.edu");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotMislabelOtherDatabaseFailuresAsDuplicateEmail() {
        when(repository.findForUpdateById(userId)).thenReturn(Optional.of(user));
        var cause = new ConstraintViolationException("other", new SQLException("other", "23505"), "other_key");
        var failure = new DataIntegrityViolationException("other", cause);
        when(repository.saveAndFlush(user)).thenThrow(failure);
        assertThatThrownBy(() -> service.updateUserProfile(
                        userId, new UpdateUserProfileRequest("Alex", "alex@u.nus.edu", null, null)))
                .isSameAs(failure);
    }
}
