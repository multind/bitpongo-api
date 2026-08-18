package com.multind.bitpongo.auth;

public interface WordPressAuthClient {
    WordPressSession login(String username, String password);
    AuthenticatedUser resolveUser(String token);
}
