package com.example.assistant.cache;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class InMemoryProductionCache implements ProductionCache {
    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    InMemoryProductionCache() {
        this(Clock.systemUTC());
    }

    InMemoryProductionCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Object> get(String key) {
        var entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (value == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        entries.put(key, new CacheEntry(value, clock.instant().plus(ttl)));
    }

    private record CacheEntry(Object value, Instant expiresAt) {
    }
}
