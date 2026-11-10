package com.cs3219.foc.order.infrastructure.storage;

import java.time.Duration;

public interface DeliveryPhotoStorage {
    void putJpeg(String key, byte[] jpeg);

    // user could click the photo to open up in browser
    String readUrl(String key, Duration lifetime);

    void delete(String key);
}
