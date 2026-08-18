package com.multind.bitpongo.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionConfigurationGuard {
    public ProductionConfigurationGuard(
            @Value("${spring.datasource.username:}") String databaseUsername,
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${zhitoubao.jwt.secret-key:}") String jwtSecret) {
        require(databaseUsername, "生产数据库用户名");
        require(databasePassword, "生产数据库密码");
        require(jwtSecret, "生产 JWT Secret");
        if (jwtSecret.length() < 32) throw new IllegalStateException("生产 JWT Secret 至少需要 32 个字符");
        rejectExample(databasePassword, "生产数据库密码");
        rejectExample(jwtSecret, "生产 JWT Secret");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "不能为空");
    }

    private static void rejectExample(String value, String name) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("replace-with-") || normalized.contains("local-only")) {
            throw new IllegalStateException(name + "不能使用示例值");
        }
    }
}
