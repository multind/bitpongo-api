package com.multind.bitpongo.auth;

import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserResolver {
    private final JwtTokenService jwtTokens;
    private final UserRepository users;

    public AuthenticatedUserResolver(
            JwtTokenService jwtTokens,
            UserRepository users) {
        this.jwtTokens = jwtTokens;
        this.users = users;
    }

    public AuthenticatedUser resolve(String token) {
        long userId = jwtTokens.decodeUserId(token);
        UserEntity user = requireActive(userId);
        return new AuthenticatedUser(userId, user.getEmail(), user.getName());
    }

    private UserEntity requireActive(long userId) {
        return users.findById(userId)
                .filter(UserEntity::isActive)
                .orElseThrow(AuthenticatedUserResolver::unavailable);
    }

    private static IllegalArgumentException unavailable() {
        return new IllegalArgumentException("账号不可用");
    }
}
