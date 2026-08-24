/**
 * 프론트 테스트 렌더 경합 게이트 — CI 에서만 랜덤하게 터지는 flaky 를 빌드 시점에 막는다.
 *
 * 잡는 형태:
 *
 *   await waitFor(() => expect(mocked.list).toHaveBeenCalled());   // API 가 "불렸는지"만 기다림
 *   fireEvent.click(screen.getByRole('button', { name: '기각' })); // 렌더 반영은 안 기다림
 *
 * API 가 호출된 시점과 그 결과가 화면에 반영된 시점 사이에는 상태 갱신 한 틱이 있다. 빠른 머신에선
 * 같은 틱에 붙어 통과하고, 느리고 붐비는 CI 러너에선 틈이 벌어져 실패한다. 로컬에서 재현되지 않아
 * 매번 PR 이 막힌 뒤에야 발견됐다 — 2026-08-13 하루에 두 파일(차지백 콘솔·카테고리 정합 패널)이
 * 같은 이유로 필수 체크를 깼다.
 *
 * 고치는 법은 재시도 조회로 바꾸는 것이다.
 *
 *   fireEvent.click(await screen.findByRole('button', { name: '기각' }));
 *
 * findBy* 는 getBy* 에 재시도만 얹은 것이라 이미 있는 엘리먼트에는 동작이 같고, 끝내 나타나지
 * 않으면 그대로 실패한다 — 결함을 가리지 않는다.
 *
 * 왜 린트가 아니라 게이트인가: `testing-library/prefer-find-by` 는 `waitFor(() => getBy...)`
 * 형태만 잡는다. 여기서 문제가 된 "waitFor 로 호출을 기다린 뒤 바깥에서 getBy" 는 그 룰의
 * 사각지대라, 잡으려면 별도 스캔이 필요하다.
 *
 * 정적 chrome(마운트부터 있는 헤더·필터·폼)을 집는 경우는 경합이 아니다. 그런 자리는 아래
 * STATIC_QUERIES 에 사유와 함께 등록한다 — 등록은 "이 엘리먼트는 데이터가 오기 전에도 있다"는
 * 명시적 선언이다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const TEST_ROOT = join(REPO_ROOT, 'frontend', 'src', '__tests__');

/**
 * 경합이 아닌 조회와 그 사유. 키는 `<파일명> :: <조회식>` 이다.
 *
 * <p>줄 번호를 키로 쓰지 않는 이유: 위에 한 줄만 끼어들어도 전부 어긋나 allowlist 가 거짓말을
 * 하게 된다. 조회식은 그 코드가 실제로 바뀔 때만 달라진다.
 */
const STATIC_QUERIES = new Map([
  // 테스트가 직접 렌더하는 하네스 컴포넌트의 버튼 — API 응답과 무관하게 항상 있다.
  ["CartContext.test.tsx :: screen.getByRole('button', { name: 'add' })", '테스트 하네스 버튼'],
  ["CartContext.test.tsx :: screen.getByRole('button', { name: 'qty5' })", '테스트 하네스 버튼'],
  ["CartContext.test.tsx :: screen.getByRole('button', { name: 'remove' })", '테스트 하네스 버튼'],
  ["CartContext.test.tsx :: screen.getByRole('button', { name: 'clear' })", '테스트 하네스 버튼'],

  // 2026-08-13 CI 가 남긴 DOM 덤프에서, 행이 하나도 없는 상태인데 이 둘은 이미 렌더돼 있었다.

  // LedgerConsolePage.tsx 의 단건 추적 폼. 조회 결과와 무관하게 조건 없이 렌더된다.

  // ReconciliationConsolePage.tsx 의 기간 스캔 버튼. 라벨만 scanning 여부로 바뀐다.

  // TaxConsolePage.tsx 의 필터 셀렉트와 편집 폼. 둘 다 조회 결과 밖(조건부 렌더 블록 밖)이라
  // 스캔·프로필이 비어 있어도 마운트부터 있다. 반대로 '전표 전기'·'세금계산서 발행'·tax-profile
  // 패널은 조건부 블록 안이라 등록하지 않고 재시도 조회로 고쳤다.

  // CommissionRateConsolePage.tsx 의 '요율 확인' 패널과 '새 정책 등록' 패널은 목록 로딩과 무관한
  // 형제 블록이라 조건부 렌더 밖이다(목록만 loading/error/empty 로 갈린다). 반대로 조회 결과인
  // simulation-result 패널은 {simulation && ...} 안이라 등록하지 않고 재시도 조회로 고쳤다.

  // DlqConsolePage.tsx 의 조회 버튼은 결과 블록 밖이라 마운트부터 있다. 같은 필터 바의 토픽
  // 셀렉트는 등록하지 않았다 — 엘리먼트는 정적이지만 <option> 은 데이터로 채워져서, 테스트가
  // **옵션**을 기다리도록 고쳤다(옵션 전에 change 를 쏘면 값이 조용히 무시되고 빈 토픽으로 조회가 나간다).

  // PointConsolePage.tsx 의 수기 지급 폼과 현황 필터는 조회 결과 밖이다. 현황 4종이 아직
  // 안 왔어도 폼은 마운트부터 그려진다(잠김 여부만 입력값으로 갈린다). 반대로 3자 대조·계정
  // 상세·정책·소멸 목록은 조건부 블록 안이라 등록하지 않고 재시도 조회(findByTestId)로 썼다.
  ["PointConsolePage.test.tsx :: screen.getByRole('button', { name: '포인트 지급' })", '지급 폼 제출 버튼 — 조건 없이 렌더'],
  ["PointConsolePage.test.tsx :: screen.getByLabelText('소멸 예정 기준 일수')", '현황 필터 입력 — 조건 없이 렌더'],
  ["PointConsolePage.test.tsx :: screen.getByRole('button', { name: '포인트 차감' })", '차감 폼 제출 버튼 — 조건 없이 렌더'],

  // SalesStatsConsolePage.tsx 의 필터 바 셀렉트 2종. <option> 이 상수 배열(DIMENSIONS·
  // GRANULARITIES)이라 데이터로 채워지지 않는다 — DLQ 토픽 셀렉트와 달리 옵션을 기다릴 필요가 없다.
]);

/** 앞선 waitFor 로부터 이 줄 수 안에 있는 조회만 같은 흐름으로 본다. */
const LOOKAHEAD = 4;

function testFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return testFiles(path);
    return /\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

/**
 * `screen.getByX(...)` 호출식을 괄호 균형까지 맞춰 잘라낸다.
 *
 * <p>따옴표 안을 세지 않는 것이 핵심이다 — `name: '수락(셀러 부담)'` 처럼 문자열 안에 괄호가
 * 들어 있는 라벨이 실제로 있어서, 단순 괄호 세기로는 식이 잘린다.
 */
function queryExpression(line) {
  const start = line.indexOf('screen.getBy');
  if (start < 0) return null;
  const open = line.indexOf('(', start);
  if (open < 0) return null;

  let depth = 0;
  let quote = null;
  for (let i = open; i < line.length; i += 1) {
    const ch = line[i];
    if (quote) {
      if (ch === quote && line[i - 1] !== '\\') quote = null;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === '`') { quote = ch; continue; }
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      depth -= 1;
      if (depth === 0) return line.slice(start, i + 1).replace(/\s+/g, ' ').trim();
    }
  }
  return line.slice(start).replace(/\s+/g, ' ').trim();
}

/** 파일 전체를 훑어 `waitFor(호출됨)` 직후의 동기 조회를 모은다. */
function racyQueries(path) {
  const lines = readFileSync(path, 'utf8').split('\n');
  const name = path.split(/[\\/]/).pop();
  const found = [];

  for (let i = 0; i < lines.length; i += 1) {
    const anchor = lines[i];
    if (!(anchor.includes('waitFor') && anchor.includes('toHaveBeenCalled'))) continue;

    for (let j = i + 1; j < Math.min(i + 1 + LOOKAHEAD, lines.length); j += 1) {
      const line = lines[j];
      // 다음 대기 지점을 만나면 이 흐름은 끝난 것이다.
      if (line.includes('waitFor') || line.includes('findBy')) break;
      // 최상위(들여쓰기 0)에서 블록이 닫히면 그 실행 흐름도 끝났다. 이어지는 줄은 다음 선언부지
      // 이 흐름의 후속 조회가 아니다 — `renderPage` 류 헬퍼가 await waitFor 로 끝나면 바로 아래
      // `const titleInput = () => screen.getByPlaceholderText('제목')` 이 오는데, 그건 실행이
      // 아니라 정의다(호출 시점에야 평가된다). LOOKAHEAD 가 함수 밖으로 새어 나가 오탐을 냈다 —
      // 실측: board 2건(BoardPage·BoardPostPage). describe/it 안의 조회는 항상 들여쓰기가 있어
      // 이 break 에 걸리지 않는다.
      if (/^\}/.test(line)) break;
      if (!line.includes('screen.getBy')) continue;

      const expression = queryExpression(line);
      if (expression) found.push({ key: `${name} :: ${expression}`, line: j + 1, file: name });
      break;
    }
  }
  return found;
}

const allQueries = () => testFiles(TEST_ROOT).flatMap(racyQueries);

test('async 경계 뒤의 동기 조회는 재시도 조회이거나 사유와 함께 등록돼 있다', () => {
  const unregistered = allQueries()
    .filter((q) => !STATIC_QUERIES.has(q.key))
    .map((q) => `${q.file}:${q.line}  ${q.key.split(' :: ')[1]}`)
    .sort();

  assert.deepEqual(unregistered, [],
    'waitFor 로 API 호출만 기다린 뒤 곧바로 동기 조회를 합니다 — CI 에서 랜덤하게 실패합니다:\n'
    + `  ${unregistered.join('\n  ')}\n`
    + 'screen.getBy* 를 await screen.findBy* 로 바꾸거나, 데이터가 오기 전에도 있는 정적\n'
    + 'chrome 이라면 scripts/harness/test/async-query-gate.test.mjs 의 STATIC_QUERIES 에\n'
    + '사유와 함께 등록하세요.');
});

test('정적 조회 allowlist 에 죽은 항목이 없다', () => {
  const live = new Set(allQueries().map((q) => q.key));
  const stale = [...STATIC_QUERIES.keys()].filter((key) => !live.has(key)).sort();

  assert.deepEqual(stale, [],
    `이미 사라졌거나 이미 findBy 로 바뀐 조회가 allowlist 에 남아 있습니다:\n  ${stale.join('\n  ')}`);
});
