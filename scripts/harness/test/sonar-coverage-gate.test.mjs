/**
 * Sonar 커버리지 게이트 — "측정을 못 한 회차"가 "측정해서 실패한 회차"를 초록으로 덮는 것을 막는다.
 *
 * <b>실제로 일어난 일 (2026-08-22 실측)</b> — develop 의 SonarCloud 품질 게이트가 status=OK 인데,
 * 조건 목록에서 `new_coverage` 가 **통째로 사라져** 있었다. 직전까지는 79.8%(기준 80%)로 ERROR 였다.
 * 코드가 좋아진 게 아니라 커버리지가 측정되지 않은 것이었다:
 *
 *   api/measures  new_lines=40263  new_lines_to_cover=0  new_uncovered_lines=0
 *
 * 경로는 이렇다. 백엔드 테스트는 변경 경로 필터로 스킵될 수 있고(정상), 그러면 JaCoCo XML 이 없다.
 * `build.gradle.kts` 는 XML 이 없는 모듈에 `sonar.coverage.exclusions="**\/*"` 를 걸어 0% 오보고를
 * 막는다(이것도 정상). 그런데 **전** 모듈이 그렇게 빠지면 분석에 커버 가능한 라인이 하나도 안 남고,
 * SonarCloud 는 판정할 수 없는 조건을 결과에서 제외한다. 브랜치 게이트는 마지막 분석 하나로
 * 정해지므로, 문서/파이썬 전용 푸시 한 번이 직전의 커버리지 실패 판정을 지워 버린다.
 *
 * <b>같이 드러난 두 번째 결함</b> — 이 상황을 알려 줬어야 할 가드가 꺼져 있었다. `Restore report
 * paths` 스텝은 아티팩트 루프 때문에 `shopt -s nullglob` 을 켠 채로 파일 목록을 세는데,
 *
 *   restored=$(ls -1 *\/build/reports/jacoco/test/jacocoTestReport.xml | cut -d/ -f1 | sort -u)
 *
 * 이렇게 쓰면 XML 이 0개일 때 패턴이 사라져 명령이 인자 없는 `ls -1` 이 되고, 저장소 루트 전체가
 * 출력된다. restored 에 모듈 디렉토리 이름이 섞여 들어가면서 두 대조가 동시에 공집합이 된다 —
 * 배선이 통째로 끊겨도 통과하고, 미측정 모듈 기록조차 남지 않는다. run 32530600570 로그의
 * "복원된 커버리지 XML: 51개 / restored: AGENTS.md ARCHITECTURE.md CLAUDE.md Dockerfile …" 이 그 흔적이다.
 *
 * <b>이 게이트가 지키는 것</b> — 위 둘의 재발만 정적으로 막는다.
 *   ① Sonar 분석 스텝은 커버리지 존재 조건을 달고 돈다.
 *   ② 커버리지 파일 목록을 nullglob 이 켜진 스텝에서 `ls` 로 세지 않는다.
 *
 * <b>잡지 못하는 것</b> — 커버리지가 실려 분석까지 갔는데 경로 불일치로 Sonar 가 0% 로 집계하는
 * 종류는 여기서 안 보인다. 그건 분석 후 서버 값을 되읽어야 알 수 있고(`sonar.qualitygate.wait=false`
 * 라 즉시 조회는 경합한다), 별도 과제다. 이 게이트가 있다고 Sonar 대시보드를 안 봐도 된다는 뜻이 아니다.
 *
 * <b>도달 증명</b> — 증거를 워크플로 소스 자체에서 취한다. 러너 로그나 API 응답은 체크아웃마다
 * 달라 게이트를 뒤집지만, `ci.yml` 은 어느 체크아웃에서나 같다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CI_PATH = join(REPO_ROOT, '.github', 'workflows', 'ci.yml');

function readCi() {
  const raw = readFileSync(CI_PATH, 'utf8');
  assert.ok(raw.length > 0, 'ci.yml 을 읽지 못했다 — 게이트가 대상에 도달하지 못했다');
  return raw;
}

/** `- name: …` 부터 다음 `- name:`(또는 다음 잡) 직전까지를 한 스텝으로 자른다. */
function steps(raw) {
  const lines = raw.split('\n');
  const out = [];
  let current = null;
  for (const line of lines) {
    const m = /^\s*-\s+name:\s*(.+?)\s*$/.exec(line);
    if (m) {
      if (current) out.push(current);
      current = { name: m[1], body: [line] };
    } else if (current) {
      current.body.push(line);
    }
  }
  if (current) out.push(current);
  return out.map((s) => ({ name: s.name, body: s.body.join('\n') }));
}

test('ci.yml 이 존재하고 Sonar 분석 스텝을 갖는다 (도달 증명)', () => {
  const found = steps(readCi()).filter((s) => /SonarCloud Scan/.test(s.name));
  assert.equal(found.length, 1, `SonarCloud Scan 스텝이 정확히 1개여야 한다 (발견 ${found.length}개)`);
});

test('Sonar 분석은 커버리지가 실린 회차에만 돈다 — 미측정 회차가 게이트를 초록으로 덮지 못하게', () => {
  const scan = steps(readCi()).find((s) => /SonarCloud Scan/.test(s.name));
  const ifLine = /^\s*if:\s*(.+)$/m.exec(scan.body);
  assert.ok(ifLine, 'SonarCloud Scan 스텝에 if 조건이 없다');
  assert.match(
    ifLine[1],
    /steps\.coverage\.outputs\.present\s*==\s*'true'/,
    '커버리지 존재 조건이 빠졌다. 커버리지 XML 0개인 회차가 분석되면 new_coverage 조건이 '
      + '결과에서 사라져 품질 게이트가 조용히 초록이 된다(2026-08-22 실측).',
  );
});

test('커버리지 존재 여부를 내보내는 스텝에 id 가 붙어 있다', () => {
  const restore = steps(readCi()).find((s) => /Restore report paths/.test(s.name));
  assert.ok(restore, 'Restore report paths 스텝을 찾지 못했다');
  assert.match(restore.body, /^\s*id:\s*coverage\s*$/m, 'id: coverage 가 없으면 위 if 조건이 항상 거짓이 된다');
  assert.match(restore.body, /present=true/, '커버리지 존재를 GITHUB_OUTPUT 으로 내보내지 않는다');
  assert.match(restore.body, /present=false/, '커버리지 부재를 GITHUB_OUTPUT 으로 내보내지 않는다');
});

test('nullglob 이 켜진 스텝에서 커버리지 파일 목록을 ls 로 세지 않는다', () => {
  for (const step of steps(readCi())) {
    if (!/shopt\s+-s\s+nullglob/.test(step.body)) continue;
    const offending = step.body
      .split('\n')
      .filter((line) => !/^\s*#/.test(line))
      .filter((line) => /\bls\b[^|\n]*\*\//.test(line));
    assert.deepEqual(
      offending,
      [],
      `스텝 "${step.name}": nullglob 이 켜진 상태에서 글로브를 ls 인자로 넘긴다. 매칭이 0건이면 `
        + `패턴이 사라져 인자 없는 ls 가 되고 저장소 전체가 목록에 들어가, 이어지는 집합 대조가 `
        + `통째로 무력화된다. 글로브는 for 루프로 받을 것.\n  ${offending.join('\n  ')}`,
    );
  }
});
