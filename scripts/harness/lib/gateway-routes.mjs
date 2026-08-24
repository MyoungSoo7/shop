/**
 * 게이트웨이 라우트 술어 추출기 + 스프링 {@code PathPattern} 근사 매처 — 게이트 두 벌의 단일 정의.
 *
 * <p>{@code java-controllers.mjs} 와 같은 이유로 분리했다: `gateway-route-gate`("컨트롤러가 라우팅
 * 되는가")와 `spa-fallback-gate`("SPA 폴백이 그 라우트를 가리지 않는가")는 <b>같은 경로표를 반대
 * 방향에서</b> 읽는다. 한쪽만 고쳐지면 드리프트를 막으라고 만든 하네스 안에서 드리프트가 난다.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** 게이트웨이 {@code - Path=a,b,c} 술어에서 패턴을 뽑는다. */
export function gatewayPatterns(repoRoot) {
  const yml = readFileSync(
    join(repoRoot, 'gateway-service', 'src', 'main', 'resources', 'application.yml'), 'utf8');
  const patterns = [];
  for (const line of yml.matchAll(/^\s*-\s*Path=(.+)$/gm)) {
    for (const raw of line[1].split(',')) {
      const pattern = raw.trim();
      if (pattern.startsWith('/')) patterns.push(pattern);
    }
  }
  return patterns;
}

/**
 * 패턴에서 <b>와일드카드 앞의 리터럴 접두사</b>를 잘라낸다.
 *
 * <p>`/admin/payouts/**` → `/admin/payouts`. "이 패턴이 실제로 점유하는 URL 공간이 어디서
 * 시작하는가"를 묻는 용도다 — 폴백 정규식이 그 공간을 삼키는지 보려면 구체 경로가 필요하다.
 */
export function literalPrefix(pattern) {
  const cut = pattern.search(/[*{]/);
  return (cut < 0 ? pattern : pattern.slice(0, cut)).replace(/\/+$/, '') || '/';
}

/**
 * 스프링 {@code PathPattern} 근사 매칭.
 *
 * <p>`/**` 는 <b>0개 이상</b> 세그먼트다 — `/a/**` 는 `/a` 자신도 매칭한다(컬렉션 루트가 이 규칙에
 * 기대고 있다: {@code GET /admin/commission-rates} 가 {@code /admin/commission-rates/**} 로 열린다).
 * {@code {var}} 와 단일 `*` 는 한 세그먼트다. 정확한 구현을 옮기는 게 아니라 <b>보수적으로</b>
 * 판정하는 것이 목적이다 — 애매하면 "안 닿는다"고 보고 사람이 확인하게 한다.
 */
export function matchesPattern(pattern, path) {
  // 꼬리 `/**` 를 먼저 자리표로 빼 둔다. 정규식으로 먼저 바꿔 버리면 그 안의 `*` 가 뒤이은
  // 단일 `*` 치환에 다시 걸려 `(/.[^/]*)?` 같은 것이 된다 — 실제로 그렇게 틀렸고,
  // gateway-route-gate 의 [자기검증] 테스트가 잡았다.
  // 자리표는 반드시 ASCII 여야 한다. 처음엔 NUL 을 썼는데, 소스에 NUL 이 들어가자 git 이
  // 이 파일을 바이너리로 취급해 diff 가 사라졌다 — 리뷰가 불가능해진다.
  const TAIL = '@@TAIL@@';
  const source = '^' + pattern
    .replace(/\/\*\*$/, TAIL)
    .replace(/[.+?^$()|[\]\\]/g, '\\$&')
    .replace(/\{[^}]*\}/g, '[^/]+')
    .replace(/\*/g, '[^/]*')
    .replace(TAIL, '(/.*)?') + '$';
  return new RegExp(source).test(path);
}
