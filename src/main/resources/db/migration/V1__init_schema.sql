-- 모든 금액은 원 단위 정수(BIGINT)입니다. 공동분담 배분에서 소수점 반올림 오차가 생기지 않도록
-- 나머지를 최대잉여법으로 정수 배분하고, 합계가 정확히 맞는지 검사합니다.

CREATE TABLE banks (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(10)  NOT NULL UNIQUE,
    name               VARCHAR(100) NOT NULL,
    settlement_balance BIGINT       NOT NULL CHECK (settlement_balance >= 0), -- 한국은행 결제계좌 잔액 역할
    net_debit_limit    BIGINT       NOT NULL CHECK (net_debit_limit >= 0),    -- 순이체한도
    collateral         BIGINT       NOT NULL CHECK (collateral >= 0)          -- 남은 담보 금액
);

CREATE TABLE customer_accounts (
    id      BIGSERIAL PRIMARY KEY,
    bank_id BIGINT NOT NULL REFERENCES banks (id),
    owner   VARCHAR(100) NOT NULL,
    balance BIGINT NOT NULL CHECK (balance >= 0)
);

-- 영업일 하나당 한 행. OPEN(이체 접수 중) → CLOSED(마감, 결제 대기) → SETTLED(차액결제 완료).
CREATE TABLE settlement_runs (
    id            BIGSERIAL PRIMARY KEY,
    business_date DATE        NOT NULL UNIQUE,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'SETTLED')),
    closed_at     TIMESTAMPTZ,
    settled_at    TIMESTAMPTZ
);

-- 동시에 열려 있는 영업일은 최대 하나.
CREATE UNIQUE INDEX settlement_runs_single_open ON settlement_runs (status) WHERE status = 'OPEN';

CREATE TABLE transfers (
    id              BIGSERIAL PRIMARY KEY,
    message_no      VARCHAR(40) NOT NULL UNIQUE, -- 전문번호: 같은 요청의 재전송을 걸러내는 키
    from_account_id BIGINT      NOT NULL REFERENCES customer_accounts (id),
    to_account_id   BIGINT      NOT NULL REFERENCES customer_accounts (id),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    business_date   DATE        NOT NULL,
    interbank       BOOLEAN     NOT NULL,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reject_reason   VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transfers_business_date ON transfers (business_date);

-- 은행별·영업일별 누적 송금/수취 합계. 순채무 = sent_total - received_total.
CREATE TABLE bank_positions (
    id             BIGSERIAL PRIMARY KEY,
    bank_id        BIGINT NOT NULL REFERENCES banks (id),
    business_date  DATE   NOT NULL,
    sent_total     BIGINT NOT NULL DEFAULT 0,
    received_total BIGINT NOT NULL DEFAULT 0,
    UNIQUE (bank_id, business_date)
);

-- 차액결제 결과. 은행 하나당 한 행.
CREATE TABLE settlement_entries (
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT NOT NULL REFERENCES settlement_runs (id),
    bank_id           BIGINT NOT NULL REFERENCES banks (id),
    net_amount        BIGINT NOT NULL, -- 받은 합계 - 보낸 합계 (+면 받을 돈, -면 갚을 돈)
    paid_from_balance BIGINT NOT NULL, -- 결제계좌에서 실제로 낸 금액
    collateral_used   BIGINT NOT NULL, -- 담보로 메운 금액
    shortfall         BIGINT NOT NULL, -- 결제계좌+담보로도 못 낸 금액 (다른 은행이 분담)
    loss_share        BIGINT NOT NULL, -- 다른 은행의 부족분 중 이 은행이 분담한 금액
    received          BIGINT NOT NULL, -- 결제계좌로 받은 금액
    UNIQUE (run_id, bank_id)
);
