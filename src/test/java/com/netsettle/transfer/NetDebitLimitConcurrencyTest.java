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
 * 순이체한도 직전에서 이체 100건을 동시에 보냅니다.
 *
 * <p>포지션 행을 잠그지 않으면 여러 스레드가 같은 순채무(900,000)를 읽고 모두 "10,000 더해도 한도 안"이라고
 * 판단해 한도를 넘겨 승인합니다. 포지션을 FOR UPDATE로 잠그고 그 안에서 검사하므로 정확히 10건만 통과해야 합니다.
 */
class NetDebitLimitConcurrencyTest extends AbstractIntegrationTest {

    private static final long LIMIT = 1_000_000;
    private static final long ALREADY_USED = 900_000;
    private static final long AMOUNT = 10_000;
    private static final int REQUESTS = 100;

    @Autowired
    private TransferService transferService;
    @Autowired
    private BankPositionRepository positionRepository;

    @Test
    void approvedTotalNeverExceedsNetDebitLimit() throws Exception {
        Bank a = bank("A", 0, LIMIT, 0);
        Bank b = bank("B", 0, LIMIT, 0);
        // 한 고객 계좌가 아니라 A은행 고객 10명이 보내게 해서, 막는 것이 계좌 잠금이 아니라 은행 포지션 잠금임을 보입니다.
        List<CustomerAccount> senders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            senders.add(account(a, 10_000_000));
        }
        CustomerAccount receiver = account(b, 0);

        transferService.submit("PRE-1", senders.get(0).getId(), receiver.getId(), ALREADY_USED);

        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            CustomerAccount sender = senders.get(i % senders.size());
            String messageNo = "LIMIT-" + i;
            tasks.add(() -> transferService.submit(messageNo, sender.getId(), receiver.getId(), AMOUNT));
        }
        List<TransferResult> results = runConcurrently(tasks, 32);

        long approved = results.stream().filter(r -> r.transfer().getStatus() == TransferStatus.APPROVED).count();
        long rejected = results.stream().filter(r -> r.transfer().getStatus() == TransferStatus.REJECTED).count();
        assertThat(approved).isEqualTo((LIMIT - ALREADY_USED) / AMOUNT); // 10
        assertThat(rejected).isEqualTo(REQUESTS - approved);
        assertThat(results).filteredOn(r -> r.transfer().getStatus() == TransferStatus.REJECTED)
                .allSatisfy(r -> assertThat(r.transfer().getRejectReason()).startsWith("순이체한도 초과"));

        BankPosition position = positionRepository.findByBankIdAndBusinessDate(a.getId(), DAY).orElseThrow();
        assertThat(position.netDebit()).isEqualTo(LIMIT).isLessThanOrEqualTo(bankRepository.findById(a.getId()).orElseThrow().getNetDebitLimit());

        // 고객 잔액도 승인된 건만큼만 움직였는지
        assertThat(customerBalance(receiver)).isEqualTo(ALREADY_USED + approved * AMOUNT);
    }
}
