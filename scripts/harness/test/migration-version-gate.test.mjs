// Flyway 버전 기준선 게이트 — 마이그레이션 4벌 전수.
//
// 막는 것: "정수 버전을 하나 더 붙이는 것".
//
// 왜 그게 위험한가. Flyway 의 버전 비교는 숫자다 — `V51` 은 51, `V20260606120000` 은
// 20260606120000 이다. 그래서 이미 타임스탬프 버전이 적용된 DB 에 정수 V51 을 새로 올리면,
// 그 파일은 "최신"이 아니라 *이미 적용된 것들보다 아래*로 끼어든다(out-of-order).
//
// 여기서 갈린다:
//   - CI 는 Testcontainers 로 매번 빈 DB 를 만든다. 빈 DB 에는 out-of-order 라는 개념이 없다
//     — 전부 처음 적용되므로 정렬만 맞으면 초록이다.
//   - 배포된 DB 에만 이력이 있다. 그래서 이 결함은 **CI 를 통과한 뒤 기동 시점에만** 드러난다.
//
// 실제로 한 번 일어났다(2026-06-16, order-service). PR 이 정수 V49/V50 을 추가했는데 그때 이미
// V20260606... 이 적용돼 있었다. order-service 는 `validate-on-migrate: false` 라서 Flyway 가
// 실패하지 않고 **조용히 건너뛰었고**, 빠진 테이블을 참조하는 새 파드가 31시간 CrashLoop 했다.
// 사후 처방으로 order-service 에 `out-of-order: true` 를 켰지만, 그건 안전망이지 예방이 아니다.
// 예방은 "정수 버전을 더 안 만드는 것" 하나뿐이고, 그건 사람 눈이 아니라 여기서 강제한다.
//
// 기준선(SEQUENTIAL_BASELINE)은 각 서비스의 정수 블록이 **닫힌 지점**이다. 이 숫자는 올라가지
// 않는다 — 올리고 싶다는 건 정수 버전을 새로 만들었다는 뜻이고, 그게 정확히 막으려는 것이다.
// 이미 배포된 DB 의 flyway_schema_history 에 남은 기록이라 rename 도 못 한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 정수 버전과 타임스탬프 버전을 가르는 값. 20000000000000 = 2000-01-01 00:00:00. */
const TIMESTAMP_FLOOR = 20_000_000_000_000n;

/**
 * 서비스별 정수 블록의 마지막 번호 — **닫힌 기준선**.
 *
 * order/operation 은 이미 타임스탬프로 넘어갔다. marketing/partner/seller 는 아직 정수만 쓰지만
 * 여기서 같이 닫는다 — 넘어간 *뒤에* 정수를 하나 더 붙이는 게 위 사고의 전개이고, 넘어가기
 * 전에 닫아 두면 그 전개 자체가 생기지 않는다.
 */
const SEQUENTIAL_BASELINE = {
  'order-service': 50, // V21 결번. 정수 블록은 49개이고 최대값이 50이다
  'operation-service': 4,
  'marketing-service': 5,
  'partner-service': 2,
  // seller-service 는 신설이라 배포된 DB 에 이력이 없다. 그래서 V1/V2 로 시작하는 것 자체는
  // 안전하다 — 위험한 건 "이력이 생긴 뒤에 정수를 더 붙이는 것"이다. 여기서 바로 닫아 두면
  // 그 시점이 오지 않는다. 다음 마이그레이션부터 타임스탬프다.
  'seller-service': 2,
};

/** `V<digits>__<summary>.sql` 에서 버전을 뽑는다. 형식이 아니면 null. */
export function parseVersion(filename) {
  const m = /^V(\d+)__[^/]+\.sql$/.exec(filename);
  return m ? BigInt(m[1]) : null;
}

/**
 * 타임스탬프 버전의 모양을 본다 — 14자리 `YYYYMMDDhhmmss`.
 *
 * 시·분·초는 검사하지 않는다. 기존 파일에 `V20260716305000`(시각 자리 30:50:00)처럼 시각을
 * 순번 tie-break 로 쓴 것들이 있고, Flyway 에게 그 자리는 그냥 숫자다 — 실제 시각일 필요가 없다.
 * 날짜 앞자리만 사람이 읽을 수 있으면 정렬 목적은 달성된다.
 */
export function timestampShapeError(version) {
  const s = String(version);
  if (s.length !== 14) return `${s} — 타임스탬프는 14자리 YYYYMMDDhhmmss 다 (현재 ${s.length}자리)`;
  const year = Number(s.slice(0, 4));
  const month = Number(s.slice(4, 6));
  const day = Number(s.slice(6, 8));
  if (year < 2020 || year > 2099) return `${s} — 연도 ${year} 가 범위 밖이다`;
  if (month < 1 || month > 12) return `${s} — 월 ${s.slice(4, 6)} 가 범위 밖이다`;
  if (day < 1 || day > 31) return `${s} — 일 ${s.slice(6, 8)} 가 범위 밖이다`;
  return null;
}

/** 한 디렉터리의 마이그레이션을 읽는다. 작업트리 기준 — 아직 커밋 안 한 새 파일도 본다. */
function readMigrations(service) {
  const dir = join(repoRoot, service, 'src', 'main', 'resources', 'db', 'migration');
  if (!existsSync(dir)) return null;
  return readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith('.sql'))
    .map((e) => ({ name: e.name, version: parseVersion(e.name) }));
}

/** db/migration 을 가진 서비스 디렉터리 전부 — 로스터가 뒤처지는 것을 잡기 위해 따로 훑는다. */
function servicesWithMigrations() {
  return readdirSync(repoRoot, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !e.name.startsWith('.'))
    .map((e) => e.name)
    .filter((name) => existsSync(join(repoRoot, name, 'src', 'main', 'resources', 'db', 'migration')))
    .sort();
}

describe('Flyway 버전 기준선', () => {
  // ── 검출기 자체 검증 — 파서가 아무것도 못 읽으면 이 게이트는 영원히 통과한다 ──
  test('파서가 실제로 동작한다', () => {
    assert.equal(parseVersion('V50__create_thing.sql'), 50n);
    assert.equal(parseVersion('V20260828210000__menu_partner_console.sql'), 20260828210000n);
    assert.equal(parseVersion('README.md'), null);
    assert.equal(parseVersion('R__repeatable.sql'), null, '반복 마이그레이션은 버전이 없다');

    assert.equal(timestampShapeError(20260828210000n), null);
    assert.equal(timestampShapeError(20260716305000n), null, '시각 자리는 tie-break 로 쓰인다');
    assert.ok(timestampShapeError(2026082821000n), '13자리는 거부한다');
    assert.ok(timestampShapeError(20261328210000n), '13월은 거부한다');
  });

  test('마이그레이션을 가진 서비스가 전부 기준선 로스터에 있다', () => {
    const missing = servicesWithMigrations().filter((s) => !(s in SEQUENTIAL_BASELINE));

    assert.deepEqual(
      missing,
      [],
      `db/migration 은 있는데 기준선이 없는 서비스 — 새 서비스가 게이트 밖에 있다. ` +
        `SEQUENTIAL_BASELINE 에 현재 정수 최대값을 등록할 것:\n  ${missing.join('\n  ')}`,
    );
  });

  for (const [service, baseline] of Object.entries(SEQUENTIAL_BASELINE)) {
    describe(service, () => {
      test('버전을 읽을 수 없는 .sql 이 없다 (게이트 공회전 방지)', () => {
        const files = readMigrations(service);
        assert.ok(files, `${service}/src/main/resources/db/migration 이 없다`);
        assert.ok(files.length > 0, '마이그레이션이 0개다 — 경로가 바뀌었는지 확인할 것');

        const unparsed = files.filter((f) => f.version === null).map((f) => f.name);
        assert.deepEqual(
          unparsed,
          [],
          `V<숫자>__<요약>.sql 로 읽히지 않는 파일 — 이 게이트도 Flyway 도 순서를 못 정한다:\n  ${unparsed.join('\n  ')}`,
        );
      });

      test(`정수 버전이 기준선 V${baseline} 을 넘지 않는다`, () => {
        const offenders = readMigrations(service)
          .filter((f) => f.version !== null && f.version < TIMESTAMP_FLOOR && f.version > BigInt(baseline))
          .map((f) => f.name);

        assert.deepEqual(
          offenders,
          [],
          `정수 버전이 기준선 V${baseline} 위에 새로 생겼다. 빈 DB(CI)에서는 통과하고 ` +
            `이력이 있는 DB(배포본)에서만 out-of-order 로 깨진다 — 타임스탬프로 rename 할 것:\n` +
            `  ${offenders.join('\n  ')}\n` +
            `  TS=$(date +%Y%m%d%H%M%S)  # V\${TS}__<요약>.sql\n` +
            `  자세한 배경: docs/db-migrations.md`,
        );
      });

      test('기준선 위쪽은 전부 타임스탬프 모양이다', () => {
        const errors = readMigrations(service)
          .filter((f) => f.version !== null && f.version >= TIMESTAMP_FLOOR)
          .map((f) => timestampShapeError(f.version))
          .filter(Boolean);

        assert.deepEqual(errors, [], `타임스탬프 모양이 아닌 버전:\n  ${errors.join('\n  ')}`);
      });

      test('같은 버전이 두 번 나오지 않는다', () => {
        const seen = new Map();
        const dupes = [];
        for (const f of readMigrations(service)) {
          if (f.version === null) continue;
          const key = String(f.version);
          if (seen.has(key)) dupes.push(`V${key}: ${seen.get(key)} / ${f.name}`);
          else seen.set(key, f.name);
        }

        assert.deepEqual(
          dupes,
          [],
          `같은 버전 두 개 — 기동 시 FlywayException 이다(병렬 PR 이 각자 같은 번호를 잡은 것):\n  ${dupes.join('\n  ')}`,
        );
      });
    });
  }
});
