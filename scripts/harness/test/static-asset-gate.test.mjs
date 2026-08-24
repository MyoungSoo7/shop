/**
 * 정적 자산 게이트 — "index.html 이 가리키는 파일이 실은 없다" 를 빌드 시점에 막는다.
 *
 * `index.html` 의 루트 상대 참조는 Vite 가 검증하지 않는다. `public/` 에 없는 파일을 가리켜도
 * 빌드는 그대로 성공하고, 런타임에 404 가 나며, 그 404 는 화면을 깨뜨리지 않아 아무도 모른다.
 * 실제로 스캐폴딩 잔재인 `/vite.svg`(favicon)가 그 상태로 오래 남아 있었다 — 프로덕션 nginx 가
 * 매 접속마다 404 를 응답하고 있었지만 화면은 멀쩡해 보였다.
 *
 * manifest 의 아이콘도 같은 함정이다. PWA 설치 시에만 드러나서 일반 브라우징으로는 안 잡힌다.
 *
 * 검사 대상은 **정적 파일로 서빙되는 루트 상대 경로**뿐이다. `/src/**` 는 Vite 가 번들로
 * 변환하는 소스 진입점이고 `/assets/**` 는 빌드 산출물이라 저장소에 없는 게 정상이다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const PUBLIC_DIR = join(REPO_ROOT, 'frontend', 'public');

/** Vite 가 생성/변환하는 경로 — 저장소에 실체가 없는 게 정상이다. */
const BUILD_GENERATED = [/^\/src\//, /^\/assets\//, /^\/@/];

const isBuildGenerated = (path) => BUILD_GENERATED.some((re) => re.test(path));

/** `href="/x"` · `src="/x"` 의 루트 상대 경로만 뽑는다(쿼리·해시는 잘라낸다). */
function rootRelativeRefs(html) {
  const refs = new Set();
  for (const match of html.matchAll(/(?:href|src)="(\/[^"]*)"/g)) {
    const path = match[1].split(/[?#]/)[0];
    if (path && !isBuildGenerated(path)) refs.add(path);
  }
  return [...refs].sort();
}

test('index.html 의 루트 상대 자산은 모두 frontend/public 에 실재한다', () => {
  const html = readFileSync(join(REPO_ROOT, 'frontend', 'index.html'), 'utf8');
  const refs = rootRelativeRefs(html);

  // 검사가 실제로 대상에 닿았는지 먼저 증명한다 — 정규식이 안 맞아 0건이면 게이트는 무의미하다.
  assert.ok(refs.length > 0, 'index.html 에서 루트 상대 자산을 하나도 찾지 못했다 — 추출 로직 점검 필요');

  const missing = refs.filter((path) => !existsSync(join(PUBLIC_DIR, path)));
  assert.deepEqual(
    missing,
    [],
    `index.html 이 가리키는 파일이 frontend/public 에 없다(런타임 404): ${missing.join(', ')}`,
  );
});

test('manifest.webmanifest 의 아이콘·스크린샷도 모두 실재한다', () => {
  const manifestPath = join(PUBLIC_DIR, 'manifest.webmanifest');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));

  const srcs = [...(manifest.icons ?? []), ...(manifest.screenshots ?? [])]
    .map((entry) => entry.src)
    .filter((src) => typeof src === 'string' && src.startsWith('/'));

  assert.ok(srcs.length > 0, 'manifest 에서 아이콘을 하나도 찾지 못했다 — 스키마 변경 점검 필요');

  const missing = srcs.filter((src) => !existsSync(join(PUBLIC_DIR, src.split(/[?#]/)[0])));
  assert.deepEqual(missing, [], `manifest 가 가리키는 파일이 없다: ${missing.join(', ')}`);
});
