package github.lms.lemuel.shipping.application.port.out;

/**
 * 셀러(사용자) 존재 확인 포트.
 *
 * <p>정책 테이블에는 {@code users(id)} 를 향한 FK 가 걸려 있어 없는 셀러로 저장하면 DB 가 막는다.
 * 그런데 그 차단은 {@code DataIntegrityViolationException} 으로 올라와 <b>500</b> 이 된다 —
 * 운영자가 셀러 ID 를 잘못 친 것뿐인데 화면에는 서버 장애로 보인다(실제로 그렇게 보였다).
 *
 * <p>그래서 저장 전에 한 번 묻는다. FK 를 대체하는 것이 아니라 <b>메시지를 위한 확인</b>이다 —
 * 확인과 저장 사이에 셀러가 지워지는 경합은 여전히 FK 가 잡는다.
 */
public interface SellerExistsPort {

    boolean existsById(Long sellerId);
}
