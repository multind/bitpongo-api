package com.multind.zhitoubao.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserResolver {
    private final JwtTokenService jwtTokens;
    private final ObjectProvider<WordPressAuthClient> wordpressClients;
    private final UserRepository users;
    private final DeletedExternalIdentityRepository tombstones;

    public AuthenticatedUserResolver(
            JwtTokenService jwtTokens,
            ObjectProvider<WordPressAuthClient> wordpressClients,
            UserRepository users,
            DeletedExternalIdentityRepository tombstones) {
        this.jwtTokens = jwtTokens;
        this.wordpressClients = wordpressClients;
        this.users = users;
        this.tombstones = tombstones;
    }

    public AuthenticatedUser resolve(String token) {
        long userId;
        try {
            userId = jwtTokens.decodeUserId(token);
        } catch (IllegalArgumentException localTokenFailure) {
            WordPressAuthClient wordpress = wordpressClients.getIfAvailable();
            if (wordpress == null) {
                throw localTokenFailure;
            }
            AuthenticatedUser external = wordpress.resolveUser(token);
            if (tombstones.existsByProviderAndSubject(
                    "wordpress", String.valueOf(external.id()))) {
                throw unavailable();
            }
            requireActive(external.id());
            return external;
        }
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
