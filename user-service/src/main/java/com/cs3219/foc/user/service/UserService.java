package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.UnsupportedEmailDomainException;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    private final UserMapper userMapper;
    private final EmailVerificationService emailVerification;

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
        user.setName(request.name());
        user.setPhoneNumber(request.phoneNumber());
        user.setFaculty(request.faculty());
        var savedUser = userRepository.saveAndFlush(user);
        return userMapper.toUserProfileDto(savedUser);
    }

    private User getUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    @Transactional
    public UserProfileDto registerUser(RegisterUserRequest request) {
        var email = request.email().strip().toLowerCase();
        if (!NusEmailPolicy.isAllowed(email)) {
            throw new UnsupportedEmailDomainException();
        }

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
        emailVerification.createRegistrationVerification(savedUser);
        return userMapper.toUserProfileDto(savedUser);
    }
}
