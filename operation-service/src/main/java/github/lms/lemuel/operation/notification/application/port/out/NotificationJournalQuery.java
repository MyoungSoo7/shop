package github.lms.lemuel.operation.notification.application.port.out;

import github.lms.lemuel.operation.notification.application.DispatchRecord;

import java.util.List;
import java.util.Optional;

/**
 * 발송 저널 조회 포트 — 쓰기({@link NotificationJournal})와 나눈다.
 *
 * <p>나누는 이유는 의존 방향이다. 팬아웃 코어는 <b>쓰기만</b> 필요하고 조회는 필요 없다.
 * 한 포트에 몰아 두면 디스패처가 조회 메서드까지 의존하게 되고, 테스트 대역이 쓸데없이 커진다.
 */
public interface NotificationJournalQuery {

    /**
     * 최신순 목록. 필터는 전부 선택이며 {@code null} 이면 그 축을 걸지 않는다.
     *
     * @param status    상태 정확일치(DELIVERED / PARTIAL / FAILED / PENDING / NO_CHANNEL)
     * @param recipient 수신자 정확일치 — 부분일치를 쓰지 않는 이유는 인덱스를 타야 하고,
     *                  수신자는 이메일·채널ID 처럼 <b>알고 찾는 값</b>이지 검색어가 아니기 때문이다.
     */
    List<DispatchRecord> findRecent(String status, String recipient, int limit, int offset);

    /** 단건 상세 — 채널별 결과까지 채워 돌려준다. */
    Optional<DispatchRecord> findById(long id);

    /** 목록 화면의 페이지네이션용 총계. 필터 조건은 {@link #findRecent} 와 같다. */
    long count(String status, String recipient);
}
