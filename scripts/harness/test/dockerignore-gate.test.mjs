/**
 * 빌드 컨텍스트 게이트 — .dockerignore 의 "루트에만 적용되는 패턴" 함정을 막는다.
 *
 * `.dockerignore` 의 `build/` 는 **컨텍스트 루트의** build 만 뺀다. `order-service/build`,
 * `shared-common/.gradle` 같은 하위 모듈 것은 그대로 업로드된다. 이 저장소는 모듈이 20개가
 * 넘어서 그 차이가 전부다.
 *
 * 증상이 "빌드가 느리다" 였다면 이 게이트는 없었을 것이다. 실제 증상은 **빌드 실패**다.
 * Gradle 이 도는 중에(게이트 실행·병행 세션 테스트) 이미지를 빌드하면 BuildKit 이
 * `shared-common/.gradle/<버전>/executionHistory.lock` 을 읽다가 Windows 파일 잠금에 걸려
 * 컨텍스트 전송이 통째로 죽는다:
 *
 *   rpc error: read ...executionHistory.lock: The process cannot access the file
 *   because another process has locked a portion of the file
 *
 * 2026-08-19 전 서비스 재빌드가 이 이유로 반복 실패했고(settlement·operation·order),
 * glob 패턴(`**` 접두) 추가 후 컨텍스트가 162.68MB → 878kB 로 줄며 모두 통과했다. 고약한 점은
 * 산발적이라는 것이다 — Gradle 이 안 도는 순간에 빌드하면 멀쩡히 성공해서, 원인을 컨텍스트가
 * 아니라 그때그때의 모듈 코드에서 찾게 된다.
 *
 * <b>도달 증명을 무엇으로 하는가</b> — 초판은 "실제로 디스크에 있는 build/.gradle 디렉터리"를
 * 세어 0곳이면 게이트 고장으로 FAIL 했다. 의도(검사가 대상에 닿았음을 먼저 증명한다)는 맞지만
 * 증거를 <b>빌드 산출물</b>에서 취한 게 문제였다: 산출물은 로컬에만 있고 CI 는 fresh checkout 이라
 * 하나도 없다. 그래서 로컬 초록·CI 빨강으로 뒤집혔다(2026-08-19 develop cd8102ead).
 *
 * 그래서 증거를 <b>저장소가 선언한 것</b>으로 옮겼다 — settings.gradle.kts 의 모듈 목록,
 * package.json 을 가진 디렉터리. 이건 어느 체크아웃에서나 동일하므로 게이트가 환경에 흔들리지
 * 않는다. 산출물이 "지금 있는지"가 아니라 "이 저장소가 어디에 산출물을 만드는지"를 묻는다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 주석·공백을 걷어낸 패턴 목록. */
function dockerignorePatterns() {
  return readFileSync(join(REPO_ROOT, '.dockerignore'), 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
}

/** 저장소 1단계 하위 디렉터리(숨김 제외). */
function topLevelDirs() {
  return readdirSync(REPO_ROOT, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !e.name.startsWith('.'))
    .map((e) => e.name);
}

/**
 * Gradle 이 build/·.gradle/ 을 만드는 곳 — settings.gradle.kts 가 정본이다.
 * include(...) 의 모듈과 includeBuild(...) 의 합성 빌드를 모두 센다.
 */
export function gradleModules(settingsSource) {
  const names = [];
  for (const block of settingsSource.matchAll(/(?<!Build)\binclude\s*\(([^)]*)\)/gs)) {
    for (const quoted of block[1].matchAll(/"([^"]+)"/g)) names.push(quoted[1]);
  }
  for (const composite of settingsSource.matchAll(/includeBuild\s*\(\s*"([^"]+)"/g)) {
    names.push(composite[1]);
  }
  return [...new Set(names)];
}

/** npm 이 node_modules 를 만드는 곳 — package.json 이 있는 디렉터리. */
function npmPackageDirs() {
  return topLevelDirs().filter((d) => existsSync(join(REPO_ROOT, d, 'package.json')));
}

/** Maven 이 target/ 을 만드는 곳. 현재 이 저장소엔 없다(있으면 자동으로 검사 대상이 된다). */
function mavenModuleDirs() {
  return topLevelDirs().filter((d) => existsSync(join(REPO_ROOT, d, 'pom.xml')));
}

/**
 * 소유자가 있는 중첩 디렉터리 중 `**` 패턴으로 덮이지 않은 것을 돌려준다.
 * 순수 함수로 떼어 둬서 자기검증(일부러 빠뜨린 패턴을 잡는지)이 가능하다.
 */
export function missingNestedPatterns(patterns, owned) {
  const missing = [];
  for (const { dir, owners } of owned) {
    if (owners.length === 0) continue; // 이 저장소가 만들지 않는 디렉터리는 강제하지 않는다
    const covered = patterns.some((p) => p === `**/${dir}/` || p === `**/${dir}`);
    if (!covered) {
      missing.push(`${dir} — 예: ${owners[0]}/${dir} (루트 전용 '${dir}/' 만으로는 안 빠진다)`);
    }
  }
  return missing;
}

function ownedNestedDirs() {
  const settings = readFileSync(join(REPO_ROOT, 'settings.gradle.kts'), 'utf8');
  const gradle = gradleModules(settings).filter((name) => existsSync(join(REPO_ROOT, name)));
  return [
    { dir: 'build', owners: gradle },
    { dir: '.gradle', owners: gradle },
    { dir: 'node_modules', owners: npmPackageDirs() },
    { dir: 'target', owners: mavenModuleDirs() },
  ];
}

test('빌드 컨텍스트 게이트 (.dockerignore 루트 전용 패턴 함정)', async (t) => {
  await t.test('.dockerignore 는 하위 모듈의 산출물·캐시 디렉터리를 ** 패턴으로 제외한다', () => {
    const patterns = dockerignorePatterns();
    assert.ok(patterns.length > 0, '.dockerignore 에서 패턴을 하나도 읽지 못했다 — 파싱 점검 필요');

    const missing = missingNestedPatterns(patterns, ownedNestedDirs());

    assert.deepEqual(missing, [], `\n하위 모듈이 빌드 컨텍스트로 올라간다:\n  ${missing.join('\n  ')}\n`);
  });

  // 검사가 실제 대상에 닿았음을 증명한다. 증거는 산출물이 아니라 선언이라 fresh checkout 에서도 같다.
  await t.test('검사 대상을 저장소 선언에서 찾아낸다 (산출물 유무와 무관)', () => {
    const owned = ownedNestedDirs();
    const gradle = owned.find((o) => o.dir === 'build').owners;
    const node = owned.find((o) => o.dir === 'node_modules').owners;

    // ★ 총 개수를 하한으로 쓰면 서비스를 합칠 때마다(ADR 0038 18→15, 0039 15→14, 0040~0042 로 다시
    //   한 자릿수) 이 게이트가 엉뚱한 이유로 빨개진다. 실제로 그때마다 빨개졌다. 하한은 "파서가
    //   통째로 깨졌는가"만 걸러낼 만큼만 두고, 파싱 성공은 "반드시 있어야 하는 이름"으로 증명한다
    //   (coverage-scope-gate 와 같은 기준).
    assert.ok(gradle.length >= 2,
      `settings.gradle.kts 에서 찾은 Gradle 모듈이 ${gradle.length}개다 — 파서가 깨졌을 수 있다`);
    for (const must of ['order-service', 'operation-service', 'gateway-service']) {
      assert.ok(gradle.includes(must), `모듈 로스터에서 ${must} 를 놓쳤다 — 파서가 깨졌을 수 있다`);
    }
    assert.ok(gradle.includes('shared-common'),
      'includeBuild 합성 빌드(shared-common)를 놓쳤다 — 락 파일이 사는 바로 그 디렉터리다');
    assert.ok(node.length >= 1, 'package.json 을 가진 디렉터리를 하나도 찾지 못했다(frontend 기대)');
  });

  await t.test('[자기검증] 루트 전용 패턴만 있으면 잡아낸다', () => {
    const missing = missingNestedPatterns(
      ['build/', '.gradle/', 'node_modules/'],
      [{ dir: 'build', owners: ['order-service'] }, { dir: '.gradle', owners: ['shared-common'] }],
    );

    assert.equal(missing.length, 2, '루트 전용 패턴을 통과시키면 이 게이트는 존재 이유가 없다');
  });

  await t.test('[자기검증] ** 패턴이 있으면 통과시키고, 소유자 없는 항목은 강제하지 않는다', () => {
    const missing = missingNestedPatterns(
      ['**/build/', '**/.gradle'],
      [
        { dir: 'build', owners: ['order-service'] },
        { dir: '.gradle', owners: ['shared-common'] },
        { dir: 'target', owners: [] },
      ],
    );

    assert.deepEqual(missing, []);
  });
});
