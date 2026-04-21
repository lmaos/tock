package com.clmcat.tock.job;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultJobRegistryTest {

    @Test
    void shouldRegisterLookupAndUnregister() {
        DefaultJobRegistry registry = new DefaultJobRegistry();
        AtomicBoolean invoked = new AtomicBoolean(false);
        JobExecutor executor = context -> invoked.set(true);

        registry.register("job-a", executor);
        Assertions.assertTrue(registry.contains("job-a"));
        Assertions.assertSame(executor, registry.get("job-a"));

        registry.unregister("job-a");
        Assertions.assertFalse(registry.contains("job-a"));
        Assertions.assertNull(registry.get("job-a"));
        Assertions.assertFalse(invoked.get());
    }

    @Test
    void shouldRejectDuplicateAndNullRegistration() {
        DefaultJobRegistry registry = new DefaultJobRegistry();
        JobExecutor executor = context -> { };

        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.register(null, executor));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.register("job-a", null));
        registry.register("job-a", executor);
        Assertions.assertThrows(IllegalStateException.class, () -> registry.register("job-a", executor));
    }
}
