package github.lms.lemuel.marketing.application.port.dto;

import java.time.LocalDate;

/**
 * 달력 한 칸.
 *
 * <p>레거시는 달력 HTML 을 JSP 스크립틀릿이 직접 그렸다 — 윤년·주 시작 요일·이번 달 밖 날짜를
 * 화면 코드가 계산했다는 뜻이다. 여기서는 서버가 "이 날이 인정일인가 / 출석했는가" 만 주고
 * 배치는 React 가 한다.
 */
public record AttendanceDayView(LocalDate date, boolean eligible, boolean attended) {
}
