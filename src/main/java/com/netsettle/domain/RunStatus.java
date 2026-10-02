package com.netsettle.domain;

public enum RunStatus {
    /** 이체 접수 중인 영업일. 동시에 하나만 존재합니다. */
    OPEN,
    /** 마감됨, 차액결제 대기. */
    CLOSED,
    /** 차액결제 완료. 다시 실행해도 아무것도 하지 않습니다. */
    SETTLED
}
