// 민감 경로 인가 출처 게이트 — 리포 전수.
//
// 막는 것: **컨트롤러는 생겼는데 인가를 아무도 선언하지 않은 상태**.
// SecurityConfig 는 포괄 `/admin/**` 매처 없이 경로를 하나씩 열거한다. 그래서 새 관리 콘솔을 붙이며
// 매처를 빠뜨리면 그 경로는 막히는 게 아니라 `anyRequest().authenticated()` 로 떨어진다 —
// **로그인만 하면 누구나 호출**할 수 있고, 컴파일도 테스트도 초록이다.
//
// 그 사고는 이미 네 번 났고 전부 SecurityConfig 주석에 남아 있다:
//   · 쿠폰 생성(POST /coupons) — GET 만 매처가 있어 "닫혀 있다"고 보였다. 누구나 자기에게 100% 할인 발행.
//   · VAN 진입점(/van/**) — 게이트웨이 미라우팅이 유일한 방어였다. 사용자 토큰으로 카드 거래 위조.
//   · 포인트 콘솔(/admin/points/**) — 수기 지급은 없던 돈을 만들고 소멸 실행은 고객 재산을 지운다.
//   · 보험 언더라이팅 승인 — 청약 UUID 만 알면 아무 로그인 사용자나 계약을 발행시킬 수 있었다.
//
// 자매 게이트: `SecurityAuthorizationMatrixTest`(shared-common)는 **선언된 규칙이 실제로 그렇게
// 판정하는지**를 필터 체인에 요청을 흘려 확인한다. 이 게이트는 반대편 — **선언 자체가 있는지**를 본다.
// 전자는 지워짐·순서를, 후자는 애초에 안 쓴 것을 잡는다.
//
// 규칙 셋:
//   1) 민감 경로(/admin·/internal·/van)에 인가 출처가 하나도 없으면 FAIL.
//   2) 같은 경로에 역할 매처가 있는데 일부 메서드만 빠지면 FAIL — 쿠폰 사고 형태.
//   3) 결정/심사 동작(approve·reject·disburse·write-off …)에 인가 출처가 하나도 없으면 FAIL —
//      보험 언더라이팅 사고 형태. 이 셋으로 위 사고 4건이 전부 재현 검증된다.
//
// 인가 출처는 네 곳이며 하나라도 있으면 통과다(설계상 어느 쪽이든 유효하다):
//   ① 서비스가 실제로 로드하는 SecurityConfig 의 requestMatchers — 역할/permitAll 등 명시 결정
//   ② @PreAuthorize (클래스 또는 메서드 레벨 메서드시큐리티)
//   ③ AdminApiKeyFilter (위성 서비스의 X-Internal-Api-Key 게이트 — 사람이 아니라 기계가 부르는 경로)
//   ④ 핸들러 내부 프로그래매틱 판정 — requireAdmin(auth) 또는 JWT 주체 파생 후 소유권 대조(IDOR 가드)
//   그 외에 정말 인증만으로 충분하면 ALLOWED_UNMATCHED 에 사유와 함께 등록한다.
//
// ★ 검출기 자기검증: 아래 unit 케이스들은 전부 **이 게이트를 만들며 실제로 밟은 버그**다.
//   합성 케이스만으로는 네 건 모두 통과했고, 리포에 돌린 순간 드러났다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripJavaComments, matchingParen, antToRegExp } from '../lib/java-source.mjs';
import { JAVA_SERVICES, walk } from '../lib/java-controllers.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 인가 선언이 필요한 경로 — 사람이 아니라 시스템을 조작하는 표면. */
const SENSITIVE_PREFIX = /^\/(admin|internal|van)(\/|$)/;

/**
 * 인가 선언 없이 두기로 한 경로 — **사유 필수**.
 * 비어 있는 것이 정상이다. 추가할 때는 "왜 anyRequest().authenticated() 로 충분한가"를 적는다.
 */
const ALLOWED_UNMATCHED = new Map([
  // 예) ['order-service GET /internal/foo', '기계 호출 전용 + 공유키 필터가 앞단에서 막는다'],
]);

const VERB = { Get: 'GET', Post: 'POST', Put: 'PUT', Patch: 'PATCH', Delete: 'DELETE' };
const norm = (p) => p.replace(/\$?\{[^}]*\}/g, '*').replace(/\/+$/, '') || '/';

// ─────────────────────────────── 추출기 ───────────────────────────────

/** SecurityConfig 한 벌에서 (메서드, 패턴들, 결정) 목록을 선언 순서대로 뽑는다. */
export function parseMatchers(source) {
  const src = stripJavaComments(source);
  const out = [];
  let idx = 0;
  for (;;) {
    const at = src.indexOf('.requestMatchers(', idx);
    if (at < 0) break;
    const open = src.indexOf('(', at);
    const close = matchingParen(src, open);
    if (close < 0) break;
    const args = src.slice(open + 1, close);
    const tail = src.slice(close + 1, close + 200);
    const decision = tail.match(
      /^\s*\.(permitAll|denyAll|authenticated|fullyAuthenticated|hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|access)\s*\(/,
    );
    out.push({
      method: args.match(/HttpMethod\.(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)/)?.[1] ?? null,
      patterns: [...args.matchAll(/"([^"]+)"/g)].map((m) => m[1]),
      decision: decision ? decision[1] : 'unknown',
    });
    idx = close + 1;
  }
  return out;
}

/**
 * 서비스별로 **실제 적용되는** SecurityConfig 들을 고른다.
 *
 * <p>세 갈래다: ① 정확히 `SecurityConfig.java` 인 파일이 있으면 제한 스캔 서비스의 유일한
 * 필터체인(company·external-data). ② `*SecurityConfig.java` 접미 파일들이 있으면 루트 스캔
 * 서비스가 securityMatcher 로 좁힌 체인 여러 벌 + shared-common 전역 체인의 공존(ADR 0042·0043
 * 흡수 후 operation, ai 슬라이스의 settlement). ③ 아무것도 없으면 shared-common 전역 체인만.
 * 이 구분을 빼먹으면 board·education 슬라이스처럼 **자체 체인으로 멀쩡히 ADMIN 보호 중인
 * 엔드포인트 19개가 통째로 미보호로 뜬다**(단수 모델 시절 실제로 그렇게 떴다).
 */
export function securityConfigFor(root, service) {
  const chains = walk(join(root, service, 'src', 'main', 'java'))
    .filter((f) => basename(f).endsWith('SecurityConfig.java'));
  const exact = chains.find((f) => basename(f) === 'SecurityConfig.java');
  // 제한 스캔 서비스(company·external-data)의 정확 일치 파일은 그 서비스의 유일한 필터체인이다.
  if (exact) return { paths: [exact], scope: 'own' };
  const shared = join(root, 'shared-common/src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java');
  const hasShared = existsSync(shared);
  // 루트 스캔 서비스가 securityMatcher 로 좁힌 체인 여러 벌(Operation/Board/Education/Ai…)을 가질 수 있다
  // (ADR 0042·0043 흡수). 이때 shared-common 전역 체인도 함께 뜨므로 매처는 전부의 합집합으로 판정한다 —
  // 한 벌만 보면 다른 슬라이스의 ADMIN 매처가 통째로 미보호로 뜬다(실제로 흡수 직후 19건이 그렇게 떴다).
  if (chains.length > 0) {
    return { paths: hasShared ? [...chains, shared] : chains, scope: 'own+shared-common' };
  }
  return hasShared ? { paths: [shared], scope: 'shared-common' } : null;
}

/** 선택된 체인 전부의 매처 합집합. */
function matchersFor(config) {
  if (!config) return [];
  return config.paths.flatMap((p) => parseMatchers(readFileSync(p, 'utf8')));
}

/** 서비스가 AdminApiKeyFilter 를 자체 배선했는가 — /admin/** 을 역할이 아니라 공유키로 막는 설계. */
export function hasAdminApiKeyFilter(root, service) {
  return walk(join(root, service, 'src', 'main', 'java'))
    .some((f) => basename(f) === 'AdminApiKeyFilter.java');
}

/**
 * 컨트롤러 엔드포인트를 **HTTP 메서드까지** 뽑는다.
 *
 * <p>메서드 축이 필요한 이유는 쿠폰 사고다 — `GET /coupons` 에는 매처가 있고
 * `POST /coupons` 에는 없었다. 경로만 보는 검출기는 "덮여 있다"고 답한다.
 */
export function parseEndpoints(source, { service = '', className = '' } = {}) {
  const src = stripJavaComments(source);
  if (!src.includes('@RestController')) return [];
  const classBase = src.match(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"/)?.[1] ?? '';
  const classDeclAt = src.search(/\bclass\s+\w/);
  const classPreAuthorize = classDeclAt > 0 && src.slice(0, classDeclAt).includes('@PreAuthorize');

  const out = [];
  for (const m of src.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\b/g)) {
    const verb = VERB[m[1]];
    const after = src.slice(m.index + m[0].length);
    let sub = '';
    if (after.trimStart().startsWith('(')) {
      const open = m.index + m[0].length + (after.length - after.trimStart().length);
      const close = matchingParen(src, open);
      if (close > 0) sub = src.slice(open + 1, close).match(/(?:value\s*=\s*)?"([^"]*)"/)?.[1] ?? '';
    }
    const path = !sub ? classBase
      : (classBase && !sub.startsWith(classBase) ? classBase + sub : sub);
    if (!path) continue;
    // 이 핸들러 바로 앞 구간에 @PreAuthorize 가 있으면 메서드 레벨 보호
    const before = src.slice(Math.max(0, m.index - 400), m.index);
    out.push({
      service,
      className,
      method: verb,
      path: norm(path),
      preAuthorize: classPreAuthorize || before.includes('@PreAuthorize'),
      programmaticAuth: PROGRAMMATIC_AUTH.test(handlerSlice(src, m.index)),
    });
  }
  return out;
}

/**
 * 핸들러 하나의 시그니처+본문만 잘라낸다.
 *
 * <p>단순히 "매핑 애노테이션 뒤 첫 여는 중괄호"를 본문 시작으로 잡으면 `@PostMapping("/{id}/disburse")`
 * 의 `{id}` 를 본문으로 읽는다. 그렇게 자르면 실제 본문의 인가 검사를 못 보고, `requireAdmin` 으로
 * 멀쩡히 막혀 있는 대출 상각·실행 경로가 미보호로 뜬다(실측에서 20건이 그렇게 떴다).
 * 애노테이션 괄호 → 뒤따르는 애노테이션들 → 파라미터 괄호를 차례로 건너뛴 뒤의 중괄호가 본문이다.
 */
export function handlerSlice(src, mappingIndex) {
  let cursor = mappingIndex;
  const rel = src.slice(mappingIndex).search(/\(|\r?\n/);
  if (rel >= 0 && src[mappingIndex + rel] === '(') {
    const close = matchingParen(src, mappingIndex + rel);
    if (close > 0) cursor = close + 1;
  }
  for (;;) {
    const next = src.slice(cursor).search(/\S/);
    if (next < 0 || src[cursor + next] !== '@') break;
    const paren = src.indexOf('(', cursor + next);
    const nl = src.indexOf('\n', cursor + next);
    if (paren > 0 && (nl < 0 || paren < nl)) {
      const c = matchingParen(src, paren);
      cursor = c > 0 ? c + 1 : cursor + next + 1;
    } else cursor = nl < 0 ? src.length : nl + 1;
  }
  const paren = src.indexOf('(', cursor);
  if (paren > 0) {
    const c = matchingParen(src, paren);
    if (c > 0) cursor = c + 1;
  }
  const brace = src.indexOf('{', cursor);
  if (brace < 0) return src.slice(mappingIndex, cursor);
  let depth = 0;
  let i = brace;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) break; }
  }
  return src.slice(mappingIndex, Math.min(i + 1, src.length));
}

/**
 * 핸들러가 **스스로** 인가를 판정하는가(④번째 축).
 *
 * <p>이 코드베이스의 인가는 URL 매처만이 아니다 — `requireAdmin(auth)` 로 역할을 보거나,
 * 식별자를 JWT 주체에서 파생해 소유권을 대조한다(IDOR 가드레일). 선정산 대출 실행이 그 예로,
 * 매처는 없지만 `callerSellerId(authentication)` 와 대조해 남의 대출 실행을 막는다.
 *
 * <p><b>`Authentication` 파라미터의 단순 존재는 신호로 치지 않는다.</b> 받아만 놓고 안 보는 핸들러가
 * 게이트를 통과해 버리기 때문이다. 실측하면 그 약한 신호만으로 통과했을 상태변경 엔드포인트가
 * <b>12건</b> 있다(account 수신·operation 인시던트·ai 챗) — 지금은 다른 축에 걸리지 않아 위반이 아니지만,
 * 인정해 두면 나중에 그 12건이 결정 동작으로 바뀔 때 조용히 면제된다.
 */
export const PROGRAMMATIC_AUTH = new RegExp([
  '@AuthenticationPrincipal',
  '\\bAuthPrincipal\\b',              // 이 프로젝트의 JWT 주체 타입
  'SecurityContextHolder',
  '\\bcurrent[A-Z]\\w*\\s*\\(',        // currentDepositorId() · currentFcId()
  '\\bcaller[A-Z]\\w*\\s*\\(',         // callerSellerId()
  // requireAdmin·requireOperator·requireSelf·requirePrincipal … 이 저장소의 인가 헬퍼 관용구.
  // Objects.requireNonNull 은 인가가 아니므로 뺀다 — 안 빼면 널체크가 인가로 통과한다.
  '\\brequire(?!NonNull)[A-Z]\\w*\\s*\\(',
  'AccessDeniedException',
  'Identity\\.current',
].join('|'));

/** 매처가 이 엔드포인트를 덮는가 — 메서드 지정 매처는 그 메서드에만 걸린다. */
export function covers(matcher, endpoint) {
  if (matcher.method && matcher.method !== endpoint.method) return false;
  return matcher.patterns.some((p) => antToRegExp(p).test(endpoint.path));
}

/** 민감 경로인데 인가 출처가 하나도 없는 엔드포인트 목록. */
export function findUnprotected(root, services = JAVA_SERVICES) {
  const violations = [];
  for (const service of services) {
    const config = securityConfigFor(root, service);
    const matchers = matchersFor(config);
    const keyGated = hasAdminApiKeyFilter(root, service);

    for (const file of walk(join(root, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const raw = readFileSync(file, 'utf8');
      if (!raw.includes('@RestController')) continue;
      const className = basename(file, '.java');
      for (const ep of parseEndpoints(raw, { service, className })) {
        if (!SENSITIVE_PREFIX.test(ep.path)) continue;
        if (ep.preAuthorize) continue;                                     // ②
        if (keyGated && ep.path.startsWith('/admin/')) continue;           // ③
        if (ep.programmaticAuth) continue;                                 // ④
        const hit = matchers.find((m) => covers(m, ep));
        if (hit && hit.decision !== 'authenticated') continue;             // ①
        const key = `${service} ${ep.method} ${ep.path}`;
        if (ALLOWED_UNMATCHED.has(key)) continue;                          // ④
        violations.push({ ...ep, configScope: config?.scope ?? '(없음)' });
      }
    }
  }
  return violations;
}

/** 역할을 요구하는 결정들 — permitAll·authenticated 와 구분한다. */
const ROLE_DECISIONS = ['hasRole', 'hasAnyRole', 'hasAuthority', 'hasAnyAuthority', 'access'];

/**
 * 부분 메서드 커버 — **같은 경로에 역할 매처가 있는데 다른 메서드만 빠진** 상태.
 *
 * <p>쿠폰 사고의 형태다: `GET /coupons` 에는 `hasAnyRole(ADMIN, MANAGER)` 이 걸려 있고
 * `POST /coupons` 만 매처가 없었다. 경로만 보는 검사는 "덮여 있다"고 답하고, 화면에서도
 * 조회가 막혀 있어 <b>닫힌 것처럼 보인다</b>. 누군가 그 경로를 <b>이미 생각했는데</b> 메서드
 * 하나를 빠뜨린 것이라 오탐이 낮다.
 *
 * <p>제외 두 가지 — 둘 다 실측으로 얻었다:
 *   · `OPTIONS`/`HEAD` 형제: CORS 프리플라이트용 전역 매처(`"/**"`)라 세면 206건이 걸린다.
 *   · `permitAll` 형제: board 의 "GET 공개 + 쓰기는 인증"은 설계다(10건). 역할 형제만 센다.
 */
export function findPartialMethodCoverage(root, services = JAVA_SERVICES) {
  const out = [];
  for (const service of services) {
    const config = securityConfigFor(root, service);
    if (!config) continue;
    const matchers = matchersFor(config);
    const keyGated = hasAdminApiKeyFilter(root, service);

    for (const file of walk(join(root, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const raw = readFileSync(file, 'utf8');
      if (!raw.includes('@RestController')) continue;
      for (const ep of parseEndpoints(raw, { service, className: basename(file, '.java') })) {
        if (ep.preAuthorize || ep.programmaticAuth) continue;
        if (keyGated && ep.path.startsWith('/admin/')) continue;
        const hit = matchers.find((m) => covers(m, ep));
        if (hit && hit.decision !== 'authenticated') continue;
        const siblings = matchers.filter(
          (m) => m.method
            && !['OPTIONS', 'HEAD'].includes(m.method)
            && ROLE_DECISIONS.includes(m.decision)
            && m.method !== ep.method
            && m.patterns.some((p) => antToRegExp(p).test(ep.path)),
        );
        if (!siblings.length) continue;
        const key = `${service} ${ep.method} ${ep.path}`;
        if (ALLOWED_UNMATCHED.has(key)) continue;
        out.push({ ...ep, siblingMethods: [...new Set(siblings.map((s) => s.method))].join(',') });
      }
    }
  }
  return out;
}

/**
 * 결정/심사 동작 — **남의 개체 상태를 뒤집는** 백오피스 액션의 마지막 경로 세그먼트.
 *
 * <p>목록은 이 저장소의 실제 컨트롤러에서 뽑았다(리스·담보대출·멤버십·정산·보험). 여기 있는 동작은
 * "인증된 아무나"가 눌러도 되는 것이 아니다 — 승인은 계약을 발행하고, 실행은 자금을 내보내고,
 * 상각은 손실을 확정한다.
 *
 * <p>동작 목록으로 좁히는 이유는 노이즈다. "인가 출처가 없는 상태변경"만으로 세면 52건이 걸리는데
 * 그 대부분은 `POST /orders`·`POST /reviews` 처럼 <b>인증된 사용자면 맞는</b> 자기 행위다.
 * 결정 동작으로 좁히면 현재 0건이고, 보험 언더라이팅 사고는 그대로 걸린다.
 */
const DECISION_ACTIONS = new Set([
  'approve', 'reject', 'review',
  'disburse', 'write-off', 'settle', 'liquidate', 'subrogate', 'dispose',
  'suspend', 'reinstate', 'escalate',
  'default', 'mature', 'overdue', 'early-termination',
]);

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** 결정 동작인데 인가 출처가 하나도 없는 엔드포인트 — 보험 언더라이팅 사고 형태. */
export function findUnguardedDecisionActions(root, services = JAVA_SERVICES) {
  const out = [];
  for (const service of services) {
    const config = securityConfigFor(root, service);
    if (!config) continue;
    const matchers = matchersFor(config);
    const keyGated = hasAdminApiKeyFilter(root, service);

    for (const file of walk(join(root, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const raw = readFileSync(file, 'utf8');
      if (!raw.includes('@RestController')) continue;
      for (const ep of parseEndpoints(raw, { service, className: basename(file, '.java') })) {
        if (!MUTATING.has(ep.method)) continue;
        const segments = ep.path.split('/').filter(Boolean);
        if (!DECISION_ACTIONS.has(segments[segments.length - 1])) continue;
        if (ep.preAuthorize || ep.programmaticAuth) continue;
        if (keyGated && ep.path.startsWith('/admin/')) continue;
        const hit = matchers.find((m) => covers(m, ep));
        if (hit && hit.decision !== 'authenticated') continue;
        const key = `${service} ${ep.method} ${ep.path}`;
        if (ALLOWED_UNMATCHED.has(key)) continue;
        out.push(ep);
      }
    }
  }
  return out;
}

// ─────────────────────────────── 게이트 ───────────────────────────────

describe('민감 경로 인가 출처 게이트', () => {
  test('/admin·/internal·/van 엔드포인트에 인가 선언이 있다', () => {
    const violations = findUnprotected(repoRoot);
    const lines = violations.map(
      (v) => `  ${v.service} ${v.method} ${v.path}  [${v.className}] (config=${v.configScope})`,
    );
    assert.equal(
      violations.length,
      0,
      `인가 출처가 없는 민감 엔드포인트 ${violations.length}건 — anyRequest().authenticated() 로 떨어져\n`
        + '로그인한 아무나 호출할 수 있다. SecurityConfig 매처 · @PreAuthorize · AdminApiKeyFilter 중\n'
        + `하나를 붙이거나, 정말 인증만으로 충분하면 ALLOWED_UNMATCHED 에 사유와 함께 등록한다.\n${lines.join('\n')}`,
    );
  });

  test('민감 경로를 실제로 훑었다 — 0건 통과가 "못 찾아서"가 아님을 증명', () => {
    let sensitive = 0;
    for (const service of JAVA_SERVICES) {
      for (const file of walk(join(repoRoot, service, 'src', 'main', 'java'))) {
        if (!file.endsWith('.java')) continue;
        const raw = readFileSync(file, 'utf8');
        if (!raw.includes('@RestController')) continue;
        sensitive += parseEndpoints(raw, { service, className: basename(file, '.java') })
          .filter((ep) => SENSITIVE_PREFIX.test(ep.path)).length;
      }
    }
    assert.ok(sensitive >= 100, `민감 엔드포인트 수집이 비정상적으로 적다(${sensitive}건) — 추출기가 깨졌을 수 있다`);
  });

  test('역할 매처가 걸린 경로에 메서드 구멍이 없다 — 쿠폰 사고 형태', () => {
    const holes = findPartialMethodCoverage(repoRoot);
    const lines = holes.map(
      (h) => `  ${h.service} ${h.method} ${h.path}  [${h.className}] — 같은 경로에 ${h.siblingMethods} 만 역할 매처`,
    );
    assert.equal(
      holes.length,
      0,
      `같은 경로에 역할 매처가 있는데 일부 메서드만 빠진 곳 ${holes.length}건 —\n`
        + '조회는 막혀 있어 "닫혀 있다"고 보이지만 그 메서드는 로그인만 하면 통과한다.\n'
        + `쿠폰 생성이 정확히 이 형태였다.\n${lines.join('\n')}`,
    );
  });

  test('승인·실행·상각 같은 결정 동작에 인가 출처가 있다 — 보험 언더라이팅 사고 형태', () => {
    const holes = findUnguardedDecisionActions(repoRoot);
    const lines = holes.map((h) => `  ${h.service} ${h.method} ${h.path}  [${h.className}]`);
    assert.equal(
      holes.length,
      0,
      `결정 동작인데 인가 출처가 없는 엔드포인트 ${holes.length}건 —\n`
        + '승인은 계약을 발행하고 실행은 자금을 내보내며 상각은 손실을 확정한다.\n'
        + '"인증된 아무나"가 누를 수 있으면 안 되는 동작들이다.\n'
        + `보험 언더라이팅 승인이 정확히 이 형태였다(청약 UUID 만 알면 계약 발행).\n${lines.join('\n')}`,
    );
  });

  test('면제 항목에는 모두 사유가 있다', () => {
    for (const [key, reason] of ALLOWED_UNMATCHED) {
      assert.ok(reason && reason.trim().length >= 10, `${key} 면제에 사유가 없다`);
    }
  });
});

describe('검출기 자기검증 — 실제로 밟은 버그들', () => {
  test('문자열 안의 /*/ 를 주석으로 먹지 않는다', () => {
    // 순진한 정규식 스트리퍼는 "/payments/*/refund" 를 "/paymentsrefund" 로 만들고
    // 79개 매처 중 6개만 파싱했다(오류 없이). 이 케이스가 그 회귀를 잠근다.
    const src = `
      http.authorizeHttpRequests(a -> a
        .requestMatchers(HttpMethod.PATCH, "/payments/*/refund").hasRole("ADMIN")
        .requestMatchers("/admin/points/**").hasRole("ADMIN"));`;
    const ms = parseMatchers(src);
    assert.equal(ms.length, 2);
    assert.deepEqual(ms[0].patterns, ['/payments/*/refund']);
    assert.equal(ms[0].method, 'PATCH');
  });

  test('메서드 지정 매처는 다른 메서드를 덮지 않는다 — 쿠폰 사고', () => {
    const ms = parseMatchers('.requestMatchers(HttpMethod.GET, "/coupons", "/coupons/**").hasAnyRole("ADMIN")');
    assert.equal(covers(ms[0], { method: 'GET', path: '/coupons' }), true);
    assert.equal(covers(ms[0], { method: 'POST', path: '/coupons' }), false,
      'GET 매처가 POST 를 덮으면 쿠폰 사고를 다시 놓친다');
  });

  test('/x/** 는 접미가 없는 /x 자체도 덮는다', () => {
    // 이걸 놓치면 멀쩡히 보호된 경로를 미보호로 잘못 보고한다.
    assert.equal(antToRegExp('/admin/points/**').test('/admin/points'), true);
    assert.equal(antToRegExp('/admin/points/**').test('/admin/points/summary'), true);
    assert.equal(antToRegExp('/admin/points/**').test('/admin/pointsx'), false);
  });

  test('자체 SecurityConfig 를 가진 서비스는 그것으로 판정한다', () => {
    // 흡수된 board·education 슬라이스의 체인들은 operation 에서 shared-common 전역 체인과 공존한다 —
    // 합집합으로 보지 않으면 /admin/boards·/admin/education 19건이 통째로 미보호로 뜬다(실제로 그렇게 떴다).
    const operation = securityConfigFor(repoRoot, 'operation-service');
    assert.equal(operation.scope, 'own+shared-common');
    assert.ok(operation.paths.some((p) => basename(p) === 'BoardSecurityConfig.java'));
    assert.ok(operation.paths.some((p) => basename(p) === 'EducationSecurityConfig.java'));
    const shared = securityConfigFor(repoRoot, 'order-service');
    assert.equal(shared.scope, 'shared-common');
  });

  test('클래스 레벨 @PreAuthorize 를 인가 출처로 인정한다', () => {
    // order-service 의 /admin/rbac·/admin/menus 는 SecurityConfig 매처가 아니라 메서드시큐리티로 막힌다.
    const src = `
      @RestController
      @RequestMapping("/admin/rbac")
      @PreAuthorize("hasRole('ADMIN')")
      public class AdminRbacController {
        @GetMapping("/roles") public String roles() { return ""; }
      }`;
    const eps = parseEndpoints(src, { service: 'order-service', className: 'AdminRbacController' });
    assert.equal(eps.length, 1);
    assert.equal(eps[0].preAuthorize, true);
    assert.equal(eps[0].path, '/admin/rbac/roles');
  });

  test('경로 없는 매핑(@GetMapping)은 클래스 base 를 가리킨다', () => {
    const src = `
      @RestController
      @RequestMapping("/admin/boards")
      public class AdminBoardController {
        @GetMapping
        public String list() { return ""; }
      }`;
    const eps = parseEndpoints(src, {});
    assert.equal(eps.length, 1);
    assert.equal(eps[0].path, '/admin/boards');
    assert.equal(eps[0].method, 'GET');
  });

  test('AdminApiKeyFilter 배선이 없는 서비스를 있다고 보지 않는다', () => {
    // 이 저장소에는 AdminApiKeyFilter 로 /admin/** 을 막는 위성 서비스가 없다. 검출기가
    // 아무 서비스에나 true 를 주면 미보호 경로가 통째로 면제된다 — 그 반대를 고정한다.
    assert.equal(hasAdminApiKeyFilter(repoRoot, 'order-service'), false);
    assert.equal(hasAdminApiKeyFilter(repoRoot, 'operation-service'), false);
  });
});
