package com.netsettle.settlement;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.RunStatus;
import com.netsettle.domain.Transfer;
import com.netsettle.repository.SettlementRunRepository;
import com.netsettle.service.TransferService;
import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러가 실제로 마감과 차액결제를 부르는지 확인합니다. 다른 테스트는 스케줄러를 꺼 두고 직접 호출하므로,
 * 여기서만 두 크론을 "매초"로 켠 별도 스프링 컨텍스트를 띄웁니다. 테스트가 끝나면 컨텍스트를 닫아서
 * 다른 테스트 도중에 스케줄러가 영업일을 마감하는 일이 없게 합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"netsettle.cutoff-cron=* * * * * *", "netsettle.settlement-cron=* * * * * *"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private SettlementRunRepository runRepository;

    @Test
    void scheduledCutoffAndSettlementRunWithoutAnyManualCall() throws InterruptedException {
        Bank a = bank("A", 10_000, 10_000, 0);
        Bank b = bank("B", 0, 10_000, 0);
        CustomerAccount customerA = account(a, 10_000);
        CustomerAccount customerB = account(b, 0);

        // 스케줄러가 매초 마감하므로 이 이체가 어느 날짜에 들어갈지는 미리 알 수 없습니다. 들어간 날짜를 기록해 둡니다.
        Transfer transfer = transferService.submit("SCHED-1", customerA.getId(), customerB.getId(), 3_000).transfer();
        LocalDate day = transfer.getBusinessDate();

        // 마감도, 결제도 직접 부르지 않습니다. 스케줄러가 그날을 SETTLED로 만들 때까지 기다립니다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline
                && runRepository.findByBusinessDate(day).map(r -> r.getStatus() != RunStatus.SETTLED).orElse(true)) {
            Thread.sleep(200);
        }

        assertThat(runRepository.findByBusinessDate(day).orElseThrow().getStatus()).isEqualTo(RunStatus.SETTLED);
        assertThat(reload(a).getSettlementBalance()).isEqualTo(7_000);
        assertThat(reload(b).getSettlementBalance()).isEqualTo(3_000);
        // 그 사이 다음 영업일들이 계속 열리고 닫혔지만, 열린 영업일은 언제나 하나뿐입니다.
        assertThat(runRepository.findByStatusOrderByBusinessDate(RunStatus.OPEN)).hasSize(1);
    }
}
