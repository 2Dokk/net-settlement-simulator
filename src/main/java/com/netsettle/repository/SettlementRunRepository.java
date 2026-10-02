package com.netsettle.repository;

import com.netsettle.domain.RunStatus;
import com.netsettle.domain.SettlementRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {

    /**
     * 이체 접수용: 열린 영업일을 공유 잠금(FOR SHARE)으로 잡습니다. 이체끼리는 서로 막지 않고,
     * 마감(FOR UPDATE)과는 서로 기다립니다.
     *
     * <p>마감이 커밋되기를 기다렸다가 깨어나면 PostgreSQL이 바뀐 행으로 WHERE를 다시 확인하므로,
     * 방금 CLOSED가 된 행은 빈 결과로 돌아옵니다. 호출자는 쿼리를 다시 실행해 새로 열린 영업일을 잡습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from SettlementRun r where r.status = com.netsettle.domain.RunStatus.OPEN")
    Optional<SettlementRun> lockOpenForShare();

    /** 마감용: 해당 날짜가 아직 열려 있을 때만 배타 잠금으로 잡습니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SettlementRun r where r.businessDate = :date and r.status = com.netsettle.domain.RunStatus.OPEN")
    Optional<SettlementRun> lockOpenByBusinessDate(@Param("date") LocalDate date);

    /** 차액결제용: 상태와 관계없이 배타 잠금. 같은 날 결제가 동시에 두 번 돌면 두 번째는 여기서 기다립니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SettlementRun r where r.businessDate = :date")
    Optional<SettlementRun> lockByBusinessDate(@Param("date") LocalDate date);

    /** 날짜만 읽습니다. 엔티티를 먼저 읽으면 뒤이은 잠금 조회가 캐시된 옛 상태를 돌려줄 수 있습니다. */
    @Query("select r.businessDate from SettlementRun r where r.status = com.netsettle.domain.RunStatus.OPEN")
    Optional<LocalDate> findOpenBusinessDate();

    Optional<SettlementRun> findByBusinessDate(LocalDate date);

    Optional<SettlementRun> findFirstByStatus(RunStatus status);

    Optional<SettlementRun> findFirstByOrderByBusinessDateDesc();

    List<SettlementRun> findByStatusOrderByBusinessDate(RunStatus status);
}
