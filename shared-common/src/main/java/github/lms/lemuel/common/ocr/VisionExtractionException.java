package github.lms.lemuel.common.ocr;

/**
 * 비전 추출 실패 — 호출 실패·빈 응답·형식 파손 전부 이 예외 하나다 (무폴백 원칙, ADR 0036).
 *
 * <p>호출 도메인이 자기 503 예외(예: settlement 의 {@code TaxOcrUnavailableException})로 번역한다.
 * 부분 결과·기본값·추정 응답을 만들지 않는다 — 추정 판독을 회계 근거로 쓰는 순간 조용한 오대사다.
 */
public class VisionExtractionException extends RuntimeException {

    public VisionExtractionException(String message) {
        super(message);
    }

    public VisionExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
