package com.netsettle.domain;

public enum TransferStatus {
    /** 행은 만들었지만 아직 판정 전. 접수 트랜잭션 안에서만 보이는 중간 상태입니다. */
    PENDING,
    APPROVED,
    REJECTED
}
