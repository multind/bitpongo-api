package com.multind.bitpongo.plan;

import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.strategy.StrategyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "plan")
public class PlanEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT") private Long id;
    @Column(name = "total_funds", columnDefinition = "FLOAT") private BigDecimal totalFunds;
    @Column(name = "total_revenue", columnDefinition = "FLOAT") private BigDecimal totalRevenue;
    @Column(name = "total_ratio", columnDefinition = "FLOAT") private BigDecimal totalRatio;
    @Column(name = "next_time") private LocalDateTime nextTime;
    @Column(length = 32) private String status;
    @Column(name = "user_id", columnDefinition = "INT") private Long userId;
    @Column(name = "triggered_count") private Integer triggeredCount;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "strategy_id", nullable = false, columnDefinition = "INT") private Long strategyId;
    @Column(name = "exchange_id", nullable = false, columnDefinition = "INT") private Long exchangeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id", insertable = false, updatable = false)
    private StrategyEntity strategy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exchange_id", insertable = false, updatable = false)
    private ExchangeEntity exchange;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getTotalFunds() { return totalFunds; }
    public void setTotalFunds(BigDecimal totalFunds) { this.totalFunds = totalFunds; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }
    public BigDecimal getTotalRatio() { return totalRatio; }
    public void setTotalRatio(BigDecimal totalRatio) { this.totalRatio = totalRatio; }
    public LocalDateTime getNextTime() { return nextTime; }
    public void setNextTime(LocalDateTime nextTime) { this.nextTime = nextTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getTriggeredCount() { return triggeredCount; }
    public void setTriggeredCount(Integer triggeredCount) { this.triggeredCount = triggeredCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long strategyId) { this.strategyId = strategyId; }
    public Long getExchangeId() { return exchangeId; }
    public void setExchangeId(Long exchangeId) { this.exchangeId = exchangeId; }
    public StrategyEntity getStrategy() { return strategy; }
    public ExchangeEntity getExchange() { return exchange; }
}
