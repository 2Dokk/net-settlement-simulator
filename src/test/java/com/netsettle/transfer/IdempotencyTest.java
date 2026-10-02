package com.netsettle.transfer;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.TransferStatus;
import com.netsettle.repository.BankPositionRepository;
import com.netsettle.repository.TransferRepository;
import com.netsettle.service.DuplicateMessageNoException;
import com.netsettle.service.TransferService;
import com.netsettle.service.TransferService.TransferResult;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 전문번호는 몇 번, 얼마나 동시에 들어오든 한 번만 반영됩니다. */
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private BankPositionRepository positionRepository;

    @Test
    void sameMessageNoSentTenTimesConcurrentlyIsAppliedOnce() throws Exception {
        Bank bankA = bank("A", 0, 1_000_000, 0);
        Bank bankB = bank("B", 0, 1_000_000, 0);
        CustomerAccount a = account(bankA, 100_000);
        CustomerAccount b = account(bankB, 0);

        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> transferService.submit("MSG-0001", a.getId(), b.getId(), 30_000));
        }
        List<TransferResult> results = runConcurrently(tasks, 10);

        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(results).extracting(r -> r.transfer().getId()).containsOnly(results.get(0).transfer().getId());
        assertThat(results).allSatisfy(r -> assertThat(r.transfer().getStatus()).isEqualTo(TransferStatus.APPROVED));
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);

        assertThat(customerBalance(a)).isEqualTo(70_000);
        assertThat(customerBalance(b)).isEqualTo(30_000);
        assertThat(positionRepository.findByBankIdAndBusinessDate(bankA.getId(), DAY).orElseThrow().getSentTotal())
                .isEqualTo(30_000);
    }

    @Test
    void replayOfRejectedTransferStaysRejectedEvenAfterFundsArrive() {
        Bank bankA = bank("A", 0, 1_000_000, 0);
        CustomerAccount a = account(bankA, 10_000);
        CustomerAccount a2 = account(bankA, 0);

        TransferResult first = transferService.submit("MSG-R", a.getId(), a2.getId(), 50_000);
        assertThat(first.transfer().getStatus()).isEqualTo(TransferStatus.REJECTED);

        // 잔액이 생긴 뒤 같은 전문번호를 다시 보내도 처음 결과(거절)를 그대로 돌려줍니다. 다시 하려면 새 번호로 보내야 합니다.
        jdbc.update("update customer_accounts set balance = 100000 where id = ?", a.getId());
        TransferResult replay = transferService.submit("MSG-R", a.getId(), a2.getId(), 50_000);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transfer().getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(customerBalance(a2)).isZero();
    }

    @Test
    void reusingMessageNoForDifferentTransferIsRefused() {
        Bank bankA = bank("A", 0, 1_000_000, 0);
        CustomerAccount a = account(bankA, 100_000);
        CustomerAccount a2 = account(bankA, 0);

        transferService.submit("MSG-X", a.getId(), a2.getId(), 1_000);
        assertThatThrownBy(() -> transferService.submit("MSG-X", a.getId(), a2.getId(), 2_000))
                .isInstanceOf(DuplicateMessageNoException.class);
        assertThat(customerBalance(a2)).isEqualTo(1_000);
    }
}
