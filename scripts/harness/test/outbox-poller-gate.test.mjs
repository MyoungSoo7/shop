// Outbox 폴러 배선 게이트 — 리포 전수.
//
// 막는 것: "Outbox 행은 쌓이는데 아무도 Kafka 로 보내지 않는 상태".
//
// Outbox 패턴은 두 조각이다 — ① DB 트랜잭션 안에서 `outbox_events` INSERT, ② 폴러가 PENDING 행을
// 집어 Kafka 발행. ①만 있으면 **컴파일도 되고 테스트도 통과하고 API 도 200 을 준다.** 이벤트만
// 영원히 나가지 않는다. 하류가 조용히 굶는데 상류에는 아무 증상이 없다.
//
// 실측 사례(2026-08-22): education-service 가 정확히 이 상태였다. `OutboxBackedEducationEventPublisher`
// 는 정상 동작해 PENDING 행을 넣지만 spring-kafka 의존·bootstrap 설정·폴러 빈이 전부 없다. 스캔이
// `github.lms.lemuel.education` 으로 한정돼 shared-common 의 `OutboxPublisherScheduler`(@Component)가
// 붙지 않았고, `PersistenceConfig` 도 이를 들이지 않았다. 전체 스캔인 서비스들은 우연히 무사했다.
// 결과적으로 `lemuel.education.course_published` 는 카탈로그에 소유 토픽으로 등재돼 있으면서 한 번도
// 생산된 적이 없다.
//
// 왜 "주석이 아니라 애노테이션"인가: 첫 검출기는 소스 전문을 정규식으로 훑어 company-service 를
// 통과시켰는데, 근거가 **자바독 주석의 'OutboxPublisherScheduler 가 발행한다'** 라는 문장이었다.
// 실제 배선은 `PersistenceConfig` 의 `@ComponentScan("github.lms.lemuel.common.outbox")` 였다 —
// 답은 맞고 이유는 틀렸다. 이유가 틀린 검출기는 다음에 틀린 답을 낸다. 그래서 주석을 걷어내고
// 빈 도달 경로(전체 스캔 · scanBasePackages · @ComponentScan · @Import)만 인정한다.
//
// 정본: docs/plan/prd-seed-drift-audit.md §6 · docs/plan/prd/education-service.md G-1.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 폴러 빈이 사는 패키지·클래스 — shared-common 의 @Component 라 스캔에 잡혀야 한다. */
const POLLER_PKG = 'github.lms.lemuel.common.outbox.application.service';
const POLLER_CLASS = 'OutboxPublisherScheduler';
/** 주기 실행을 거는 클래스 — 여기 붙은 @Scheduled 는 @EnableScheduling 없이는 동작하지 않는다. */
const TRIGGER_CLASS = 'OutboxPollingTrigger';
/** shared-common 에서 @EnableScheduling 을 들고 있는 설정 — 전체 스캔 서비스는 이걸로 켜진다. */
const SCHEDULING_PKG = 'github.lms.lemuel.common.config.elasticsearch';
const SCHEDULING_CLASS = 'AsyncConfig';

/**
 * 폴러가 배선되지 않았음이 **확인된** 서비스와 그 사유.
 *
 * 이것은 설계 결정이 아니라 **기록된 결함**이다 — 여기 등록하는 것은 "괜찮다"가 아니라 "알고
 * 있고 아직 못 고쳤다"는 뜻이다. 고치면 이 항목을 반드시 지워야 한다(아래 죽은 항목 검사가 강제).
 * 새 서비스를 여기 넣어 게이트를 통과시키는 것은 우회다.
 */
const KNOWN_UNWIRED = new Map([]);

/** 블록·라인 주석 제거 — "주석이 배선을 대신하지 못한다". */
export function stripComments(src) {
  return String(src).replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

const covers = (base, pkg) => pkg === base || pkg.startsWith(`${base}.`);

/**
 * shared-common 의 어떤 빈이 이 소스를 통해 컨텍스트에 들어오는 경로를 반환한다(없으면 빈 배열).
 *
 * 근거를 문자열로 남기는 이유 — 통과했을 때 **무엇 덕분에 통과했는지**가 보여야 한다. 첫 검출기는
 * 소스 전문 정규식이라 자바독 언급만으로 통과시켰고, 그때도 "통과"라는 결과만 남았다.
 *
 * @param targetPkg   대상 빈이 사는 패키지(스캔이 이 패키지를 덮어야 한다)
 * @param targetClass 대상 클래스명(@Import 로 직접 들일 때 쓰는 이름)
 */
export function beanGrants(rawSrc, targetPkg, targetClasses) {
  const src = stripComments(rawSrc);
  const grants = [];

  const boot = src.match(/@SpringBootApplication(\s*\(([\s\S]*?)\))?/);
  if (boot) {
    const args = boot[2] || '';
    if (!/scanBasePackages/.test(args)) grants.push('@SpringBootApplication 전체 스캔');
    else {
      for (const m of args.matchAll(/"([^"]+)"/g)) {
        if (covers(m[1], targetPkg)) grants.push(`scanBasePackages="${m[1]}"`);
      }
    }
  }
  for (const m of src.matchAll(/@ComponentScan\s*\(([\s\S]*?)\)/g)) {
    for (const s of m[1].matchAll(/"([^"]+)"/g)) {
      if (covers(s[1], targetPkg)) grants.push(`@ComponentScan("${s[1]}")`);
    }
  }
  for (const m of src.matchAll(/@Import\s*\(([\s\S]*?)\)/g)) {
    for (const cls of targetClasses) {
      if (m[1].includes(cls)) grants.push(`@Import(${cls})`);
    }
  }
  return grants;
}

/** 폴러 빈 도달 경로. */
export function pollerGrants(rawSrc) {
  return beanGrants(rawSrc, POLLER_PKG, [POLLER_CLASS, TRIGGER_CLASS]);
}

/**
 * 주기 실행이 켜져 있는가 — {@code @EnableScheduling} 이 컨텍스트에 도달하는가.
 *
 * ★ 이 축이 왜 따로 필요한가: {@code @Scheduled} 는 {@code OutboxPollingTrigger} 에 붙어 있고,
 * {@code @EnableScheduling} 이 없으면 그 빈은 **등록만 된 채 영영 돌지 않는다.** 빈 도달 경로만
 * 검사하면 이 상태를 GREEN 으로 읽는다 — education 을 배선하다 실제로 밟은 함정이다.
 * 기동 로그에도 API 응답에도 증상이 없다.
 *
 * ★ 자기 소스의 애노테이션만 보면 안 된다: settlement 등 전체 스캔 서비스는 shared-common 의
 * {@code AsyncConfig}(@EnableScheduling) 를 스캔으로 가져간다. 그 경로를 모르면 실제로 발행 중인
 * 서비스를 미배선으로 오탐한다 — 실측으로 밟았다.
 */
export function schedulingGrants(rawSrc) {
  const own = /@EnableScheduling\b/.test(stripComments(rawSrc)) ? ['@EnableScheduling'] : [];
  return [...own, ...beanGrants(rawSrc, SCHEDULING_PKG, [SCHEDULING_CLASS])];
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkJava(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** Outbox 에 쓰는 서비스만 대상 — 발행하지 않는 서비스에 폴러를 요구할 이유가 없다. */
function outboxProducers() {
  const result = [];
  for (const entry of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith('-service')) continue;
    const files = walkJava(join(REPO_ROOT, entry.name, 'src', 'main'));
    if (!files.length) continue;

    let writesOutbox = false;
    const schedulingReasons = [];
    const grants = [];
    for (const f of files) {
      const raw = readFileSync(f, 'utf8');
      if (stripComments(raw).includes('SaveOutboxEventPort')) writesOutbox = true;
      schedulingReasons.push(...schedulingGrants(raw));
      grants.push(...pollerGrants(raw));
    }
    if (!writesOutbox) continue;

    const gradlePath = join(REPO_ROOT, entry.name, 'build.gradle.kts');
    const gradle = existsSync(gradlePath) ? readFileSync(gradlePath, 'utf8') : '';
    const ymlPath = join(REPO_ROOT, entry.name, 'src', 'main', 'resources', 'application.yml');
    const yml = existsSync(ymlPath) ? readFileSync(ymlPath, 'utf8') : '';

    result.push({
      name: entry.name,
      grants,
      scheduling: schedulingReasons,
      hasKafkaDependency: /spring-kafka|spring-boot-starter-kafka/.test(gradle),
      hasBootstrapConfig: /bootstrap-servers/.test(yml),
    });
  }
  return result.sort((a, b) => a.name.localeCompare(b.name));
}

/** 네 축이 모두 서야 이벤트가 실제로 나간다 — 하나라도 빠지면 조용히 안 나간다. */
export function unwiredAxes(svc) {
  const axes = [];
  if (!svc.grants.length) axes.push('폴러 빈 도달 경로 없음');
  if (!svc.scheduling.length) axes.push('@EnableScheduling 도달 경로 없음(폴러가 등록만 되고 돌지 않는다)');
  if (!svc.hasKafkaDependency) axes.push('spring-kafka 의존 없음');
  if (!svc.hasBootstrapConfig) axes.push('bootstrap-servers 설정 없음');
  return axes;
}

describe('Outbox 폴러 배선', () => {
  const producers = outboxProducers();

  // ── 검출기 자체 검증 — 주석을 배선으로 읽으면 게이트가 영원히 통과한다 ──
  test('[자기검증] 자바독에 적힌 폴러 이름을 배선으로 읽지 않는다', () => {
    const src = [
      '/**',
      ` * shared-common 의 ${POLLER_CLASS} 가 이 행을 집어 발행한다.`,
      ' */',
      '@Configuration',
      'public class Foo { }',
    ].join('\n');

    assert.deepEqual(pollerGrants(src), []);
  });

  test('[자기검증] 라인 주석의 @Import 도 배선이 아니다', () => {
    assert.deepEqual(pollerGrants(`// @Import(${POLLER_CLASS}.class)\n@Configuration class A {}`), []);
  });

  test('[자기검증] 실제 배선 4경로를 모두 인정한다', () => {
    assert.deepEqual(pollerGrants('@SpringBootApplication\nclass A {}'),
      ['@SpringBootApplication 전체 스캔']);
    assert.deepEqual(pollerGrants('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.common")\nclass A {}'),
      ['scanBasePackages="github.lms.lemuel.common"']);
    assert.deepEqual(pollerGrants('@ComponentScan(basePackages = "github.lms.lemuel.common.outbox")\nclass A {}'),
      ['@ComponentScan("github.lms.lemuel.common.outbox")']);
    assert.deepEqual(pollerGrants(`@Import({${POLLER_CLASS}.class, Other.class})\nclass A {}`),
      [`@Import(${POLLER_CLASS})`]);
  });

  test('[자기검증] @EnableScheduling 을 주석과 구분한다', () => {
    assert.deepEqual(schedulingGrants('@Configuration\n@EnableScheduling\nclass A {}'), ['@EnableScheduling']);
    assert.deepEqual(schedulingGrants('// @EnableScheduling 은 폴러를 켠다\nclass A {}'), []);
    assert.deepEqual(schedulingGrants('/** @EnableScheduling 참조 */\nclass A {}'), []);
    assert.deepEqual(schedulingGrants('class A {}'), []);
  });

  test('[자기검증] 전체 스캔은 shared-common AsyncConfig 로 스케줄링을 켠다', () => {
    // settlement 등은 자기 소스에 @EnableScheduling 이 없다 — 전체 스캔이
    // github.lms.lemuel.common.config.elasticsearch.AsyncConfig 를 가져가서 켜진다.
    // 이 경로를 모르면 실제로 발행 중인 서비스를 미배선으로 오탐한다.
    assert.deepEqual(schedulingGrants('@SpringBootApplication\nclass A {}'),
      ['@SpringBootApplication 전체 스캔']);
    assert.deepEqual(schedulingGrants(`@Import(${SCHEDULING_CLASS}.class)\nclass A {}`),
      [`@Import(${SCHEDULING_CLASS})`]);
    // 자기 패키지만 스캔하면 켜지지 않는다.
    assert.deepEqual(
      schedulingGrants('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.education")\nclass A {}'), []);
  });

  test('[자기검증] 폴러 패키지를 덮지 않는 스캔은 인정하지 않는다', () => {
    assert.deepEqual(pollerGrants('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.education")\nclass A {}'), []);
    // 접두사가 우연히 겹치는 패키지를 덮는 것으로 오인하지 않는다.
    assert.deepEqual(pollerGrants('@ComponentScan(basePackages = "github.lms.lemuel.commonx")\nclass A {}'), []);
  });

  // ── 스캔 자체 검증 ──
  test('스캔이 실제로 Outbox 발행 서비스에 도달했다', () => {
    // ★ 개수 하한으로 "검사가 돌았음"을 증명하지 않는다 — 그 숫자는 모듈을 합칠 때마다 낡아
    //   게이트가 엉뚱한 이유로 빨개진다. 도달 증명은 이름으로 한다.
    assert.ok(producers.length >= 2,
      `Outbox 발행 서비스 수집이 적다(${producers.length}) — 검출기가 깨졌을 수 있다`);
    for (const must of ['order-service', 'operation-service']) {
      assert.ok(producers.some((p) => p.name === must),
        `${must} 가 발행 서비스로 잡히지 않았다 — 검출기가 깨졌을 수 있다`);
    }
  });

  // ── 본 검사 ──
  test('Outbox 에 쓰는 서비스는 폴러·Kafka 의존·bootstrap 설정을 갖춘다', () => {
    const unwired = producers
      .filter((p) => unwiredAxes(p).length > 0 && !KNOWN_UNWIRED.has(p.name))
      .map((p) => `${p.name}: ${unwiredAxes(p).join(' / ')}`);

    assert.deepEqual(unwired, [],
      'Outbox 행은 쌓이는데 발행되지 않는 상태입니다. 폴러를 배선하세요 — '
      + `전체 스캔이 아니면 @Import(${POLLER_CLASS}.class) 또는 `
      + `@ComponentScan("${POLLER_PKG.replace('.application.service', '')}") 가 필요합니다.`);
  });

  // ── allowlist 위생 ──
  test('KNOWN_UNWIRED 에 죽은 항목이 없다 — 고쳤으면 지워야 한다', () => {
    const fixed = [...KNOWN_UNWIRED.keys()].filter((name) => {
      const svc = producers.find((p) => p.name === name);
      return !svc || unwiredAxes(svc).length === 0;
    });

    assert.deepEqual(fixed, [],
      '배선이 끝났거나 서비스가 사라졌는데 KNOWN_UNWIRED 에 남아 있습니다 — 항목을 지우세요');
  });

  test('KNOWN_UNWIRED 의 모든 항목에 사유가 있다', () => {
    const bare = [...KNOWN_UNWIRED.entries()].filter(([, why]) => !why || why.trim().length < 20);
    assert.deepEqual(bare.map(([n]) => n), [],
      '사유 없는 면제는 게이트를 끄는 것과 같습니다');
  });
});
