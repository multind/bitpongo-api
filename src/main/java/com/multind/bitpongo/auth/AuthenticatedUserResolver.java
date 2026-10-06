package com.multind.bitpongo.auth;

import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserResolver {
    private final JwtTokenService jwtTokens;
    private final UserRepository users;
    private LoginSessionService sessions;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void sessions(LoginSessionService service) { this.sessions=service; }

    public AuthenticatedUserResolver(
            JwtTokenService jwtTokens,
            UserRepository users) {
        this.jwtTokens = jwtTokens;
        this.users = users;
    }

    public AuthenticatedUser resolve(String token) {
        var verified = jwtTokens.verify(token);
        long userId = verified.userId();
        UserEntity user = requireActive(userId);
        if (sessions != null) sessions.authenticate(userId,token,verified.expiresAt());
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
