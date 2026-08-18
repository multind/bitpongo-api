package com.multind.bitpongo.auth;

public record WordPressSession(
        String token,
        long userId,
        String email,
        String displayName) {}
