package com.netsettle.settlement;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.RunStatus;
import com.netsettle.domain.TransferStatus;
import com.netsettle.service.SettlementService;
import com.netsettle.service.SettlementService.NettingPreview;
import com.netsettle.service.SettlementService.SettlementResult;
import com.netsettle.service.TransferService;
import com.netsettle.service.TransferService.TransferResult;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 동안 5개 은행 고객끼리 이체 1,000건 → 마감 → 다자간 상계 → 차액결제.
 *
 * <p>상계 결과(은행별 차액)를 이체 테이블에서 직접 다시 계산한 값과 비교하고, 차액의 합이 0인지,
 * 결제 후 전체 돈의 합이 그대로인지 확인합니다. 상계 효과(이체 건수 → 결제 건수)를 출력합니다.
 */
class NettingTest extends AbstractIntegrationTest {

    private static final int BANKS = 5;
    private static final int TRANSFERS = 1_000;
    private static final int ACCOUNTS_PER_BANK = 4;

    @Autowired
    private TransferService transferService;
    @Autowired
    private SettlementService settlementService;

    @Test
    void thousandTransfersNetDownToOneMovementPerBankAndSumToZero() throws Exception {
        List<Bank> banks = new ArrayList<>();
        List<CustomerAccount> accounts = new ArrayList<>();
        for (int i = 0; i < BANKS; i++) {
            // 한도·결제계좌를 넉넉히 줘서 이 테스트에서는 거절이나 부족이 생기지 않게 합니다(상계 자체만 봄).
            Bank bank = bank("B" + i, 100_000_000, 100_000_000, 10_000_000);
            banks.add(bank);
            for (int j = 0; j < ACCOUNTS_PER_BANK; j++) {
                accounts.add(account(bank, 50_000_000));
            }
        }
        long totalBefore = totalMoney();

        Random random = new Random(20261009L); // 고정 시드: 매번 같은 이체 1,000건
        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            CustomerAccount from = accounts.get(random.nextInt(accounts.size()));
            CustomerAccount to;
            do {
                to = accounts.get(random.nextInt(accounts.size()));
            } while (to.getBankId().equals(from.getBankId())); // 은행 간 이체만
            long amount = (1 + random.nextInt(1_000)) * 1_000L; // 1천 ~ 100만 원
            String messageNo = "N-" + i;
            CustomerAccount target = to;
            tasks.add(() -> transferService.submit(messageNo, from.getId(), target.getId(), amount));
        }
        List<TransferResult> results = runConcurrently(tasks, 16);
        assertThat(results).allSatisfy(r -> assertThat(r.transfer().getStatus()).isEqualTo(TransferStatus.APPROVED));

        businessDayService.close(DAY);

        // 다자간 상계: 포지션 테이블 기준 차액을 이체 테이블에서 독립적으로 다시 계산한 값과 비교
        NettingPreview preview = settlementService.previewNetting(DAY);
        assertThat(preview.netAmounts().values().stream().mapToLong(Long::longValue).sum()).isZero();
        assertThat(preview.netAmounts()).isEqualTo(netFromTransferTable());

        SettlementResult result = settlementService.settle(DAY);
        assertThat(result.status()).isEqualTo(RunStatus.SETTLED);
        assertThat(result.stats().interbankTransfers()).isEqualTo(TRANSFERS);
        assertThat(result.stats().settlementCount()).isLessThanOrEqualTo(BANKS);
        assertThat(result.entries()).allSatisfy(e -> {
            assertThat(e.getShortfall()).isZero();
            assertThat(e.getCollateralUsed()).isZero();
        });
        assertThat(totalMoney()).isEqualTo(totalBefore);

        // 은행 결제계좌 변화량 = 그 은행의 차액
        for (Bank bank : banks) {
            long delta = reload(bank).getSettlementBalance() - bank.getSettlementBalance();
            assertThat(delta).isEqualTo(preview.netAmounts().getOrDefault(bank.getId(), 0L));
        }

        System.out.printf("[상계 효과] 은행 간 이체 %,d건 (총 %,d원) → 차액결제 %d건 (총 %,d원), 금액 기준 %.1f%% 감소%n",
                result.stats().interbankTransfers(), result.stats().grossAmount(),
                result.stats().settlementCount(), result.stats().netAmount(),
                100.0 * (result.stats().grossAmount() - result.stats().netAmount()) / result.stats().grossAmount());
    }

    private Map<Long, Long> netFromTransferTable() {
        Map<Long, Long> net = new TreeMap<>();
        jdbc.query("""
                select fa.bank_id as from_bank, ta.bank_id as to_bank, sum(t.amount) as amount
                from transfers t
                join customer_accounts fa on fa.id = t.from_account_id
                join customer_accounts ta on ta.id = t.to_account_id
                where t.business_date = ? and t.status = 'APPROVED' and t.interbank
                group by fa.bank_id, ta.bank_id
                """, rs -> {
            net.merge(rs.getLong("from_bank"), -rs.getLong("amount"), Long::sum);
            net.merge(rs.getLong("to_bank"), rs.getLong("amount"), Long::sum);
        }, DAY);
        return net;
    }
}
