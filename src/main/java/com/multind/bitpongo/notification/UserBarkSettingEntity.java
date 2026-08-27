package com.multind.bitpongo.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "user_bark_setting")
public class UserBarkSettingEntity {

    @Id
    @Column(name = "user_id", columnDefinition = "INT")
    private Long userId;

    @Column(name = "server_url", nullable = false, length = 255)
    private String serverUrl;

    @Column(name = "device_key_ciphertext", nullable = false, length = 1024)
    private String deviceKeyCiphertext;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 16)
    private String locale = "zh-CN";

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getDeviceKeyCiphertext() { return deviceKeyCiphertext; }
    public void setDeviceKeyCiphertext(String deviceKeyCiphertext) { this.deviceKeyCiphertext = deviceKeyCiphertext; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
