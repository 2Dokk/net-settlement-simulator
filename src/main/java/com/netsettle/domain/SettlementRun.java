package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 영업일 하나의 생애주기(OPEN → CLOSED → SETTLED).
 *
 * <p>이 행이 영업일 경계의 기준점입니다. 이체 접수는 OPEN 행에 공유 잠금(FOR SHARE)을, 마감과 결제는
 * 배타 잠금(FOR UPDATE)을 잡습니다. 그래서 마감은 진행 중인 이체가 끝날 때까지 기다리고, 마감 뒤에 온
 * 이체는 다음 영업일로 들어가며, 같은 날 결제를 두 번 돌려도 두 번째는 SETTLED를 보고 멈춥니다.
 */
@Entity
@Table(name = "settlement_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false, unique = true)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public SettlementRun(LocalDate businessDate) {
        this.businessDate = businessDate;
        this.status = RunStatus.OPEN;
    }

    public void close() {
        if (status != RunStatus.OPEN) {
            throw new IllegalStateException("열려 있는 영업일만 마감할 수 있습니다: " + businessDate + " (" + status + ")");
        }
        status = RunStatus.CLOSED;
        closedAt = Instant.now();
    }

    public void markSettled() {
        if (status != RunStatus.CLOSED) {
            throw new IllegalStateException("마감된 영업일만 결제할 수 있습니다: " + businessDate + " (" + status + ")");
        }
        status = RunStatus.SETTLED;
        settledAt = Instant.now();
    }
}
