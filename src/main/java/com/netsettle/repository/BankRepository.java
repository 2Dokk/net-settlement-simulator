package com.netsettle.repository;

import com.netsettle.domain.Bank;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BankRepository extends JpaRepository<Bank, Long> {

    boolean existsByCode(String code);

    /** 이체 접수에서 한도만 필요하므로 엔티티 대신 값만 읽습니다. */
    @Query("select b.netDebitLimit from Bank b where b.id = :id")
    Optional<Long> findNetDebitLimitById(@Param("id") Long id);

    /**
     * 차액결제용: 모든 은행을 id 오름차순으로 잠급니다(SELECT ... ORDER BY id FOR UPDATE).
     * 은행 행을 잠그는 곳은 차액결제 하나뿐이고, 그마저도 결제 회차 행을 먼저 잡은 뒤에 들어오므로
     * 결제끼리 교착될 일이 없습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bank b order by b.id")
    List<Bank> lockAllOrderById();
}
