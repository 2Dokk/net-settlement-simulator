package com.netsettle.support;

import com.netsettle.domain.Bank;
import com.netsettle.domain.CustomerAccount;
import com.netsettle.repository.BankRepository;
import com.netsettle.repository.CustomerAccountRepository;
import com.netsettle.service.BusinessDayService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 테스트 JVM 전체에서 PostgreSQL 컨테이너 하나를 씁니다(DVP 시뮬레이터와 같은 싱글턴 방식). 클래스마다
 * {@code @Container}를 두면 클래스가 끝날 때 컨테이너가 내려가 새 포트로 다시 뜨는데, 캐시된 스프링
 * 컨텍스트는 옛 포트를 계속 가리키기 때문입니다.
 *
 * <p>스케줄러의 자동 마감·결제는 꺼 두고, 테스트가 직접 마감과 결제를 부릅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"netsettle.cutoff-cron=-", "netsettle.settlement-cron=-"})
public abstract class AbstractIntegrationTest {

    /** 금요일. 마감하면 다음 영업일은 주말을 건너뛴 월요일(2026-10-12)입니다. */
    protected static final LocalDate DAY = LocalDate.of(2026, 10, 9);
    protected static final LocalDate NEXT_DAY = LocalDate.of(2026, 10, 12);

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("netsettle")
                    .withUsername("netsettle")
                    .withPassword("netsettle");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected BankRepository bankRepository;
    @Autowired
    protected CustomerAccountRepository accountRepository;
    @Autowired
    protected BusinessDayService businessDayService;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE settlement_entries, bank_positions, transfers, settlement_runs, customer_accounts, banks
                RESTART IDENTITY CASCADE
                """);
        businessDayService.ensureOpenDay(DAY);
    }

    protected Bank bank(String code, long settlementBalance, long netDebitLimit, long collateral) {
        return bankRepository.save(new Bank(code, code + " Bank", settlementBalance, netDebitLimit, collateral));
    }

    protected CustomerAccount account(Bank bank, long balance) {
        return accountRepository.save(new CustomerAccount(bank.getId(), "customer@" + bank.getCode(), balance));
    }

    /** 은행 행을 DB에서 다시 읽습니다(결제 후 잔액 확인용). */
    protected Bank reload(Bank bank) {
        return bankRepository.findById(bank.getId()).orElseThrow();
    }

    protected long customerBalance(CustomerAccount account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    /**
     * 전체 돈의 합 = 고객 잔액 + 은행 결제계좌 + 담보. 공동분담액은 살아남은 은행의 결제계좌·담보에서 나가
     * 받을 은행의 결제계좌로 들어가므로 이 합 안에 이미 들어 있습니다. 어떤 시나리오에서도 처음과 끝이 같아야 합니다.
     */
    protected long totalMoney() {
        Long customers = jdbc.queryForObject("select coalesce(sum(balance), 0) from customer_accounts", Long.class);
        Long banks = jdbc.queryForObject("select coalesce(sum(settlement_balance + collateral), 0) from banks", Long.class);
        return customers + banks;
    }

    /** 모든 작업을 한 번에 출발시키고 결과를 제출 순서대로 모읍니다. 하나라도 예외가 나면 테스트가 실패합니다. */
    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
