package github.lms.lemuel.operation.notification.application.port.out;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.domain.Notification;

import java.util.List;
import java.util.Optional;

/**
 * 발송 저널 포트 — <b>내구 멱등과 발송 이력을 같은 쓰기로</b> 처리한다.
 *
 * <p>{@link DedupeStore} 와 역할이 겹치는 것이 아니라 계층이 다르다. dedupe 는 프로세스 안의
 * 값싼 1차 필터(L1)이고, 이 포트는 <b>인스턴스 밖에서 공유되는</b> 판정(L2)이다. 레플리카가 둘이면
 * L1 은 각자 자기 것만 알아서 같은 알림을 두 번 통과시키지만, L2 는 저장소의 UNIQUE 제약이라
 * 한 번만 통과한다. settlement 의 계층형 멱등과 같은 구조다.
 *
 * <p>그래서 {@link #begin}이 돌려주는 것은 "기록됐다"가 아니라 <b>"진행해도 되는가"</b>다 —
 * 빈 Optional 은 저장 실패가 아니라 <b>이미 처리된 이벤트</b>라는 뜻이다.
 */
public interface NotificationJournal {

    /**
     * 발송을 시작하며 저널 항목을 연다.
     *
     * @return 새 항목의 id. 같은 {@code eventId} 가 이미 있으면 {@link Optional#empty()} — 중복이므로 발송하지 않는다.
     *         {@code eventId} 가 {@code null} 인 알림(수기 발송 등)은 멱등 대상이 아니므로 항상 새 항목이 열린다.
     */
    Optional<Long> begin(Notification notification);

    /**
     * 팬아웃 결과를 그 항목에 확정한다. 채널별 행을 남기고 부모의 상태를 계산해 닫는다.
     *
     * <p>같은 항목에 두 번 불려도 결과가 같아야 한다(채널 행에 UNIQUE(dispatch_id, channel) 가 있다).
     */
    void complete(long journalId, List<ChannelResult> results);

    /**
     * 재발송으로 생긴 항목에 원본 계보를 붙인다.
     *
     * <p>발송 <b>뒤에</b> 부르는 이유는 순서 때문이다 — 행은 {@link #begin} 이 만들므로 그 전에는
     * 붙일 대상이 없고, 디스패처는 "이게 재발송인지" 를 알 필요가 없다(알면 팬아웃 코어가 운영
     * 기능을 알게 된다). 이미 계보가 있는 행은 건드리지 않는다.
     *
     * @param eventId    새로 발송한 알림의 멱등 키
     * @param originalId 이 발송이 파생된 원본 저널 id
     */
    void linkResend(String eventId, long originalId);

    /**
     * 저널이 없는 조립(단위 테스트·저장소 없는 실행)을 위한 무동작 구현.
     *
     * <p>항상 진행을 허용한다 — 저널이 없다는 것은 "중복인지 모른다"는 뜻이고, 그때의 안전한 기본값은
     * 발송을 막는 쪽이 아니라 보내는 쪽이다(알림은 늦거나 중복되는 것보다 <b>안 가는</b> 것이 나쁘다).
     */
    NotificationJournal NOOP = new NotificationJournal() {
        @Override
        public Optional<Long> begin(Notification notification) {
            return Optional.of(0L);
        }

        @Override
        public void complete(long journalId, List<ChannelResult> results) {
            // 기록할 곳이 없다.
        }

        @Override
        public void linkResend(String eventId, long originalId) {
            // 기록할 곳이 없다.
        }
    };
}
