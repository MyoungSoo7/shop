/**
 * Dockerfile 모듈 로스터 게이트 — settings.gradle.kts 가 include 한 모듈이 빌드 컨텍스트에
 * 복사되는지 확인한다.
 *
 * <b>왜 있나</b> — 2026-08-28 실측. `marketing-service` 를 settings.gradle.kts 에 넣고 루트
 * Dockerfile 의 COPY 목록에 넣지 않았다. 그러자 marketing 이미지 하나가 아니라
 * **네 모듈의 이미지 빌드가 전부** 이렇게 죽었다:
 *
 *   > Configuring project ':marketing-service' without an existing directory is not allowed.
 *     The configured projectDirectory '/workspace/marketing-service' does not exist
 *   BUILD FAILED
 *
 * Gradle 은 설정 단계에서 전 모듈의 프로젝트 디렉터리를 확인한다. 그래서 빠진 모듈이
 * `:${MODULE}` 로 지목되지 않아도 설정이 통째로 실패하고, 그 Dockerfile 로 만드는 모든
 * 이미지가 같이 무너진다. 한 줄 빠뜨린 대가가 그 줄 하나가 아니다.
 *
 * <b>왜 다른 게이트가 못 잡았나</b> — 컴파일도 테스트도 전부 초록이었다. `./gradlew` 는
 * 저장소 루트에서 돌아 모든 디렉터리를 보므로 이 결함이 존재하지 않는다. 오직 **Docker
 * 빌드 컨텍스트 안**에서만, 즉 파일이 선택적으로 복사된 뒤에만 드러난다.
 *
 * <b>판정 방식</b> — 로스터를 손으로 적지 않는다. settings.gradle.kts 를 정본으로 삼고
 * (java-services-roster-gate 와 같은 파서를 공유한다) Dockerfile 의 COPY 대상과 대조한다.
 * 목록을 여기 베껴 두면 그 사본이 다음번에 똑같이 뒤처진다.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseIncludedModules } from '../lib/java-controllers.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const DOCKERFILE = readFileSync(join(REPO_ROOT, 'Dockerfile'), 'utf8');
const MODULES = parseIncludedModules(readFileSync(join(REPO_ROOT, 'settings.gradle.kts'), 'utf8'));

/** `COPY <src> <dst>` 의 src 만 모은다(`--mount` 옵션이 붙은 RUN 은 대상이 아니다). */
const copySources = () =>
  [...DOCKERFILE.matchAll(/^COPY\s+(?!--from)(.+)$/gm)]
    .flatMap((m) => m[1].trim().split(/\s+/).slice(0, -1));

describe('Dockerfile 모듈 로스터 게이트', () => {
  test('대조 대상이 비어 있지 않다 — 0개를 대조하고 통과하면 게이트가 아니다', () => {
    assert.ok(MODULES.length > 0, 'settings.gradle.kts 에서 모듈을 한 개도 못 읽었다');
    assert.ok(copySources().length > 0, 'Dockerfile 에서 COPY 를 한 줄도 못 읽었다');
  });

  test('include 된 모든 모듈의 소스가 빌드 컨텍스트로 복사된다', () => {
    const copied = new Set(copySources());
    // 하나라도 빠지면 그 모듈만이 아니라 이 Dockerfile 로 만드는 이미지 전부가 실패한다.
    const missing = MODULES.filter((m) => !copied.has(m));

    assert.deepEqual(missing, []);
  });

  test('include 된 모든 모듈의 build.gradle.kts 가 의존성 캐싱 단계에도 복사된다', () => {
    const copied = new Set(copySources());
    // 이 단계가 빠져도 최종 결과는 같지만, 캐싱 레이어의 설정이 먼저 실패해 캐시가 통째로
    // 무의미해진다(`|| true` 로 가려져 조용히 느려진다).
    const missing = MODULES.filter((m) => !copied.has(`${m}/build.gradle.kts`));

    assert.deepEqual(missing, []);
  });
});
