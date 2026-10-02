package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 차액결제에서 은행 하나에 일어난 일. */
@Entity
@Table(name = "settlement_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    /** 받은 합계 - 보낸 합계. */
    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    /** 결제계좌에서 실제로 낸 금액 (갚을 몫 + 공동분담 몫). */
    @Column(name = "paid_from_balance", nullable = false)
    private long paidFromBalance;

    /** 담보로 메운 금액 (갚을 몫 + 공동분담 몫). */
    @Column(name = "collateral_used", nullable = false)
    private long collateralUsed;

    /** 결제계좌와 담보로도 못 갚아 다른 은행들이 대신 낸 금액. */
    @Column(nullable = false)
    private long shortfall;

    /** 다른 은행의 부족분 중 이 은행이 분담한 금액. */
    @Column(name = "loss_share", nullable = false)
    private long lossShare;

    /** 결제계좌로 입금받은 금액. */
    @Column(nullable = false)
    private long received;

    public SettlementEntry(Long runId, Long bankId, long netAmount, long paidFromBalance, long collateralUsed,
                           long shortfall, long lossShare, long received) {
        this.runId = runId;
        this.bankId = bankId;
        this.netAmount = netAmount;
        this.paidFromBalance = paidFromBalance;
        this.collateralUsed = collateralUsed;
        this.shortfall = shortfall;
        this.lossShare = lossShare;
        this.received = received;
    }
}
