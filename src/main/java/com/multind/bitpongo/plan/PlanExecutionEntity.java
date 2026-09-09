package com.multind.bitpongo.plan;

import com.multind.bitpongo.common.time.UtcDateTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "plan_fire_execution")
public class PlanExecutionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false, columnDefinition = "INT")
    private Integer planId;

    @Column(name = "scheduled_fire_time", nullable = false)
    private LocalDateTime scheduledFireTime;

    @Column(name = "result_status", nullable = false, length = 32)
    private String resultStatus;

    @Column(length = 64)
    private String reason;

    @Column(nullable = false, length = 1024)
    private String symbols;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer planId) { this.planId = planId; }
    public LocalDateTime getScheduledFireTime() { return scheduledFireTime; }
    public void setScheduledFireTime(LocalDateTime scheduledFireTime) {
        this.scheduledFireTime = scheduledFireTime;
    }
    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSymbols() { return symbols; }
    public void setSymbols(String symbols) { this.symbols = symbols; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public List<String> getSymbolList() {
        if (symbols == null || symbols.isBlank()) return List.of();
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public Instant getScheduledAtInstant() { return UtcDateTimes.toInstant(scheduledFireTime); }
    public Instant getCompletedAtInstant() { return UtcDateTimes.toInstant(completedAt); }
}
