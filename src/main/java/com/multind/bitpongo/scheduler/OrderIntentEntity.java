package com.multind.bitpongo.scheduler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_intent")
public class OrderIntentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "client_order_id", length = 64, nullable = false, unique = true)
    private String clientOrderId;
    @Column(name = "plan_id", nullable = false, columnDefinition = "INT") private Long planId;
    @Column(name = "coin_id", nullable = false, columnDefinition = "INT") private Long coinId;
    @Column(name = "user_id", nullable = false, columnDefinition = "INT") private Long userId;
    @Column(length = 100, nullable = false) private String symbol;
    @Column(precision = 36, scale = 18, nullable = false) private BigDecimal quantity;
    @Column(name = "scheduled_fire_time", nullable = false) private LocalDateTime scheduledFireTime;
    @Column(length = 32, nullable = false) private String status;
    @Column(nullable = false) private Integer attempts;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getCoinId() { return coinId; }
    public void setCoinId(Long coinId) { this.coinId = coinId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public LocalDateTime getScheduledFireTime() { return scheduledFireTime; }
    public void setScheduledFireTime(LocalDateTime scheduledFireTime) { this.scheduledFireTime = scheduledFireTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
