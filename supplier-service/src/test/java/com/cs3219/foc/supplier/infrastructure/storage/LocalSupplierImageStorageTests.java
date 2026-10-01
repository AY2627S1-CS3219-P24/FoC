package com.cs3219.foc.supplier.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs3219.foc.supplier.config.SupplierImageProperties;
import com.cs3219.foc.supplier.exception.SupplierImageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class LocalSupplierImageStorageTests {
    @TempDir
    Path directory;

    private LocalSupplierImageStorage storage() {
        return new LocalSupplierImageStorage(new SupplierImageProperties(directory));
    }

    @Test
    void writesReadsAndDeletesImages() {
        var storage = storage();
        var key = UUID.randomUUID() + ".jpg";
        storage.write(key, new byte[] {1, 2, 3});
        assertThat(storage.read(key)).containsExactly(1, 2, 3);
        storage.delete(key);
        assertThat(Files.exists(directory.resolve(key))).isFalse();
    }

    @Test
    void rejectsKeysThatTheServerDidNotGenerate() {
        assertThatThrownBy(() -> storage().write("../escape.jpg", new byte[] {1}))
                .isInstanceOf(SupplierImageException.class);
        assertThatThrownBy(() -> storage().read("photo.png")).isInstanceOf(SupplierImageException.class);
    }

    @Test
    void reportsMissingImagesAsNotFound() {
        assertThatThrownBy(() -> storage().read(UUID.randomUUID() + ".jpg"))
                .isInstanceOfSatisfying(SupplierImageException.class, exception -> assertThat(exception.getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
