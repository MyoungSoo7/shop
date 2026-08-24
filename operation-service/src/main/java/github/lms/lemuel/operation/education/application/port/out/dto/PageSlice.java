package github.lms.lemuel.operation.education.application.port.out.dto;

import java.util.List;

/**
 * 페이지 결과 — {@code org.springframework.data.domain.Page} 의 애플리케이션 경계 대체 타입.
 *
 * <p>웹 어댑터가 이 값을 Spring {@code Page} 로 감싸 응답하므로 프론트가 보는 JSON
 * ({@code content/totalElements/totalPages/number/size})은 그대로다.
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
