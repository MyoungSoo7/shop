package github.lms.lemuel.bulkorder.application.port.out;

import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;

import java.util.List;

/**
 * 업로드 양식(열 정의) 조회 포트.
 *
 * <p>양식이 DB 에 있는 이유는 그것이 <b>운영 데이터</b>이기 때문이다 — 고객사·시즌마다 열이 늘고
 * 필수 여부가 바뀐다. 코드에 박으면 그 요구가 올 때마다 배포가 따라온다.
 */
public interface BulkOrderColumnSpecPort {

    /** 열 위치 오름차순. 순서가 곧 CSV 열 순서다. */
    List<BulkOrderColumnSpec> findAllOrdered();
}
