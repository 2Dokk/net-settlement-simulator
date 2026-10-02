package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false)
    private long balance;

    public CustomerAccount(Long bankId, String owner, long openingBalance) {
        if (openingBalance < 0) {
            throw new IllegalArgumentException("초기 잔액은 0 이상이어야 합니다");
        }
        this.bankId = bankId;
        this.owner = owner;
        this.balance = openingBalance;
    }

    /** 호출자가 CustomerAccountRepository#lockById로 이 행을 잠근 상태여야 합니다. */
    public void debit(long amount) {
        if (balance < amount) {
            throw new IllegalStateException("고객 계좌 %d 잔액 부족: 잔액=%d, 요청=%d".formatted(id, balance, amount));
        }
        balance -= amount;
    }

    public void credit(long amount) {
        balance += amount;
    }
}
