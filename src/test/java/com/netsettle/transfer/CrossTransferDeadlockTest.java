package com.netsettle.transfer;

import com.netsettle.domain.Bank;
import com.netsettle.domain.BankPosition;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.TransferStatus;
import com.netsettle.repository.BankPositionRepository;
import com.netsettle.service.TransferService;
import com.netsettle.service.TransferService.TransferResult;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A→B와 B→A 이체를 같은 두 계좌 사이에서 섞어 동시에 보냅니다.
 *
 * <p>"보내는 쪽 먼저 잠금"으로 짰다면 A→B는 A→B 순, B→A는 B→A 순으로 잠가서 서로 상대의 잠금을 기다리는
 * 교착상태가 납니다(PostgreSQL이 한쪽을 deadlock detected로 죽이고, 그 예외로 이 테스트가 실패). 은행 포지션과
 * 고객 계좌를 항상 id 오름차순으로 잠그므로 200건이 모두 처리되어야 합니다.
 */
class CrossTransferDeadlockTest extends AbstractIntegrationTest {

    private static final int EACH_DIRECTION = 100;
    private static final long A_TO_B = 1_000;
    private static final long B_TO_A = 700;
    private static final long OPENING = 10_000_000;

    @Autowired
    private TransferService transferService;
    @Autowired
    private BankPositionRepository positionRepository;

    @Test
    void crossingTransfersAllCompleteWithoutDeadlock() throws Exception {
        Bank bankA = bank("A", 0, 100_000_000, 0);
        Bank bankB = bank("B", 0, 100_000_000, 0);
        CustomerAccount a = account(bankA, OPENING);
        CustomerAccount b = account(bankB, OPENING);
        long totalBefore = totalMoney();

        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < EACH_DIRECTION; i++) {
            String ab = "AB-" + i;
            String ba = "BA-" + i;
            tasks.add(() -> transferService.submit(ab, a.getId(), b.getId(), A_TO_B));
            tasks.add(() -> transferService.submit(ba, b.getId(), a.getId(), B_TO_A));
        }
        List<TransferResult> results = runConcurrently(tasks, 32);

        assertThat(results).hasSize(2 * EACH_DIRECTION)
                .allSatisfy(r -> assertThat(r.transfer().getStatus()).isEqualTo(TransferStatus.APPROVED));

        assertThat(customerBalance(a)).isEqualTo(OPENING - EACH_DIRECTION * A_TO_B + EACH_DIRECTION * B_TO_A);
        assertThat(customerBalance(b)).isEqualTo(OPENING + EACH_DIRECTION * A_TO_B - EACH_DIRECTION * B_TO_A);

        BankPosition posA = positionRepository.findByBankIdAndBusinessDate(bankA.getId(), DAY).orElseThrow();
        BankPosition posB = positionRepository.findByBankIdAndBusinessDate(bankB.getId(), DAY).orElseThrow();
        assertThat(posA.getSentTotal()).isEqualTo(EACH_DIRECTION * A_TO_B);
        assertThat(posA.getReceivedTotal()).isEqualTo(EACH_DIRECTION * B_TO_A);
        assertThat(posB.getSentTotal()).isEqualTo(posA.getReceivedTotal());
        assertThat(posB.getReceivedTotal()).isEqualTo(posA.getSentTotal());
        assertThat(totalMoney()).isEqualTo(totalBefore);
    }
}
