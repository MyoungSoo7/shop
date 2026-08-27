package github.lms.lemuel.point.application.port.out;

import java.util.Optional;

/**
 * 선물 받는 이 조회 포트 — <b>point 슬라이스가 자기 말로 정의한</b> 아웃바운드 포트다.
 *
 * <p>구현({@code point.adapter.out.user.TransferRecipientAdapter})이 user 슬라이스의 로드 포트에
 * 위임한다. point 가 user 의 포트를 직접 부르면 슬라이스 간 의존이 생기고, 언젠가 user 가
 * point 를 부르는 날 ArchUnit 의 순환 금지 규칙에 걸린다. 어댑터를 내 슬라이스 안에 두면
 * 의존 방향이 항상 point → user 한 방향으로 고정된다.
 *
 * <p>돌려주는 것은 {@link Recipient} 뿐이다 — user 도메인 객체를 그대로 흘리면 point 의 서비스가
 * 남의 도메인(비밀번호 해시·권한·회원 상태)을 손에 쥐게 된다.
 */
public interface LoadTransferRecipientPort {

    /**
     * 이메일과 이름이 <b>둘 다</b> 맞는 회원. 하나라도 어긋나면 빈 값이다 — 어느 쪽이 틀렸는지
     * 알려 주지 않는 것이 요점이라, 실패 사유를 나누어 돌려주지 않는다.
     */
    Optional<Recipient> findActiveRecipient(String email, String name);

    /** 이력 화면이 상대방 이름을 채울 때 쓴다. 탈퇴 등으로 없으면 빈 값. */
    Optional<String> findNameById(Long userId);

    /**
     * @param name 표시용 이름. 이메일은 담지 않는다 — 응답으로 나가는 값은 서비스가 마스킹한 것만이다
     */
    record Recipient(Long userId, String name) {
    }
}
