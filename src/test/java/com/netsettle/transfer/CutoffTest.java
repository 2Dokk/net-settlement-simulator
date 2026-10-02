package com.netsettle.transfer;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.RunStatus;
import com.netsettle.domain.Transfer;
import com.netsettle.domain.TransferStatus;
import com.netsettle.repository.BankPositionRepository;
import com.netsettle.repository.SettlementRunRepository;
import com.netsettle.service.BusinessDayService.CloseResult;
import com.netsettle.service.TransferService;
import com.netsettle.service.TransferService.TransferResult;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업일 경계. 마감과 이체가 겹칠 때 이체가 어느 날짜로 들어가는지 정확히 정해져야 합니다.
 *
 * <p>테스트는 한 트랜잭션을 일부러 커밋 직전에 멈춰 두고(TransactionTemplate + latch), 다른 쪽이 실제로
 * PostgreSQL 잠금을 기다리고 있는지 pg_stat_activity로 확인한 뒤 놓아줍니다.
 */
class CutoffTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private SettlementRunRepository runRepository;
    @Autowired
    private BankPositionRepository positionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void transferArrivingDuringCutoffMovesToNextBusinessDay() throws Exception {
        Bank bankA = bank("A", 0, 1_000_000, 0);
        Bank bankB = bank("B", 0, 1_000_000, 0);
        CustomerAccount a = account(bankA, 100_000);
        CustomerAccount b = account(bankB, 0);

        Transfer before = transferService.submit("BEFORE", a.getId(), b.getId(), 1_000).transfer();
        assertThat(before.getBusinessDate()).isEqualTo(DAY);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch closedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        try {
            // 1. 마감 트랜잭션: CLOSED로 바꾸고 다음 영업일을 연 뒤, 커밋하지 않고 멈춥니다.
            Future<CloseResult> close = pool.submit(() -> transactionTemplate.execute(status -> {
                CloseResult result = businessDayService.close(DAY);
                closedButNotCommitted.countDown();
                await(releaseClose);
                return result;
            }));
            assertThat(closedButNotCommitted.await(10, TimeUnit.SECONDS)).isTrue();

            // 2. 그 사이 이체가 들어옵니다. 마감이 영업일 행을 잡고 있으므로 기다려야 합니다.
            Future<TransferResult> during = pool.submit(() -> transferService.submit("DURING", a.getId(), b.getId(), 2_000));
            waitUntilSomeoneIsBlockedOnALock();
            assertThat(during.isDone()).isFalse();

            // 3. 마감 커밋 → 기다리던 이체는 방금 열린 다음 영업일로 들어갑니다.
            releaseClose.countDown();
            assertThat(close.get(10, TimeUnit.SECONDS).closedNow()).isTrue();
            Transfer duringTransfer = during.get(10, TimeUnit.SECONDS).transfer();

            assertThat(duringTransfer.getStatus()).isEqualTo(TransferStatus.APPROVED);
            assertThat(duringTransfer.getBusinessDate()).isEqualTo(NEXT_DAY);
        } finally {
            releaseClose.countDown();
            pool.shutdownNow();
        }

        // 그날 포지션에는 마감 전 이체만, 다음 영업일 포지션에는 마감 중 이체만 들어갑니다.
        assertThat(positionRepository.findByBankIdAndBusinessDate(bankA.getId(), DAY).orElseThrow().getSentTotal()).isEqualTo(1_000);
        assertThat(positionRepository.findByBankIdAndBusinessDate(bankA.getId(), NEXT_DAY).orElseThrow().getSentTotal()).isEqualTo(2_000);
        assertThat(runRepository.findByBusinessDate(DAY).orElseThrow().getStatus()).isEqualTo(RunStatus.CLOSED);
        assertThat(runRepository.findByBusinessDate(NEXT_DAY).orElseThrow().getStatus()).isEqualTo(RunStatus.OPEN);
    }

    @Test
    void cutoffWaitsForTransferAlreadyInProgress() throws Exception {
        Bank bankA = bank("A", 0, 1_000_000, 0);
        Bank bankB = bank("B", 0, 1_000_000, 0);
        CustomerAccount a = account(bankA, 100_000);
        CustomerAccount b = account(bankB, 0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch transferApplied = new CountDownLatch(1);
        CountDownLatch releaseTransfer = new CountDownLatch(1);
        try {
            // 1. 이체 트랜잭션이 반영까지 마치고 커밋 직전에 멈춥니다.
            Future<Transfer> inFlight = pool.submit(() -> transactionTemplate.execute(status -> {
                Transfer t = transferService.submit("IN-FLIGHT", a.getId(), b.getId(), 5_000).transfer();
                transferApplied.countDown();
                await(releaseTransfer);
                return t;
            }));
            assertThat(transferApplied.await(10, TimeUnit.SECONDS)).isTrue();

            // 2. 마감은 이 이체가 끝나기를 기다립니다. 기다리지 않으면 이체가 마감된 날짜에 뒤늦게 섞여 들어갑니다.
            Future<CloseResult> close = pool.submit(() -> businessDayService.close(DAY));
            waitUntilSomeoneIsBlockedOnALock();
            assertThat(close.isDone()).isFalse();

            releaseTransfer.countDown();
            assertThat(inFlight.get(10, TimeUnit.SECONDS).getBusinessDate()).isEqualTo(DAY);
            assertThat(close.get(10, TimeUnit.SECONDS).closedNow()).isTrue();
        } finally {
            releaseTransfer.countDown();
            pool.shutdownNow();
        }
        assertThat(positionRepository.findByBankIdAndBusinessDate(bankA.getId(), DAY).orElseThrow().getSentTotal()).isEqualTo(5_000);
    }

    @Test
    void closingTheSameDayTwiceIsANoOp() {
        assertThat(businessDayService.close(DAY).closedNow()).isTrue();
        CloseResult again = businessDayService.close(DAY);
        assertThat(again.closedNow()).isFalse();
        assertThat(again.nextOpenDay()).isEqualTo(NEXT_DAY);
        assertThat(runRepository.count()).isEqualTo(2); // 다음 영업일이 두 번 열리지 않음
    }

    private void waitUntilSomeoneIsBlockedOnALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("잠금을 기다리는 트랜잭션이 보이지 않습니다");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 트랜잭션을 놓아주지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
