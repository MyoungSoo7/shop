// 계약 스키마 정합 게이트 — 카탈로그에 등재된 토픽과 계약 스키마가 1:1 인가.
//
// 막는 것: **계약 없이 발행되는 토픽**.
//
// 실측 결함: `lemuel.education.course_published` 만 계약 스키마가 없었다. 카탈로그에는 소유
// 토픽으로 등재돼 있어 토픽 21건 대 스키마 20건으로 어긋났고, 그 사실이 SPEC.md 와
// docs/SEQUENCE-DIAGRAM.md 에 "하나 더 많다"는 각주로 박제돼 있었다. 각주는 결함을 설명할 뿐
// 막지 않는다 — 두 문서 모두 `ls ... | wc -l` 로 세라는 검증 절차까지 적어 두었지만, 그 절차를
// 돌리는 것은 사람이다.
//
// 이 토픽은 소비자가 저장소 밖이라 필드가 바뀌어도 저장소 안에서는 아무것도 깨지지 않는다.
// 계약이 유일한 경보인 자리에서 계약이 빠져 있었다는 뜻이다.
//
// 스키마 목록은 **파일시스템**에서 읽는다. `git ls-files` 로 세면 아직 스테이지되지 않은 새
// 스키마가 빠져서, 커밋 직후에 처음 어긋난다(SPEC.md §5 의 경고와 같은 이유). 대신 추적
// 여부는 따로 본다 — 커밋되지 않은 계약은 CI 에 없는 계약이다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CATALOG = join(REPO_ROOT, 'shared-common/src/main/resources/kafka/topic-catalog.json');
const SCHEMA_DIR = 'shared-common/src/testFixtures/resources/contracts/events';
const SAMPLE_DIR = `${SCHEMA_DIR}/samples`;

function catalogTopics() {
  const parsed = JSON.parse(readFileSync(CATALOG, 'utf8'));
  const topics = Array.isArray(parsed) ? parsed : parsed.topics;
  return topics.map((entry) => entry.name).sort();
}

function schemaTopics() {
  return readdirSync(join(REPO_ROOT, SCHEMA_DIR))
    .filter((name) => name.endsWith('.schema.json'))
    .map((name) => name.replace(/\.schema\.json$/, ''))
    .sort();
}

describe('계약 스키마 정합 게이트 (계약 없는 토픽은 조용히 발행된다)', () => {
  test('카탈로그 토픽과 계약 스키마가 정확히 일치한다', () => {
    const topics = catalogTopics();
    const schemas = schemaTopics();

    assert.ok(topics.length > 0, '카탈로그가 비었다 — 대조가 무력화된 것이지 통과한 것이 아니다');
    assert.deepEqual(
      schemas,
      topics,
      `카탈로그만 있는 토픽=${topics.filter((t) => !schemas.includes(t))} · ` +
        `스키마만 있는 토픽=${schemas.filter((s) => !topics.includes(s))}`,
    );
  });

  test('모든 스키마에 정본 샘플이 있다', () => {
    // 샘플이 없으면 컨슈머 계약 테스트가 입력을 못 만든다 — 스키마만 있고 아무도 안 쓰는 상태.
    const missing = schemaTopics().filter(
      (topic) => !existsSync(join(REPO_ROOT, SAMPLE_DIR, `${topic}.sample.json`)),
    );
    assert.deepEqual(missing, [], `정본 샘플이 없는 토픽: ${missing}`);
  });

  test('스키마와 샘플이 모두 git 에 추적된다 — 커밋 안 된 계약은 CI 에 없다', () => {
    const tracked = new Set(
      execFileSync('git', ['ls-files', '-z', SCHEMA_DIR], { cwd: REPO_ROOT, encoding: 'utf8' })
        .split('\0')
        .filter(Boolean),
    );
    const untracked = [];
    for (const topic of schemaTopics()) {
      for (const path of [`${SCHEMA_DIR}/${topic}.schema.json`, `${SAMPLE_DIR}/${topic}.sample.json`]) {
        if (!tracked.has(path)) untracked.push(path);
      }
    }
    assert.deepEqual(untracked, [], `추적되지 않은 계약 파일: ${untracked}`);
  });

  test('스키마마다 필수 필드가 선언돼 있다 — required 가 비면 무엇이든 통과한다', () => {
    const toothless = [];
    for (const topic of schemaTopics()) {
      const schema = JSON.parse(readFileSync(join(REPO_ROOT, SCHEMA_DIR, `${topic}.schema.json`), 'utf8'));
      const hasRequired = Array.isArray(schema.required) && schema.required.length > 0;
      // anyOf 로 "둘 중 하나는 있어야 한다"를 표현하는 스키마가 있다(payment.refunded 의 금액 필드).
      const hasAnyOf = Array.isArray(schema.anyOf) && schema.anyOf.length > 0;
      if (!hasRequired && !hasAnyOf) toothless.push(topic);
    }
    assert.deepEqual(toothless, [], `required 도 anyOf 도 없는 스키마: ${toothless}`);
  });

  test('문서가 적어 둔 토픽 수가 실제와 같다 — 각주는 결함을 막지 못한다', () => {
    // SPEC.md 와 SEQUENCE-DIAGRAM.md 는 스키마 수를 본문에 적고 "ls | wc -l 로 검증하라"고
    // 적어 두었다. 그 검증을 여기서 기계로 돌린다.
    const count = schemaTopics().length;
    for (const doc of ['SPEC.md', 'docs/SEQUENCE-DIAGRAM.md']) {
      const text = readFileSync(join(REPO_ROOT, doc), 'utf8');
      const claimed = [...text.matchAll(/\*\*(?:총\s*)?(\d+)\s*(?:개\s*)?토픽\*\*/g)].map((m) => Number(m[1]));
      assert.ok(claimed.length > 0, `${doc} 에서 토픽 수 주장을 찾지 못했다 — 문장이 바뀌었나?`);
      for (const value of claimed) {
        assert.equal(value, count, `${doc} 이 ${value} 토픽이라고 적었지만 실제는 ${count} 개다`);
      }
    }
  });
});
