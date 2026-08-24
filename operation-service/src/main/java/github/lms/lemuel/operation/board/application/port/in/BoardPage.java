package github.lms.lemuel.operation.board.application.port.in;

import java.util.List;

/**
 * 페이지 결과 — 스프링 데이터 {@code Page} 를 포트 시그니처에 노출하지 않기 위한 최소 표현.
 *
 * <p>포트가 프레임워크 타입을 들면 영속 기술을 바꿀 때 응용 계층 인터페이스가 따라 바뀐다.
 * 필요한 것은 네 개(내용·현재 쪽·전체 건수·전체 쪽)뿐이다.
 */
public record BoardPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> BoardPage<T> of(List<T> content, int page, int size, long totalElements) {
        // 올림 나눗셈을 정수로 한다 — 부동소수 캐스팅은 큰 건수에서 경계가 흔들리고,
        // 이 저장소의 실시간 가드가 double 사용을 금액 오용으로 읽는다.
        int totalPages = size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new BoardPage<>(content, page, size, totalElements, totalPages);
    }
}
