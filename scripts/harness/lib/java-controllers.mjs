/**
 * 자바 서비스의 REST 컨트롤러 표면 추출기 — 게이트 두 벌이 함께 쓰는 단일 정의.
 *
 * <p>이 파일이 생긴 이유: `api-screen-gate`(화면이 부르는가)와 `gateway-route-gate`(게이트웨이가
 * 라우팅하는가)는 <b>같은 질문의 두 면</b>이라 같은 "엔드포인트란 무엇인가"를 써야 한다. 파서를 두
 * 벌 두면 한쪽만 고쳐져서, 드리프트를 막으라고 만든 하네스 안에서 드리프트가 난다.
 *
 * <p>추출 규칙은 실제 코드베이스에 맞춰 굳은 것이다:
 *   - 엔드포인트 = 클래스 {@code @RequestMapping} + 메서드 매핑의 조합.
 *   - 메서드 경로가 이미 클래스 base 로 시작하면 덧붙이지 않는다 — 클래스 매핑 없이 메서드에
 *     전체 경로를 다는 컨트롤러가 실제로 있다(ApplicationDocumentController).
 *   - 경로 없는 매핑({@code @GetMapping})은 클래스 base 그 자체를 가리킨다.
 *
 * <p>필터링(내부 전용 경로 제외 등)은 <b>하지 않는다</b>. 무엇을 뺄지는 게이트마다 다르다 —
 * 화면 게이트는 {@code /internal} 을 무시하지만, 라우팅 게이트는 그것이 게이트웨이로 새지
 * 않았는지 확인해야 해서 오히려 필요하다.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/** 이 파일 기준 저장소 루트 — scripts/harness/lib/ 에서 세 단계 위. */
const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 라우팅하는 쪽이라 라우팅 대상이 아니다. 로스터에서 뺀다. */
const NOT_ROUTED = new Set(['gateway-service']);

/**
 * settings.gradle.kts 의 include(...) 블록에서 모듈명을 뽑는다.
 * coverage-scope-gate 의 parseIncludedModules 와 같은 규칙 — 로스터 정본은 한 곳뿐이다.
 */
export function parseIncludedModules(settingsText) {
  const start = String(settingsText).indexOf('include(');
  if (start < 0) return [];
  const end = String(settingsText).indexOf(')', start);
  if (end < 0) return [];
  return [...String(settingsText).slice(start, end).matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

/**
 * 자바 서비스 로스터를 settings.gradle.kts 에서 읽는다.
 *
 * <p>손으로 적어 두면 안 되는 이유(실측): 이 목록은 settlement 에서 떼어 오기 전의 이름
 * 8개를 그대로 들고 있었고, 그중 6개는 이 저장소에 존재한 적이 없다. walk() 는 없는
 * 디렉터리에 대해 조용히 빈 배열을 돌려주므로 게이트는 **아무것도 검사하지 않고 통과**한다.
 * 새 서비스를 추가할 때 이 목록을 잊으면 그 서비스의 엔드포인트는 라우팅·화면 게이트 어디에도
 * 잡히지 않는다 — 목록을 유지보수 대상에서 빼는 것이 유일하게 안전한 형태다.
 */
export function javaServices(repoRoot = REPO_ROOT) {
  const settings = join(repoRoot, 'settings.gradle.kts');
  if (!existsSync(settings)) return [];
  return parseIncludedModules(readFileSync(settings, 'utf8')).filter((m) => !NOT_ROUTED.has(m));
}

export const JAVA_SERVICES = javaServices();

export function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/** `${id}`(프론트 템플릿)와 `{id}`(스프링 경로변수)를 같은 와일드카드로 접는다. */
export const norm = (path) => path.replace(/\$?\{[^}]*\}/g, '*').replace(/\/+$/, '') || '/';

/**
 * @param {string} repoRoot 저장소 루트 절대경로
 * @param {string[]} services 훑을 서비스 디렉터리 이름들
 * @returns {{key:string, service:string, className:string, endpoints:string[]}[]}
 *          엔드포인트가 하나도 없는 클래스는 결과에서 빠진다
 *          ({@code @RestControllerAdvice} 가 문자열 포함으로 걸리지만 매핑이 없어 여기서 걸러진다).
 */
export function controllers(repoRoot, services = JAVA_SERVICES) {
  const found = [];
  for (const service of services) {
    for (const file of walk(join(repoRoot, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const src = readFileSync(file, 'utf8');
      if (!src.includes('@RestController')) continue;

      const classBase = src.match(/@RequestMapping\(\s*"([^"]+)"/)?.[1] ?? '';
      const methodPaths = [...src.matchAll(/@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)]
        .map((m) => m[1]);
      const bareMapping = /@(?:Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*\))?\s*[\r\n]/.test(src);

      const paths = new Set();
      if (classBase && (bareMapping || methodPaths.length === 0)) paths.add(norm(classBase));
      for (const mp of methodPaths) {
        if (!mp) { if (classBase) paths.add(norm(classBase)); continue; }
        paths.add(norm(classBase && !mp.startsWith(classBase) ? classBase + mp : mp));
      }

      const endpoints = [...paths].filter((p) => p !== '/');
      if (endpoints.length) {
        const className = basename(file, '.java');
        found.push({ key: `${service}/${className}`, service, className, endpoints });
      }
    }
  }
  return found;
}
