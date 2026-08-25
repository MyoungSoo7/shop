// 자바 서비스 로스터 게이트 — 라우팅·화면 게이트가 훑는 서비스 목록이 실제 모듈과 같은가.
//
// 막는 것: **아무것도 검사하지 않고 통과하는** 게이트.
//
// 실측 결함: lib/java-controllers.mjs 의 JAVA_SERVICES 가 settlement 에서 떼어 오기 전의
// 이름 8개를 손으로 들고 있었다(settlement-service·finance-service·company-service·
// external-data-service·education-service·board-service). 그중 6개는 이 저장소에 존재한 적이
// 없다. walk() 는 없는 디렉터리에 대해 조용히 빈 배열을 돌려주므로, 게이트는 그 6개에 대해
// 0건을 훑고 초록불을 냈다. 반대 방향이 더 위험하다 — 새 서비스를 추가하고 이 목록을 잊으면
// 그 서비스의 엔드포인트는 라우팅 게이트에도, 화면 게이트에도 잡히지 않는다.
//
// 그래서 목록을 유지보수 대상에서 빼고 settings.gradle.kts 에서 파생시켰다. 이 게이트는 그
// 파생이 유지되는지와, 파생 결과가 디스크의 실제 모듈과 일치하는지를 본다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { JAVA_SERVICES, javaServices, parseIncludedModules, controllers } from '../lib/java-controllers.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SETTINGS = readFileSync(join(REPO_ROOT, 'settings.gradle.kts'), 'utf8');

describe('자바 서비스 로스터 게이트 (없는 서비스를 훑으면 검사가 0건이 된다)', () => {
  test('로스터의 모든 서비스가 디스크에 실제로 있다', () => {
    const missing = JAVA_SERVICES.filter((s) => !existsSync(join(REPO_ROOT, s, 'src', 'main', 'java')));
    assert.deepEqual(missing, [], `없는 서비스를 훑고 있다 — 그 몫의 검사는 0건이다: ${missing}`);
  });

  test('settings.gradle.kts 의 모듈 중 gateway 를 뺀 전부가 로스터에 있다', () => {
    // 이 방향이 진짜 구멍이다 — 새 서비스를 추가하고 목록을 잊으면 조용히 검사 밖에 남는다.
    const expected = parseIncludedModules(SETTINGS).filter((m) => m !== 'gateway-service');
    assert.deepEqual([...JAVA_SERVICES].sort(), expected.sort());
  });

  test('gateway-service 는 로스터에 없다 — 라우팅하는 쪽이라 라우팅 대상이 아니다', () => {
    assert.ok(!JAVA_SERVICES.includes('gateway-service'));
  });

  test('로스터는 손으로 적은 목록이 아니라 settings.gradle.kts 에서 파생된다', () => {
    // 정본을 바꾸면 로스터가 따라와야 한다. 상수를 다시 손목록으로 되돌리면 여기서 걸린다.
    const derived = javaServices(REPO_ROOT);
    assert.deepEqual(derived, JAVA_SERVICES);

    const fromFixture = parseIncludedModules('include(\n  "alpha-service",\n  "gateway-service",\n)');
    assert.deepEqual(fromFixture, ['alpha-service', 'gateway-service']);
  });

  test('로스터로 훑은 컨트롤러가 실제로 잡힌다 (게이트 공회전 방지)', () => {
    // 개수를 박제하지 않는다 — 하한만 둔다. 0 이면 파싱이나 경로가 통째로 깨진 것이다.
    const found = controllers(REPO_ROOT);
    assert.ok(found.length > 0, '컨트롤러를 하나도 못 찾았다 — 로스터나 경로 규칙이 깨졌다');
    for (const service of JAVA_SERVICES) {
      assert.ok(
        found.some((c) => c.service === service),
        `${service} 에서 컨트롤러를 하나도 못 찾았다 — 그 서비스는 게이트 밖에 있다`,
      );
    }
  });
});
