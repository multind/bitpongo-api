package com.multind.bitpongo.strategy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.multind.bitpongo.common.time.UtcDateTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "strategy")
public class StrategyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT") private Long id;
    @Column(length = 100) private String name;
    private Integer instalment;
    @Column(name = "exchange_id", columnDefinition = "INT") private Long exchangeId;
    @Column(length = 100) private String frequency;
    @Column(length = 100) private String cron;
    @Column(name = "schedule_timezone", length = 64, nullable = false)
    private String scheduleTimezone;
    @Column(name = "`condition`", length = 32, nullable = false) private String condition;
    @Column(name = "user_id", columnDefinition = "INT") private Long userId;
    @Column(name = "created_at") private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getInstalment() { return instalment; }
    public void setInstalment(Integer instalment) { this.instalment = instalment; }
    public Long getExchangeId() { return exchangeId; }
    public void setExchangeId(Long exchangeId) { this.exchangeId = exchangeId; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getScheduleTimezone() { return scheduleTimezone; }
    public void setScheduleTimezone(String scheduleTimezone) { this.scheduleTimezone = scheduleTimezone; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    @JsonIgnore
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @JsonProperty("created_at")
    public Instant getCreatedAtInstant() { return UtcDateTimes.toInstant(createdAt); }
}
