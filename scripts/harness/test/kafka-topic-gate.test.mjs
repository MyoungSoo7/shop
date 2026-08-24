// Kafka 토픽 카탈로그 게이트 — 리포 전수 (ADR 0035).
//
// 막는 것: "토픽 파티션 수가 코드 밖에서 정해지는 상태".
//
// 이 저장소는 outbox 의 aggregateId 를 메시지 키로 써서 "같은 결제/정산의 이벤트는 같은 파티션 →
// 시간 순서 보장"을 얻는다. 파티션 수 N 이 바뀌면 hash(key) % N 이 바뀌어 같은 애그리거트의 이벤트가
// 다른 파티션으로 흩어진다 — 이미 쌓인 메시지까지 순서 보장이 소급 붕괴하는, 되돌릴 수 없는 변경이다.
// 그런데 도입 전까지 NewTopic 선언은 payment 계열 4개뿐이었고 나머지 40여 개는 브로커 자동생성에
// 맡겨져 있었다(= 리뷰할 대상 자체가 없었다). 어떤 코드도 "틀리지" 않기 때문에 컴파일도 테스트도
// 잡지 못한다.
//
// 실측 사례: notification-service 의 KafkaErrorHandlingConfig 주석에 lemuel.payment.captured 가
// 6 파티션인데 DLT 는 3 이어서 파티션 3~5 의 레코드가 격리 발행조차 실패한 기록이 남아 있다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CATALOG_PATH = join(repoRoot, 'shared-common', 'src', 'main', 'resources', 'kafka', 'topic-catalog.json');

/**
 * application.yml 의 app.kafka.topic.* 아래에서 lemuel.* 토픽명을 뽑는다.
 * 값이 토픽명인 키만 대상 — owner 같은 메타 키는 lemuel. 접두사가 없어 자연히 걸러진다.
 */
export function extractReferencedTopics(yaml) {
  const topics = new Set();
  for (const match of String(yaml).matchAll(/^\s*[a-z0-9-]+:\s*(lemuel\.[a-z0-9_.]+)\s*(?:#.*)?$/gim)) {
    topics.add(match[1]);
  }
  return topics;
}

/** 서비스 모듈의 application.yml 경로들 — 작업트리 기준 스캔(미추적 파일도 본다). */
function serviceConfigs() {
  return readdirSync(repoRoot, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name.endsWith('-service'))
    .map((e) => join(repoRoot, e.name, 'src', 'main', 'resources', 'application.yml'))
    .filter((p) => existsSync(p));
}

describe('Kafka 토픽 카탈로그 (ADR 0035)', () => {
  const catalog = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  const declared = new Set(catalog.topics.map((t) => t.name));

  // ── 검출기 자체 검증 — 정규식이 아무것도 못 잡으면 게이트는 영원히 통과한다 ──
  test('참조 토픽 추출기가 실제로 동작한다', () => {
    const yaml = [
      'app:',
      '  kafka:',
      '    topic:',
      '      owner: settlement-service   # 메타 키는 토픽이 아니다',
      '      payment-captured: lemuel.payment.captured',
      '      pg-recon-approved: lemuel.pgreconciliation.discrepancy_approved   # 주석 붙은 값',
    ].join('\n');

    assert.deepEqual([...extractReferencedTopics(yaml)].sort(),
      ['lemuel.payment.captured', 'lemuel.pgreconciliation.discrepancy_approved']);
  });

  test('메타 키(owner)를 토픽으로 오인하지 않는다', () => {
    assert.equal(extractReferencedTopics('      owner: settlement-service').size, 0);
  });

  // ── 카탈로그 자체 불변식 ──
  test('빈 카탈로그로 통과하지 않는다', () => {
    assert.ok(declared.size >= 15,
      `카탈로그 토픽이 비정상적으로 적다(${declared.size}) — 파일이 잘렸거나 파싱이 깨졌다`);
  });

  test('토픽명이 중복되지 않는다', () => {
    assert.equal(declared.size, catalog.topics.length, '카탈로그에 중복 토픽이 있다');
  });

  test('DLT 는 카탈로그에 직접 등록되지 않는다 — 원본에서 파생되어야 파티션이 갈리지 않는다', () => {
    assert.deepEqual(catalog.topics.filter((t) => t.name.endsWith('.DLT')).map((t) => t.name), []);
  });

  test('모든 토픽이 owner·orderingKey·partitions·replicas·retentionDays 를 선언한다', () => {
    const incomplete = catalog.topics
      .filter((t) => !t.owner || !t.orderingKey || !(t.partitions >= 1) || !(t.replicas >= 1)
        || !(t.retentionDays >= 1))
      .map((t) => t.name);

    assert.deepEqual(incomplete, [],
      'orderingKey 미선언 = "무엇의 시간 순서를 지키는지" 모른 채 파티션을 나눈 상태다. '
      + 'replicas 미선언 = 내구성이 코드 상수로 숨는다');
  });

  test('DLT 보존기간이 원본보다 길다는 전제가 카탈로그에서 성립한다', () => {
    // DLT 는 파생값이라 카탈로그에 없다 — 원본 보존기간이 DLT 상수(30일)보다 짧아야 그 전제가 성립한다.
    const tooLong = catalog.topics.filter((t) => t.retentionDays >= 30).map((t) => t.name);

    assert.deepEqual(tooLong, [],
      '원본 보존이 DLT(30일) 이상이면 "운영자가 사후 분석할 시간을 더 준다"는 설계가 무너진다');
  });

  test('owner 는 실재하는 서비스 모듈이다', () => {
    const modules = new Set(readdirSync(repoRoot, { withFileTypes: true })
      .filter((e) => e.isDirectory()).map((e) => e.name));
    const unknown = [...new Set(catalog.topics.map((t) => t.owner))].filter((o) => !modules.has(o));

    assert.deepEqual(unknown, [], '없는 모듈이 토픽 소유자로 선언됐다');
  });

  test('토픽 하나의 소유자는 하나다 — 둘이면 파티션 수 결정 주체가 갈린다', () => {
    const owners = new Map();
    for (const t of catalog.topics) {
      if (!owners.has(t.name)) owners.set(t.name, new Set());
      owners.get(t.name).add(t.owner);
    }
    const shared = [...owners.entries()].filter(([, o]) => o.size > 1).map(([n]) => n);

    assert.deepEqual(shared, []);
  });

  // ── 리포 전수: 참조되는데 선언되지 않은 토픽 ──
  const configs = serviceConfigs();
  const referenced = new Set();
  for (const path of configs) {
    for (const topic of extractReferencedTopics(readFileSync(path, 'utf8'))) referenced.add(topic);
  }

  test('스캔이 실제로 서비스 설정에 도달했다', () => {
    // 하한은 "스캔이 통째로 깨졌는가"만 걸러낸다. 모듈 수에 붙여 두면 서비스를 합칠 때마다
    // 게이트가 엉뚱한 이유로 빨개진다 — 실제로 ADR 0038·0039·0042 통합에서 연달아 그랬다.
    // 진짜 도달 증거는 아래 `referenced.size` 쪽이다(설정을 읽어 토픽을 실제로 뽑아냈는가).
    assert.ok(configs.length >= 3, `application.yml 스캔 결과가 적다(${configs.length})`);
    assert.ok(referenced.size >= 3, `참조 토픽 수집이 적다(${referenced.size}) — 추출기가 깨졌을 수 있다`);
  });

  test('참조되는 모든 토픽이 카탈로그에 선언돼 있다', () => {
    const missing = [...referenced].filter((t) => !declared.has(t)).sort();

    assert.deepEqual(missing, [],
      '카탈로그에 없는 토픽은 브로커 기본값으로 자동생성된다 — 파티션 수가 코드 밖에서 정해진다(ADR 0035)');
  });
});
