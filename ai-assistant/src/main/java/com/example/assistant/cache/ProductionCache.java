package com.example.assistant.cache;

import java.time.Duration;
import java.util.Optional;

public interface ProductionCache {
    Optional<Object> get(String key);

    void put(String key, Object value, Duration ttl);
}
