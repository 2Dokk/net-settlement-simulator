package com.netsettle.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LossAllocatorTest {

    @Test
    void splitsExactlyWhenDivisible() {
        assertThat(LossAllocator.allocate(900, Map.of(1L, 1L, 2L, 2L))).containsEntry(1L, 300L).containsEntry(2L, 600L);
    }

    @Test
    void leftoverWonsGoToLargestRemainders() {
        // 100을 1:1:1 → 33.33씩, 남은 1원은 나머지가 같으므로 id가 작은 1번에게
        assertThat(LossAllocator.allocate(100, Map.of(1L, 1L, 2L, 1L, 3L, 1L)))
                .containsEntry(1L, 34L).containsEntry(2L, 33L).containsEntry(3L, 33L);
        // 500을 1:2 → 166.67 / 333.33 → 남은 1원은 소수 부분이 큰 1번에게
        assertThat(LossAllocator.allocate(500, Map.of(1L, 1_000L, 2L, 2_000L)))
                .containsEntry(1L, 167L).containsEntry(2L, 333L);
    }

    @Test
    void sumAlwaysEqualsTotalAndEachShareIsWithinOneWonOfExactProportion() {
        Random random = new Random(7);
        for (int round = 0; round < 1_000; round++) {
            Map<Long, Long> weights = new TreeMap<>();
            int banks = 1 + random.nextInt(10);
            for (long id = 1; id <= banks; id++) {
                weights.put(id, (long) random.nextInt(1_000_000_000));
            }
            weights.put(1L, weights.get(1L) + 1); // 합이 0이 되지 않게
            long total = Math.abs(random.nextLong() % 1_000_000_000_000L);
            double weightSum = weights.values().stream().mapToLong(Long::longValue).sum();

            Map<Long, Long> shares = LossAllocator.allocate(total, weights);

            assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
            shares.forEach((id, share) -> assertThat((double) share)
                    .isCloseTo(total * (weights.get(id) / weightSum), org.assertj.core.data.Offset.offset(1.0001)));
        }
    }

    @Test
    void zeroWeightBankPaysNothing() {
        assertThat(LossAllocator.allocate(999, Map.of(1L, 0L, 2L, 5L))).containsEntry(1L, 0L).containsEntry(2L, 999L);
    }

    @Test
    void rejectsAllZeroWeights() {
        assertThatThrownBy(() -> LossAllocator.allocate(1, Map.of(1L, 0L))).isInstanceOf(IllegalArgumentException.class);
    }
}
