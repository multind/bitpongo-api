package com.multind.bitpongo.alerts;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AlertAccountCleanup {
    private final JdbcTemplate jdbc;

    public AlertAccountCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deleteForUser(long userId) {
        // Serialize cleanup with alert delivery and Webhook receipt processing.
        jdbc.queryForList("SELECT id FROM user WHERE id = ? FOR UPDATE", userId);
        jdbc.update("DELETE FROM alert_event WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM price_alert WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM webhook_rule WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM strategy_subscription WHERE user_id = ?", userId);
    }
}
