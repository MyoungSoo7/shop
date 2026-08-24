package github.lms.lemuel.category.application.port.out;

import github.lms.lemuel.category.domain.EcommerceCategory;

/**
 * 카테고리 저장 Outbound Port
 */
public interface SaveEcommerceCategoryPort {

    EcommerceCategory save(EcommerceCategory category);

    /**
     * 트리 경로(path_ids/path_slug) 재계산. 구조가 바뀐 뒤(생성·이동·삭제) 호출한다 —
     * 부모 변경은 옮겨진 노드만이 아니라 그 아래 전부의 경로를 바꾸기 때문이다.
     *
     * @return 실제로 경로가 달라진 행 수
     */
    int recalculatePaths();

    /**
     * 상품수 캐시 재계산.
     *
     * @return 값이 달라진 행 수 — 0 이면 캐시와 정본이 일치한다(정합성 점검에 쓴다)
     */
    int refreshProductCounts();
}
