package com.multind.bitpongo.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "user")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT")
    private Long id;

    @Column(length = 100)
    private String name;

    @Column(length = 100, unique = true)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(length = 16, nullable = false)
    private String status = "active";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "display_timezone_mode", length = 16, nullable = false)
    private String displayTimezoneMode = "FOLLOW_DEVICE";

    @Column(name = "display_timezone", length = 64)
    private String displayTimezone;

    @Column(name = "last_device_timezone", length = 64)
    private String lastDeviceTimezone;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isActive() { return "active".equals(status); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDisplayTimezoneMode() { return displayTimezoneMode; }
    public void setDisplayTimezoneMode(String displayTimezoneMode) { this.displayTimezoneMode = displayTimezoneMode; }
    public String getDisplayTimezone() { return displayTimezone; }
    public void setDisplayTimezone(String displayTimezone) { this.displayTimezone = displayTimezone; }
    public String getLastDeviceTimezone() { return lastDeviceTimezone; }
    public void setLastDeviceTimezone(String lastDeviceTimezone) { this.lastDeviceTimezone = lastDeviceTimezone; }
}
