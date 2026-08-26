package github.lms.lemuel.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 플랫폼 공통 에러 코드 카탈로그.
 *
 * <p>각 코드는 (HTTP 상태, 기본 메시지)를 보유한다. {@link BusinessException} 이 이 코드를 들고 던져지면
 * {@code GlobalExceptionHandler} 의 단일 핸들러가 코드→상태/응답으로 변환한다. 새로운 도메인 예외는
 * 여기에 코드만 추가하고 {@code BusinessException} 을 상속하면 되며 별도의 @ExceptionHandler 가 필요 없다.
 *
 * <p>이 enum 은 {@code common.exception}(인프라) 패키지에 있어 HttpStatus 를 참조해도 무방하다.
 * 도메인 예외는 이 코드(enum 상수)만 참조하므로 Spring 에 직접 의존하지 않는다(헥사고날 도메인 순수성 유지).
 */
public enum ErrorCode {

    // ─── 공통(기술) ──────────────────────────────────────────────────────────
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서 처리할 수 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    LOCK_TIMEOUT(HttpStatus.CONFLICT, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 리소스에 접근할 권한이 없습니다."),
    // 도메인 리소스 부재(XXX_NOT_FOUND)가 아니라 "그런 엔드포인트 자체가 없다"는 뜻이다. 둘을 섞으면
    // 클라이언트가 "주문이 없다" 와 "URL 을 잘못 썼다" 를 구분하지 못한다.
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 경로를 찾을 수 없습니다."),
    // 경로는 있는데 메서드가 다른 경우다. 404 로 뭉치면 클라이언트가 "주소가 틀렸다"로 오인해
    // 엉뚱한 곳을 고친다 — 실제로는 GET/POST 를 바꿔 부르면 되는 상황이다.
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 메서드입니다."),
    // 서블릿이 멀티파트를 끊은 경우다. 도메인 크기 검증(예: ImageUpload)에는 닿지도 못하므로
    // 도메인 오류로 표현할 수 없다. 400 이 아니라 413 인 이유 — 요청이 틀린 게 아니라 크다.
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 파일이 허용 크기를 넘습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),

    // ─── order ───────────────────────────────────────────────────────────────
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    USER_NOT_EXISTS(HttpStatus.BAD_REQUEST, "존재하지 않는 사용자입니다."),
    DUPLICATE_ORDER_SUBMISSION(HttpStatus.CONFLICT, "이미 처리 중이거나 처리된 주문 요청입니다."),
    // ─── 대량주문(초안 업로드 → 검증 → 확정) ───
    BULK_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "대량주문 초안을 찾을 수 없습니다."),
    BULK_ORDER_INVARIANT(HttpStatus.BAD_REQUEST, "대량주문 초안 구성이 올바르지 않습니다."),
    INVALID_BULK_ORDER_STATE(HttpStatus.CONFLICT, "현재 상태에서 처리할 수 없는 대량주문 요청입니다."),
    // 행 단위가 아니라 파일 자체를 읽을 수 없는 경우다 — 행별로 사유를 돌려줄 방법이 없어 전체를 거절한다.
    INVALID_BULK_ORDER_FILE(HttpStatus.BAD_REQUEST, "업로드 파일을 읽을 수 없습니다."),
    // ─── 선물 주문(받는 사람이 배송지를 낸다) ───
    // 없는 토큰과 폐기된 토큰을 같은 문구로 돌려준다 — 구분해 주면 유효한 토큰을 찾는 데 쓰인다.
    GIFT_CLAIM_NOT_FOUND(HttpStatus.NOT_FOUND, "선물 링크를 찾을 수 없습니다."),
    GIFT_MESSAGE_CHANNEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "선물 안내를 보낼 채널이 구성되지 않았습니다."),
    // ─── 주문 시점 개인정보 동의(수집·이용 / 제3자 제공) ───
    PRIVACY_CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 동의 항목에 동의해야 주문할 수 있습니다."),
    // 409 인 이유 — 요청이 틀린 게 아니라 화면이 낡았다. 400 으로 내리면 클라이언트가 입력값을
    // 고치려 들지만, 실제로 해야 할 일은 바뀐 문안을 다시 받아 다시 보여 주는 것이다.
    PRIVACY_CONSENT_TERMS_STALE(HttpStatus.CONFLICT, "동의 문안이 변경되었습니다. 새로 고친 뒤 다시 시도해주세요."),

    // ─── user ────────────────────────────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다."),
    // 423 LOCKED — 401 로 내리면 클라이언트가 "비밀번호가 틀렸다"로 읽고 재시도를 유도해 잠금만 길어진다.
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "연속 로그인 실패로 계정이 잠겼습니다. 잠시 후 다시 시도해주세요."),
    PASSWORD_EXPIRED(HttpStatus.FORBIDDEN, "비밀번호 사용 기한이 지났습니다. 비밀번호를 재설정해주세요."),

    // ─── product ─────────────────────────────────────────────────────────────
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DUPLICATE_PRODUCT_NAME(HttpStatus.CONFLICT, "이미 존재하는 상품명입니다."),
    INSUFFICIENT_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),
    STOCK_CONCURRENCY(HttpStatus.CONFLICT, "재고 동시성 충돌이 발생했습니다. 잠시 후 다시 시도해주세요."),
    IMAGE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // ─── category ────────────────────────────────────────────────────────────
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."),
    DUPLICATE_SLUG(HttpStatus.CONFLICT, "이미 존재하는 슬러그입니다."),
    CIRCULAR_REFERENCE(HttpStatus.BAD_REQUEST, "순환 참조가 발생합니다."),
    CATEGORY_HAS_PRODUCTS(HttpStatus.CONFLICT, "연결된 상품이 있어 삭제할 수 없습니다."),
    CATEGORY_HAS_CHILDREN(HttpStatus.CONFLICT, "하위 카테고리가 있어 삭제할 수 없습니다."),
    CATEGORY_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "허용된 카테고리 깊이를 초과했습니다."),

    // ─── payment ─────────────────────────────────────────────────────────────
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),
    INVALID_PAYMENT_STATE(HttpStatus.BAD_REQUEST, "잘못된 결제 상태입니다."),
    INVALID_ORDER_STATE(HttpStatus.BAD_REQUEST, "잘못된 주문 상태입니다."),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "멱등성 키(Idempotency-Key)가 필요합니다."),
    // PG 승인 금액과 서버가 보관한 주문 금액이 다르다 — 클라이언트가 결제창 금액을 낮춰 연 경우가
    // 대표적이다. 400(요청 형식 오류)이 아니라 409 인 이유: 요청 자체는 문법적으로 유효하고,
    // 서버가 보관한 상태와 "충돌"한 것이기 때문이다(REFUND_EXCEEDS_PAYMENT 와 같은 결).
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "결제 금액이 주문 금액과 일치하지 않습니다."),
    REFUND_EXCEEDS_PAYMENT(HttpStatus.CONFLICT, "환불 금액이 결제 금액을 초과합니다."),
    // 같은 멱등 키가 다른 요청(다른 주문)에 재사용된 경우. 저장된 결과를 돌려주면 결제되지
    // 않은 주문이 성공으로 보이므로, 조용한 replay 대신 충돌로 드러낸다.
    PAYMENT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "이미 다른 요청에 사용된 멱등 키입니다."),
    REFUND_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "환불 처리 중 오류가 발생했습니다."),
    CASH_RECEIPT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "이 결제는 현금영수증 발급 대상이 아닙니다."),
    INVALID_CASH_RECEIPT_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서 처리할 수 없는 현금영수증 요청입니다."),
    DUPLICATE_CASH_RECEIPT(HttpStatus.CONFLICT, "이미 발급된 현금영수증이 있습니다."),

    // ─── settlement / ledger ──────────────────────────────────────────────────
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정산을 찾을 수 없습니다."),
    LEDGER_NOT_FOUND(HttpStatus.NOT_FOUND, "원장 항목을 찾을 수 없습니다."),
    LEDGER_PERIOD_CLOSED(HttpStatus.CONFLICT, "마감된 원장 기간에는 신규 분개를 작성할 수 없습니다."),
    LEDGER_PERIOD_IMBALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "시산표 차대가 균형을 이루지 않아 기간을 마감할 수 없습니다."),
    MONTHLY_CLOSING_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 월의 정보계 마감 이력이 없습니다."),
    MONTHLY_CLOSING_LOCKED(HttpStatus.CONFLICT, "원장 마감된 기간의 정보계 마트는 재적재할 수 없습니다."),
    MONTHLY_CLOSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "정보계 월마감 실행에 실패했습니다."),

    // ─── tax (세금계산서 OCR 스캔) ────────────────────────────────────────────
    TAX_INVOICE_SCAN_NOT_FOUND(HttpStatus.NOT_FOUND, "세금계산서 스캔을 찾을 수 없습니다."),
    TAX_OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "세금계산서 OCR 을 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    TAX_SCAN_UNSUPPORTED_FILE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 스캔 파일 형식입니다."),

    // ─── loan (선정산·기업 신용대출) ─────────────────────────────────────────────
    CORPORATE_LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "대출 건 또는 재무자료를 찾을 수 없습니다."),
    CORPORATE_LOAN_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "대출 심사가 거절되었습니다."),

    // ─── loan (담보·개인신용 대출) ───────────────────────────────────────────────
    SECURED_LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "담보/개인신용 대출을 찾을 수 없습니다."),
    SECURED_LOAN_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "담보/개인신용 대출 심사가 거절되었습니다."),
    // 담보서류 OCR (ADR 0036 확산) — 판독 실패는 무폴백 503, 대사 미통과 승인은 422.
    LOAN_COLLATERAL_DOC_NOT_FOUND(HttpStatus.NOT_FOUND, "담보서류를 찾을 수 없습니다."),
    LOAN_COLLATERAL_DOC_OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "담보서류 판독에 실패했습니다. 잠시 후 다시 시도해주세요."),
    LOAN_COLLATERAL_DOC_NOT_MATCHED(HttpStatus.UNPROCESSABLE_ENTITY, "담보서류 대사가 완료되지 않아 승인할 수 없습니다."),

    // ─── loan (리스·할부 물건금융) ──────────────────────────────────────────────
    LEASE_CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND, "리스·할부 계약을 찾을 수 없습니다."),

    // ─── investment (CEO 투자하기) ──────────────────────────────────────────────
    INVESTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "투자 주문을 찾을 수 없습니다."),
    NOT_INVESTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "투자 부적격 종목입니다."),
    INSUFFICIENT_FUNDING(HttpStatus.UNPROCESSABLE_ENTITY, "가용 재원이 부족합니다."),

    // ─── account (계정계 GL) ────────────────────────────────────────────────────
    NON_POSITIVE_ENTRY_AMOUNT(HttpStatus.BAD_REQUEST, "전표 금액은 양수여야 합니다."),
    UNBALANCED_ACCOUNT_ENTRY(HttpStatus.BAD_REQUEST, "차변과 대변 계정은 달라야 합니다."),
    ENTRY_AMOUNT_SCALE_EXCEEDED(HttpStatus.BAD_REQUEST, "전표 금액의 소수 자릿수가 허용 범위(2)를 초과했습니다."),

    // ─── card (법인카드) ───
    CARD_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "카드계정을 찾을 수 없습니다."),
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "카드를 찾을 수 없습니다."),
    CARD_ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 카드계정이 존재하는 조직입니다."),
    CARD_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 활성 카드를 보유한 임직원입니다."),
    CARD_SCREENING_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "카드 심사 기준을 충족하지 못했습니다."),
    CARD_SUB_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "임직원 한도 합계가 법인 마스터 한도를 초과합니다."),
    CARD_HOLDER_NOT_MEMBER(HttpStatus.UNPROCESSABLE_ENTITY, "해당 조직의 활성 구성원이 아닙니다."),
    CARD_FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    CARD_AUTHORIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "승인 홀드를 찾을 수 없습니다."),
    // 재원 조회 실패는 폴백 없이 명시적 실패시킨다 — 재원을 모른 채 추정 한도를 주면 그 자체가 여신 사고다.
    CARD_FUNDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "재원 조회에 실패했습니다. 잠시 후 다시 시도해주세요."),
    // 영수증 판독 실패도 재원 조회와 같은 무폴백 원칙(ADR 0036) — 추정 판독을 대사 근거로 쓰지 않는다.
    CARD_RECEIPT_OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "영수증 판독에 실패했습니다. 잠시 후 다시 시도해주세요."),
    CARD_RECEIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "영수증을 찾을 수 없습니다."),
    // 대사 미통과 영수증으로는 승인 불가 — 요청 형식의 잘못이 아니라 "지금은 승인 불가"라서 422.
    CARD_RECEIPT_NOT_MATCHED(HttpStatus.UNPROCESSABLE_ENTITY, "영수증 대사가 완료되지 않아 승인할 수 없습니다."),

    // ─── banking (수신 상품 — 정기예금·적금·퇴직연금) ─────────────────────────────
    // 형제 도메인과 같이 상품별 전용 NOT_FOUND 를 둔다. INVALID_ARGUMENT 로 대용하면 없는 리소스가
    // 400 으로 나가 클라이언트가 "잘못 보냈다"와 "없다"를 구분하지 못한다.
    TIME_DEPOSIT_NOT_FOUND(HttpStatus.NOT_FOUND, "정기예금을 찾을 수 없습니다."),
    INSTALLMENT_SAVINGS_NOT_FOUND(HttpStatus.NOT_FOUND, "적금을 찾을 수 없습니다."),
    RETIREMENT_PENSION_NOT_FOUND(HttpStatus.NOT_FOUND, "퇴직연금을 찾을 수 없습니다."),

    // ─── deposit (셀러 예치금 원장) ──────────────────────────────────────────────
    DEPOSIT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "예치 계좌를 찾을 수 없습니다."),
    // 잔고 부족은 요청 형식의 잘못이 아니라 "지금은 처리 불가"라서 400 이 아닌 422 다
    // (investment 의 INSUFFICIENT_FUNDING 과 같은 판단).
    INSUFFICIENT_DEPOSIT(HttpStatus.UNPROCESSABLE_ENTITY, "예치금 잔고가 부족합니다."),
    // deposit_entries 자연키(UNIQUE) 충돌 — 같은 referenceId 로 두 번 기표하려 한 경우.
    // 잔고를 두 번 움직이지 않고 409 로 되돌린다(L3 멱등 방어선이 잡아낸 상황).
    DUPLICATE_DEPOSIT_ENTRY(HttpStatus.CONFLICT, "이미 처리된 예치금 요청입니다."),
    // 수기 기표 증빙 OCR (ADR 0036 확산) — 판독 실패는 무폴백 503, 대사 미통과 기표는 422.
    DEPOSIT_PROOF_NOT_FOUND(HttpStatus.NOT_FOUND, "예치금 증빙을 찾을 수 없습니다."),
    DEPOSIT_PROOF_OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "예치금 증빙 판독에 실패했습니다. 잠시 후 다시 시도해주세요."),
    DEPOSIT_PROOF_NOT_MATCHED(HttpStatus.UNPROCESSABLE_ENTITY, "예치금 증빙 대사가 완료되지 않아 기표할 수 없습니다."),

    // ── 포인트·기프트카드 원장 ────────────────────────────────────────────────
    // 잔액 부족은 오류가 아니라 정상 답변이라 422 다 — 요청 형식은 옳고 상태가 허락하지 않을 뿐이다.
    POINT_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "포인트 잔액이 부족합니다."),
    POINT_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "포인트 금액이 올바르지 않습니다."),
    POINT_INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서는 포인트를 처리할 수 없습니다."),
    // 요청 자체는 옳은데 기존 정책과 기간이 겹치는 상태다 — 먼저 현재 정책을 종료해야 자리가 난다(ADR 0032).
    POINT_POLICY_PERIOD_OVERLAP(HttpStatus.CONFLICT, "같은 범위에 기간이 겹치는 적립률 정책이 이미 있습니다."),
    GIFT_CARD_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "기프트카드 잔액이 부족합니다."),
    GIFT_CARD_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "기프트카드 금액이 올바르지 않습니다."),
    // 코드 등록 실패는 사유를 구분하지 않는다 — 구분하면 유효한 코드의 존재가 새어 나간다.
    GIFT_CARD_INVALID_STATE(HttpStatus.BAD_REQUEST, "사용할 수 없는 기프트카드입니다."),

    // ─── education (과정·차시) ───────────────────────────────────────────────────
    // 코드 문자열 COURSE_NOT_FOUND 는 education 이 자체 advice 로 이미 내보내던 값이다 —
    // 본문 스키마만 공통으로 바꾸고 코드는 그대로 둬서 기존 클라이언트의 분기를 깨지 않는다.
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "과정을 찾을 수 없습니다."),
    // 상태머신 전이 거부 — order/payment 의 INVALID_ORDER_STATE·INVALID_PAYMENT_STATE 와 같은 결이라
    // 409 가 아닌 400 으로 맞춘다(같은 성격의 실패가 서비스마다 다른 코드로 나가지 않게).
    COURSE_INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서는 과정을 변경할 수 없습니다."),
    // 재정렬 요청이 과정의 차시 집합과 일치하지 않는 경우 — 서버 상태가 아니라 요청이 틀렸다.
    LESSON_ORDER_INVALID(HttpStatus.BAD_REQUEST, "차시 순서 요청이 과정의 차시 목록과 일치하지 않습니다."),
    // 경로가 주장한 과정에 그 차시가 없다 — 403 이 아니라 404 다. 403 으로 답하면 "다른 과정에는
    // 그 차시가 있다"는 사실까지 알려주게 된다.
    LESSON_NOT_IN_COURSE(HttpStatus.NOT_FOUND, "해당 과정에 속한 차시가 아닙니다."),

    // ─── education (수강 신청) ──────────────────────────────────────────────────
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수강 신청을 찾을 수 없습니다."),
    // 취소된 신청을 다시 확정하려는 것 같은 전이 거부 — COURSE_INVALID_STATE 와 같은 결로 400 이다.
    ENROLLMENT_INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서는 수강 신청을 변경할 수 없습니다."),
    // 요청 자체는 옳은데 지금 자리가 없다 — 400 이면 "요청을 고치라"는 뜻이 되지만 고칠 것이 없다.
    // 자리가 나면 같은 요청이 그대로 성공하므로 POINT_POLICY_PERIOD_OVERLAP 과 같이 409 다.
    COURSE_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "과정 정원을 초과합니다."),

    // ─── education (강사 명부) ─────────────────────────────────────────────────
    LECTURER_NOT_FOUND(HttpStatus.NOT_FOUND, "강사를 찾을 수 없습니다."),
    // 지웠거나 쉬는 강사에 대한 조작 — 요청을 고치면(다른 강사를 고르면) 성공하므로 400 이다.
    LECTURER_INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서는 강사를 변경하거나 배정할 수 없습니다."),
    // 이미 있는 배정을 다시 만들려 했다 — 요청은 옳고 상태가 이미 그렇다는 뜻이라 409 다.
    LECTURER_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 그 과정에 배정된 강사입니다."),
    // 해제하려는 배정이 없다. LECTURER_NOT_FOUND 와 뭉치면 화면이 "강사가 사라졌나"를 의심하게 된다.
    LECTURER_ASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "그 과정에 배정된 강사가 아닙니다."),

    // ─── site (사이트 팝업) ───────────────────────────────────────────────────
    POPUP_NOT_FOUND(HttpStatus.NOT_FOUND, "팝업을 찾을 수 없습니다."),
    // 지운 팝업을 고치려 했거나 노출 구간이 뒤집혔다 — 요청을 고치면 성공하므로 400 이다.
    POPUP_INVALID_STATE(HttpStatus.BAD_REQUEST, "현재 상태에서는 팝업을 변경할 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답 본문의 {@code errorCode} 값 — enum 이름을 그대로 사용한다. */
    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
