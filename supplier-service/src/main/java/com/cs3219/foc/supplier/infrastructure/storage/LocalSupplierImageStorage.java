package com.cs3219.foc.supplier.infrastructure.storage;

import com.cs3219.foc.supplier.config.SupplierImageProperties;
import com.cs3219.foc.supplier.exception.SupplierImageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// store supplier images local directory as files (production will be volume)
@Component
public class LocalSupplierImageStorage implements SupplierImageStorage {
    private final Path directory;

    public LocalSupplierImageStorage(SupplierImageProperties properties) {
        this.directory = properties.directory().toAbsolutePath().normalize();
    }

    @Override
    public void write(String key, byte[] image) {
        var path = resolve(key);
        try {
            Files.createDirectories(directory);
            try (var output = Files.newOutputStream(
                    path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                output.write(image);
            }
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public byte[] read(String key) {
        try (var input = Files.newInputStream(resolve(key), LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        } catch (NoSuchFileException exception) {
            throw new SupplierImageException(HttpStatus.NOT_FOUND, "Image not found");
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")) {
            throw new SupplierImageException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid image storage reference");
        }
        return directory.resolve(key);
    }

    private SupplierImageException unavailable(IOException cause) {
        return new SupplierImageException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is unavailable", cause);
    }
}
