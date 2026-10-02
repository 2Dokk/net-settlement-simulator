package com.netsettle.api;

import com.netsettle.service.BusinessDayService;
import com.netsettle.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
public class SettlementController {

    private final BusinessDayService businessDayService;
    private final SettlementService settlementService;

    @GetMapping("/business-days/current")
    public Map<String, LocalDate> current() {
        return Map.of("businessDate", businessDayService.currentOpenDay()
                .orElseThrow(() -> new NoSuchElementException("열린 영업일이 없습니다")));
    }

    /** 영업일 마감. 이미 마감된 날짜면 closedNow=false로 아무것도 하지 않습니다. */
    @PostMapping("/business-days/{date}/close")
    public BusinessDayService.CloseResult close(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return businessDayService.close(date);
    }

    @GetMapping("/settlements/{date}/netting")
    public SettlementService.NettingPreview netting(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.previewNetting(date);
    }

    /** 차액결제 실행. 이미 결제된 날짜면 settledNow=false로 기존 결과만 돌려줍니다. */
    @PostMapping("/settlements/{date}")
    public SettlementService.SettlementResult settle(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.settle(date);
    }

    @GetMapping("/settlements/{date}")
    public SettlementService.SettlementResult get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.get(date);
    }
}
