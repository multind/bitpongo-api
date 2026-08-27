package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.multind.bitpongo.auth.UserDtos.LoginData;
import static com.multind.bitpongo.auth.UserDtos.UserCreateRequest;
import static com.multind.bitpongo.auth.UserDtos.UserInfo;
import static com.multind.bitpongo.auth.UserDtos.UserLoginRequest;
import static com.multind.bitpongo.auth.UserDtos.UserResponse;

@Service
public class UserApplicationService {
    private final UserRepository users;
    private final PasswordCompatibilityService passwords;
    private final JwtTokenService tokens;
    private final Clock clock;

    @Autowired
    public UserApplicationService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            JwtTokenService tokens) {
        this(users, passwords, tokens, Clock.systemUTC());
    }

    UserApplicationService(
            UserRepository users,
            PasswordCompatibilityService passwords,
            JwtTokenService tokens,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public LoginData login(UserLoginRequest request) {
        UserEntity user = users.findByEmail(normalizeEmail(request.username()))
                .filter(UserEntity::isActive)
                .filter(found -> passwords.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        user.setLastLogin(now());
        return new LoginData(tokens.issue(user.getId()), info(user));
    }

    @Transactional
    public LoginData register(UserCreateRequest request) {
        String email = normalizeEmail(request.email());
        if (users.findByEmail(email).isPresent()) {
            throw new BusinessException(400, "用户已存在");
        }
        LocalDateTime now = now();
        UserEntity user = new UserEntity();
        user.setName(request.name());
        user.setEmail(email);
        user.setPassword(passwords.hash(request.password()));
        user.setStatus("active");
        user.setCreatedAt(now);
        user.setLastLogin(now);
        try {
            UserEntity saved = users.saveAndFlush(user);
            return new LoginData(tokens.issue(saved.getId()), info(saved));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(400, "用户已存在");
        }
    }

    @Transactional(readOnly = true)
    public UserResponse profile(long userId) {
        return users.findById(userId)
                .map(UserApplicationService::response)
                .orElseThrow(() -> new BusinessException(401, "无法验证凭据"));
    }

    private LocalDateTime now() {
        return UtcDateTimes.toDatabase(clock.instant());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserInfo info(UserEntity user) {
        return new UserInfo(user.getId(), user.getName(), user.getEmail());
    }

    private static UserResponse response(UserEntity user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(),
                UtcDateTimes.toInstant(user.getCreatedAt()));
    }
}
