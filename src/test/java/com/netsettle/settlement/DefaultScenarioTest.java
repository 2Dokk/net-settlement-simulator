package com.netsettle.settlement;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.RunStatus;
import com.netsettle.domain.SettlementEntry;
import com.netsettle.repository.SettlementEntryRepository;
import com.netsettle.repository.SettlementRunRepository;
import com.netsettle.service.SettlementFailedException;
import com.netsettle.service.SettlementService;
import com.netsettle.service.SettlementService.SettlementResult;
import com.netsettle.service.TransferService;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 갚을 은행이 결제계좌 잔액으로 다 못 갚는 시나리오. 이체는 어제 이미 고객에게 반영됐으므로,
 * 모자라는 돈은 담보 → 다른 은행 공동분담 순으로 메워야 하고 어떤 경우에도 전체 돈의 합은 그대로여야 합니다.
 */
class DefaultScenarioTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementEntryRepository entryRepository;
    @Autowired
    private SettlementRunRepository runRepository;

    @Test
    void collateralCoversWhatTheSettlementAccountCannot() {
        Bank a = bank("A", 600, 5_000, 500);
        Bank b = bank("B", 0, 5_000, 0);
        CustomerAccount customerA = account(a, 10_000);
        CustomerAccount customerB = account(b, 0);
        long totalBefore = totalMoney();

        transferService.submit("T1", customerA.getId(), customerB.getId(), 1_000);
        businessDayService.close(DAY);
        Map<Long, SettlementEntry> entries = byBank(settlementService.settle(DAY));

        // A: 1,000을 갚아야 하는데 결제계좌 600 → 나머지 400은 담보
        assertThat(entries.get(a.getId()).getNetAmount()).isEqualTo(-1_000);
        assertThat(entries.get(a.getId()).getPaidFromBalance()).isEqualTo(600);
        assertThat(entries.get(a.getId()).getCollateralUsed()).isEqualTo(400);
        assertThat(entries.get(a.getId()).getShortfall()).isZero();
        assertThat(reload(a).getSettlementBalance()).isZero();
        assertThat(reload(a).getCollateral()).isEqualTo(100);

        // B: 1,000 전액 수령, 아무도 공동분담하지 않음
        assertThat(entries.get(b.getId()).getReceived()).isEqualTo(1_000);
        assertThat(entries.values()).allSatisfy(e -> assertThat(e.getLossShare()).isZero());
        assertThat(reload(b).getSettlementBalance()).isEqualTo(1_000);

        assertThat(totalMoney()).isEqualTo(totalBefore);
    }

    @Test
    void remainingShortfallIsSharedByOtherBanksInProportionToTheirLimits() {
        // A는 결제계좌 300 + 담보 200 = 500밖에 없는데 1,000을 갚아야 함 → 부족 500
        Bank a = bank("A", 300, 10_000, 200);
        Bank b = bank("B", 0, 1_000, 0);     // 한도 비율 1
        Bank c = bank("C", 0, 2_000, 0);     // 한도 비율 2
        CustomerAccount customerA = account(a, 10_000);
        CustomerAccount customerB = account(b, 0);
        CustomerAccount customerC = account(c, 0);
        long totalBefore = totalMoney();

        transferService.submit("T1", customerA.getId(), customerB.getId(), 600);
        transferService.submit("T2", customerA.getId(), customerC.getId(), 400);
        businessDayService.close(DAY);
        Map<Long, SettlementEntry> entries = byBank(settlementService.settle(DAY));

        SettlementEntry ea = entries.get(a.getId());
        assertThat(ea.getNetAmount()).isEqualTo(-1_000);
        assertThat(ea.getPaidFromBalance()).isEqualTo(300);
        assertThat(ea.getCollateralUsed()).isEqualTo(200);
        assertThat(ea.getShortfall()).isEqualTo(500);

        // 500을 1:2로 → 166.67 / 333.33. 정수 부분 166 + 333 = 499, 남은 1원은 소수 부분이 큰 B에게.
        SettlementEntry eb = entries.get(b.getId());
        SettlementEntry ec = entries.get(c.getId());
        assertThat(eb.getLossShare()).isEqualTo(167);
        assertThat(ec.getLossShare()).isEqualTo(333);
        assertThat(eb.getLossShare() + ec.getLossShare()).isEqualTo(ea.getShortfall());

        // 받을 은행은 차액 전액을 받고, 거기서 분담액을 냅니다.
        assertThat(eb.getReceived()).isEqualTo(600);
        assertThat(ec.getReceived()).isEqualTo(400);
        assertThat(reload(b).getSettlementBalance()).isEqualTo(600 - 167);
        assertThat(reload(c).getSettlementBalance()).isEqualTo(400 - 333);
        assertThat(reload(a).getSettlementBalance()).isZero();
        assertThat(reload(a).getCollateral()).isZero();

        assertThat(totalMoney()).isEqualTo(totalBefore);
    }

    @Test
    void ifASurvivorCannotPayItsShareTheWholeSettlementRollsBack() {
        // A는 1,000을 갚아야 하는데 아무것도 없음. 공동분담 몫 500을 C도 낼 수 없음.
        Bank a = bank("A", 0, 10_000, 0);
        Bank b = bank("B", 0, 1, 0);
        Bank c = bank("C", 0, 1, 0);
        CustomerAccount customerA = account(a, 10_000);
        CustomerAccount customerB = account(b, 0);

        transferService.submit("T1", customerA.getId(), customerB.getId(), 1_000);
        businessDayService.close(DAY);
        long totalBefore = totalMoney();

        assertThatThrownBy(() -> settlementService.settle(DAY)).isInstanceOf(SettlementFailedException.class);

        // 한 트랜잭션이라 B에 입금됐던 1,000, B가 냈던 분담액까지 모두 되돌아갑니다.
        assertThat(reload(a).getSettlementBalance()).isZero();
        assertThat(reload(b).getSettlementBalance()).isZero();
        assertThat(reload(c).getSettlementBalance()).isZero();
        assertThat(entryRepository.count()).isZero();
        assertThat(runRepository.findByBusinessDate(DAY).orElseThrow().getStatus()).isEqualTo(RunStatus.CLOSED);
        assertThat(totalMoney()).isEqualTo(totalBefore);
    }

    private static Map<Long, SettlementEntry> byBank(SettlementResult result) {
        return result.entries().stream().collect(Collectors.toMap(SettlementEntry::getBankId, Function.identity()));
    }
}
