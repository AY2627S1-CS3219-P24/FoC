package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.PageDto;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UpdateUserRequest;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    private final UserMapper userMapper;

    private static final Set<String> SORTABLE_COLUMNS = Set.of("email", "name");

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public PageDto<UserProfileDto> listUsers(Pageable pageable, String search, UserRole role) {
        validatePageable(pageable);
        validateSort(pageable.getSort());

        var pageRequest = buildPageRequest(pageable);
        search = search.strip().toLowerCase();

        var roleName = role == null ? null : role.name();

        var matches = userRepository.findAllBySearchAndRolePaginated(search, roleName, pageRequest);
        var items = matches.map(userMapper::toUserProfileDto).getContent();

        return new PageDto<>(items, matches.getTotalElements(), pageable.getPageNumber(), pageable.getPageSize());
    }

    private void validatePageable(Pageable pageable) {
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1–100.");
        }
    }

    private void validateSort(Sort sort) {
        var orders = sort.stream().toList();

        if (orders.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort must contain exactly one field.");
        }

        var property = orders.getFirst().getProperty();

        if (!SORTABLE_COLUMNS.contains(property)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort must be name or email.");
        }
    }

    private PageRequest buildPageRequest(Pageable pageable) {
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserProfileDto updateUser(UUID id, UpdateUserRequest request) {
        var user = getUser(id);

        var name = request.name().strip();
        var email = request.email().strip().toLowerCase();

        validateRoleChange(user.getRoles(), request.roles());

        if (!email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists with this email.");
        }

        user.setName(name);
        user.setEmail(email);
        user.setRoles(request.roles());

        return userMapper.toUserProfileDto(userRepository.saveAndFlush(user));
    }

    private void validateRoleChange(Collection<UserRole> currentRoles, Collection<UserRole> requestedRoles) {
        if (currentRoles.contains(UserRole.ADMIN) && !requestedRoles.contains(UserRole.ADMIN) && isLastAdmin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last admin cannot be demoted.");
        }
    }

    private boolean isLastAdmin() {
        return userRepository.countAllByRolesContains(UserRole.ADMIN.name()) <= 1;
    }

    @Transactional(readOnly = true)
    public UserProfileDto getUserProfile(UUID userId) {
        var user = getUser(userId);
        return userMapper.toUserProfileDto(user);
    }

    @Transactional
    public UserProfileDto updateUserProfile(UUID userId, UpdateUserProfileRequest request) {
        // Avoid saving a stale password hash if a password change happens concurrently.
        var user =
                userRepository.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (userRepository.existsByEmailAndIdNot(request.email(), userId)) {
            throw new EntityAlreadyExistsException("Email is already associated with another account");
        }

        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhoneNumber(request.phoneNumber());
        user.setFaculty(request.faculty());
        var savedUser = userRepository.saveAndFlush(user);
        return userMapper.toUserProfileDto(savedUser);
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    @Transactional
    public UserProfileDto registerUser(RegisterUserRequest request) {
        var email = request.email().strip().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new EntityAlreadyExistsException("User already exists with this email");
        }

        var name = request.name().strip();
        var passwordHash = encoder.encode(request.password());

        var user = User.builder()
                .email(email)
                .name(name)
                .passwordHash(passwordHash)
                .roles(List.of(UserRole.USER))
                .build();

        var savedUser = userRepository.save(user);
        return userMapper.toUserProfileDto(savedUser);
    }
}
