package com.multind.zhitoubao.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    private final BearerAuthenticationFilter bearerAuthenticationFilter;

    public SecurityConfiguration(BearerAuthenticationFilter bearerAuthenticationFilter) {
        this.bearerAuthenticationFilter = bearerAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/", "/health", "/actuator/health",
                                "/api/ws/price",
                                "/api/users/login", "/api/users/v1/login", "/api/users/register")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"code\":401,\"message\":\"未认证\",\"data\":null}");
                }))
                .addFilterBefore(bearerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${zhitoubao.cors.allowed-origins:*}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            origins = List.of("*");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION, "X-Request-ID"));
        configuration.setAllowCredentials(!origins.contains("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
