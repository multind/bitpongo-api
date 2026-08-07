package com.multind.zhitoubao.auth;

public interface WordPressAuthClient {
    WordPressSession login(String username, String password);
    AuthenticatedUser resolveUser(String token);
}
