package com.netsettle.service;

import com.netsettle.domain.RunStatus;
import com.netsettle.domain.SettlementRun;
import com.netsettle.repository.SettlementRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;

/** 영업일 열기와 마감. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDayService {

    /** 열린 영업일을 찾다가 마감과 겹쳐 놓쳤을 때 다시 시도하는 횟수. 마감 1번당 1번이면 충분합니다. */
    private static final int OPEN_DAY_LOOKUP_ATTEMPTS = 3;

    private final SettlementRunRepository runRepository;

    /**
     * 열린 영업일이 없을 때만 하나 엽니다(서버 시작 시 호출). 마지막 영업일이 있으면 그다음 영업일,
     * 없으면 today를 엽니다.
     */
    @Transactional
    public SettlementRun ensureOpenDay(LocalDate today) {
        Optional<SettlementRun> open = runRepository.findFirstByStatus(RunStatus.OPEN);
        if (open.isPresent()) {
            return open.get();
        }
        LocalDate date = runRepository.findFirstByOrderByBusinessDateDesc()
                .map(latest -> nextBusinessDay(latest.getBusinessDate()))
                .orElse(today);
        log.info("영업일 개시: {}", date);
        return runRepository.save(new SettlementRun(date));
    }

    /**
     * 이체 접수 트랜잭션 안에서 호출: 열린 영업일에 공유 잠금을 잡고 그 날짜를 돌려줍니다.
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로, 이 이체가 커밋되기 전에는 그날이 마감되지 않습니다.
     */
    @Transactional
    public LocalDate lockOpenDayForTransfer() {
        for (int attempt = 0; attempt < OPEN_DAY_LOOKUP_ATTEMPTS; attempt++) {
            // 매 시도가 새 SQL 문이라 READ COMMITTED에서 새 스냅샷을 봅니다. 마감을 기다렸다가 빈 결과를 받았다면
            // 다음 시도에서는 마감이 커밋하면서 만든 다음 영업일이 보입니다.
            Optional<SettlementRun> open = runRepository.lockOpenForShare();
            if (open.isPresent()) {
                return open.get().getBusinessDate();
            }
        }
        throw new IllegalStateException("열린 영업일이 없습니다");
    }

    /**
     * 영업일 마감. 그날 이체를 잡고 있는 트랜잭션이 모두 끝날 때까지 기다린 뒤 CLOSED로 바꾸고,
     * 같은 트랜잭션에서 다음 영업일을 엽니다. 이미 마감된 날짜면 아무것도 하지 않습니다.
     */
    @Transactional
    public CloseResult close(LocalDate date) {
        Optional<SettlementRun> locked = runRepository.lockOpenByBusinessDate(date);
        if (locked.isEmpty()) {
            SettlementRun existing = runRepository.findByBusinessDate(date)
                    .orElseThrow(() -> new NoSuchElementException("영업일이 없습니다: " + date));
            return new CloseResult(date, existing.getStatus(), runRepository.findOpenBusinessDate().orElse(null), false);
        }

        SettlementRun run = locked.get();
        run.close();
        // 열린 영업일은 하나만 허용(부분 유니크 인덱스)하는데, Hibernate는 flush할 때 INSERT를 UPDATE보다
        // 먼저 보냅니다. CLOSED로 바꾼 것을 먼저 내보내야 다음 영업일 INSERT가 인덱스에 걸리지 않습니다.
        runRepository.flush();

        LocalDate next = nextBusinessDay(date);
        runRepository.save(new SettlementRun(next));
        log.info("영업일 마감: {} → 다음 영업일 {}", date, next);
        return new CloseResult(date, RunStatus.CLOSED, next, true);
    }

    /** 스케줄러용: 지금 열려 있는 영업일을 마감합니다. */
    @Transactional
    public Optional<CloseResult> closeCurrent() {
        return runRepository.findOpenBusinessDate().map(this::close);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> currentOpenDay() {
        return runRepository.findOpenBusinessDate();
    }

    /** 주말만 건너뜁니다. 공휴일 달력은 단순화를 위해 넣지 않았습니다. */
    public static LocalDate nextBusinessDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    public record CloseResult(LocalDate businessDate, RunStatus status, LocalDate nextOpenDay, boolean closedNow) {
    }
}
