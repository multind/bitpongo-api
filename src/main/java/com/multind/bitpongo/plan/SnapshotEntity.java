package com.multind.bitpongo.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.multind.bitpongo.common.time.UtcDateTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "snapshot")
public class SnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INT") private Long id;
    @Column(length = 32) private String value;
    @Column(length = 32) private String type;
    @Column(name = "user_id", columnDefinition = "INT") private Long userId;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "plan_id", columnDefinition = "INT") private Long planId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    private PlanEntity plan;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    @JsonIgnore
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @JsonProperty("created_at")
    public Instant getCreatedAtInstant() { return UtcDateTimes.toInstant(createdAt); }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public PlanEntity getPlan() { return plan; }
}
