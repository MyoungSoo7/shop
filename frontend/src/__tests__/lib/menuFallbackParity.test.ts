import { describe, it, expect } from 'vitest';
import { resolveFallbackMenus } from '@/data/menuFallback';

/**
 * 네비게이션 회귀 고정 — 트리 기반 네비게이션이 <b>메뉴 시드와 같은 것</b>을 그리는지.
 *
 * <p>정본은 order-service 의 메뉴 시드 마이그레이션이고 이 폴백은 그 사본이다. 둘의 **경로**
 * 정합은 `menu-route-gate.test.mjs` 가 CI 에서 대조하고, 이 파일은 그보다 한 겹 위 —
 * **역할별로 무엇이 보이는가**를 고정한다. 서버 필터링(역할+권한)과 폴백 필터링(역할만)이
 * 같은 결과를 내는 것이 전제이므로, 여기가 깨지면 그 전제가 흔들린 것이다.
 */

const labelsOf = (role: string) => resolveFallbackMenus(role).map((m) => m.label);
const childrenOf = (role: string, groupName: string) =>
  resolveFallbackMenus(role).find((m) => m.name === groupName)?.children.map((c) => c.name) ?? [];

describe('상단 네비 — 역할별 항목·순서 고정', () => {
  // '반품·교환'은 '승인' 바로 뒤다 — '승인' 의 자식이 아니라 형제. '승인' 은 ITEM 이라
  // 자식을 붙이려면 GROUP 으로 바꿔야 하고, 그러면 지금 그 링크로 들어가는 취소·환불 승인
  // 큐가 링크가 아니게 된다. 시드(V20260827160000)도 같은 자리에 끼우도록 뒤를 한 칸 민다.
  it('ADMIN: 대시보드·상품관리·배송·승인·반품교환·시스템', () => {
    expect(labelsOf('ADMIN')).toEqual([
      '대시보드', '상품관리', '배송', '승인', '반품·교환', '시스템',
    ]);
  });

  it('MANAGER: ADMIN 목록에서 시스템만 빠진다', () => {
    // 반품 응대는 CS 업무라 MANAGER 에게도 열린다 — 서버의 /admin/return-requests/**
    // 매처가 ADMIN,MANAGER 이므로 죽은 링크가 아니다.
    expect(labelsOf('MANAGER')).toEqual([
      '대시보드', '상품관리', '배송', '승인', '반품·교환',
    ]);
  });

  it('USER: 구매자 화면 6개', () => {
    // 잔액 화면은 결제 직전에 "얼마까지 낼 수 있나"를 확인하러 오는 경로다.
    // 대량주문은 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다 — SHOP 최상위.
    // 나눠 결제는 주문(20)과 잔액 확인(30) 사이 — 주문에서 결제로 이어지는 순서다.
    // 내 알림(35)은 잔액(30) 다음 — 둘 다 "내 것"을 보는 개인 화면이라 붙여 둔다.
    expect(labelsOf('USER')).toEqual([
      '주문하기', '추천받기', '대량주문', '나눠 결제', '내 포인트·상품권', '내 알림',
    ]);
  });

  it('미인증: 아무 것도 없다', () => {
    expect(labelsOf('')).toEqual([]);
    expect(resolveFallbackMenus(null)).toEqual([]);
  });
});

describe('사이드바 — 그룹별 항목', () => {
  it('배송 (ADMIN): 배송 관리·배송비 정책', () => {
    expect(childrenOf('ADMIN', '배송')).toEqual(['배송 관리', '배송비 정책']);
  });

  it('배송 (MANAGER): 배송비 정책은 빠진다 — 서버가 ADMIN 으로 막는 금액 결정 경로', () => {
    // 고객이 실제로 지불하는 금액을 바꾸는 표면이다. MANAGER 에게 열면 눌러도 되돌려보내지는
    // 죽은 링크가 된다 — 서버가 막는 것을 화면이 먼저 감춘다.
    expect(childrenOf('MANAGER', '배송')).toEqual(['배송 관리']);
  });

  it('시스템 (ADMIN): 26개 항목', () => {
    // 카탈로그 3종이 붙어 있다 — 무엇으로 묶이나(분류) → 언제 앞에 세우나(진열) → 무엇으로 고르나(옵션)
    // 그 다음이 관제(운영관리), 그리고 콘텐츠 관리 2종(게시판·교육).
    expect(childrenOf('ADMIN', '시스템 관리')).toEqual([
      '메뉴 관리', '공통코드 관리', 'RBAC 관리', '이커머스 카테고리', '진열 편성', '옵션 카탈로그', '운영관리',
      '게시판 관리', '교육 관리',
      // 내부잔액 원장 2종의 운영 콘솔 — 수기 지급·발행은 없던 재산을 만들고 소멸은 지운다.
      '포인트 운영', '기프트카드 운영',
      // 운영 콘솔 4종 — 감사 로그는 적재만 하고 아무도 못 보던 표면, 회원 관리는 승인 API 는 있는데
      // 대상을 못 찾던 표면, 리뷰·쿠폰은 내리고 멈추는 방법이 DB 직접 수정뿐이던 표면.
      // 조직·멤버십은 회원 관리 바로 뒤 — 개인(회원)을 본 다음 그 사람이 속한 조직으로 이어진다.
      '감사 로그', '회원 관리', '조직 · 멤버십', '리뷰 관리', '쿠폰 운영',
      // 마지막 둘은 사라진 '정산운영' 그룹에서 옮겨 왔다 — 부르는 API 가 order-service 것이라
      // 이 저장소에서도 살아 있다.
      '환불 운영', '셀러 등급',
      // dentis 관리자 콘솔에서 옮겨 온 4종. 앞의 셋은 "보기만 하는" 표면이고, 작업 큐만
      // MANAGER 에게도 열린다 — 밀린 주문을 실제로 처리하는 쪽이라서다.
      '권한 계정', '지표 추이', '판매 통계', '작업 큐',
      // 수강 신청은 '교육 관리'(과정·차시) 와 짝이지만 자리는 맨 뒤다 — 그룹의 sort_order 가
      // 빈틈없이 차 있어 중간에 끼우면 그 자리의 항목과 겹친다.
      '수강 신청', '강사 관리',
      // 팝업 관리는 교육이 아니라 사이트 콘텐츠지만 자리는 역시 맨 뒤다 — 이유는 같다.
      '팝업 관리',
      // 댓글 관리는 '게시판 관리'(게시판 정의) 와 다른 표면이다 — 이미 달린 댓글을 게시판·글을
      // 건너뛰고 훑는 유일한 자리. 자리는 역시 맨 뒤.
      '댓글 관리',
    ]);
  });

  it('시스템 그룹은 ADMIN 전용이라 MANAGER 에게는 통째로 없다', () => {
    expect(childrenOf('MANAGER', '시스템 관리')).toEqual([]);
  });
});

describe('사이드바 머리글', () => {
  it('시스템은 상단 네비 라벨과 사이드바 제목이 다르다 (시스템 / 시스템 관리)', () => {
    const system = resolveFallbackMenus('ADMIN').find((m) => m.name === '시스템 관리');

    expect(system?.label).toBe('시스템');
    expect(system?.name).toBe('시스템 관리');
    expect(system?.description).toBe('System Administration');
  });
});
