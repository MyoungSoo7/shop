import type { NavMenuNode } from '@/api/menu';

/**
 * 메뉴 트리에서 "지금 어디에 있는가"를 계산하는 순수 함수들.
 *
 * <p>레이아웃 컴포넌트에서 분리한 이유는 이 규칙이 화면 정확성의 핵심이라 단위 테스트로
 * 고정해야 하기 때문이다. 하드코딩 시절에는 각 셸이 저마다 접두 매칭을 했고, 그래서
 * `/admin` (대시보드)이 `/admin/settlement` 에서도 활성으로 칠해지는 문제를 피하려고
 * 예외 규칙을 손으로 넣어 뒀다(그 결과 `/admin/payouts` 에서 상단 '정산'이 꺼지는 버그가 남았다).
 */

/** 경로 일치 — 세그먼트 경계를 지킨다. `/admin/ceo/loans` 는 `/admin/ceo/loan` 에 걸리지 않는다. */
export const matchesPath = (menuPath: string, pathname: string): boolean =>
  menuPath === pathname || pathname.startsWith(menuPath + '/');

interface Candidate {
  trail: NavMenuNode[];
  length: number;
  depth: number;
}

/**
 * 현재 경로에 해당하는 노드까지의 경로(루트 → … → 노드)를 돌려준다. 없으면 빈 배열.
 *
 * <p>규칙은 하나다: <b>현재 경로의 접두사인 메뉴 경로 중 가장 긴 것</b>이 이긴다.
 * 길이가 같으면 더 깊은 노드가 이긴다(묶음과 대표 자식이 같은 경로를 쓰는 경우 —
 * 예: '정산'(묶음)과 '정산관리'(자식)가 모두 `/admin/settlement`).
 */
export const findActiveTrail = (menus: NavMenuNode[], pathname: string): NavMenuNode[] => {
  let best: Candidate | null = null;

  const walk = (node: NavMenuNode, trail: NavMenuNode[]) => {
    const nextTrail = [...trail, node];
    if (node.path && matchesPath(node.path, pathname)) {
      const candidate: Candidate = {
        trail: nextTrail,
        length: node.path.length,
        depth: nextTrail.length,
      };
      if (
        best === null ||
        candidate.length > best.length ||
        (candidate.length === best.length && candidate.depth > best.depth)
      ) {
        best = candidate;
      }
    }
    node.children.forEach((child) => walk(child, nextTrail));
  };

  menus.forEach((menu) => walk(menu, []));
  return best === null ? [] : (best as Candidate).trail;
};

/** 이 노드가 활성 경로 위에 있는가 (자기 자신이거나 활성 노드의 조상). */
export const isOnActiveTrail = (node: NavMenuNode, trail: NavMenuNode[]): boolean =>
  trail.some((n) => n.id === node.id);

/** 현재 경로가 속한 최상위 노드 — 사이드바를 어느 묶음으로 그릴지 결정한다. */
export const findActiveRoot = (menus: NavMenuNode[], pathname: string): NavMenuNode | null =>
  findActiveTrail(menus, pathname)[0] ?? null;

/** 트리의 모든 링크 경로를 평면으로 — 라우트 정합 검사·테스트용. */
export const collectPaths = (menus: NavMenuNode[]): string[] => {
  const paths: string[] = [];
  const walk = (node: NavMenuNode) => {
    if (node.path) paths.push(node.path);
    node.children.forEach(walk);
  };
  menus.forEach(walk);
  return paths;
};
