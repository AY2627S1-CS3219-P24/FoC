package com.cs3219.foc.user.service;

import java.util.Set;

final class NusEmailPolicy {
    private static final Set<String> ALLOWED_DOMAINS = Set.of("u.nus.edu", "nus.edu.sg");

    private NusEmailPolicy() {}

    static boolean isAllowed(String email) {
        return ALLOWED_DOMAINS.contains(email.substring(email.lastIndexOf('@') + 1));
    }
}
