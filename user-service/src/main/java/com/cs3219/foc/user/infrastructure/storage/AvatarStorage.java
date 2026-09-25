package com.cs3219.foc.user.infrastructure.storage;

public interface AvatarStorage {
    void write(String key, byte[] image);

    byte[] read(String key);

    void delete(String key);
}
