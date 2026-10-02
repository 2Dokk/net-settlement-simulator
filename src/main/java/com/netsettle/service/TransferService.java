package com.netsettle.service;

import com.netsettle.domain.BankPosition;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.Transfer;
import com.netsettle.repository.BankPositionRepository;
import com.netsettle.repository.BankRepository;
import com.netsettle.repository.CustomerAccountRepository;
import com.netsettle.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 고객 이체 접수. 고객 잔액은 즉시 옮기고, 은행 간 이체면 두 은행의 그날 포지션만 늘립니다.
 * 은행 사이의 실제 돈(결제계좌)은 다음 날 차액결제에서 움직입니다.
 *
 * <p>잠금 순서(모든 이체가 같은 순서를 따르므로 교착상태가 생기지 않음):
 * <ol>
 *   <li>열린 영업일 행 — 공유 잠금(FOR SHARE)</li>
 *   <li>전문번호 — INSERT ... ON CONFLICT DO NOTHING (같은 번호끼리만 줄을 섬)</li>
 *   <li>은행 포지션 — 은행 id 오름차순</li>
 *   <li>고객 계좌 — 계좌 id 오름차순</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final BusinessDayService businessDayService;
    private final TransferRepository transferRepository;
    private final CustomerAccountRepository accountRepository;
    private final BankPositionRepository positionRepository;
    private final BankRepository bankRepository;

    @Transactional
    public TransferResult submit(String messageNo, Long fromAccountId, Long toAccountId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("이체 금액은 0보다 커야 합니다");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("보내는 계좌와 받는 계좌가 같습니다");
        }
        // 엔티티가 아니라 은행 id만 읽습니다. 계좌 엔티티는 잠그면서 처음 읽습니다.
        Long fromBankId = accountRepository.findBankIdById(fromAccountId)
                .orElseThrow(() -> new NoSuchElementException("계좌가 없습니다: " + fromAccountId));
        Long toBankId = accountRepository.findBankIdById(toAccountId)
                .orElseThrow(() -> new NoSuchElementException("계좌가 없습니다: " + toAccountId));
        boolean interbank = !fromBankId.equals(toBankId);

        LocalDate businessDate = businessDayService.lockOpenDayForTransfer();

        int inserted = transferRepository.insertIfAbsent(messageNo, fromAccountId, toAccountId, amount,
                businessDate, interbank);
        Transfer transfer = transferRepository.findByMessageNo(messageNo).orElseThrow();
        if (inserted == 0) {
            // 이미 처리된 전문번호: 아무것도 바꾸지 않고 처음 결과를 그대로 돌려줍니다(멱등성).
            if (!transfer.sameRequest(fromAccountId, toAccountId, amount)) {
                throw new DuplicateMessageNoException(
                        "전문번호 %s는 다른 내용의 이체(id=%d)에 이미 쓰였습니다".formatted(messageNo, transfer.getId()));
            }
            return new TransferResult(transfer, true);
        }

        BankPosition senderPosition = null;
        BankPosition receiverPosition = null;
        if (interbank) {
            Map<Long, BankPosition> positions = lockPositions(fromBankId, toBankId, businessDate);
            senderPosition = positions.get(fromBankId);
            receiverPosition = positions.get(toBankId);

            long limit = bankRepository.findNetDebitLimitById(fromBankId).orElseThrow();
            long netDebitAfter = senderPosition.netDebit() + amount;
            if (netDebitAfter > limit) {
                transfer.reject("순이체한도 초과: 한도=%d, 이체 후 순채무=%d".formatted(limit, netDebitAfter));
                return new TransferResult(transfer, false);
            }
        }

        Map<Long, CustomerAccount> accounts = lockAccounts(fromAccountId, toAccountId);
        CustomerAccount from = accounts.get(fromAccountId);
        CustomerAccount to = accounts.get(toAccountId);
        if (from.getBalance() < amount) {
            transfer.reject("잔액 부족: 잔액=%d, 요청=%d".formatted(from.getBalance(), amount));
            return new TransferResult(transfer, false);
        }

        from.debit(amount);
        to.credit(amount);
        if (interbank) {
            senderPosition.addSent(amount);
            receiverPosition.addReceived(amount);
        }
        transfer.approve();
        return new TransferResult(transfer, false);
    }

    private Map<Long, BankPosition> lockPositions(Long bankA, Long bankB, LocalDate businessDate) {
        List<Long> ordered = ascending(bankA, bankB);
        for (Long bankId : ordered) {
            positionRepository.insertIfAbsent(bankId, businessDate);
        }
        Map<Long, BankPosition> locked = new HashMap<>();
        for (Long bankId : ordered) {
            locked.put(bankId, positionRepository.lockByBankIdAndBusinessDate(bankId, businessDate).orElseThrow());
        }
        return locked;
    }

    private Map<Long, CustomerAccount> lockAccounts(Long accountA, Long accountB) {
        Map<Long, CustomerAccount> locked = new HashMap<>();
        for (Long accountId : ascending(accountA, accountB)) {
            locked.put(accountId, accountRepository.lockById(accountId).orElseThrow());
        }
        return locked;
    }

    private static List<Long> ascending(Long a, Long b) {
        return a < b ? List.of(a, b) : List.of(b, a);
    }

    /** @param replayed 이미 처리된 전문번호라 기존 결과를 돌려준 경우 true */
    public record TransferResult(Transfer transfer, boolean replayed) {
    }
}
