package com.cs3219.foc.supplier.infrastructure.storage;

public interface SupplierImageStorage {
    void write(String key, byte[] image);

    byte[] read(String key);

    void delete(String key);
}
