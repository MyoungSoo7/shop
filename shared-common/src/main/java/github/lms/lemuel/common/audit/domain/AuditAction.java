package github.lms.lemuel.common.audit.domain;

public enum AuditAction {
    SETTLEMENT_CONFIRMED,
    SETTLEMENT_CANCELED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    REFUND_FAILED,
    USER_ROLE_CHANGED,
    // 관리자 콘솔에서 회원 명부를 CSV 로 내보냄. 조회는 상태를 바꾸지 않지만 PII 가 밖으로
    // 나가는 사건이라, 목록을 보는 것과 가져가는 것은 감사에서 같은 무게가 아니다.
    MEMBER_LIST_EXPORTED,
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    CASHFLOW_REPORT_ACCESSED,
    DLQ_INSPECTED,
    DLQ_REPLAYED,
    DLQ_PURGED,
    // 정산금 출금(Payout) 운영자 조작 — 실자금 이동이라 감사 추적 필수.
    PAYOUT_EXECUTED,
    PAYOUT_RETRIED,
    PAYOUT_CANCELED,
    // 송금 반송(bounce) 기록 + 정정계좌 재지급 — 실자금 재이동이라 감사 추적 필수.
    PAYOUT_BOUNCE_RECORDED,
    // 셀러 지급 계좌 레지스트리 등록·정정 — PII 계좌 변경의 감사 추적.
    SELLER_BANK_ACCOUNT_REGISTERED,
    // 카드사 분쟁(Chargeback) 결정 — 셀러 환수/면책 판단의 감사 추적.
    CHARGEBACK_ACCEPTED,
    CHARGEBACK_REJECTED,
    // PG 대사 승인 → 정산 역정산(clawback) 적용.
    RECON_ADJUSTMENT_APPLIED,
    // 운영자가 PG 대사를 마감 — 해당 (PG, 날짜) 기간이 잠기고 새 대사가 차단된다.
    PG_RECONCILIATION_CLOSED,
    // ledger_outbox FAILED 항목 운영자 일괄 재큐.
    LEDGER_OUTBOX_REQUEUED,
    // 격리(quarantined) 소비 이벤트 운영자 재처리 — 원본 토픽 republish, operator·quarantineId·event_id 추적.
    QUARANTINE_REPLAYED,
    // 이벤트드리븐 정산 생성(payment.captured 컨슈머, actor=system) — 정산금 발생 지점의 감사 추적.
    SETTLEMENT_CREATED,
    // 홀드백 해제 배치(actor=system) — 셀러 출금가능액 증가 시점의 감사 추적.
    HOLDBACK_RELEASED,
    // 정체 지급후 회수 채권(seed-p0-6) 수기 이관 배치(actor=system) — 자동 상계 정체 감지 시점의 감사 추적.
    SELLER_RECOVERY_ESCALATED,

    // ── loan-service (선정산 LoanAdvance · 기업 CorporateLoan) 금전 액션 ──
    LOAN_ADVANCE_REQUESTED,
    LOAN_ADVANCE_DISBURSED,
    CORPORATE_LOAN_REQUESTED,
    CORPORATE_LOAN_REJECTED,
    CORPORATE_LOAN_DISBURSED,
    LOAN_REPAYMENT_APPLIED,
    // 기업 신용대출 상환(미상환잔액 차감) — 실자금 회수라 감사 추적 필수.
    CORPORATE_LOAN_REPAID,
    // 선정산 대출 연체 진입·상각(대손 확정) — 회수 리스크 상태 전이의 감사 추적.
    LOAN_ADVANCE_OVERDUE,
    LOAN_ADVANCE_WRITTEN_OFF,

    // ── investment-service (투자주문) 금전 액션 ──
    INVESTMENT_ORDER_PLACED,
    INVESTMENT_ORDER_EXECUTED,
    INVESTMENT_ORDER_CANCELED,
    INVESTMENT_ORDER_REJECTED,

    // ── 과거 데이터 멱등 백필 — 누가·언제·몇 건 감사 추적 ──
    // 확정(DONE) 정산에서 누락된 Payout 을 append-only 로 신규 생성하는 백필 실행.
    PAYOUT_BACKFILL_EXECUTED,
    // 차지백·PG 대사 조정의 역분개 누락분을 ledger_outbox 에 적재하는 백필 실행.
    LEDGER_REVERSE_BACKFILL_EXECUTED,
    // 운영자가 정산 배치(확정·홀드백 해제·지급 실행)를 수동 재실행. 자금 이동 단계 포함 여부가 상세에 남는다.
    SETTLEMENT_BATCH_RERUN,

    // ── card-service (법인카드) 금전·상태 액션 ──
    CARD_ACCOUNT_OPENED,
    CARD_ISSUED,
    CARD_LIMIT_CHANGED,
    CARD_STATUS_CHANGED,

    // ── insurance-service (GA 보험) 배치·금전 액션 (actor=system 배치 감사) ──
    // 만기·실효소멸 판정 배치 — 계약 상태를 EXPIRED 로 종결시키는 자동 전이의 감사 추적.
    INSURANCE_POLICY_EXPIRY_BATCH,
    // 수수료 회차 지급 배치 — FC 수수료 실지급 시점의 감사 추적.
    INSURANCE_COMMISSION_PAID,
    // 환수 스윕 배치 — 기지급 수수료를 환수 대기로 전환(금전 회수 개시)한 감사 추적.
    INSURANCE_COMMISSION_CLAWBACK_FLAGGED,
    // 월 수수료 마감 배치 — FC별 당월 지급분 확정 스냅샷 생성의 감사 추적.
    INSURANCE_COMMISSION_MONTHLY_CLOSED,
    // 상품설명서 교부 — 완전판매 증빙(누가·언제·어떤 버전) 기록의 감사 추적.
    INSURANCE_DISCLOSURE_DELIVERED,
    // 방카 25%룰 모니터링 실행 — 위반 0건이어도 "점검했음" 자체가 규제 증빙.
    INSURANCE_BANCA_RULE_CHECKED,
    // 청약 승인 → 계약 발행 — 보장 개시 + 초년도 수수료 확정의 금전적 사건, 건 단위 감사.
    INSURANCE_POLICY_ISSUED,
    // 계약 임의해지 — 해약환급금 산출·지급 요청을 동반하는 금전적 사건, 건 단위 감사.
    INSURANCE_POLICY_SURRENDERED,
    // 청약철회 — 기납입보험료 전액 환급 + 수수료 전액 환수의 기산 사건, 건 단위 감사.
    INSURANCE_POLICY_CANCELLED,
    // 일반지급 실행 배치 — 해약환급금·만기보험금·철회환급금 실지급 시점의 감사 추적.
    INSURANCE_GENERAL_PAYOUT_PAID,
    // 정보계 월마감 배치 — 셀러 월 정산 마트 적재 실행(성공/실패)의 잡 단위 감사 추적.
    MONTHLY_CLOSING_EXECUTED,

    // ── ai 슬라이스 (AI 챗봇 — ADR 0040 으로 settlement-service 에 흡수) ──
    // 사용자가 자기 대화를 삭제 — 발화(비정형 PII 가능)와 그 이력이 사라지는 사건이라 "누가 언제
    // 어떤 대화를 지웠나"를 되짚을 수 있어야 한다. 흡수 전 ai-service 는 자체 DB 에 같은 표준
    // 스키마의 audit_logs 를 따로 갖고 문자열 액션을 넣었는데, settlement_db 로 합쳐지면서
    // 감사 어휘도 이 enum 하나로 모인다(문자열 오타가 조용히 감사 유실이 되던 경로가 닫힌다).
    CONVERSATION_DELETED
}
