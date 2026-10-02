package com.netsettle.service;

import com.netsettle.domain.RunStatus;
import com.netsettle.domain.SettlementRun;
import com.netsettle.repository.SettlementRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 지정 시각 마감과 다음 날 차액결제. 시각은 application.yml의 netsettle.*-cron으로 바꿀 수 있고 "-"면 꺼집니다.
 * 같은 작업이 겹쳐 돌아도 마감·결제 쪽에서 잠금과 상태 확인으로 한 번만 반영됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessDayScheduler implements ApplicationRunner {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BusinessDayService businessDayService;
    private final SettlementService settlementService;
    private final SettlementRunRepository runRepository;

    /** 서버 시작 시 열린 영업일이 없으면 엽니다. */
    @Override
    public void run(ApplicationArguments args) {
        businessDayService.ensureOpenDay(LocalDate.now(SEOUL));
    }

    @Scheduled(cron = "${netsettle.cutoff-cron:-}", zone = "Asia/Seoul")
    public void cutoff() {
        businessDayService.closeCurrent();
    }

    @Scheduled(cron = "${netsettle.settlement-cron:-}", zone = "Asia/Seoul")
    public void settleClosedDays() {
        for (SettlementRun run : runRepository.findByStatusOrderByBusinessDate(RunStatus.CLOSED)) {
            try {
                settlementService.settle(run.getBusinessDate());
            } catch (SettlementFailedException e) {
                log.error("차액결제 실패, 다음 실행에서 다시 시도합니다: {} - {}", run.getBusinessDate(), e.getMessage());
            }
        }
    }
}
