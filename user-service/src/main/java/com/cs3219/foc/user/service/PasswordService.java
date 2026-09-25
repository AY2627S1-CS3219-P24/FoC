package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.InvalidPasswordException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.model.dto.ChangePasswordRequest;
import com.cs3219.foc.user.repository.RefreshTokenRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        var user =
                userRepository.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException("currentPassword", "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new InvalidPasswordException("newPassword", "New password must differ from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        refreshTokenRepository.deleteAllByUserId(userId);
    }
}
