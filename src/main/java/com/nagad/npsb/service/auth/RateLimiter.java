package com.nagad.npsb.service.auth;

import com.nagad.npsb.web.ApiException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** At most `limit` requests per `window` seconds for one key (in memory, per server process). */
public final class RateLimiter {
    private final IntSupplier limit;
    private final int window;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    /** limit is read on every check, so a change on the System Settings page applies at once. */
    public RateLimiter(IntSupplier limit, int window) {
        this.limit = limit;
        this.window = window;
    }

    /** 429 (with Retry-After) when the key has used up its requests in the last window. */
    public synchronized void check(String key) {
        long now = System.nanoTime();
        long windowNanos = window * 1_000_000_000L;
        Deque<Long> h = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!h.isEmpty() && h.peekFirst() <= now - windowNanos) h.pollFirst();
        if (h.size() >= limit.getAsInt()) {
            long retry = (long) (window - (now - h.peekFirst()) / 1e9) + 1;
            throw new ApiException(429, "Too many attempts. Please wait " + retry + " seconds and try again.")
                    .header("Retry-After", String.valueOf(retry));
        }
        h.addLast(now);
    }
}
