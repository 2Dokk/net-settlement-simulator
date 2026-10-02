package com.netsettle.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 공동분담 배분. 부족 금액을 가중치(이 프로젝트에서는 순이체한도) 비율대로 원 단위 정수로 나눕니다.
 *
 * <p>최대잉여법(largest remainder): 먼저 각자 몫의 정수 부분을 주고, 남은 몇 원은 소수 부분이 큰 순서로
 * 1원씩 더 줍니다(같으면 은행 id가 작은 쪽). 그래서 배분 합계가 항상 부족 금액과 정확히 같습니다.
 */
public final class LossAllocator {

    private LossAllocator() {
    }

    /**
     * @param total   나눌 금액 (0 이상)
     * @param weights 은행 id → 가중치 (0 이상, 합계는 양수)
     * @return 은행 id → 분담액, 합계 = total
     */
    public static Map<Long, Long> allocate(long total, Map<Long, Long> weights) {
        if (total < 0) {
            throw new IllegalArgumentException("배분 금액은 0 이상이어야 합니다: " + total);
        }
        Map<Long, Long> ordered = new TreeMap<>(weights);
        BigInteger weightSum = BigInteger.ZERO;
        for (long w : ordered.values()) {
            if (w < 0) {
                throw new IllegalArgumentException("가중치는 0 이상이어야 합니다: " + w);
            }
            weightSum = weightSum.add(BigInteger.valueOf(w));
        }
        if (weightSum.signum() == 0) {
            throw new IllegalArgumentException("가중치 합이 0이라 배분할 수 없습니다");
        }

        Map<Long, Long> shares = new LinkedHashMap<>();
        List<Remainder> remainders = new ArrayList<>();
        long allocated = 0;
        for (Map.Entry<Long, Long> e : ordered.entrySet()) {
            // total * weight 는 long 범위를 넘을 수 있어 BigInteger로 계산합니다.
            BigInteger[] qr = BigInteger.valueOf(total).multiply(BigInteger.valueOf(e.getValue()))
                    .divideAndRemainder(weightSum);
            long share = qr[0].longValueExact();
            shares.put(e.getKey(), share);
            remainders.add(new Remainder(e.getKey(), qr[1]));
            allocated += share;
        }

        long leftover = total - allocated; // 은행 수보다 작음
        remainders.sort(Comparator.comparing(Remainder::value).reversed().thenComparing(Remainder::bankId));
        for (int i = 0; i < leftover; i++) {
            shares.merge(remainders.get(i).bankId(), 1L, Long::sum);
        }
        return shares;
    }

    private record Remainder(Long bankId, BigInteger value) {
    }
}
