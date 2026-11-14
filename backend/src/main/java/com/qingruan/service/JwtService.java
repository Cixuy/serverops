package com.qingruan.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwtService {
    private final byte[] secret;
    private final long expiration;
    private final ConcurrentHashMap<String, Long> revokedTokens = new ConcurrentHashMap<>();

    public JwtService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration}") long expiration) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expiration = expiration;
    }

    public String create(Long id, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .id(java.util.UUID.randomUUID().toString())
                .claim("uid", id)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(Keys.hmacShaKeyFor(secret))
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public void revoke(String token) {
        Claims claims = parse(token);
        Date expiresAt = claims.getExpiration();
        if (expiresAt != null && expiresAt.after(new Date())) {
            revokedTokens.put(token, expiresAt.getTime());
        }
        purgeExpiredRevocations();
    }

    public boolean isRevoked(String token) {
        purgeExpiredRevocations();
        Long expiresAt = revokedTokens.get(token);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void purgeExpiredRevocations() {
        long now = System.currentTimeMillis();
        revokedTokens.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public long expirationSeconds() {
        return expiration / 1000;
    }
}