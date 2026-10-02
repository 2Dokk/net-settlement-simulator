package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공동망 참가 은행. settlementBalance는 한국은행에 맡겨둔 결제계좌 잔액 역할입니다.
 *
 * <p>잔액·담보를 바꾸는 메서드는 호출자가 이 행을 비관적 잠금으로 잡고 있을 때만 안전합니다
 * (BankRepository#lockAllOrderById).
 */
@Entity
@Table(name = "banks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "settlement_balance", nullable = false)
    private long settlementBalance;

    @Column(name = "net_debit_limit", nullable = false)
    private long netDebitLimit;

    @Column(nullable = false)
    private long collateral;

    public Bank(String code, String name, long settlementBalance, long netDebitLimit, long collateral) {
        if (settlementBalance < 0 || netDebitLimit < 0 || collateral < 0) {
            throw new IllegalArgumentException("결제계좌 잔액, 순이체한도, 담보는 0 이상이어야 합니다");
        }
        this.code = code;
        this.name = name;
        this.settlementBalance = settlementBalance;
        this.netDebitLimit = netDebitLimit;
        this.collateral = collateral;
    }

    /** 결제계좌에서 낼 수 있는 만큼 내고, 실제로 낸 금액을 돌려줍니다. */
    public long payFromBalanceUpTo(long amount) {
        long paid = Math.min(settlementBalance, amount);
        settlementBalance -= paid;
        return paid;
    }

    /** 담보에서 쓸 수 있는 만큼 쓰고, 실제로 쓴 금액을 돌려줍니다. */
    public long useCollateralUpTo(long amount) {
        long used = Math.min(collateral, amount);
        collateral -= used;
        return used;
    }

    public void credit(long amount) {
        settlementBalance += amount;
    }
}
