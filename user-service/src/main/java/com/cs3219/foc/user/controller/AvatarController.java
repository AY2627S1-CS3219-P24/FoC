package com.cs3219.foc.user.controller;

import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.service.AvatarService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/users/me/avatar")
@RequiredArgsConstructor
public class AvatarController {
    private final AvatarService avatars;

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserProfileDto upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        return avatars.upload(UUID.fromString(jwt.getSubject()), file);
    }

    @GetMapping
    public ResponseEntity<byte[]> read(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(avatars.read(UUID.fromString(jwt.getSubject())));
    }

    @DeleteMapping
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt) {
        avatars.remove(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
