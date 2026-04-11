package com.sc.lcm.core.domain;

import java.util.Locale;

public enum RedfishAuthMode {
    BASIC_ONLY,
    SESSION_PREFERRED,
    SESSION_ONLY;

    public static RedfishAuthMode parse(String rawValue, RedfishAuthMode fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return RedfishAuthMode.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static String normalize(String rawValue, RedfishAuthMode fallback) {
        return parse(rawValue, fallback).name();
    }
}
