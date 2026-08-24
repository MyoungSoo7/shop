// OO 구조 게이트 — 2026-07-14 OO 캠페인(3인 패널 축별 중앙값 9.5+)을 만든 구조 불변식의
// 리포 전수 회귀 게이트. guard.mjs 의 OO-* 규칙이 "쓰기 시점" 방어라면, 이 테스트는
// "현재 트리 전체"가 청정함을 CI 에서 증명한다 (QueryParamBindingGateTest 와 동일 철학 —
// ArchUnit 1.3.0 이 Java 25 바이트코드를 못 읽어 소스 스캔을 쓴다).
//
// 판정 자체(9.5점)는 LLM 몫이라 여기서 못 하지만, 그 점수를 만든 기계적 불변식은 여기서 잠근다.
// 채점 재현 절차는 .claude/skills/oo-score/SKILL.md.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const trackedJava = execFileSync('git', ['ls-files', '*.java'], { cwd: repoRoot })
  .toString('utf8').split('\n').filter(Boolean);

// 감사 인프라 DTO(common/audit)는 패널 3회 모두 "코어 애그리거트 아님" 판정 — 대상 밖.
const domainMain = trackedJava.filter(
  (f) => /\/src\/main\/java\/.+\/domain\//.test(f) && !/common\/audit\//.test(f),
);
// guard.mjs 의 CAMPAIGN_SERVICES 와 **같은 판단·같은 범위**여야 한다 — 한쪽만 고치면 실시간 가드와
// CI 전수 게이트가 어긋나 "커밋은 되는데 CI 는 빨간" 상태가 된다.
const isCampaignService = (f) => /^order-service\//.test(f);

const read = (f) => readFileSync(join(repoRoot, f), 'utf8');
const codeLines = (content) => String(content).split(/\r?\n/)
  .map((line, i) => ({ line, no: i + 1 }))
  .filter(({ line }) => !/^\s*(\/\/|\*|\/\*)/.test(line)); // 주석 라인 제외 (guard.mjs 동일 규칙)

function findViolations(files, predicate) {
  const violations = [];
  for (const f of files) {
    for (const { line, no } of codeLines(read(f))) {
      if (predicate(line)) violations.push(`${f}:${no} ${line.trim()}`);
    }
  }
  return violations;
}

// 봉인 상태가 실측 검증된 코어 애그리거트 — public 생성자 0 이 정본 상태.
// 신규 애그리거트를 봉인 완료하면 이 목록에 추가할 것.
const SEALED_AGGREGATES = [
  ['order-service', 'Order'], ['order-service', 'PaymentDomain'], ['order-service', 'PaymentTender'],
  ['order-service', 'Refund'], ['order-service', 'Product'], ['order-service', 'Coupon'], ['order-service', 'Menu'],
];

// 상태 전이 규칙의 선언적 단일 출처 — canTransitionTo 가 enum 에 존재해야 한다.
const TRANSITION_TABLE_ENUMS = [
  'OrderStatus', 'PaymentStatus',
];

describe('OO 구조 게이트 (캠페인 정본 회귀 방지)', () => {
  test('도메인 프로덕션 소스에 public setter 가 없다', () => {
    assert.deepEqual(findViolations(domainMain, (l) => /public\s+void\s+set[A-Z]\w*\s*\(/.test(l)), []);
  });

  test('도메인 프로덕션 소스에 @Setter/@Data 가 없다', () => {
    assert.deepEqual(findViolations(domainMain, (l) => /@(Setter|Data)\b/.test(l)), []);
  });

  test('금융 5서비스 도메인에 generic IllegalArgumentException throw 가 없다', () => {
    assert.deepEqual(
      findViolations(domainMain.filter(isCampaignService), (l) => /throw\s+new\s+IllegalArgumentException\s*\(/.test(l)),
      [],
    );
  });

  test('봉인된 코어 애그리거트에 public 생성자가 없다 (rehydrate/팩토리 전용)', () => {
    const violations = [];
    for (const [service, name] of SEALED_AGGREGATES) {
      const file = domainMain.find((f) => f.startsWith(`${service}/`) && f.endsWith(`/domain/${name}.java`));
      if (!file) { violations.push(`${service}/${name}: 도메인 파일을 찾을 수 없음 (이동 시 목록 갱신 필요)`); continue; }
      const pattern = new RegExp(`public\\s+${name}\\s*\\(`);
      for (const { line, no } of codeLines(read(file))) {
        if (pattern.test(line)) violations.push(`${file}:${no} ${line.trim()}`);
      }
    }
    assert.deepEqual(violations, []);
  });

  test('상태 enum 이 canTransitionTo 선언 전이표를 보유한다 (단일 출처)', () => {
    const violations = [];
    for (const name of TRANSITION_TABLE_ENUMS) {
      const file = domainMain.find((f) => f.endsWith(`/domain/${name}.java`));
      if (!file) { violations.push(`${name}: enum 파일을 찾을 수 없음`); continue; }
      if (!read(file).includes('canTransitionTo')) violations.push(`${file}: canTransitionTo 부재`);
    }
    assert.deepEqual(violations, []);
  });

  test('게이트 대상 도메인 파일이 실제로 존재한다 (스캔 자체의 무결성)', () => {
    assert.ok(domainMain.length >= 200, `domain 파일 ${domainMain.length}개 — 스캔 글롭이 깨졌을 수 있음`);
  });
});
