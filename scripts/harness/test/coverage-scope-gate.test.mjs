// 커버리지 측정 범위 게이트 — 리포 전수.
//
// 막는 것: "커버리지 게이트가 아무것도 재지 않은 채 통과하는 상태(공전)".
//
// JaCoCo 의 jacocoTestCoverageVerification 은 측정 대상 클래스가 하나도 없으면 만들 위반이 없어
// **통과**한다. 커버리지가 높아서가 아니라 잰 게 없어서다. 빌드는 초록이고 리포트도 생성되므로
// 컴파일도 테스트도 이 상태를 잡지 못한다 — 사람이 XML 을 열어 <class> 개수를 세야만 보인다.
//
// 실측 사례(2026-08-19): deposit-service·board-service 는 리포트+검증 양쪽이, education-service 는
// 검증이 이 상태였다. CI 아티팩트의 jacocoTestReport.xml 은 클래스 0개(245바이트), HTML 은
// "No class files specified", LINE 90% 게이트는 BUILD SUCCESSFUL. 격리 워크트리에서 임계값을 1.00 으로
// 올려도 통과하는 것으로 재현했다(살아있는 모듈은 같은 조건에서 정상 FAIL).
//
// 원인은 classDirectories 의 이중 적용이다. 루트 build.gradle.kts 가 이미 classDirectories 를
// 교체했는데 모듈 빌드 스크립트가 그 위에 같은 관용구(`classDirectories.setFrom(classDirectories.files.map{...})`)를
// 한 번 더 얹으면, classDirectories.files 가 **설정 시점에 즉시 평가**되면서
//   ① build/classes 가 아직 없는 클린 빌드(=CI 의 `clean :module:build`)에서는 빈 집합이 스냅샷되고
//   ② 산출물이 남은 빌드에서도 엔트리가 디렉터리가 아니라 개별 .class 파일로 굳어(트리 루트가 파일)
//      모듈이 얹은 경로 제외 패턴이 한 건도 매치되지 않는다.
//
// 그래서 이 게이트는 두 겹이다:
//   - 정적(이 파일): 모듈 빌드 스크립트가 classDirectories 를 다시 건드리는 것을 금지 — 원인 차단.
//   - 런타임(build.gradle.kts 의 doFirst): 측정 대상이 0개면 빌드 FAIL — 다른 경로로 0개가 되어도 차단.
// 런타임 스모크가 조용히 삭제되면 정적 검사만 남아 다시 공전할 수 있으므로, 그 존재 자체도 여기서 검증한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * settings.gradle.kts 의 include(...) 블록에서 모듈명을 뽑는다.
 * 모듈 로스터 정본은 settings.gradle.kts — CI 매트릭스·harness-audit 과 같은 출처를 쓴다.
 */
export function parseIncludedModules(settingsText) {
  const start = String(settingsText).indexOf('include(');
  if (start < 0) return [];
  const end = String(settingsText).indexOf(')', start);
  if (end < 0) return [];
  const block = String(settingsText).slice(start, end);
  return [...block.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

/** 커버리지 측정 범위를 재정의하는 관용구가 있는 줄 번호들. */
export function classDirectoriesLines(buildScript) {
  return String(buildScript)
    .split('\n')
    .map((line, i) => ({ line: line.trim(), no: i + 1 }))
    .filter((e) => e.line.includes('classDirectories') && !e.line.startsWith('//'))
    .map((e) => e.no);
}

const rootBuild = readFileSync(join(repoRoot, 'build.gradle.kts'), 'utf8');
const modules = parseIncludedModules(readFileSync(join(repoRoot, 'settings.gradle.kts'), 'utf8'));

describe('커버리지 측정 범위 게이트', () => {
  test('모듈 로스터를 settings.gradle.kts 에서 읽어온다(검사가 실제로 돌았음을 증명)', () => {
    // ★ 총 개수로 "스캔이 돌았음"을 증명하지 않는다 — 서비스를 합치거나(ADR 0038 로 18→15) 쪼갤 때마다
    //   이 숫자가 낡아 게이트가 엉뚱한 이유로 빨개진다. 파싱이 성공했음은 "반드시 있어야 하는 이름"으로 증명한다.
    // shop 로스터는 3 모듈이다: order + operation + gateway. 하한은 파싱이 통째로 깨졌는가만
    // 거른다 — 개수 박제는 모듈이 늘거나 줄 때마다 게이트를 엉뚱한 이유로 빨갛게 만든다.
    assert.ok(modules.length >= 3, `모듈 로스터를 못 읽었다: ${JSON.stringify(modules)}`);
    assert.ok(modules.includes('order-service'));
    assert.ok(modules.includes('gateway-service'));
    assert.ok(modules.includes('operation-service'));
  });

  test('모듈 build.gradle.kts 는 classDirectories 를 재정의하지 않는다 (루트 단독 소유)', () => {
    const offenders = [];
    for (const moduleName of modules) {
      const path = join(repoRoot, moduleName, 'build.gradle.kts');
      if (!existsSync(path)) continue;
      const lines = classDirectoriesLines(readFileSync(path, 'utf8'));
      if (lines.length > 0) offenders.push(`${moduleName}/build.gradle.kts:${lines.join(',')}`);
    }
    assert.deepEqual(
      offenders,
      [],
      '루트가 이미 교체한 classDirectories 위에 모듈이 같은 관용구를 다시 얹으면 ' +
        '설정 시점 즉시 평가 때문에 클린 빌드(=CI)에서 측정 대상이 0개로 스냅샷되어 게이트가 공전한다. ' +
        `제외 패턴이 필요하면 루트 build.gradle.kts 의 목록에 추가할 것 → ${offenders.join(' / ')}`
    );
  });

  test('루트 게이트에 "측정 대상 0개 = FAIL" 런타임 스모크가 살아 있다', () => {
    for (const taskName of ['jacocoTestReport', 'jacocoTestCoverageVerification']) {
      assert.ok(
        rootBuild.includes(`doFirst { requireNonEmptyCoverageScope("${taskName}", classDirectories) }`),
        `build.gradle.kts 의 ${taskName} 에서 0개 측정 스모크가 사라졌다 — 대상 0개가 다시 조용히 통과한다`
      );
    }
    assert.ok(
      rootBuild.includes('fun requireNonEmptyCoverageScope('),
      'requireNonEmptyCoverageScope 정의가 사라졌다'
    );
  });

  test('shared-common(별도 빌드)도 같은 스모크를 갖고 classDirectories 는 한 번만 설정한다', () => {
    const sharedBuild = readFileSync(join(repoRoot, 'shared-common', 'build.gradle.kts'), 'utf8');
    assert.ok(
      sharedBuild.includes('fun requireNonEmptyCoverageScope('),
      'shared-common 은 composite build 라 루트 subprojects 설정을 받지 않는다 — 자체 스모크가 필요하다'
    );
    for (const taskName of ['jacocoTestReport', 'jacocoTestCoverageVerification']) {
      assert.ok(
        sharedBuild.includes(`doFirst { requireNonEmptyCoverageScope("${taskName}", classDirectories) }`),
        `shared-common 의 ${taskName} 에서 0개 측정 스모크가 사라졌다`
      );
    }
    const setFromCount = sharedBuild.split('classDirectories.setFrom(').length - 1;
    assert.equal(
      setFromCount,
      1,
      `shared-common 의 classDirectories.setFrom 은 1회여야 한다(겹쳐 적용 시 즉시 평가로 범위가 굳는다) — 실제 ${setFromCount}회`
    );
  });
});
