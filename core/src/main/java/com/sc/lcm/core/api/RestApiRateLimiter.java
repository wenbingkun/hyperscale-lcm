package com.sc.lcm.core.api;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RestApiRateLimiter {

    private static final long IDLE_STATE_TTL_MS = 30L * 60L * 1000L;

    @ConfigProperty(name = "lcm.api.rate-limit.window-seconds", defaultValue = "60")
    int windowSeconds;

    @ConfigProperty(name = "lcm.api.rate-limit.user.requests", defaultValue = "60")
    int userLimit;

    @ConfigProperty(name = "lcm.api.rate-limit.operator.requests", defaultValue = "120")
    int operatorLimit;

    @ConfigProperty(name = "lcm.api.rate-limit.admin.requests", defaultValue = "300")
    int adminLimit;

    private final Map<String, WindowState> states = new ConcurrentHashMap<>();

    public void enforce(String principalName, String role) {
        long now = System.currentTimeMillis();
        cleanupExpiredStates(now);

        String effectiveRole = normalizeRole(role);
        String effectivePrincipal = normalizePrincipal(principalName);
        String key = effectiveRole + ":" + effectivePrincipal;
        WindowState state = states.computeIfAbsent(key, ignored -> new WindowState());

        synchronized (state) {
            pruneExpiredRequests(state, now);

            int requestLimit = limitForRole(effectiveRole);
            if (state.requestTimestamps.size() >= requestLimit) {
                long oldestRequest = state.requestTimestamps.peekFirst();
                long retryAfterMs = Math.max(1L, windowDurationMs() - (now - oldestRequest));
                state.lastAccessTime = now;
                throw new RateLimitExceededException(effectiveRole, toRetryAfterSeconds(retryAfterMs));
            }

            state.requestTimestamps.addLast(now);
            state.lastAccessTime = now;
        }
    }

    void clearLimits() {
        states.clear();
    }

    private void pruneExpiredRequests(WindowState state, long now) {
        long windowMs = windowDurationMs();
        while (!state.requestTimestamps.isEmpty() && (now - state.requestTimestamps.peekFirst()) >= windowMs) {
            state.requestTimestamps.removeFirst();
        }
    }

    private int limitForRole(String role) {
        return switch (role) {
            case "ADMIN" -> Math.max(1, adminLimit);
            case "OPERATOR" -> Math.max(1, operatorLimit);
            default -> Math.max(1, userLimit);
        };
    }

    private long windowDurationMs() {
        return Math.max(1L, windowSeconds) * 1000L;
    }

    private void cleanupExpiredStates(long now) {
        states.entrySet().removeIf(entry -> (now - entry.getValue().lastAccessTime) >= IDLE_STATE_TTL_MS);
    }

    private String normalizeRole(String role) {
        if ("ADMIN".equals(role) || "OPERATOR".equals(role)) {
            return role;
        }
        return "USER";
    }

    private String normalizePrincipal(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return "anonymous";
        }
        return principalName;
    }

    private int toRetryAfterSeconds(long retryAfterMs) {
        return (int) Math.max(1L, (retryAfterMs + 999L) / 1000L);
    }

    public static final class RateLimitExceededException extends RuntimeException {
        private final String role;
        private final int retryAfterSeconds;

        public RateLimitExceededException(String role, int retryAfterSeconds) {
            super("API rate limit exceeded for role " + role);
            this.role = role;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getRole() {
            return role;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final class WindowState {
        private final Deque<Long> requestTimestamps = new ArrayDeque<>();
        private volatile long lastAccessTime = System.currentTimeMillis();
    }
}
