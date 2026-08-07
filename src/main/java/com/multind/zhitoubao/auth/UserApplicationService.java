package com.multind.zhitoubao.auth;

import com.multind.zhitoubao.common.api.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.multind.zhitoubao.auth.UserDtos.LoginData;
import static com.multind.zhitoubao.auth.UserDtos.UserCreateRequest;
import static com.multind.zhitoubao.auth.UserDtos.UserInfo;
import static com.multind.zhitoubao.auth.UserDtos.UserLoginRequest;
import static com.multind.zhitoubao.auth.UserDtos.UserResponse;

@Service
public class UserApplicationService {
    private final UserRepository users;
    private final PasswordCompatibilityService passwords;
    private final JwtTokenService tokens;
    private final WordPressAuthClient wordpress;
    private final Clock clock;

    @Autowired
    public UserApplicationService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            JwtTokenService tokens,
            WordPressAuthClient wordpress) {
        this(users, passwords, tokens, wordpress, Clock.systemUTC());
    }

    UserApplicationService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            JwtTokenService tokens,
            WordPressAuthClient wordpress,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.wordpress = wordpress;
        this.clock = clock;
    }

    @Transactional
    public LoginData login(UserLoginRequest request) {
        UserEntity user = users.findByEmail(normalizeEmail(request.username()))
                .filter(found -> passwords.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        user.setLastLogin(now());
        return new LoginData(tokens.issue(user.getId()), info(user));
    }

    @Transactional
    public UserResponse register(UserCreateRequest request) {
        String email = normalizeEmail(request.email());
        if (users.findByEmail(email).isPresent()) {
            throw new BusinessException(400, "用户已存在");
        }
        LocalDateTime now = now();
        UserEntity user = new UserEntity();
        user.setName(request.name());
        user.setEmail(email);
        user.setPassword(passwords.hash(request.password()));
        user.setCreatedAt(now);
        user.setLastLogin(now);
        return response(users.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse profile(long userId) {
        return users.findById(userId)
                .map(UserApplicationService::response)
                .orElseThrow(() -> new BusinessException(401, "无法验证凭据"));
    }

    @Transactional
    public LoginData wordpressLogin(UserLoginRequest request) {
        WordPressSession session = wordpress.login(request.username(), request.password());
        LocalDateTime now = now();
        UserEntity user = users.findByEmail(normalizeEmail(session.email())).orElseGet(() -> {
            UserEntity created = new UserEntity();
            created.setId(session.userId());
            created.setEmail(normalizeEmail(session.email()));
            created.setPassword(passwords.hash(request.password()));
            created.setCreatedAt(now);
            return created;
        });
        user.setName(session.displayName());
        user.setLastLogin(now);
        users.save(user);
        return new LoginData(
                session.token(),
                new UserInfo(session.userId(), session.displayName(), normalizeEmail(session.email())));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserInfo info(UserEntity user) {
        return new UserInfo(user.getId(), user.getName(), user.getEmail());
    }

    private static UserResponse response(UserEntity user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
