package github.lms.lemuel.operation.education.application.port.dto;

/**
 * 페이지 요청 — {@code org.springframework.data.domain.Pageable} 을 애플리케이션 경계 밖에 두기 위한 대체 타입.
 *
 * <p>웹 어댑터가 {@code Pageable} 을 받아 이 타입으로 옮기고, 영속 어댑터가 다시 {@code Pageable} 로 옮긴다.
 * 페이지네이션은 두 어댑터의 관심사이고, 애플리케이션은 "몇 번째 페이지를 몇 개" 만 안다.
 */
public record PageSpec(int page, int size) {
    public PageSpec {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (size < 1) throw new IllegalArgumentException("size must be positive");
    }
}
