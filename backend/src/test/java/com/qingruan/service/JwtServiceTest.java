package com.qingruan.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {
    private static final String SECRET = "YourSuperSecretKeyAtLeast256BitsLongForHS256Algorithm!!";

    @Test
    void revokedTokenIsRejectedUntilItExpires() {
        JwtService service = new JwtService(SECRET, 60_000);
        String token = service.create(1L, "admin", "ADMIN");

        assertFalse(service.isRevoked(token));
        service.revoke(token);
        assertTrue(service.isRevoked(token));
    }

    @Test
    void differentTokenRemainsValidWhenOneTokenIsRevoked() {
        JwtService service = new JwtService(SECRET, 60_000);
        String first = service.create(1L, "admin", "ADMIN");
        String second = service.create(1L, "admin", "ADMIN");

        service.revoke(first);

        assertTrue(service.isRevoked(first));
        assertFalse(service.isRevoked(second));
    }
}