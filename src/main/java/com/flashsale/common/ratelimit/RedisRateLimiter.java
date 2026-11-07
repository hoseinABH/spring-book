package com.flashsale.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Atomic sliding window rate limiter Lua script.
     * Arguments: key, currentTimestamp, windowSeconds, maxRequests
     */
    private static final String LUA_SLIDING_WINDOW = """
        local key = KEYS[1]
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local clearBefore = now - window

        -- Remove timestamps older than the window
        redis.call('ZREMRANGEBYSCORE', key, 0, clearBefore)

        -- Count requests in the current window
        local currentRequests = redis.call('ZCARD', key)

        if currentRequests < limit then
            -- Add current request timestamp
            redis.call('ZADD', key, now, now)
            redis.call('EXPIRE', key, window)
            return 1 -- Allowed
        else
            return 0 -- Denied (Rate limit exceeded)
        end
        """;

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SLIDING_WINDOW, Long.class);

    public boolean isAllowed(String clientIdentifier, int maxRequests, int windowSeconds) {
        String key = "ratelimit:" + clientIdentifier;
        long now = Instant.now().getEpochSecond();

        try {
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowSeconds),
                    String.valueOf(maxRequests)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Redis rate limiter evaluation failed (failing open): {}", e.getMessage());
            return true; // Fail open to maintain availability during redis glitches
        }
    }
}
