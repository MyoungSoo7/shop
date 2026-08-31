package github.lms.lemuel.product.application.port.out;

/**
 * product 도메인 이벤트 발행 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>상품 생성/이름변경을 이벤트로 발행해 settlement-service 등이 로컬 상품 프로젝션(product_view, name)을
 * 동기화하게 한다 (ADR 0020 Phase 3b, Event-Carried State Transfer).
 */
public interface PublishProductEventPort {

    /** 상품 생성·이름변경 발행 (productId + 현재 name). */
    void publishProductChanged(Long productId, String name);

    /**
     * 셀러 신청서가 카탈로그에 실렸다는 <b>회신</b> 발행 ({@code lemuel.product.registered}).
     *
     * <p>{@link #publishProductChanged}(사본 동기화용 브로드캐스트)와 굳이 나눠 둔 이유는 수신자가
     * 다르기 때문이다. 이건 seller-service 가 낸 요청 하나에 대한 답이라서 {@code submissionId} 를
     * 실어야 하고, 그 필드는 셀러 신청과 무관한 상품 변경에는 채울 값이 없다. 한 토픽에 합치면
     * 수신 측이 "submissionId 가 있으면 회신" 이라는 <b>암묵</b> 규칙으로 갈라 읽게 되고, 그 규칙은
     * 어느 스키마에도 적히지 않는다.
     */
    void publishSellerProductRegistered(Long productId, String name, long submissionId, long sellerId);
}
