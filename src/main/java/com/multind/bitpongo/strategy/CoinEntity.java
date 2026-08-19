package com.multind.bitpongo.strategy;

import com.multind.bitpongo.plan.PlanEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "coin")
public class CoinEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT") private Long id;
    @Column(length = 100, nullable = false) private String proportion;
    @Column(length = 256, nullable = false) private String icon;
    @Column(nullable = false, columnDefinition = "FLOAT") private BigDecimal min;
    @Column(nullable = false, columnDefinition = "FLOAT") private BigDecimal max;
    @Column(name = "average_down", nullable = false) private Boolean averageDown;
    @Column(length = 100, nullable = false) private String symbol;
    @Column(nullable = false, columnDefinition = "FLOAT") private BigDecimal average;
    @Column(name = "total_amount", nullable = false, columnDefinition = "FLOAT") private BigDecimal totalAmount;
    @Column(nullable = false, columnDefinition = "FLOAT") private BigDecimal income;
    @Column(name = "user_id", columnDefinition = "INT") private Long userId;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "plan_id", columnDefinition = "INT") private Long planId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    private PlanEntity plan;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProportion() { return proportion; }
    public void setProportion(String proportion) { this.proportion = proportion; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public BigDecimal getMin() { return min; }
    public void setMin(BigDecimal min) { this.min = min; }
    public BigDecimal getMax() { return max; }
    public void setMax(BigDecimal max) { this.max = max; }
    public Boolean getAverageDown() { return averageDown; }
    public void setAverageDown(Boolean averageDown) { this.averageDown = averageDown; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getAverage() { return average; }
    public void setAverage(BigDecimal average) { this.average = average; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getIncome() { return income; }
    public void setIncome(BigDecimal income) { this.income = income; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    @JsonIgnore
    public PlanEntity getPlan() { return plan; }
}
