package com.multind.zhitoubao.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserResolver {
    private final JwtTokenService jwtTokens;
    private final ObjectProvider<WordPressAuthClient> wordpressClients;

    public AuthenticatedUserResolver(
            JwtTokenService jwtTokens,
            ObjectProvider<WordPressAuthClient> wordpressClients) {
        this.jwtTokens = jwtTokens;
        this.wordpressClients = wordpressClients;
    }

    public AuthenticatedUser resolve(String token) {
        try {
            return new AuthenticatedUser(jwtTokens.decodeUserId(token), null, null);
        } catch (IllegalArgumentException localTokenFailure) {
            WordPressAuthClient wordpress = wordpressClients.getIfAvailable();
            if (wordpress == null) {
                throw localTokenFailure;
            }
            return wordpress.resolveUser(token);
        }
    }
}
