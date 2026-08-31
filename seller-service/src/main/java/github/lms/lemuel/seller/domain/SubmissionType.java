package github.lms.lemuel.seller.domain;

/**
 * 신규 등록인가 기존 상품 수정인가 — 레퍼런스의 {@code SELLPRODUCTUPDATE} 를 옮긴 것.
 *
 * <p>매핑: {@code 'C'} 신규대기 → {@link #NEW}, {@code 'Y'} 수정대기 → {@link #UPDATE}.
 *
 * <p>둘을 한 테이블의 플래그로 둔 것은 레퍼런스를 따른 결정이다. 분리하면 심사 화면이 두 벌이
 * 되고 "대기 건수" 를 세는 곳마다 두 번 세게 된다 — 그 둘이 어긋나는 순간 어느 쪽이 맞는지
 * 아무도 모른다.
 */
public enum SubmissionType {

    /** 카탈로그에 없는 새 상품. 승인되면 order-service 가 상품을 생성한다. */
    NEW,

    /** 이미 팔고 있는 상품의 수정. 승인되면 order-service 가 그 상품을 갱신한다. */
    UPDATE
}
