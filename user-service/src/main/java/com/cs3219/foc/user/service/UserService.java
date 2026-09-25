package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserProfileDto getUserProfile(UUID userId) {
        return userMapper.toUserProfileDto(findUser(userId));
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
        try {
            return userMapper.toUserProfileDto(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            // V1 creates this PostgreSQL constraint. Only translate the email race;
            // unrelated integrity failures must not be reported as duplicate emails.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "users_email_key".equals(violation.getConstraintName())) {
                    throw new EntityAlreadyExistsException("Email is already associated with another account");
                }
            }
            throw exception;
        }
    }

    private User findUser(UUID userId) {
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
