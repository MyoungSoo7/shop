package github.lms.lemuel.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * card-service 가 사용하는 ErrorCode 상수의 HTTP 상태 매핑 고정.
 * 스펙 §8 의 표가 정본 — 여기서 어긋나면 컨트롤러 계약이 조용히 바뀐다.
 */
class CardErrorCodeTest {

    @Test
    @DisplayName("재원 조회 실패는 503 — 폴백 없이 명시적 실패")
    void fundingUnavailableIs503() {
        assertThat(ErrorCode.CARD_FUNDING_UNAVAILABLE.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("심사 탈락·한도 초과·비멤버 발급은 422")
    void businessRejectionsAre422() {
        assertThat(ErrorCode.CARD_SCREENING_REJECTED.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.CARD_SUB_LIMIT_EXCEEDED.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.CARD_HOLDER_NOT_MEMBER.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("중복은 409, 미존재는 404, 권한 부족은 403")
    void conflictNotFoundForbidden() {
        assertThat(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CARD_ALREADY_ISSUED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CARD_ACCOUNT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CARD_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CARD_FORBIDDEN.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("영수증: 판독 실패 503(무폴백) · 미존재 404 · 대사 미통과 승인은 422")
    void receiptCodes() {
        assertThat(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.CARD_RECEIPT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CARD_RECEIPT_NOT_MATCHED.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("code() 는 enum 이름 그대로 — 응답 본문 errorCode 계약")
    void codeIsEnumName() {
        assertThat(ErrorCode.CARD_SCREENING_REJECTED.code()).isEqualTo("CARD_SCREENING_REJECTED");
    }
}
