package github.lms.lemuel.expirynotice.application.port.out;

import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpirySubject;

import java.time.OffsetDateTime;
import java.util.List;

/** 만료 임박 대상 조회 — 읽기 전용이다. 통보 배치는 금전 애그리것을 절대 수정하지 않는다. */
public interface LoadExpiringItemsPort {

    /**
     * {@code [floor, ceiling)} 창 안에 만료되는, 아직 살아 있고 잔액이 남은 대상을 돌려준다.
     *
     * <p>경계는 <b>하한 포함 · 상한 배제</b>다. 단계별 창을 이어 붙였을 때 겹치지도 비지도 않아야
     * 한 대상이 하루에 두 통을 받거나 경계에 걸려 한 통도 못 받는 일이 없다.
     *
     * <p>이미 통보한 건을 걸러내는 것은 이 포트가 아니라 원장의 UNIQUE 다 — 조회에서 조인으로
     * 거르면 "필터를 빠뜨렸다" 는 실수가 조용히 중복 발송이 된다.
     */
    List<ExpiringItem> findExpiringBetween(ExpirySubject subject,
                                           OffsetDateTime floorInclusive,
                                           OffsetDateTime ceilingExclusive,
                                           int limit);
}
