package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AccountSecurityService {
    private final JdbcTemplate jdbc;
    private final PasswordCompatibilityService passwords;
    public AccountSecurityService(JdbcTemplate jdbc, PasswordCompatibilityService passwords) {
        this.jdbc=jdbc; this.passwords=passwords;
    }
    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        validatePassword(newPassword);
        var rows=jdbc.queryForList("select password from user where id=? and status='active' for update",userId);
        if(rows.isEmpty() || !passwords.matches(oldPassword,(String)rows.getFirst().get("password")))
            throw new BusinessException(400,"当前密码错误");
        if(passwords.matches(newPassword,(String)rows.getFirst().get("password"))) throw new BusinessException(400,"新密码不能与当前密码相同");
        jdbc.update("update user set password=? where id=?",passwords.hash(newPassword),userId);
        invalidate(userId);
    }
    void invalidate(long userId) {
        jdbc.update("update user set security_version=security_version+1 where id=?",userId);
        jdbc.update("update login_session set revoked_at=coalesce(revoked_at,UTC_TIMESTAMP(6)) where user_id=?",userId);
        jdbc.update("update account_email_challenge set consumed=true where user_id=?",userId);
    }
    /** Same rule as registration (UserDtos.UserCreateRequest): 8-128 characters with a letter and a digit. */
    static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).{8,128}$";

    static void validatePassword(String password) {
        if(password==null || !password.matches(PASSWORD_PATTERN))
            throw new BusinessException(400,"密码须为 8 至 128 个字符，且同时包含字母和数字");
    }
}
