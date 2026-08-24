package github.lms.lemuel.bulkorder.domain;

import github.lms.lemuel.bulkorder.domain.exception.BulkOrderInvariantViolationException;

/**
 * 대량주문 업로드 양식의 <b>열 1개</b> 정의 — 이름·필수 여부·최대 길이·형식 규칙.
 *
 * <p>레거시 커머스(ssgb2e)의 핵심 아이디어를 그대로 가져온다: <b>검증 규칙은 코드가 아니라 데이터다.</b>
 * 양식에 열이 하나 늘거나 "이 항목도 필수로" 같은 요구가 왔을 때 배포 없이 행 하나로 끝난다 —
 * 대량주문 양식은 고객사·시즌마다 바뀌는 종류의 것이라, 코드에 박으면 매번 배포가 따라온다.
 *
 * @param columnIndex    CSV 열 위치(0-based). 순서가 곧 양식이다
 * @param itemCode       업무 코드(product_id, quantity …) — 확정 단계가 값을 꺼낼 때 쓰는 키
 * @param name           사람이 읽는 이름. 오류 메시지에 그대로 나간다
 * @param required       필수 여부
 * @param maxLength      최대 길이. {@code null} 이면 제한 없음
 * @param validationType 형식 규칙
 * @param validationText 규칙 보조값(ENUM 허용 목록 등)
 */
public record BulkOrderColumnSpec(
        int columnIndex,
        String itemCode,
        String name,
        boolean required,
        Integer maxLength,
        BulkOrderValidationType validationType,
        String validationText
) {

    public BulkOrderColumnSpec {
        if (columnIndex < 0) {
            throw new BulkOrderInvariantViolationException("열 위치는 0 이상이어야 합니다: " + columnIndex);
        }
        if (itemCode == null || itemCode.isBlank()) {
            throw new BulkOrderInvariantViolationException("열 업무 코드는 필수입니다");
        }
        if (name == null || name.isBlank()) {
            throw new BulkOrderInvariantViolationException("열 이름은 필수입니다");
        }
        if (maxLength != null && maxLength <= 0) {
            throw new BulkOrderInvariantViolationException("최대 길이는 양수여야 합니다: " + maxLength);
        }
        if (validationType == null) {
            validationType = BulkOrderValidationType.NONE;
        }
    }

    /**
     * 셀 하나를 검증한다. 순서는 <b>필수 → 길이 → 형식</b>이다.
     *
     * <p>필수가 먼저인 이유: 비어 있는데 "형식이 틀렸다"고 하면 운영자는 무엇을 고쳐야 할지 모른다.
     * 길이가 형식보다 먼저인 이유: 200 자 주소가 잘려 들어온 상황에서 형식 오류까지 겹쳐 나오면
     * 진짜 원인(잘림)이 묻힌다.
     *
     * @return 오류 메시지, 통과하면 {@code null}
     */
    public String validate(String value) {
        boolean empty = value == null || value.isBlank();
        if (required && empty) {
            return name + "이(가) 누락되었습니다.";
        }
        if (empty) {
            return null;
        }
        String trimmed = value.trim();
        if (maxLength != null && trimmed.length() > maxLength) {
            return name + "은(는) " + maxLength + "글자까지만 입력해 주세요.";
        }
        return validationType.validate(name, trimmed, validationText);
    }
}
