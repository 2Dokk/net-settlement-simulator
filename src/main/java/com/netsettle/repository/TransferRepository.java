package com.netsettle.repository;

import com.netsettle.domain.Transfer;
import com.netsettle.domain.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /**
     * 전문번호 선점. 같은 전문번호로 이미 행이 있으면 아무것도 하지 않고 0을 돌려줍니다.
     *
     * <p>다른 트랜잭션이 같은 전문번호를 넣고 아직 커밋하지 않았다면 PostgreSQL은 그 트랜잭션이 끝날 때까지
     * 기다렸다가 판단합니다. 그래서 동시에 10번 들어와도 정확히 1건만 1을 받습니다.
     */
    @Modifying
    @Query(value = """
            insert into transfers (message_no, from_account_id, to_account_id, amount, business_date, interbank, status)
            values (:messageNo, :fromAccountId, :toAccountId, :amount, :businessDate, :interbank, 'PENDING')
            on conflict (message_no) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("messageNo") String messageNo,
                       @Param("fromAccountId") Long fromAccountId,
                       @Param("toAccountId") Long toAccountId,
                       @Param("amount") long amount,
                       @Param("businessDate") LocalDate businessDate,
                       @Param("interbank") boolean interbank);

    Optional<Transfer> findByMessageNo(String messageNo);

    long countByBusinessDateAndInterbankAndStatus(LocalDate businessDate, boolean interbank, TransferStatus status);

    @Query("""
            select coalesce(sum(t.amount), 0) from Transfer t
            where t.businessDate = :businessDate and t.interbank = true
              and t.status = com.netsettle.domain.TransferStatus.APPROVED
            """)
    long sumApprovedInterbankAmount(@Param("businessDate") LocalDate businessDate);
}
