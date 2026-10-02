package com.netsettle.repository;

import com.netsettle.domain.BankPosition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BankPositionRepository extends JpaRepository<BankPosition, Long> {

    /** 그날 첫 이체가 동시에 두 개 들어와도 한 행만 생깁니다. */
    @Modifying
    @Query(value = """
            insert into bank_positions (bank_id, business_date, sent_total, received_total)
            values (:bankId, :businessDate, 0, 0)
            on conflict (bank_id, business_date) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("bankId") Long bankId, @Param("businessDate") LocalDate businessDate);

    /** SELECT ... FOR UPDATE. 두 은행 포지션을 잠글 때는 항상 은행 id 오름차순이어야 합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BankPosition p where p.bankId = :bankId and p.businessDate = :businessDate")
    Optional<BankPosition> lockByBankIdAndBusinessDate(@Param("bankId") Long bankId,
                                                       @Param("businessDate") LocalDate businessDate);

    List<BankPosition> findByBusinessDateOrderByBankId(LocalDate businessDate);

    Optional<BankPosition> findByBankIdAndBusinessDate(Long bankId, LocalDate businessDate);
}
