package com.multind.bitpongo.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Encrypts exchange secrets that were stored as plaintext before encryption was introduced. */
@Component
@Profile("!test")
public class ExchangeCredentialBackfill {
    private static final Logger log = LoggerFactory.getLogger(ExchangeCredentialBackfill.class);
    private final JdbcTemplate jdbc;
    private final ExchangeCredentialCrypto crypto;

    public ExchangeCredentialBackfill(JdbcTemplate jdbc, ExchangeCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void encryptLegacyRows() {
        if (!crypto.enabled()) return;
        record Legacy(long id, String secretKey, String password) {}
        var rows = jdbc.query("""
                select id,secret_key,password from exchange
                where (secret_key is not null and secret_key<>'' and secret_key not like 'v1:%')
                   or (password is not null and password<>'' and password not like 'v1:%')
                """, (rs, index) -> new Legacy(rs.getLong("id"), rs.getString("secret_key"), rs.getString("password")));
        int[] updated = {0};
        for (Legacy row : rows) {
            updated[0] += jdbc.update("update exchange set secret_key=?,password=? where id=?",
                    ExchangeCredentialCrypto.encrypt(row.secretKey()),
                    ExchangeCredentialCrypto.encrypt(row.password()),
                    row.id());
        }
        if (updated[0] > 0) log.info("已加密历史交易所凭据 count={}", updated[0]);
    }
}
