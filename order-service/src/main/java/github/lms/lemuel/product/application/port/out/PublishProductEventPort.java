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
}
