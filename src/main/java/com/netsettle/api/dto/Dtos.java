package com.netsettle.api.dto;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.domain.Transfer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class Dtos {

    public record CreateBankRequest(@NotBlank @Size(max = 10) String code,
                                    @NotBlank String name,
                                    @PositiveOrZero long settlementBalance,
                                    @PositiveOrZero long netDebitLimit,
                                    @PositiveOrZero long collateral) {
    }

    public record BankResponse(Long id, String code, String name, long settlementBalance, long netDebitLimit,
                               long collateral, Long currentNetDebit) {
        public static BankResponse from(Bank b, Long currentNetDebit) {
            return new BankResponse(b.getId(), b.getCode(), b.getName(), b.getSettlementBalance(),
                    b.getNetDebitLimit(), b.getCollateral(), currentNetDebit);
        }
    }

    public record CreateAccountRequest(@NotNull Long bankId, @NotBlank String owner, @PositiveOrZero long openingBalance) {
    }

    public record AccountResponse(Long id, Long bankId, String owner, long balance) {
        public static AccountResponse from(CustomerAccount a) {
            return new AccountResponse(a.getId(), a.getBankId(), a.getOwner(), a.getBalance());
        }
    }

    public record TransferRequest(@NotBlank @Size(max = 40) String messageNo,
                                  @NotNull Long fromAccountId,
                                  @NotNull Long toAccountId,
                                  @Positive long amount) {
    }

    public record TransferResponse(Long id, String messageNo, Long fromAccountId, Long toAccountId, long amount,
                                   LocalDate businessDate, boolean interbank, String status, String rejectReason,
                                   boolean replayed) {
        public static TransferResponse from(Transfer t, boolean replayed) {
            return new TransferResponse(t.getId(), t.getMessageNo(), t.getFromAccountId(), t.getToAccountId(),
                    t.getAmount(), t.getBusinessDate(), t.isInterbank(), t.getStatus().name(), t.getRejectReason(),
                    replayed);
        }
    }
}
