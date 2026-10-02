package com.netsettle.service;

import com.netsettle.domain.*;
import com.netsettle.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 마감된 영업일의 차액결제. <b>처음부터 끝까지 한 트랜잭션</b>입니다.
 *
 * <p>DVP 시뮬레이터에서는 참가자별 정산을 따로 커밋했다가, 중간에 하나가 실패하자 이미 끝난 정산 때문에
 * 차액이 남는 문제를 겪었습니다. 여기서는 갚기(결제계좌 → 담보) → 입금 → 공동분담을 모두 한 트랜잭션에서 하고,
 * 하나라도 막히면 전부 롤백해서 "일부 은행만 결제된 상태"가 생길 수 없게 했습니다.
 *
 * <p>잠금 순서: 결제 회차 행(FOR UPDATE) → 모든 은행(id 오름차순).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRunRepository runRepository;
    private final SettlementEntryRepository entryRepository;
    private final BankPositionRepository positionRepository;
    private final BankRepository bankRepository;
    private final TransferRepository transferRepository;

    @Transactional
    public SettlementResult settle(LocalDate businessDate) {
        SettlementRun run = runRepository.lockByBusinessDate(businessDate)
                .orElseThrow(() -> new NoSuchElementException("영업일이 없습니다: " + businessDate));
        if (run.getStatus() == RunStatus.SETTLED) {
            // 이미 결제된 날짜: 아무것도 하지 않고 기존 결과만 돌려줍니다.
            return result(run, false);
        }
        if (run.getStatus() == RunStatus.OPEN) {
            throw new IllegalStateException("아직 마감되지 않은 영업일입니다: " + businessDate);
        }

        // CLOSED가 되려면 마감이 이 날짜의 모든 이체 트랜잭션을 기다렸어야 하므로, 포지션은 더 바뀌지 않습니다.
        Map<Long, Long> net = NettingCalculator.netAmounts(positionRepository.findByBusinessDateOrderByBankId(businessDate));

        List<Bank> banks = bankRepository.lockAllOrderById();
        Map<Long, Movement> movements = new TreeMap<>();
        for (Bank bank : banks) {
            movements.put(bank.getId(), new Movement(net.getOrDefault(bank.getId(), 0L)));
        }

        // 1. 갚을 은행: 결제계좌 → 담보 순으로 냅니다. 그래도 남으면 부족분(shortfall)입니다.
        long totalShortfall = 0;
        for (Bank bank : banks) {
            Movement m = movements.get(bank.getId());
            if (m.net >= 0) {
                continue;
            }
            long owed = -m.net;
            m.paid = bank.payFromBalanceUpTo(owed);
            m.collateralUsed = bank.useCollateralUpTo(owed - m.paid);
            m.shortfall = owed - m.paid - m.collateralUsed;
            totalShortfall += m.shortfall;
        }

        // 2. 받을 은행에 차액 전액을 입금합니다. 공동분담보다 먼저 해서, 받을 은행은 들어온 돈으로 분담액을 낼 수 있습니다.
        for (Bank bank : banks) {
            Movement m = movements.get(bank.getId());
            if (m.net > 0) {
                bank.credit(m.net);
                m.received = m.net;
            }
        }

        // 3. 공동분담: 부족분이 생긴 은행을 뺀 나머지 은행이 순이체한도 비율대로 나눠 냅니다.
        if (totalShortfall > 0) {
            Map<Long, Long> weights = new TreeMap<>();
            for (Bank bank : banks) {
                if (movements.get(bank.getId()).shortfall == 0) {
                    weights.put(bank.getId(), bank.getNetDebitLimit());
                }
            }
            if (weights.values().stream().mapToLong(Long::longValue).sum() == 0) {
                throw new SettlementFailedException("공동분담할 은행이 없습니다. 부족 금액=" + totalShortfall);
            }
            Map<Long, Long> shares = LossAllocator.allocate(totalShortfall, weights);
            for (Bank bank : banks) {
                long share = shares.getOrDefault(bank.getId(), 0L);
                if (share == 0) {
                    continue;
                }
                Movement m = movements.get(bank.getId());
                long fromBalance = bank.payFromBalanceUpTo(share);
                long fromCollateral = bank.useCollateralUpTo(share - fromBalance);
                if (fromBalance + fromCollateral < share) {
                    throw new SettlementFailedException("은행 %s가 공동분담액 %d를 낼 수 없습니다(결제계좌+담보 부족). 결제 전체를 취소합니다"
                            .formatted(bank.getCode(), share));
                }
                m.lossShare = share;
                m.paid += fromBalance;
                m.collateralUsed += fromCollateral;
            }
        }

        long totalIn = 0;
        long totalOut = 0;
        for (Movement m : movements.values()) {
            totalIn += m.paid + m.collateralUsed;
            totalOut += m.received;
        }
        // 낸 돈(결제계좌+담보)과 받은 돈이 같아야 돈이 새로 생기거나 사라지지 않은 것입니다.
        if (totalIn != totalOut) {
            throw new IllegalStateException("차액결제 수지 불일치: 낸 돈=%d, 받은 돈=%d".formatted(totalIn, totalOut));
        }

        for (Bank bank : banks) {
            Movement m = movements.get(bank.getId());
            if (m.touched()) {
                entryRepository.save(new SettlementEntry(run.getId(), bank.getId(), m.net, m.paid, m.collateralUsed,
                        m.shortfall, m.lossShare, m.received));
            }
        }
        run.markSettled();
        log.info("차액결제 완료: {} (부족 금액 {}원 공동분담)", businessDate, totalShortfall);
        return result(run, true);
    }

    /** 결제 전에 상계 결과만 미리 봅니다. */
    @Transactional(readOnly = true)
    public NettingPreview previewNetting(LocalDate businessDate) {
        Map<Long, Long> net = NettingCalculator.netAmounts(positionRepository.findByBusinessDateOrderByBankId(businessDate));
        return new NettingPreview(businessDate, net, stats(businessDate, net.values()));
    }

    @Transactional(readOnly = true)
    public SettlementResult get(LocalDate businessDate) {
        SettlementRun run = runRepository.findByBusinessDate(businessDate)
                .orElseThrow(() -> new NoSuchElementException("영업일이 없습니다: " + businessDate));
        return result(run, false);
    }

    private SettlementResult result(SettlementRun run, boolean settledNow) {
        List<SettlementEntry> entries = entryRepository.findByRunIdOrderByBankId(run.getId());
        return new SettlementResult(run.getBusinessDate(), run.getStatus(), settledNow, entries,
                stats(run.getBusinessDate(), entries.stream().map(SettlementEntry::getNetAmount).toList()));
    }

    /** 상계 효과: 은행 간 이체 N건이 차액결제 몇 건으로 줄었는지. */
    private NettingStats stats(LocalDate businessDate, Collection<Long> netAmounts) {
        long interbankCount = transferRepository.countByBusinessDateAndInterbankAndStatus(businessDate, true, TransferStatus.APPROVED);
        long grossAmount = transferRepository.sumApprovedInterbankAmount(businessDate);
        long settlementCount = netAmounts.stream().filter(n -> n != 0).count();
        long netAmount = netAmounts.stream().filter(n -> n > 0).mapToLong(Long::longValue).sum();
        return new NettingStats(interbankCount, grossAmount, settlementCount, netAmount);
    }

    private static final class Movement {
        final long net;
        long paid;
        long collateralUsed;
        long shortfall;
        long lossShare;
        long received;

        Movement(long net) {
            this.net = net;
        }

        boolean touched() {
            return net != 0 || paid != 0 || collateralUsed != 0 || lossShare != 0;
        }
    }

    /**
     * @param interbankTransfers 승인된 은행 간 이체 건수
     * @param grossAmount        그 이체 금액 합계 (상계 전에 은행끼리 오갔어야 할 돈)
     * @param settlementCount    차액이 0이 아닌 은행 수 = 실제 결제 이동 건수
     * @param netAmount          받을 은행들이 받는 금액 합계 (상계 후 실제로 오가는 돈)
     */
    public record NettingStats(long interbankTransfers, long grossAmount, long settlementCount, long netAmount) {
    }

    public record NettingPreview(LocalDate businessDate, Map<Long, Long> netAmounts, NettingStats stats) {
    }

    public record SettlementResult(LocalDate businessDate, RunStatus status, boolean settledNow,
                                   List<SettlementEntry> entries, NettingStats stats) {
    }
}
