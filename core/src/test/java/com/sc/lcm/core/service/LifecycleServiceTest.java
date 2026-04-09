package com.sc.lcm.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleServiceTest {

    private LifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = new LifecycleService();
    }

    @Test
    void beginAndEndRequestTrackActiveRequestCount() {
        lifecycleService.beginRequest();
        lifecycleService.beginRequest();

        assertEquals(2, lifecycleService.getActiveRequestCount());

        lifecycleService.endRequest();

        assertEquals(1, lifecycleService.getActiveRequestCount());
    }

    @Test
    void onStopMarksServiceAsShuttingDown() {
        assertFalse(lifecycleService.isShuttingDown());

        lifecycleService.onStop(null);

        assertTrue(lifecycleService.isShuttingDown());
        assertEquals(0, lifecycleService.getActiveRequestCount());
    }
}
