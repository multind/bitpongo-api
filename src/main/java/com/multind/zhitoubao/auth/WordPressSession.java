package com.multind.zhitoubao.auth;

public record WordPressSession(
        String token,
        long userId,
        String email,
        String displayName) {}
