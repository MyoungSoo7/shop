package github.lms.lemuel.operation.dashboard.application.port.in;

import github.lms.lemuel.operation.dashboard.domain.TodayOverview;

import java.time.LocalDate;

/** "오늘 한눈에" 한 화면치를 조립한다. */
public interface ViewTodayOverviewUseCase {

    /** 오늘(설정된 타임존 기준). */
    TodayOverview today();

    /** 특정 날짜 — 어제와 비교하거나 사고 당일을 되짚을 때. */
    TodayOverview onDate(LocalDate date);
}
