package com.cs3219.foc.user.infrastructure.storage;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.user.config.AvatarProperties;
import com.cs3219.foc.user.exception.AvatarException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class LocalAvatarStorageTests {
    @TempDir
    Path directory;

    @Test
    void storesAcrossInstancesAndDeletesIdempotently() {
        var properties = new AvatarProperties(directory);
        var first = new LocalAvatarStorage(properties);
        var key = UUID.randomUUID() + ".png";
        first.write(key, new byte[] {1, 2, 3});
        var restarted = new LocalAvatarStorage(properties);
        assertThat(restarted.read(key)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        restarted.delete(key);
        restarted.delete(key);
        assertThatThrownBy(() -> restarted.read(key))
                .isInstanceOfSatisfying(
                        AvatarException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void refusesPathTraversalAndOverwritingExistingKeys() {
        var storage = new LocalAvatarStorage(new AvatarProperties(directory));
        assertThatThrownBy(() -> storage.write("../escape.png", new byte[] {1})).isInstanceOf(AvatarException.class);
        var key = UUID.randomUUID() + ".png";
        storage.write(key, new byte[] {1});
        assertThatThrownBy(() -> storage.write(key, new byte[] {2})).isInstanceOf(AvatarException.class);
        assertThat(storage.read(key)).containsExactly((byte) 1);
    }

    @Test
    void reportsStorageFailureWithoutExposingLocalPath() throws Exception {
        var blocked = Files.createFile(directory.resolve("blocked"));
        var storage = new LocalAvatarStorage(new AvatarProperties(blocked));
        assertThatThrownBy(() -> storage.write(UUID.randomUUID() + ".png", new byte[] {1}))
                .isInstanceOfSatisfying(AvatarException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain(directory.toString());
                });
    }
}
