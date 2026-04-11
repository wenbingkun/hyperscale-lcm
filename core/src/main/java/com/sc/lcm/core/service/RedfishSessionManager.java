package com.sc.lcm.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RedfishSessionManager {

    private final Map<SessionKey, CachedSession> sessions = new ConcurrentHashMap<>();
    private final Map<SessionKey, Object> locks = new ConcurrentHashMap<>();

    CachedSession getValid(SessionKey key) {
        CachedSession session = sessions.get(key);
        if (session == null) {
            return null;
        }
        if (!session.isExpired()) {
            return session;
        }
        sessions.remove(key, session);
        return null;
    }

    CachedSession getOrCreate(SessionKey key, SessionFactory factory) throws Exception {
        CachedSession existing = getValid(key);
        if (existing != null) {
            return existing;
        }

        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            CachedSession cached = getValid(key);
            if (cached != null) {
                return cached;
            }

            CachedSession created = factory.create();
            sessions.put(key, created);
            return created;
        }
    }

    void invalidate(SessionKey key) {
        if (key != null) {
            sessions.remove(key);
        }
    }

    Collection<Map.Entry<SessionKey, CachedSession>> snapshot() {
        return new LinkedHashMap<>(sessions).entrySet();
    }

    static SessionKey keyFor(RedfishTransport.RequestOptions options) {
        return new SessionKey(
                normalizeEndpoint(options.endpoint()),
                normalizeUsername(options.username()),
                hashSecret(options.password()),
                options.insecure());
    }

    private static String normalizeEndpoint(String endpoint) {
        return endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private static String hashSecret(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash session cache key", e);
        }
    }

    @FunctionalInterface
    interface SessionFactory {
        CachedSession create() throws Exception;
    }

    record SessionKey(
            String endpoint,
            String username,
            String passwordHash,
            boolean insecure) {
    }

    record CachedSession(
            String token,
            String sessionUri,
            Instant expiresAt,
            RedfishTransport.RequestOptions options) {

        boolean isExpired() {
            return expiresAt == null || !expiresAt.isAfter(Instant.now());
        }
    }
}
