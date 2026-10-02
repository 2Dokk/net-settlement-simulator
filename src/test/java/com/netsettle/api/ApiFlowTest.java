package com.netsettle.api;

import com.netsettle.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계층 확인: 은행 등록 → 계좌 → 이체(승인·재전송·번호 재사용·한도 초과) → 마감 → 상계 → 차액결제 → 재결제.
 * 서비스 로직은 다른 테스트가 검증하므로, 여기서는 요청·응답 형식과 상태 코드를 봅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"netsettle.cutoff-cron=-", "netsettle.settlement-cron=-"})
@AutoConfigureMockMvc
class ApiFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullDayThroughTheApi() throws Exception {
        postJson("/banks", """
                {"code":"KB","name":"국민","settlementBalance":300,"netDebitLimit":100000,"collateral":200}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("국민"));
        postJson("/banks", """
                {"code":"SH","name":"신한","settlementBalance":5000,"netDebitLimit":1000,"collateral":0}""");
        postJson("/banks", """
                {"code":"KB","name":"중복","settlementBalance":0,"netDebitLimit":0,"collateral":0}""")
                .andExpect(status().isBadRequest());

        postJson("/accounts", """
                {"bankId":1,"owner":"홍길동","openingBalance":10000}""").andExpect(jsonPath("$.id").value(1));
        postJson("/accounts", """
                {"bankId":2,"owner":"김철수","openingBalance":5000}""").andExpect(jsonPath("$.id").value(2));
        postJson("/accounts", """
                {"bankId":99,"owner":"없음","openingBalance":0}""").andExpect(status().isNotFound());

        String transfer = """
                {"messageNo":"API-1","fromAccountId":1,"toAccountId":2,"amount":600}""";
        postJson("/transfers", transfer)
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.businessDate").value(DAY.toString()));
        postJson("/transfers", transfer).andExpect(jsonPath("$.replayed").value(true));
        postJson("/transfers", """
                {"messageNo":"API-1","fromAccountId":1,"toAccountId":2,"amount":999}""").andExpect(status().isConflict());
        // 신한 순채무 = 2000 - 600 = 1400 > 한도 1000
        postJson("/transfers", """
                {"messageNo":"API-2","fromAccountId":2,"toAccountId":1,"amount":2000}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("순이체한도 초과: 한도=1000, 이체 후 순채무=1400"));
        postJson("/transfers", """
                {"messageNo":"API-3","fromAccountId":2,"toAccountId":1,"amount":0}""").andExpect(status().isBadRequest());

        postJson("/settlements/" + DAY, "{}").andExpect(status().isBadRequest()); // 마감 전
        postJson("/business-days/" + DAY + "/close", "{}")
                .andExpect(jsonPath("$.closedNow").value(true)).andExpect(jsonPath("$.nextOpenDay").value(NEXT_DAY.toString()));
        mvc.perform(get("/business-days/current")).andExpect(jsonPath("$.businessDate").value(NEXT_DAY.toString()));

        mvc.perform(get("/settlements/" + DAY + "/netting"))
                .andExpect(jsonPath("$.netAmounts.1").value(-600))
                .andExpect(jsonPath("$.netAmounts.2").value(600));
        // 국민: 600을 결제계좌 300 + 담보 300... 담보는 200뿐 → 부족 100을 신한이 분담
        postJson("/settlements/" + DAY, "{}")
                .andExpect(jsonPath("$.settledNow").value(true))
                .andExpect(jsonPath("$.entries[0].paidFromBalance").value(300))
                .andExpect(jsonPath("$.entries[0].collateralUsed").value(200))
                .andExpect(jsonPath("$.entries[0].shortfall").value(100))
                .andExpect(jsonPath("$.entries[1].received").value(600))
                .andExpect(jsonPath("$.entries[1].lossShare").value(100));
        postJson("/settlements/" + DAY, "{}").andExpect(jsonPath("$.settledNow").value(false));

        mvc.perform(get("/settlements/2020-01-01")).andExpect(status().isNotFound());
    }

    @Test
    void bodyThatIsNotUtf8GetsAReadableError() throws Exception {
        // Windows 명령줄에서 curl로 한글을 보내면 CP949로 바뀌어 들어옵니다. 메시지 없는 400 대신 원인을 알려줍니다.
        mvc.perform(post("/banks").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"KB","name":"국민","settlementBalance":0,"netDebitLimit":0,"collateral":0}"""
                                .getBytes(Charset.forName("MS949"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다(JSON 형식과 UTF-8 인코딩을 확인하세요)"));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)));
    }
}
