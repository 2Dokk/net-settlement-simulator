package com.netsettle.service;

import com.netsettle.domain.BankPosition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettingCalculatorTest {

    @Test
    void netIsReceivedMinusSent() {
        // A→B 100, B→C 70, C→A 20
        var net = NettingCalculator.netAmounts(List.of(position(1L, 100, 20), position(2L, 70, 100), position(3L, 20, 70)));
        assertThat(net).containsEntry(1L, -80L).containsEntry(2L, 30L).containsEntry(3L, 50L);
    }

    @Test
    void refusesWhenNetDoesNotSumToZero() {
        assertThatThrownBy(() -> NettingCalculator.netAmounts(List.of(position(1L, 100, 0), position(2L, 0, 99))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static BankPosition position(Long bankId, long sent, long received) {
        BankPosition p = (BankPosition) org.springframework.beans.BeanUtils.instantiateClass(BankPosition.class);
        ReflectionTestUtils.setField(p, "bankId", bankId);
        ReflectionTestUtils.setField(p, "sentTotal", sent);
        ReflectionTestUtils.setField(p, "receivedTotal", received);
        return p;
    }
}
