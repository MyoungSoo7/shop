package github.lms.lemuel.marketing.adapter.in.web;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 프로모션의 "오늘".
 *
 * <p>서버는 UTC 로 뜨고 사용자는 KST 를 산다. {@code LocalDate.now()} 를 그대로 쓰면 한국 시간
 * 자정부터 오전 9시까지 출석이 <b>어제 날짜로</b> 찍힌다 — 그 시간대에 출석한 사람은 다음 날
 * 다시 눌러도 "이미 출석했습니다" 를 보고, 연속 출석은 하루씩 어긋난다. 날짜 경계는 화면이
 * 아니라 서버가 정해야 하므로 여기 한 곳에 둔다.
 */
final class MarketingClock {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private MarketingClock() {
    }

    static LocalDate today() {
        return LocalDate.now(KST);
    }
}
