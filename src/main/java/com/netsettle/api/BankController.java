package com.netsettle.api;

import com.netsettle.domain.Bank;
import com.netsettle.domain.BankPosition;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.repository.BankPositionRepository;
import com.netsettle.repository.BankRepository;
import com.netsettle.repository.CustomerAccountRepository;
import com.netsettle.service.BusinessDayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static com.netsettle.api.dto.Dtos.*;

@RestController
@RequiredArgsConstructor
public class BankController {

    private final BankRepository bankRepository;
    private final CustomerAccountRepository accountRepository;
    private final BankPositionRepository positionRepository;
    private final BusinessDayService businessDayService;

    @PostMapping("/banks")
    public BankResponse createBank(@Valid @RequestBody CreateBankRequest request) {
        if (bankRepository.existsByCode(request.code())) {
            throw new IllegalArgumentException("이미 있는 은행 코드입니다: " + request.code());
        }
        Bank bank = bankRepository.save(new Bank(request.code(), request.name(), request.settlementBalance(),
                request.netDebitLimit(), request.collateral()));
        return BankResponse.from(bank, 0L);
    }

    /** 은행 목록과 현재 영업일 순채무(보낸 합계 - 받은 합계). */
    @GetMapping("/banks")
    public List<BankResponse> banks() {
        LocalDate today = businessDayService.currentOpenDay().orElse(null);
        return bankRepository.findAll().stream()
                .map(b -> BankResponse.from(b, today == null ? null : positionRepository
                        .findByBankIdAndBusinessDate(b.getId(), today).map(BankPosition::netDebit).orElse(0L)))
                .toList();
    }

    @PostMapping("/accounts")
    @Transactional
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        if (!bankRepository.existsById(request.bankId())) {
            throw new NoSuchElementException("은행이 없습니다: " + request.bankId());
        }
        return AccountResponse.from(accountRepository.save(
                new CustomerAccount(request.bankId(), request.owner(), request.openingBalance())));
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse account(@PathVariable Long id) {
        return accountRepository.findById(id).map(AccountResponse::from)
                .orElseThrow(() -> new NoSuchElementException("계좌가 없습니다: " + id));
    }
}
