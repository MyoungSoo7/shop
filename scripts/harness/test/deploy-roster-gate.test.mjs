/**
 * 배포 로스터 게이트 — 새 서비스가 "빌드는 되는데 아무 데도 안 나가는" 상태로 남지 않게 한다.
 *
 * <b>왜 있나</b> — 2026-08-28 실측. marketing-service 는 코드도 테스트도 컨테이너 정의도
 * 다 갖춘 채로 병합돼 있었는데, 배포 주변의 목록 세 개에서 빠져 있었다. 셋 다 조용했다:
 *
 *   1. k8s/buildkit/build.sh 의 MAPPING — `--all` 이 백엔드 3개만 굽고 marketing 을 건너뛴다.
 *      실패가 아니라 <b>누락</b>이라 출력만 봐서는 정상과 구분되지 않는다. 그 파일 자기 주석이
 *      "ci.yml 의 mapping 과 반드시 일치해야 한다" 고 경고해 둔 자리인데, 지킬 장치가 없었다.
 *   2. monitoring/prometheus.yml 의 스크레이프 대상 — 앱은 /actuator/prometheus 로 지표를
 *      전부 내보내는데 아무도 긁어가지 않았다. 여기서 중요한 건 up{job=...} 이 0 이 되는 게
 *      아니라 <b>시계열 자체가 없다</b>는 점이다. 없는 벡터를 == 0 으로 보는 알람 규칙은
 *      영원히 발화하지 않는다. "계측했다" 와 "감시된다" 는 다른 말이다.
 *   3. scripts/verify.sh 의 MODULES — 다른 저장소에서 베껴 온 14개가 그대로 남아 있었다.
 *      지금은 settings.gradle.kts 에서 읽는다. 이 게이트는 그게 다시 손으로 적힌 목록으로
 *      되돌아가지 않는지를 본다.
 *
 * <b>판정 방식</b> — 로스터를 여기 베껴 두지 않는다. settings.gradle.kts 를 정본으로 삼고
 * (dockerfile-module-roster-gate·java-services-roster-gate 와 같은 파서를 공유한다) 각
 * 배포 정의가 그 목록을 덮는지 대조한다. 목록을 여기 적어 두면 이 사본이 다음번에 똑같이
 * 뒤처진다 — 이 게이트가 막으려는 바로 그 일이다.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseIncludedModules } from '../lib/java-controllers.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p) => readFileSync(join(REPO_ROOT, p), 'utf8');

const MODULES = parseIncludedModules(read('settings.gradle.kts'));
const CI = read('.github/workflows/ci.yml');
const BUILD_SH = read('k8s/buildkit/build.sh');
const PROMETHEUS = read('monitoring/prometheus.yml');
const VERIFY_SH = read('scripts/verify.sh');

/** ci.yml 의 `mapping='{ "module":"suffix", ... }'` 블록에서 module→suffix 를 읽는다. */
function ciImageMapping() {
  const block = CI.match(/mapping='(\{[\s\S]*?\})'/);
  if (!block) return null;
  return Object.fromEntries(
    [...block[1].matchAll(/"([a-z0-9-]+)"\s*:\s*"([a-z0-9-]*)"/g)].map((m) => [m[1], m[2]]),
  );
}

/** build.sh 의 MAPPING="..." 히어독에서 module=suffix 를 읽는다. */
function buildShMapping() {
  const block = BUILD_SH.match(/MAPPING="\n([\s\S]*?)"/);
  if (!block) return null;
  return Object.fromEntries(
    block[1]
      .split('\n')
      .filter((line) => line.includes('='))
      .map((line) => {
        const [module, suffix] = line.trim().split('=');
        return [module, suffix ?? ''];
      }),
  );
}

/** prometheus.yml 의 static_configs targets 에서 호스트명만 모은다. */
const scrapeTargets = () =>
  new Set([...PROMETHEUS.matchAll(/targets:\s*\['([^':]+):\d+'\]/g)].map((m) => m[1]));

describe('배포 로스터 게이트', () => {
  test('대조 대상이 비어 있지 않다 — 0개를 대조하고 통과하면 게이트가 아니다', () => {
    assert.ok(MODULES.length > 0, 'settings.gradle.kts 에서 모듈을 한 개도 못 읽었다');
    assert.ok(ciImageMapping(), 'ci.yml 에서 이미지 매핑 블록을 못 찾았다');
    assert.ok(buildShMapping(), 'build.sh 에서 MAPPING 블록을 못 찾았다');
    assert.ok(scrapeTargets().size > 0, 'prometheus.yml 에서 스크레이프 대상을 못 읽었다');
  });

  test('모든 모듈이 CI 이미지 매트릭스에 있다', () => {
    // 빠진 모듈은 어떤 커밋에서도 이미지가 만들어지지 않는다. 배포는 옛 이미지에 고정되고
    // 필수 체크는 내내 초록이다 — ci.yml 이 backend-ghcr 주석에서 "조용한 스큐" 라 부르는 것.
    const missing = MODULES.filter((m) => !(m in ciImageMapping()));

    assert.deepEqual(missing, []);
  });

  test('모든 모듈이 변경 감지 필터에 있다', () => {
    // 필터에 없으면 그 모듈만 고친 PR 은 "변경 없음" 으로 읽혀 테스트도 이미지 빌드도 건너뛴다.
    const missing = MODULES.filter((m) => !new RegExp(`^\\s+${m}:`, 'm').test(CI));

    assert.deepEqual(missing, []);
  });

  test('k3s 빌드 스크립트의 매핑이 CI 와 글자 그대로 같다', () => {
    // 접미사가 다르면 같은 서비스가 GitHub Actions 와 k3s 에서 서로 다른 이미지로 나간다.
    // 한쪽에만 있는 모듈이면 그 경로에서는 이미지가 아예 안 생긴다.
    assert.deepEqual(buildShMapping(), ciImageMapping());
  });

  test('모든 모듈이 프로메테우스 스크레이프 대상이다', () => {
    // 대상이 아니면 up{job=...} 시계열이 존재하지 않는다. 0 이 아니라 없다 — 그래서
    // `up == 0` 알람은 빈 벡터를 평가하며 영원히 안 울린다. 죽어도 소리가 안 난다.
    const targets = scrapeTargets();
    const missing = MODULES.filter((m) => !targets.has(m));

    assert.deepEqual(missing, []);
  });

  test('로컬 검증 스크립트는 모듈 목록을 손으로 들고 있지 않다', () => {
    // 손으로 적힌 사본은 반드시 뒤처진다(여기 있던 14개는 다른 저장소 것이었고, 그중 6개는
    // 이 저장소에 존재한 적이 없다). 목록은 settings.gradle.kts 에서 읽어야 한다.
    assert.match(
      VERIFY_SH,
      /MODULES=\(\)/,
      'scripts/verify.sh 가 MODULES 를 다시 손으로 나열하고 있다',
    );
    assert.ok(
      VERIFY_SH.includes('settings.gradle.kts'),
      'scripts/verify.sh 가 모듈 로스터를 settings.gradle.kts 에서 읽지 않는다',
    );
  });
});
