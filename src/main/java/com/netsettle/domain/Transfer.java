package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 고객 이체 한 건. 행은 TransferRepository#insertIfAbsent(전문번호 UNIQUE)로만 만들어서,
 * 같은 전문번호가 동시에 여러 번 들어와도 한 행만 생기게 합니다.
 */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_no", nullable = false, unique = true, length = 40)
    private String messageNo;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private Long toAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private boolean interbank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransferStatus status;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public void approve() {
        requirePending();
        this.status = TransferStatus.APPROVED;
    }

    public void reject(String reason) {
        requirePending();
        this.status = TransferStatus.REJECTED;
        this.rejectReason = reason;
    }

    /** 같은 전문번호로 다른 내용의 요청이 들어왔는지 확인할 때 씁니다. */
    public boolean sameRequest(Long fromAccountId, Long toAccountId, long amount) {
        return this.fromAccountId.equals(fromAccountId) && this.toAccountId.equals(toAccountId) && this.amount == amount;
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("이미 판정된 이체입니다: " + id);
        }
    }
}
