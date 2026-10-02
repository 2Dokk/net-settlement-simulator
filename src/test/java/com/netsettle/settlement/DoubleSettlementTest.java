package com.netsettle.settlement;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.RunStatus;
import com.netsettle.repository.SettlementEntryRepository;
import com.netsettle.service.SettlementService;
import com.netsettle.service.SettlementService.SettlementResult;
import com.netsettle.service.TransferService;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 날 결제 배치가 두 번(또는 동시에 여러 번) 돌아도 은행 잔액은 한 번만 움직입니다. */
class DoubleSettlementTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementEntryRepository entryRepository;

    @Test
    void concurrentSettlementRunsForTheSameDaySettleOnce() throws Exception {
        Bank a = bank("A", 10_000, 10_000, 0);
        Bank b = bank("B", 10_000, 10_000, 0);
        CustomerAccount customerA = account(a, 10_000);
        CustomerAccount customerB = account(b, 10_000);
        transferService.submit("T1", customerA.getId(), customerB.getId(), 3_000);
        transferService.submit("T2", customerB.getId(), customerA.getId(), 1_000);
        businessDayService.close(DAY);

        List<Callable<SettlementResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> settlementService.settle(DAY));
        }
        List<SettlementResult> results = runConcurrently(tasks, 5);

        assertThat(results).filteredOn(SettlementResult::settledNow).hasSize(1);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(RunStatus.SETTLED));
        assertThat(entryRepository.count()).isEqualTo(2);
        assertThat(reload(a).getSettlementBalance()).isEqualTo(10_000 - 2_000);
        assertThat(reload(b).getSettlementBalance()).isEqualTo(10_000 + 2_000);

        // 나중에 한 번 더 돌려도 그대로
        assertThat(settlementService.settle(DAY).settledNow()).isFalse();
        assertThat(reload(a).getSettlementBalance()).isEqualTo(8_000);
    }

    @Test
    void anOpenDayCannotBeSettled() {
        assertThatThrownBy(() -> settlementService.settle(DAY)).isInstanceOf(IllegalStateException.class);
    }
}
