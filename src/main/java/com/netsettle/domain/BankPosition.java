package com.netsettle.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 은행 하나의 영업일 하루치 누적 송금·수취 합계. */
@Entity
@Table(name = "bank_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "sent_total", nullable = false)
    private long sentTotal;

    @Column(name = "received_total", nullable = false)
    private long receivedTotal;

    /** 순채무 = 보낸 합계 - 받은 합계. 양수면 다음 날 갚아야 할 돈입니다. */
    public long netDebit() {
        return sentTotal - receivedTotal;
    }

    /** 다자간 상계 결과 = 받은 합계 - 보낸 합계. 음수면 갚을 은행, 양수면 받을 은행입니다. */
    public long netSettlementAmount() {
        return receivedTotal - sentTotal;
    }

    public void addSent(long amount) {
        sentTotal += amount;
    }

    public void addReceived(long amount) {
        receivedTotal += amount;
    }
}
