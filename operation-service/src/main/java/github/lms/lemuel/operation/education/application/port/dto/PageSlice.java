package github.lms.lemuel.operation.education.application.port.dto;

import java.util.List;

/**
 * 페이지 결과 — {@code org.springframework.data.domain.Page} 의 애플리케이션 경계 대체 타입.
 *
 * <p>웹 어댑터가 이 값을 Spring {@code Page} 로 감싸 응답하므로 프론트가 보는 JSON
 * ({@code content/totalElements/totalPages/number/size})은 그대로다.
 *
 * <p><b>{@code port.out.dto} 가 아니라 {@code port.dto} 에 있는 이유</b> — 2026-08-27 에 옮겼다.
 * 이 타입은 저장소가 돌려주는 값이자 유스케이스가 돌려주는 값이다. 조회 포트를 세우고 나니
 * {@code QueryCourseUseCase} 가 {@code port.out} 을 임포트하는 모양이 됐고, 그건 "인바운드 계약이
 * 아웃바운드 계약에 딸려 있다"고 읽힌다 — 저장소를 바꾸면 컨트롤러 시그니처가 따라 흔들린다는 뜻이다.
 * 실제로 흔들리지는 않지만 그렇게 읽히는 것 자체가 나중에 잘못된 결정을 부른다. 양쪽이 함께 쓰는
 * 타입은 어느 한쪽 밑이 아니라 둘의 공통 자리에 둔다.
 */
public record PageSlice<T>(List<T> content, int page, int size, long totalElements) {
    public PageSlice {
        content = List.copyOf(content);
    }

    public static <T> PageSlice<T> empty(PageSpec spec) {
        return new PageSlice<>(List.of(), spec.page(), spec.size(), 0L);
    }

    /** 올림 나눗셈 — 부동소수 캐스팅 없이 정확하게 센다. */
    public int totalPages() {
        return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
    }
}
