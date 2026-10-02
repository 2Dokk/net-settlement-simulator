package com.netsettle.service;

/**
 * 차액결제를 끝까지 마칠 수 없는 경우(예: 공동분담 몫조차 낼 수 없는 은행이 있음).
 * 던지는 순간 결제 트랜잭션 전체가 롤백되어, 아무 은행의 잔액도 바뀌지 않은 채 CLOSED로 남습니다.
 */
public class SettlementFailedException extends RuntimeException {

    public SettlementFailedException(String message) {
        super(message);
    }
}
