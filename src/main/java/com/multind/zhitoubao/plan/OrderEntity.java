package com.multind.zhitoubao.plan;

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
@Table(name = "`order`")
public class OrderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT") private Long id;
    @Column(length = 32, nullable = false) private String symbol;
    @Column(name = "order_no", length = 64, nullable = false) private String orderNo;
    @Column(name = "client_order_id", length = 64, unique = true) private String clientOrderId;
    @Column(name = "total_amount", nullable = false, columnDefinition = "FLOAT") private BigDecimal totalAmount;
    @Column(name = "average_price", nullable = false, columnDefinition = "FLOAT") private BigDecimal averagePrice;
    @Column(name = "total_cost", nullable = false, columnDefinition = "FLOAT") private BigDecimal totalCost;
    @Column(nullable = false, columnDefinition = "FLOAT") private BigDecimal fee;
    @Column(name = "user_id", columnDefinition = "INT") private Long userId;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "plan_id", columnDefinition = "INT") private Long planId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    private PlanEntity plan;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getAveragePrice() { return averagePrice; }
    public void setAveragePrice(BigDecimal averagePrice) { this.averagePrice = averagePrice; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public PlanEntity getPlan() { return plan; }
}
