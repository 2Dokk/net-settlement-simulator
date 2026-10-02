package com.netsettle.repository;

import com.netsettle.domain.CustomerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {

    /**
     * 엔티티가 아니라 소속 은행 id만 읽습니다. 여기서 엔티티를 읽으면 잠기지 않은 사본이 영속성 컨텍스트에
     * 들어가고, 뒤에서 lockById를 불러도 Hibernate가 그 옛날 사본을 그대로 돌려줍니다.
     * DVP 시뮬레이터에서 실제로 겪은 버그라 처음부터 이렇게 설계했습니다.
     */
    @Query("select a.bankId from CustomerAccount a where a.id = :id")
    Optional<Long> findBankIdById(@Param("id") Long id);

    /** SELECT ... FOR UPDATE. 두 계좌를 잠글 때는 항상 id 오름차순이어야 합니다(TransferService). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CustomerAccount a where a.id = :id")
    Optional<CustomerAccount> lockById(@Param("id") Long id);

    @Query("select coalesce(sum(a.balance), 0) from CustomerAccount a")
    long sumBalances();
}
