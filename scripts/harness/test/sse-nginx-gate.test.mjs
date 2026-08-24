/**
 * SSE 프록시 배선 게이트 — "게이트웨이에 열었는데 nginx 를 안 고쳐" 를 빌드 시점에 막는다.
 *
 * SSE 는 프록시 설정 하나로 조용히 죽는다. 전용 location 이 없으면 요청이 일반 `api` location 으로
 * 떨어져 `proxy_buffering on` + `proxy_read_timeout 60s` 를 받는데, 그러면 이벤트가 버퍼에 묶여
 * 실시간이 아니게 되고 유휴 연결은 60초에 잘린다.
 *
 * 고약한 점은 **개발에서는 멀쩡하다**는 것이다. vite dev·gateway 직결 경로에는 nginx 가 없어서
 * 로컬에선 완벽히 동작하고, compose·배포 경로에서만 깨진다. 실제로 알림 푸시 스트림
 * (`/api/notifications/stream`)이 한동안 그 상태였다 — 게이트웨이 라우팅과 컨슈머·컨트롤러는 다
 * 있는데 nginx 두 파일에만 location 이 없었다.
 *
 * 검증 방식(2026-08-14 실측): 스텁 업스트림이 2초 간격으로 3틱을 보내면, 전용 location 은 2초
 * 간격 그대로 도착하고 일반 `api` location 은 스트림이 끝난 뒤 세 틱이 한꺼번에 도착한다.
 *
 * 정본은 docs/sse.md. 새 SSE 경로를 게이트웨이에 열면 아래 표에 한 줄 추가하고 nginx 두 파일에
 * location 을 넣는다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * nginx 를 거치는 SSE 경로. gateway 가 라우팅하는 것만 대상이다(정본: docs/sse.md).
 * minReadTimeoutSec: 장기 유휴 스트림(시세·알림)은 600s 이상, ai 챗 스트림은 유한 응답이라
 * 120s 가 의도값이다(LLM 생성이 60s 를 넘을 수 있어 일반 api 의 60s 로는 잘린다).
 */
const SSE_ROUTES = [
  { path: '/api/market-stream/', service: 'market-stream-service (Go, 8110)', minReadTimeoutSec: 600 },
  { path: '/api/notifications/stream', service: 'operation-service notification 슬라이스 (8092, ADR 0041)', minReadTimeoutSec: 600 },
  { path: '/api/ai/', service: 'settlement-service ai 슬라이스 챗 SSE (/api/ai/chat/stream, 8082, ADR 0040)', minReadTimeoutSec: 120 },
];

/** SSE 를 태우는 프론트 nginx 설정 — k8s 이미지용과 compose 용 두 벌이 모두 대상이다. */
const NGINX_CONFIGS = ['frontend/nginx.conf', 'frontend/nginx.compose.conf'];

const read = (path) => readFileSync(join(REPO_ROOT, path), 'utf8');

/**
 * `location <매처> <경로> {` 블록의 본문을 잘라낸다. 매처(`=`·`^~`)는 경로 성격에 따라
 * 다르므로 강제하지 않는다 — 이 게이트가 보는 것은 "전용 블록이 있고 버퍼링이 꺼졌는가" 다.
 */
function locationBody(config, routePath) {
  const escaped = routePath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const header = new RegExp(`location\\s+(?:=|\\^~|~\\*?)?\\s*${escaped}\\s*\\{`);
  const match = header.exec(config);
  if (!match) return null;

  let depth = 0;
  for (let i = match.index + match[0].length - 1; i < config.length; i++) {
    if (config[i] === '{') depth++;
    else if (config[i] === '}' && --depth === 0) {
      return config.slice(match.index, i + 1);
    }
  }
  return null;
}

test('게이트 대상이 비어 있지 않다 (공회전 방지)', () => {
  assert.ok(SSE_ROUTES.length > 0);
  assert.ok(NGINX_CONFIGS.length > 0);
});

test('gateway 에 라우팅된 SSE 경로는 nginx 두 벌 모두에 전용 location 을 갖는다', () => {
  const missing = [];
  for (const file of NGINX_CONFIGS) {
    const config = read(file);
    for (const route of SSE_ROUTES) {
      if (!locationBody(config, route.path)) {
        missing.push(`${file}: ${route.path} 전용 location 없음 — ${route.service}`);
      }
    }
  }
  assert.deepEqual(missing, []);
});

test('SSE location 은 버퍼링을 끄고 장기 연결을 허용한다', () => {
  const violations = [];
  for (const file of NGINX_CONFIGS) {
    const config = read(file);
    for (const route of SSE_ROUTES) {
      const body = locationBody(config, route.path);
      if (!body) continue;   // 위 테스트가 이미 잡는다
      if (!/proxy_buffering\s+off\s*;/.test(body)) {
        violations.push(`${file}: ${route.path} — proxy_buffering off 없음 (이벤트가 묶여서 도착한다)`);
      }
      const readTimeout = /proxy_read_timeout\s+(\d+)s\s*;/.exec(body);
      if (!readTimeout) {
        violations.push(`${file}: ${route.path} — proxy_read_timeout 없음 (기본 60s 로 유휴 연결이 끊긴다)`);
      } else if (Number(readTimeout[1]) < route.minReadTimeoutSec) {
        violations.push(`${file}: ${route.path} — proxy_read_timeout ${readTimeout[1]}s 는 너무 짧다 (최소 ${route.minReadTimeoutSec}s)`);
      }
    }
  }
  assert.deepEqual(violations, []);
});

test('[자기검증] 전용 location 이 사라지거나 버퍼링이 켜지면 잡아낸다', () => {
  const sample = `
    location = /api/notifications/stream {
        proxy_pass http://gateway-service:8080;
        proxy_buffering off;
        proxy_read_timeout 3600s;
    }
  `;
  const body = locationBody(sample, '/api/notifications/stream');
  assert.ok(body, '정상 블록은 찾아야 한다');
  assert.match(body, /proxy_buffering\s+off/);

  assert.equal(locationBody(sample, '/api/market-stream/'), null, '없는 경로는 null 이어야 한다');
  assert.equal(
    locationBody(sample.replace('proxy_buffering off;', 'proxy_buffering on;'), '/api/notifications/stream')
      .includes('proxy_buffering off'),
    false,
    '버퍼링이 켜진 블록을 통과시키면 안 된다',
  );
});
