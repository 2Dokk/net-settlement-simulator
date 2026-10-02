package com.netsettle.api;

import com.netsettle.repository.TransferRepository;
import com.netsettle.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

import static com.netsettle.api.dto.Dtos.*;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final TransferRepository transferRepository;

    /** 거절도 정상 처리 결과라 200으로 돌려주고 status로 구분합니다. 같은 전문번호 재전송이면 replayed=true. */
    @PostMapping
    public TransferResponse submit(@Valid @RequestBody TransferRequest request) {
        TransferService.TransferResult result = transferService.submit(request.messageNo(), request.fromAccountId(),
                request.toAccountId(), request.amount());
        return TransferResponse.from(result.transfer(), result.replayed());
    }

    @GetMapping("/{messageNo}")
    public TransferResponse get(@PathVariable String messageNo) {
        return transferRepository.findByMessageNo(messageNo).map(t -> TransferResponse.from(t, false))
                .orElseThrow(() -> new NoSuchElementException("이체가 없습니다: " + messageNo));
    }
}
