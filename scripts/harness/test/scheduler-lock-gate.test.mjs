// ShedLock 락 이름 유일성 게이트 — 리포 전수.
//
// 같은 이름을 두 스케줄러가 공유하면 락 보유 기간(lockAtLeastFor/lockAtMostFor) 동안 나머지가
// **조용히 스킵**된다. 예외도 로그도 없이 "그날 하나만 실행"되므로 컴파일도 CI 도 잡지 못하고,
// 운영에서 "왜 이 배치만 안 돌았지"로 뒤늦게 드러난다.
//
// 실제 사례(2026-08-06 ofDentis 레거시 분석): 한 스케줄러 클래스가 name="cancelMarketDeposit" 을
// 3개 메서드에, name="cancelEduDeposit" 을 2개 메서드에 붙이고 lockAtLeastFor 를 23~24시간으로 둬서
// 매일 그중 하나만 실행되고 있었다. 우리 트리는 현재 청정하며, 이 게이트가 그 상태를 잠근다.
//
// 같은 이름을 의도적으로 공유해 상호배제를 노리는 설계가 필요해지면 ALLOWED_SHARED 에 근거와 함께
// 등록한다 — 침묵하는 스킵이 아니라 명시적 선언이 되도록.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 의도적으로 이름을 공유하는 쌍(현재 없음) — 추가 시 반드시 근거 주석을 남긴다. */
const ALLOWED_SHARED = new Set();

const LOCK_NAME = /@SchedulerLock\s*\([^)]*?name\s*=\s*"([^"]+)"/gs;

/** 소스에서 (락 이름, 위치) 목록을 뽑는다. 주석 처리된 선언은 세지 않는다. */
export function collectLockNames(files, read) {
  const found = [];
  for (const file of files) {
    const content = read(file);
    // 라인 주석으로 죽여둔 선언 제외 — 살아있는 배치만 대상.
    const live = content.split(/\r?\n/).filter((line) => !/^\s*(\/\/|\*)/.test(line)).join('\n');
    for (const match of live.matchAll(LOCK_NAME)) {
      found.push({ name: match[1], file });
    }
  }
  return found;
}

/** 같은 이름을 2회 이상 쓰는 항목만 돌려준다. */
export function findDuplicateLockNames(entries, allowed = ALLOWED_SHARED) {
  const byName = new Map();
  for (const { name, file } of entries) {
    if (!byName.has(name)) byName.set(name, []);
    byName.get(name).push(file);
  }
  return [...byName.entries()]
    .filter(([name, files]) => files.length > 1 && !allowed.has(name))
    .map(([name, files]) => `${name} → ${files.join(', ')}`);
}

describe('ShedLock 락 이름 유일성', () => {
  // 검출기 자체가 동작하는지 먼저 못박는다 — 정규식이 아무것도 못 잡으면 게이트는 항상 통과한다.
  test('중복을 실제로 검출한다', () => {
    const entries = [
      { name: 'cancelMarketDeposit', file: 'A.java' },
      { name: 'cancelMarketDeposit', file: 'B.java' },
      { name: 'unique-one', file: 'C.java' },
    ];

    assert.deepEqual(findDuplicateLockNames(entries),
      ['cancelMarketDeposit → A.java, B.java']);
  });

  test('같은 파일 안의 중복도 검출한다(ofDentis 실제 패턴)', () => {
    const entries = [
      { name: 'cancelEduDeposit', file: 'PointScheduler.java' },
      { name: 'cancelEduDeposit', file: 'PointScheduler.java' },
    ];

    assert.equal(findDuplicateLockNames(entries).length, 1);
  });

  test('허용 목록에 등록된 이름은 통과시킨다', () => {
    const entries = [
      { name: 'shared-on-purpose', file: 'A.java' },
      { name: 'shared-on-purpose', file: 'B.java' },
    ];

    assert.deepEqual(findDuplicateLockNames(entries, new Set(['shared-on-purpose'])), []);
  });

  test('주석 처리된 선언은 세지 않는다', () => {
    const source = [
      '    // @SchedulerLock(name = "dead-one")',
      '    @SchedulerLock(name = "live-one", lockAtMostFor = "PT1H")',
    ].join('\n');

    assert.deepEqual(collectLockNames(['X.java'], () => source),
      [{ name: 'live-one', file: 'X.java' }]);
  });

  test('여러 줄에 걸친 선언도 인식한다', () => {
    const source = [
      '    @SchedulerLock(',
      '            name = "wrapped-one",',
      '            lockAtMostFor = "PT1H")',
    ].join('\n');

    assert.deepEqual(collectLockNames(['X.java'], () => source).map((e) => e.name),
      ['wrapped-one']);
  });

  // ── 리포 전수 ──
  const trackedJava = execFileSync('git', ['ls-files', '*.java'], { cwd: repoRoot })
    .toString('utf8').split('\n').filter(Boolean);
  const entries = collectLockNames(trackedJava, (f) => readFileSync(join(repoRoot, f), 'utf8'));

  test('스캔이 실제로 락 선언을 수집한다(빈 스캔으로 통과하는 것 방지)', () => {
    assert.ok(entries.length >= 5,
      `@SchedulerLock 수집 결과가 비정상적으로 적다(${entries.length}) — 정규식이 깨졌을 수 있다`);
  });

  test('리포 전체에서 락 이름이 유일하다', () => {
    assert.deepEqual(findDuplicateLockNames(entries), [],
      '락 이름이 겹치면 겹친 배치들이 서로를 굶긴다(그날 하나만 실행)');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 락 인프라가 있는데 락을 안 쓰는 스케줄러 게이트 (2026-08-25 신설)
//
// 막는 것: "이 모듈은 단일 인스턴스라 락이 필요 없다"는 **옛 전제를 안고 코어로 흡수된 스케줄러".
//
// 실측 사례. 통합 사이클(ADR 0038~0044)에서 위성 서비스들이 코어로 흡수되면서, 아래 문장을 단 스케줄러
// 6개가 함께 옮겨 왔다:
//
//     "단일 인스턴스 위성 서비스라 노드 경합이 없어 ShedLock 없이 안전하다
//      (다중 replicas 로 확장 시 settlement 처럼 @SchedulerLock 도입 필요)"
//
// 그 전제는 흡수되는 순간 거짓이 됐는데 **문장만 코드와 함께 옮겨 다녔다.** 특히 finance-service 는
// 자체 shedlock 테이블을 갖고 형제 스케줄러 11개가 전부 락을 쓰는데, 흡수로 들어온 3개(파티션 유지보수·
// 일일 스크리닝·연체 스캔)만 락 없이 돌고 있었다 — 배포 레플리카 수와 무관한 모듈 내부 모순이다.
//
// ★ 왜 사람 눈과 grep 이 놓치는가. `grep -c '@SchedulerLock'` 을 하면 이 파일들이 **1을 반환한다.**
//   그 1은 애노테이션이 아니라 *"@SchedulerLock 도입 필요"라고 적힌 주석*이다. 락이 없다고 말하는
//   문장이 락이 있다는 신호로 읽힌다. 그래서 이 게이트는 반드시 **줄머리**(`^\s*@SchedulerLock`)로 본다.
//
// 판정 기준을 "레플리카 수"가 아니라 "**락 테이블 유무**"로 잡은 이유: 레플리카 수는 이 저장소 밖
// (helm-deploy)에 있어 검증할 수 없다. 반면 shedlock 테이블 마이그레이션은 코드에 있고, 그것이 있다는
// 것은 "이 모듈은 다중 인스턴스를 전제한다"는 팀의 결정이 이미 내려졌다는 뜻이다. 테이블이 없는 모듈은
// 애초에 락을 붙일 수단이 없으므로 이 게이트의 대상이 아니다 — 대신 그 전제를 주석에 명시하도록 했다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 락을 붙이지 않는 것이 의도인 스케줄러와 그 근거. 등록은 면제가 아니라 **선언**이다.
 *
 * 판정 기준은 하나다 — "파드마다 돌아야 하는가, 아니면 한 파드만 돌아야 하는가".
 * 메트릭 갱신처럼 **각 파드가 자기 것을 내보내야 하는** 작업은 락을 걸면 오히려 다른 파드의 게이지가
 * 낡은 채 남는다. 반대로 상태를 바꾸는 배치는 한 파드만 돌아야 한다.
 */
const LOCKLESS_BY_DESIGN = new Map([
  ['order-service/src/main/java/github/lms/lemuel/payment/adapter/in/scheduler/RefundRetryScheduler.java',
    '다중 인스턴스 안전이 **명시적 설계**다 — 결제 행 비관 락 + 멱등 키(동일 키 COMPLETED 면 no-op)로 '
    + '두 파드가 같은 건을 집어도 이중 환불되지 않는다(클래스 javadoc). 락을 걸면 재시도 처리량만 줄어든다'],
]);

/** shedlock 테이블 마이그레이션을 가진 모듈 = 다중 인스턴스를 전제한 모듈. */
export function modulesWithLockTable(root, listDir = readdirSync, read = readFileSync) {
  const mods = listDir(root, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name.endsWith('-service'))
    .map((e) => e.name);
  return mods.filter((m) => {
    const dir = join(root, m, 'src', 'main', 'resources', 'db', 'migration');
    if (!existsSync(dir)) return false;
    return listDir(dir).some((f) =>
      String(f).endsWith('.sql') && /shedlock/i.test(read(join(dir, f), 'utf8')));
  });
}

/**
 * 락 없는 @Scheduled 를 가진 파일. **줄머리 매칭**이 핵심 — 주석 속 "@SchedulerLock 도입 필요" 를
 * 락으로 오인하면 이 게이트는 정확히 잡아야 할 대상을 통과시킨다(신설 동기 그 자체).
 */
export function locklessScheduled(files, read) {
  return files.filter((f) => {
    const c = read(f);
    return /^\s*@Scheduled\b/m.test(c) && !/^\s*@SchedulerLock\b/m.test(c);
  });
}

/** 작업트리 기준 .java 수집 — git ls-files 는 병행 세션의 미추적 파일을 못 본다. */
function javaFiles(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, e.name);
    if (e.isDirectory()) javaFiles(full, out);
    else if (e.name.endsWith('.java')) out.push(full);
  }
  return out;
}

describe('락 인프라가 있는 모듈의 @Scheduled 는 @SchedulerLock 을 단다', () => {
  // ── 검출기 자기검증 — 이 게이트가 태어난 이유를 테스트로 못박는다 ──
  test('★ 주석 속 "@SchedulerLock 도입 필요" 를 락으로 세지 않는다', () => {
    const source = [
      '    /**',
      '     * 단일 인스턴스라 안전하다(다중 replicas 확장 시 @SchedulerLock 도입 필요).',
      '     */',
      '    @Scheduled(cron = "0 30 2 1 * *")',
      '    public void ensureMonthly() {}',
    ].join('\n');

    assert.deepEqual(locklessScheduled(['X.java'], () => source), ['X.java'],
      '주석을 락으로 오인하면 이 게이트가 잡아야 할 바로 그 형태를 통과시킨다');
  });

  test('실제 애노테이션이 있으면 통과시킨다', () => {
    const source = [
      '    @Scheduled(cron = "0 30 2 1 * *")',
      '    @SchedulerLock(name = "x-monthly", lockAtMostFor = "PT10M")',
      '    public void ensureMonthly() {}',
    ].join('\n');

    assert.deepEqual(locklessScheduled(['X.java'], () => source), []);
  });

  test('@Scheduled 가 없는 파일은 대상이 아니다', () => {
    assert.deepEqual(locklessScheduled(['X.java'], () => 'class X { void run() {} }'), []);
  });

  // ── 리포 전수 ──
  const lockModules = modulesWithLockTable(repoRoot);

  test('스캔이 실제로 락 인프라 모듈에 도달했다', () => {
    // 하한은 "스캔이 깨졌는가"만 본다. 도달 증거는 아래 이름 단언이 담당한다.
    assert.ok(lockModules.length >= 1,
      `shedlock 테이블을 가진 모듈을 하나도 못 찾았다(${lockModules.length}) — 스캔이 깨졌을 수 있다`);
    assert.ok(lockModules.includes('order-service'),
      `order-service 가 락 인프라 모듈로 잡히지 않았다(찾은 것: ${lockModules.join(', ')})`);
  });

  test('락 인프라 모듈에 락 없는 @Scheduled 가 없다', () => {
    const offenders = lockModules.flatMap((m) =>
      locklessScheduled(javaFiles(join(repoRoot, m, 'src', 'main', 'java')),
        (f) => readFileSync(f, 'utf8'))
        .map((f) => f.slice(repoRoot.length + 1).split('\\').join('/')))
      .filter((f) => !LOCKLESS_BY_DESIGN.has(f))
      .sort();

    assert.deepEqual(offenders, [],
      '이 모듈은 shedlock 테이블을 가진다 = 다중 인스턴스를 전제한다. 락 없는 @Scheduled 는 파드 수만큼 '
      + '중복 실행된다. @SchedulerLock 을 달거나, 파드마다 돌아야 하는 작업이라면 이 게이트의 '
      + 'LOCKLESS_BY_DESIGN 에 근거와 함께 등록하세요.');
  });

  test('LOCKLESS_BY_DESIGN 에 죽은 항목이 없다', () => {
    const stale = [...LOCKLESS_BY_DESIGN.keys()].filter((f) => !existsSync(join(repoRoot, f)));
    assert.deepEqual(stale, [], '이미 사라진 파일이 면제 목록에 남아 있습니다');
  });
});
