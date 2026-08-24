/**
 * 캐시 마운트 게이트 — 하나의 Dockerfile 이 여러 타깃을 만들 때 캐시를 공유해 깨지는 것을 막는다.
 *
 * 루트 `Dockerfile` 은 `ARG MODULE` 하나로 19개 JVM 서비스 이미지를 전부 찍어낸다. 그런데
 * BuildKit 캐시 마운트에 `id=` 를 생략하면 **id 가 target 경로로 정해진다**. 즉 19개 모듈이
 * 같은 `/home/gradle/.gradle` 를 기본값 `sharing=shared` 로 **동시에** 쓴다. Gradle user home 은
 * 동시 접근에 안전하지 않아, 전 모듈을 한 번에 빌드하면 이렇게 죽는다:
 *
 *   > Timeout waiting to lock journal cache (/home/gradle/.gradle/caches/journal-1).
 *     It is currently in use by another process. Owner PID: 49 / Our PID: 50
 *   BUILD FAILED
 *
 * 2026-08-21 실측: `docker compose build` 가 exit 1, `BUILD FAILED` 13회, 락 타임아웃 17개 모듈,
 * 신규 이미지 0개. `id=gradle-${MODULE}` 로 가른 뒤 exit 0, 락 타임아웃 0건, 이미지 21종 전부 빌드.
 *
 * <b>왜 CI 가 못 잡았나</b> — 이게 이 게이트의 존재 이유다. `backend-ghcr` 잡은 모듈당 러너 하나인
 * 매트릭스라(실측 run 32458180979: 19개 잡, 각 2~4분) **한 러너에 한 모듈**만 빌드한다. 단일 모듈
 * 빌드로는 이 결함이 영영 재현되지 않는다 — 컴파일도 Dockerfile 로직도 멀쩡하고, "다 같이" 빌드할
 * 때만 깨지기 때문이다. 로컬에서도 대개 서비스 하나씩 빌드하니 똑같이 안 보인다.
 *
 * <b>왜 실제 동시 빌드 잡을 두지 않는가</b> — 공개 저장소 표준 러너는 4 vCPU / 16 GB RAM /
 * 여유 디스크 ~14 GB 다. Gradle JVM 19개 동시는 RAM 도 디스크도 안 들어간다(모듈 이미지만 ~8GB).
 * 그 잡은 락 경합이 아니라 OOM·디스크풀로 빨개져서 게이트로 쓸 수가 없다. 큰 러너는 개인 계정에
 * 아예 제공되지 않고(Team/Enterprise 전용), 공개 저장소도 항상 과금된다. 반면 이 불변식은
 * **정적으로 판정된다** — Dockerfile 을 읽으면 답이 나온다. 그래서 19개를 실제로 돌려 이미 아는
 * 답을 재확인하는 대신, 여기서 밀리초에 끝낸다.
 *
 * <b>이 게이트가 잡지 못하는 것</b> — 캐시 id 누락이라는 *이 종류*만 잡는다. 앞으로 생길 다른
 * 동시성 결함은 실제 전체 동시 빌드만 잡을 수 있고, 그건 로컬 `docker compose build` 나 릴리스 전
 * 수동 실행의 몫이다. 정적 게이트를 붙였다고 전체 빌드를 한 번도 안 돌려도 된다는 뜻이 아니다.
 *
 * <b>도달 증명</b> — `dockerignore-gate` 와 같은 이유로 증거를 **저장소가 선언한 것**에서 취한다.
 * 빌드 산출물이나 이미지 존재 여부는 fresh checkout 인 CI 와 로컬이 달라 게이트를 뒤집는다.
 * 여기서는 Dockerfile 소스 자체가 증거라 어느 체크아웃에서나 동일하다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * 줄바꿈 이어짐(`\`)을 합쳐 논리 줄로 만든다. 주석·빈 줄은 버리되 원본 줄번호는 보존한다
 * (위반 메시지가 실제 Dockerfile 줄을 가리켜야 고치러 갈 수 있다).
 */
export function logicalLines(source) {
  const raw = source.split(/\r?\n/);
  const out = [];
  let buf = null;

  for (let i = 0; i < raw.length; i += 1) {
    const line = raw[i];
    const continues = /\\\s*$/.test(line);
    const body = line.replace(/\\\s*$/, '');

    if (buf === null) {
      if (line.trim() === '' || /^\s*#/.test(line)) continue;
      buf = { lineNo: i + 1, text: body };
    } else {
      buf.text += ` ${body.trim()}`;
    }

    if (!continues) {
      out.push(buf);
      buf = null;
    }
  }
  if (buf) out.push(buf);

  return out;
}

/** `--mount=type=cache,id=x,target=y` → { type:'cache', id:'x', target:'y' } */
export function parseMount(spec) {
  const opts = {};
  for (const kv of spec.replace(/^--mount=/, '').split(',')) {
    const eq = kv.indexOf('=');
    if (eq < 0) {
      opts[kv] = true;
      continue;
    }
    opts[kv.slice(0, eq)] = kv.slice(eq + 1);
  }
  return opts;
}

/**
 * 이 Dockerfile 이 "여러 타깃을 찍어내는" 파일인지 판정하고, 타깃을 가르는 ARG 이름을 돌려준다.
 * 판정 기준: 선언된 ARG 가 RUN 본문에서 `${NAME}` 으로 쓰인다 = 같은 파일이 인자에 따라 다른
 * 빌드를 수행한다. 그런 파일에서만 캐시 공유가 문제가 된다(단일 타깃이면 자기 자신끼리 공유다).
 */
export function targetSelectingArgs(lines) {
  const declared = [];
  for (const { text } of lines) {
    const m = /^\s*ARG\s+([A-Za-z_][A-Za-z0-9_]*)/.exec(text);
    if (m) declared.push(m[1]);
  }
  return declared.filter((name) =>
    lines.some(({ text }) => /^\s*RUN\b/.test(text) && text.includes(`\${${name}}`)),
  );
}

/**
 * 위반 목록을 돌려준다. 순수 함수로 떼어 둬서 자기검증(일부러 깨진 입력을 잡는지)이 가능하다.
 *
 * 통과 조건은 둘 중 하나다:
 *   - `id=` 가 타깃 ARG 를 품는다 → 모듈별로 캐시가 갈린다(병렬 유지, 현재 채택안)
 *   - `sharing=locked|private` → 동시 접근 자체가 없다(직렬화 또는 전용 캐시)
 */
export function cacheMountViolations(files) {
  const violations = [];

  for (const { path, source } of files) {
    const lines = logicalLines(source);
    const targetArgs = targetSelectingArgs(lines);
    if (targetArgs.length === 0) continue; // 단일 타깃 Dockerfile — 강제 대상이 아니다

    for (const { lineNo, text } of lines) {
      for (const spec of text.match(/--mount=type=cache[^\s]*/g) ?? []) {
        const opts = parseMount(spec);
        if (opts.sharing === 'locked' || opts.sharing === 'private') continue;

        // id 생략 시 BuildKit 은 target 을 id 로 쓴다 — 그래서 생략도 "상수 id" 다.
        const effectiveId = opts.id ?? opts.target ?? '';
        const varies = targetArgs.some(
          (a) => effectiveId.includes(`\${${a}}`) || effectiveId.includes(`$${a}`),
        );
        if (varies) continue;

        violations.push(
          `${path}:${lineNo} — 캐시 id 가 ${targetArgs.map((a) => `\${${a}}`).join('/')} 에 따라 갈리지 않는다` +
            ` (id=${opts.id ?? '생략 → target 이 id 가 된다'}).` +
            ` 같은 파일로 찍는 모든 타깃이 이 캐시를 동시에 쓴다 → id 를 가르거나 sharing=locked 를 줄 것`,
        );
      }
    }
  }

  return violations;
}

/** 저장소가 가진 Dockerfile — 루트 + 1단계 하위 디렉터리(폴리글랏 7종·frontend). */
function repoDockerfiles() {
  const rel = [];
  if (existsSync(join(REPO_ROOT, 'Dockerfile'))) rel.push('Dockerfile');

  for (const entry of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    if (existsSync(join(REPO_ROOT, entry.name, 'Dockerfile'))) rel.push(`${entry.name}/Dockerfile`);
  }

  return rel.map((path) => ({ path, source: readFileSync(join(REPO_ROOT, path), 'utf8') }));
}

test('캐시 마운트 게이트 (다중 타깃 Dockerfile 의 Gradle 캐시 락 경합)', async (t) => {
  await t.test('여러 타깃을 찍는 Dockerfile 의 캐시 마운트는 타깃별로 갈린다', () => {
    const violations = cacheMountViolations(repoDockerfiles());

    assert.deepEqual(
      violations,
      [],
      `\n전 타깃 동시 빌드가 캐시 락에서 깨진다:\n  ${violations.join('\n  ')}\n`,
    );
  });

  // 검사가 실제 대상에 닿았음을 증명한다 — 0건 통과가 "위반 없음"인지 "아무것도 안 봤음"인지 가른다.
  await t.test('검사가 루트 Dockerfile 의 캐시 마운트에 실제로 닿았다', () => {
    const files = repoDockerfiles();
    const root = files.find((f) => f.path === 'Dockerfile');

    assert.ok(root, '루트 Dockerfile 을 찾지 못했다 — 19개 JVM 이미지를 찍는 바로 그 파일이다');

    const lines = logicalLines(root.source);
    const targetArgs = targetSelectingArgs(lines);
    assert.deepEqual(targetArgs, ['MODULE'], '루트 Dockerfile 의 타깃 ARG 는 MODULE 이어야 한다');

    const mounts = lines.flatMap(({ text }) => text.match(/--mount=type=cache[^\s]*/g) ?? []);
    assert.ok(
      mounts.length >= 2,
      `루트 Dockerfile 에서 캐시 마운트를 ${mounts.length}개 찾았다 — dependencies·bootJar 두 스텝이라 2개 이상이어야 한다.` +
        ' 파서가 깨졌거나 스텝이 사라졌다(한쪽만 고치면 남은 쪽에서 그대로 터진다).',
    );
  });

  await t.test('[자기검증] id 를 생략한 수정 전 상태를 잡아낸다', () => {
    const before = [
      'FROM gradle:9.7.0-jdk25 AS builder',
      'ARG MODULE',
      'RUN --mount=type=cache,target=/home/gradle/.gradle \\',
      '    gradle --no-daemon :${MODULE}:dependencies || true',
      'RUN --mount=type=cache,target=/home/gradle/.gradle \\',
      '    gradle --no-daemon :${MODULE}:bootJar -x test',
    ].join('\n');

    const violations = cacheMountViolations([{ path: 'Dockerfile', source: before }]);

    assert.equal(
      violations.length,
      2,
      '수정 전 Dockerfile 을 통과시키면 이 게이트는 존재 이유가 없다 — 두 스텝 다 잡아야 한다',
    );
  });

  await t.test('[자기검증] 한쪽만 고친 절반 수정도 잡아낸다', () => {
    const half = [
      'ARG MODULE',
      'RUN --mount=type=cache,id=gradle-${MODULE},target=/home/gradle/.gradle \\',
      '    gradle --no-daemon :${MODULE}:dependencies || true',
      'RUN --mount=type=cache,target=/home/gradle/.gradle \\',
      '    gradle --no-daemon :${MODULE}:bootJar -x test',
    ].join('\n');

    const violations = cacheMountViolations([{ path: 'Dockerfile', source: half }]);

    assert.equal(violations.length, 1, '남은 한 스텝에서 그대로 락 경합이 난다');
    assert.match(violations[0], /Dockerfile:4/, '고쳐야 할 줄을 정확히 가리켜야 한다');
  });

  await t.test('[자기검증] 갈린 id·sharing=locked 는 통과시킨다', () => {
    const byId = 'ARG MODULE\nRUN --mount=type=cache,id=gradle-${MODULE},target=/home/gradle/.gradle gradle :${MODULE}:bootJar';
    const byLock = 'ARG MODULE\nRUN --mount=type=cache,sharing=locked,target=/home/gradle/.gradle gradle :${MODULE}:bootJar';

    assert.deepEqual(cacheMountViolations([{ path: 'a', source: byId }]), []);
    assert.deepEqual(cacheMountViolations([{ path: 'b', source: byLock }]), []);
  });

  await t.test('[자기검증] 단일 타깃 Dockerfile 은 강제하지 않는다', () => {
    // 폴리글랏 서비스처럼 자기 이미지 하나만 만드는 파일은 캐시를 공유해도 자기 자신끼리다.
    const single = 'FROM golang:1.26\nRUN --mount=type=cache,target=/go/pkg/mod go build ./...';

    assert.deepEqual(cacheMountViolations([{ path: 'market-stream-service/Dockerfile', source: single }]), []);
  });
});
