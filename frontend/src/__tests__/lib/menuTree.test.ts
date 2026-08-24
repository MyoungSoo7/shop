import { describe, it, expect } from 'vitest';
import { findActiveTrail, findActiveRoot, matchesPath, collectPaths } from '@/lib/menuTree';
import { resolveFallbackMenus } from '@/data/menuFallback';

const adminMenus = resolveFallbackMenus('ADMIN');

describe('matchesPath', () => {
  it('정확히 같으면 일치', () => {
    expect(matchesPath('/admin', '/admin')).toBe(true);
  });

  it('세그먼트 경계에서만 접두 일치 — /shipping 이 /shippings 를 삼키지 않는다', () => {
    expect(matchesPath('/admin/shipping', '/admin/shipping/3')).toBe(true);
    expect(matchesPath('/admin/shipping', '/admin/shippings')).toBe(false);
  });
});

describe('findActiveTrail — 가장 긴 접두사가 이긴다', () => {
  it('/admin 에서는 대시보드만 활성 (배송·시스템이 함께 켜지지 않는다)', () => {
    const trail = findActiveTrail(adminMenus, '/admin');
    expect(trail.map((n) => n.name)).toEqual(['대시보드']);
  });

  it('/product 는 최상위 항목이라 묶음이 없다', () => {
    const trail = findActiveTrail(adminMenus, '/product');
    expect(trail.map((n) => n.name)).toEqual(['상품관리']);
    expect(findActiveRoot(adminMenus, '/product')?.name).toBe('상품관리');
  });

  // 배송비 정책은 형제('배송 관리' = /admin/shipping)의 경로를 접두사로 포함한다.
  // 가장 긴 접두사 하나만 고르는 규칙이라야 배송비 정책이 켜진다 — 짧은 쪽을 고르면
  // 정책 화면에서 사이드바가 '배송 관리'를 가리킨다.
  it('/admin/shipping/policies 는 형제가 아니라 자기 자신이 활성이다', () => {
    const trail = findActiveTrail(adminMenus, '/admin/shipping/policies');
    expect(trail.map((n) => n.name)).toEqual(['배송', '배송비 정책']);
  });

  it('/admin/system/codes 는 시스템 > 공통코드 관리', () => {
    const trail = findActiveTrail(adminMenus, '/admin/system/codes');
    expect(trail.map((n) => n.name)).toEqual(['시스템 관리', '공통코드 관리']);
  });

  it('묶음과 대표 자식의 경로가 같으면 더 깊은 자식이 활성이 된다', () => {
    // '배송' 묶음과 '배송 관리' 항목이 둘 다 /admin/shipping 이다.
    const trail = findActiveTrail(adminMenus, '/admin/shipping');
    expect(trail.map((n) => n.name)).toEqual(['배송', '배송 관리']);
  });

  it('어느 메뉴에도 걸리지 않으면 빈 배열', () => {
    expect(findActiveTrail(adminMenus, '/mypage')).toEqual([]);
    expect(findActiveRoot(adminMenus, '/mypage')).toBeNull();
  });
});

describe('collectPaths', () => {
  it('트리의 모든 링크 경로를 모은다', () => {
    const paths = collectPaths(adminMenus);
    expect(paths).toContain('/admin');
    expect(paths).toContain('/product');
    expect(paths).toContain('/admin/system/operation');
    expect(paths).toContain('/admin/system/refunds');
    expect(new Set(paths).size).toBeGreaterThan(0);
  });
});
