package com.netsettle.service;

import com.netsettle.domain.BankPosition;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * 다자간 상계. 은행별 최종 차액 = 받은 합계 - 보낸 합계.
 *
 * <p>모든 은행 간 이체는 한 은행의 '보낸 합계'와 다른 은행의 '받은 합계'를 같은 금액만큼 늘리므로,
 * 차액을 모두 더하면 반드시 0입니다. 0이 아니면 포지션 기록이 어딘가 깨졌다는 뜻이라 결제를 진행하지 않습니다.
 */
public final class NettingCalculator {

    private NettingCalculator() {
    }

    /** @return 은행 id → 최종 차액 (음수: 갚을 은행, 양수: 받을 은행), 은행 id 순 */
    public static Map<Long, Long> netAmounts(Collection<BankPosition> positions) {
        Map<Long, Long> net = new TreeMap<>();
        long sum = 0;
        for (BankPosition position : positions) {
            long amount = position.netSettlementAmount();
            net.merge(position.getBankId(), amount, Math::addExact);
            sum = Math.addExact(sum, amount);
        }
        if (sum != 0) {
            throw new IllegalStateException("상계 결과의 합이 0이 아닙니다: " + sum);
        }
        return net;
    }
}
