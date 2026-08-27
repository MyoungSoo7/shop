package github.lms.lemuel.inquiry.domain;

/**
 * 답변 상태.
 *
 * <p><b>저장하지 않는다.</b> 답변이 있으면 ANSWERED, 없으면 WAITING — 답변 목록에서 매번
 * 계산한다. 레거시는 이 값을 질문 행의 칼럼으로 들고 있었고, 답변을 지우는 경로
 * ({@code deleteProductQnaAnswer})가 그 칼럼을 되돌리지 않았다. 답변이 사라진 뒤에도 목록은
 * "답변 완료"라고 말했고, 상세를 열면 아무것도 없었다. 파생값을 저장하면 그것을 갱신하는
 * 경로를 하나 빠뜨리는 순간 두 사실이 어긋난다.
 */
public enum InquiryStatus {

    WAITING("답변 대기"),
    ANSWERED("답변 완료");

    private final String label;

    InquiryStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
