/**
 * 메뉴 정합 게이트 — 메뉴 트리의 세 사본이 서로 어긋나지 않는지 빌드 시점에 대조한다.
 *
 *   ① 시드 SQL (정본)  order-service/.../V20260813100000__menu_area_permission.sql
 *   ② 프론트 폴백      frontend/src/data/menuFallback.ts
 *   ③ 라우트           frontend/src/App.tsx
 *
 * 왜 필요한가: 메뉴는 "보여 줄 곳", 라우트는 "갈 수 있는 곳"이다. 둘은 다른 파일에 살기 때문에
 * 한쪽만 바꾸면 아무도 모르게 죽은 링크(메뉴는 있는데 라우트 없음)나 유령 화면(라우트는 있는데
 * 진입점 없음)이 생긴다. 컴파일러가 잡아 주지 않는 종류의 드리프트라 게이트가 대신 잡는다.
 *
 * 새 화면을 붙이는 절차는 두 스텝으로 고정된다:
 *   1) App.tsx 에 라우트 추가   2) 시드 SQL + 폴백에 메뉴 행 추가
 * 메뉴에 넣지 않을 화면이라면 아래 ROUTES_WITHOUT_MENU 에 사유와 함께 등록한다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const MIGRATION_DIR = join(REPO_ROOT, 'order-service', 'src', 'main', 'resources', 'db', 'migration');

/**
 * 현재 트리를 만드는 시드 기준점. 이 마이그레이션이 이전 시드(V20260623100002 의 4행)를
 * 전부 지우고 다시 심었으므로, 그보다 앞선 메뉴 마이그레이션은 세면 중복이 된다.
 */
const SEED_BASELINE = 'V20260813100000__menu_area_permission.sql';

/**
 * 기준점 이후의 메뉴 마이그레이션 <b>전부</b>. 한 파일만 지목하면 그 다음 마이그레이션부터
 * 게이트가 눈을 감는다 — 실제로 '정산운영' 그룹을 추가한 두 번째 파일이 그렇게 새어 나갔다.
 * 버전 접두사가 같은 자리수의 타임스탬프라 사전순 비교가 곧 시간순 비교다.
 */
const seedSqlFiles = () => readdirSync(MIGRATION_DIR)
  .filter((name) => /^V\d+__menu_.*\.sql$/.test(name) && name >= SEED_BASELINE)
  .sort()
  .map((name) => join(MIGRATION_DIR, name));
const FALLBACK_TS = join(REPO_ROOT, 'frontend', 'src', 'data', 'menuFallback.ts');
const APP_TSX = join(REPO_ROOT, 'frontend', 'src', 'App.tsx');

/**
 * 메뉴에 넣지 않는 라우트와 그 사유.
 * 여기에 등록하는 것은 "진입점이 네비게이션이 아니다"라는 명시적 선언이다.
 */
const ROUTES_WITHOUT_MENU = new Map([
  ['/', '로그인으로 리다이렉트'],
  ['/login', '공개 — 로그인'],
  ['/admin/login', '공개 — 관리자 로그인'],
  ['/register', '공개 — 회원가입'],
  ['/forgot-password', '공개 — 비밀번호 찾기'],
  ['/reset-password', '공개 — 비밀번호 재설정'],
  ['/order/toss/fail', 'PG 콜백'],
  // 선물 받기. 진입점은 이 사이트의 메뉴가 아니라 받는 사람 휴대폰으로 간 링크다 — 받는 사람은
  // 회원이 아니므로 네비게이션에 닿을 일이 없고, 메뉴에 걸면 토큰 없는 진입만 늘어난다.
  ['/gift/:token', '공개 — 링크로만 진입하는 선물 수령'],
  ['/order/toss/success', 'PG 콜백'],
  ['/cart', '헤더 장바구니 아이콘으로 진입'],
  ['/wishlist', '헤더 찜 아이콘으로 진입 — 장바구니와 같은 자리, 같은 성격의 개인 목록'],
  ['/mypage', '헤더 MY 버튼으로 진입'],
  ['/tags', '관리 화면 내부 이동'],
  // 게시판은 메뉴 행이 시드가 아니라 런타임에 만들어진다(관리자가 /admin/system/boards 에서
  // 기존 POST /admin/menus 로 붙인다). 시드 SQL 과 대조할 대상이 애초에 없으므로 여기 등록한다.
  // 라우트가 게시판 수만큼 늘지 않는 것이 이 설계의 핵심이다 — docs/plan/board-service.md §2.
  ['/boards/:boardKey', '메타 주도 게시판 — 메뉴 행은 관리 화면이 런타임에 등록한다'],
  ['/boards/:boardKey/:postId', '게시글 상세 — 목록에서 진입'],
  ['/admin/system', '그룹 진입 리다이렉트'],
  ['/admin/operation', '구 경로 리다이렉트'],
]);

const read = (path) => readFileSync(path, 'utf8');

/**
 * 시드 SQL 에서 <b>삽입되는 값</b>의 경로 리터럴만 뽑는다.
 *
 * <p>제외 대상: `DELETE`/`UPDATE` 문과 조건절(`WHERE`/`AND`/`OR`). 이 줄들의 경로는 "행을
 * 고르는 조건"이지 새로 생기는 메뉴가 아니라서, 같이 세면 한 경로가 두 번 잡힌다
 * (실제로 '원장·시산표' 의 정렬을 바꾸는 UPDATE 가 그렇게 잡혔다).
 *
 * <p>경로 <b>이동</b>({@code UPDATE menus SET path = '/new' WHERE path = '/old'})은 별도로 읽어
 * 누적 목록에서 제자리 치환한다. 예전에는 이 형태를 못 읽어 "경로 변경은 DELETE + INSERT 로
 * 표현하라"고 안내했지만, 그건 같은 메뉴에 새 id 를 주는 셈이라 참조가 끊긴다. 실제로 화면 URL 3개를
 * 옮겨야 했을 때(2026-08-21, SPA 폴백) UPDATE 가 옳은 표현이었다.
 */

/** 마이그레이션 한 벌이 <b>새로 심는</b> 경로 — 조건절과 갱신문의 경로는 새 메뉴가 아니라서 뺀다. */
/** 줄 주석(`--`)을 걷어낸다 — 주석 안의 세미콜론이 문장 분리를 깨뜨린다. */
const stripComments = (sql) => sql.split('\n').filter((line) => !/^\s*--/.test(line)).join('\n');

/** 문장 안의 경로 리터럴 전부. */
const literalPaths = (text) => [...text.matchAll(/'(\/[^']*)'/g)].map((m) => m[1]);

/** 마이그레이션 한 벌이 <b>새로 심는</b> 경로 — 조건절의 경로는 새 메뉴가 아니라서 뺀다. */
function insertedPaths(sql) {
  const body = sql
    .split('\n')
    .filter((line) => !/^\s*(DELETE|UPDATE|SET)\b/i.test(line))
    .filter((line) => !/^\s*(WHERE|AND|OR)\b/i.test(line))
    .join('\n');
  return literalPaths(body);
}

/**
 * {@code UPDATE menus SET path = '/new' … WHERE path = '/old'} 을 (from → to) 로 읽는다.
 * {@code [^;]*?} 로 한 문장 안에 가둔다 — 문장 경계를 넘으면 엉뚱한 두 경로를 짝지을 수 있다.
 */
function pathRenames(sql) {
  return [...sql.matchAll(
    /UPDATE\s+menus\s+SET\s+path\s*=\s*'(\/[^']*)'[^;]*?WHERE\s+path\s*=\s*'(\/[^']*)'/gi)]
    .map((m) => ({ from: m[2], to: m[1] }));
}

/**
 * `DELETE FROM menus … path IN ('/a', '/b')` 이 지우는 경로.
 *
 * <p>같은 경로의 행이 둘이면(그룹 + 항목) 둘 다 사라지므로 누적 목록에서도 그 경로의 <b>모든</b>
 * 항목을 뺀다 — SQL 과 같은 의미다.
 */
const deletedPaths = (stmt) => literalPaths(stmt);

/**
 * 마이그레이션을 <b>문장 단위로, 시간순으로</b> 적용해 최종 경로 목록을 만든다.
 *
 * <p>파일 단위가 아니라 문장 단위여야 한다: 시드 기준점 파일 자신이 `DELETE FROM menus WHERE path
 * IN (…)` 로 옛 행을 지운 <b>뒤에</b> 같은 경로를 다시 심는다. 파일 단위로 삭제를 나중에 적용하면
 * 방금 심은 행까지 지워져, 멀쩡한 메뉴가 통째로 사라진 것처럼 보인다(실제로 그렇게 보였다).
 */
function seedPaths() {
  const paths = [];
  for (const file of seedSqlFiles()) {
    for (const raw of stripComments(read(file)).split(';')) {
      const stmt = raw.trim();
      if (stmt === '') continue;
      if (/^DELETE\s+FROM\s+menus\b/i.test(stmt)) {
        const removed = new Set(deletedPaths(stmt));
        for (let i = paths.length - 1; i >= 0; i--) if (removed.has(paths[i])) paths.splice(i, 1);
        continue;
      }
      if (/^UPDATE\s+menus\b/i.test(stmt)) {
        for (const { from, to } of pathRenames(`${stmt};`)) {
          for (let i = 0; i < paths.length; i++) if (paths[i] === from) paths[i] = to;
        }
        continue;
      }
      paths.push(...insertedPaths(stmt));
    }
  }
  return paths;
}

function fallbackPaths() {
  return [...read(FALLBACK_TS).matchAll(/path:\s*'(\/[^']*)'/g)].map((m) => m[1]);
}

function routePaths() {
  return [...read(APP_TSX).matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]);
}

const sorted = (values) => [...new Set(values)].sort();

test('시드 SQL 과 프론트 폴백의 메뉴 경로가 같다', () => {
  assert.deepEqual(sorted(fallbackPaths()), sorted(seedPaths()),
    'menuFallback.ts 가 시드 SQL 과 어긋납니다. 정본은 SQL 이며 폴백은 그 사본입니다.');
});

test('시드 SQL 과 프론트 폴백의 메뉴 개수가 같다', () => {
  // 시드된 모든 행은 링크(GROUP/ITEM)라 행마다 경로가 정확히 하나 있다.
  assert.equal(fallbackPaths().length, seedPaths().length,
    '메뉴 행 수가 다릅니다 — 한쪽에만 항목을 추가했을 가능성이 큽니다.');
});

test('모든 메뉴 경로에 대응하는 라우트가 있다 (죽은 링크 없음)', () => {
  const routes = new Set(routePaths());
  const dead = sorted(seedPaths()).filter((path) => !routes.has(path));

  assert.deepEqual(dead, [],
    `메뉴는 있는데 App.tsx 에 라우트가 없습니다: ${dead.join(', ')}`);
});

test('메뉴에 없는 라우트는 사유와 함께 등록돼 있다 (유령 화면 없음)', () => {
  const menu = new Set(seedPaths());
  const unlisted = sorted(routePaths())
    .filter((path) => !menu.has(path) && !ROUTES_WITHOUT_MENU.has(path));

  assert.deepEqual(unlisted, [],
    `라우트는 있는데 진입할 메뉴가 없습니다: ${unlisted.join(', ')}\n`
    + '메뉴 행을 추가하거나, 네비게이션 진입점이 아니라면 '
    + 'scripts/harness/test/menu-route-gate.test.mjs 의 ROUTES_WITHOUT_MENU 에 사유와 함께 등록하세요.');
});

test('메뉴 없는 라우트 allowlist 에 죽은 항목이 없다', () => {
  const routes = new Set(routePaths());
  const stale = [...ROUTES_WITHOUT_MENU.keys()].filter((path) => !routes.has(path));

  assert.deepEqual(stale, [],
    `이미 사라진 라우트가 allowlist 에 남아 있습니다: ${stale.join(', ')}`);
});

test('[자기검증] 경로 이동(UPDATE)을 새 메뉴가 아니라 이동으로 읽는다', () => {
  const move = `
    UPDATE menus SET path = '/admin/system/education', updated_at = NOW()
     WHERE path = '/admin/education/courses';
  `;
  assert.deepEqual(insertedPaths(move), [],
    '갱신문의 경로를 새 메뉴로 세면 메뉴 수가 늘어난 것처럼 보인다');
  assert.deepEqual(pathRenames(move),
    [{ from: '/admin/education/courses', to: '/admin/system/education' }]);

  // 경로가 아닌 컬럼을 바꾸는 UPDATE 는 이동이 아니다(실제로 menu_type 을 바꾸는 것이 있다).
  assert.deepEqual(pathRenames("UPDATE menus SET menu_type = 'GROUP' WHERE name = '배송';"), []);

  // 두 문장을 가로질러 엉뚱한 짝을 만들면 안 된다.
  const two = `
    UPDATE menus SET path = '/a' WHERE name = 'x';
    UPDATE menus SET sort_order = 1 WHERE path = '/b';
  `;
  assert.deepEqual(pathRenames(two), [], '문장 경계를 넘어 짝지으면 경로가 조용히 뒤바뀐다');
});

test('삭제된 사이드바 셸 3종이 되살아나지 않았다', () => {
  // 트리 기반 셸(SideNavLayout)로 합쳤다. 파일이 다시 생기면 하드코딩 네비가 부활한 것이다.
  const app = read(APP_TSX);
  for (const gone of ['SettlementLayout', 'CeoLayout', 'SystemLayout']) {
    assert.ok(!new RegExp(`<${gone}[\\s>]`).test(app),
      `${gone} 이 App.tsx 에서 다시 쓰이고 있습니다 — 네비게이션 정본은 menus 테이블입니다.`);
  }
});
