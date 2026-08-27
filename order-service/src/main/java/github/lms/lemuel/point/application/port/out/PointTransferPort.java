package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointTransfer;

import java.util.List;
import java.util.Optional;

/**
 * 회원 간 포인트 선물 기록 포트.
 *
 * <p>{@link #findBySenderAndRequestId} 가 멱등 판정이다. 선물 행과 양쪽 원장 기입이 한 트랜잭션이라
 * <b>행이 있다는 것이 곧 이미 처리됐다는 뜻</b>이다 — 원장을 따로 뒤져 볼 필요가 없다. 보내는 이를
 * 함께 묻는 이유는 멱등 키가 화면이 만든 값이기 때문이다. 키만으로 찾으면 남이 우연히(혹은 일부러)
 * 같은 값을 만들었을 때 남의 선물 결과를 돌려받는다.
 */
public interface PointTransferPort {

    PointTransfer save(PointTransfer transfer);

    /**
     * 다음 선물 번호를 DB 시퀀스에서 뽑는다.
     *
     * <p>레거시는 {@code SELECT NVL(MAX(IDX),0)+1} 로 번호를 잡았다 — 동시에 들어온 두 건이 같은
     * 값을 읽어, 뒤에 커밋되는 쪽이 유니크 제약에 부딪히거나(있는 경우) 같은 번호 두 건이
     * 태연히 남았다(없는 경우). 채번은 읽고-더하고-쓰는 일이라 애플리케이션에서 안전하게 할 수
     * 없다. 시퀀스는 그 일을 트랜잭션 밖에서 원자적으로 해 준다.
     */
    String nextTransferNo();

    /** 멱등 판정 — 같은 보내는 이가 같은 요청 식별자로 이미 보냈는가. */
    Optional<PointTransfer> findBySenderAndRequestId(Long senderUserId, String requestId);

    /** 보낸 것과 받은 것을 합쳐 최신순으로. */
    List<PointTransfer> findByParticipant(Long userId, int limit);
}
