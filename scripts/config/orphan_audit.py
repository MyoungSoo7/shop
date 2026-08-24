#!/usr/bin/env python3
"""고아 파라미터 감사 — 설정 키의 '정의'와 '사용'이 짝이 맞는지 전수 대조한다.

두 방향을 본다.

  ①  yml 에 정의됐는데 코드가 아무도 읽지 않는 커스텀 키   → 죽은 설정
  ②  코드가 ``${...}`` 로 읽는데 어디에도 정의가 없는 키    → 부팅 실패 / 조용한 기본값

왜 게이트인가
-------------
이 종류의 결함은 **아무 신호도 내지 않는다.** 테스트는 통과하고, 앱은 뜨고, 로그도
조용하다. 설정 파일에는 값이 또렷이 적혀 있으므로 코드 리뷰에서도 정상으로 보인다.
실제로 2026-08-20 첫 감사에서 8건이 나왔고 그중에는 이런 것들이 있었다.

  * order-service 업로드 한도가 프로덕션에서 로드되지 않는 프로파일에만 있어,
    파일엔 5MB 라고 적힌 채 운영은 스프링 기본값 1MB 로 돌고 있었다.
  * market-service 만 "쿼터 보호용" 호출 간격을 선언해 놓고 읽지 않아,
    형제 서비스 3개와 달리 외부 API 를 무간격으로 연타하고 있었다.
  * reconciliation-service 의 허용오차 환경변수를 올려도 아무 일도 일어나지 않았다.
    (그 서비스는 2026-08-25 제거됐다 — 사례로서의 값은 그대로다.)

셋 다 몇 달을 살아남았다. 사람이 주기적으로 볼 것을 기대하는 대신 CI 에서 막는다.

프레임워크 소유 키(``spring.*``, ``management.*`` 등)는 ① 에서 제외한다 — 그건 우리
코드가 아니라 스프링이 읽기 때문이다.

사용법
------
  python3 scripts/config/orphan_audit.py              # 사람이 읽는 리포트
  python3 scripts/config/orphan_audit.py --check      # CI 게이트 (위반 시 exit 1)
  python3 scripts/config/orphan_audit.py --json out.json
  python3 scripts/config/orphan_audit.py --helm ../helm-deploy   # 배포 env 도 정의로 인정

``--helm`` 은 선택이다. 붙이면 helm 차트가 주입하는 대문자 env 이름을 '정의됨' 으로
쳐 주지만, 현재 리포 상태에서는 붙이든 말든 판정이 같다(둘 다 위반 0). CI 러너에는
helm-deploy 체크아웃이 없으므로 게이트는 이 리포 안에서 자족적으로 돈다.
"""
import argparse
import glob
import json
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - 환경 문제는 조용히 통과시키지 않는다
    sys.exit("PyYAML 이 필요하다: pip install pyyaml")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ALLOWLIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "orphan-allowlist.txt")

# 스프링/라이브러리가 스스로 읽는 루트. 여기 속하면 "코드에서 안 읽어도" 고아가 아니다.
FRAMEWORK_ROOTS = {
    "spring", "management", "server", "logging", "springdoc", "eureka", "resilience4j",
    "feign", "ribbon", "hystrix", "cloud", "info", "endpoints", "jasypt", "otel",
    "micrometer", "debug", "trace", "shedlock", "decorator", "jackson", "sentry",
    "grpc", "graphql", "netty", "reactor", "kafka", "redis", "hibernate", "flyway",
    "liquibase", "swagger", "springfox",
}


def flatten(node, prefix=""):
    out = {}
    if isinstance(node, dict):
        for k, v in node.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            out.update(flatten(v, key))
    elif isinstance(node, list):
        # 리스트는 원소별 키를 만들지 않는다(인덱스 키는 코드가 그렇게 읽지 않는다).
        out[prefix] = node
    else:
        out[prefix] = node
    return out


def load_yaml_keys(path):
    keys = {}
    with open(path, encoding="utf-8") as fh:
        raw = fh.read()
    try:
        for doc in yaml.safe_load_all(raw):
            if isinstance(doc, dict):
                keys.update(flatten(doc))
    except yaml.YAMLError as e:
        # 파싱 실패를 삼키면 그 파일의 키가 통째로 사라져 "고아 없음" 이라는 거짓 통과가 된다.
        raise SystemExit(f"YAML 파싱 실패 {path}: {e}")
    return keys, raw


# ── 코드에서 설정을 읽는 모든 형태 ────────────────────────────────────────────
RE_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.\-\[\]]+)(?::([^}]*))?\}")
RE_CONFPROPS = re.compile(r"@ConfigurationProperties\s*\(([^)]*)\)", re.S)
RE_PREFIX_VAL = re.compile(r'(?:prefix\s*=\s*)?"([A-Za-z0-9_.\-]+)"')
RE_GETPROP = re.compile(r'get(?:Required)?Property\s*\(\s*"([A-Za-z0-9_.\-]+)"')
RE_COND_PROP = re.compile(r"@ConditionalOnProperty\s*\(([^)]*)\)", re.S)
RE_GETENV = re.compile(r'System\.getenv\s*\(\s*"([A-Za-z0-9_]+)"')
# 스프링이 플레이스홀더를 해석해 주는 애노테이션들. 여기 인자 안의 ${} 만 설정 참조다.
RE_SPRING_ANNO = re.compile(
    # 코틀린은 use-site target 을 붙인다: @param:Value / @field:Value / @get:Value.
    # 이 접두사를 허용하지 않으면 코틀린 모듈의 @Value 참조를 통째로 놓쳐,
    # 실제로는 읽히는 키가 "고아" 로 잘못 잡힌다(당시 reconciliation tolerance-krw 로 실측.
    # 그 모듈은 사라졌지만 코틀린 모듈이 남아 있는 한 이 규칙은 계속 필요하다).
    r"@(?:param:|field:|get:|set:|property:|setparam:|delegate:|receiver:)?"
    r"(?:Value|Scheduled|KafkaListener|ConditionalOnProperty|ConditionalOnExpression"
    r"|RabbitListener|SqsListener|TestPropertySource|DynamicPropertySource|Profile"
    r"|CrossOrigin|FeignClient|EnableConfigurationProperties)\s*\((?:[^()]|\([^()]*\))*\)",
    re.S)


def scan_code(root):
    """java/kotlin 소스가 참조하는 설정 키를 모은다."""
    refs = {}          # key -> {'default': bool, 'files': set}
    prefixes = set()   # @ConfigurationProperties / @ConditionalOnProperty prefix
    envs = set()       # System.getenv
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ("build", "node_modules", ".git", "target", ".gradle")]
        for fn in filenames:
            if not fn.endswith((".java", ".kt")):
                continue
            p = os.path.join(dirpath, fn)
            try:
                with open(p, encoding="utf-8", errors="replace") as fh:
                    src = fh.read()
            except OSError:
                continue
            rel = os.path.relpath(p, root)
            # ${...} 를 파일 전체에서 긁으면 Kotlin 문자열 템플릿("${it.channel}")까지 딸려온다.
            # 스프링이 실제로 해석하는 자리 — 애노테이션 인자 안 — 으로만 한정한다.
            for am in RE_SPRING_ANNO.finditer(src):
                for m in RE_PLACEHOLDER.finditer(am.group(0)):
                    key, dflt = m.group(1), m.group(2)
                    e = refs.setdefault(key, {"default": False, "files": set()})
                    if dflt is not None:
                        e["default"] = True
                    e["files"].add(rel)
            for m in RE_CONFPROPS.finditer(src):
                for pm in RE_PREFIX_VAL.finditer(m.group(1)):
                    prefixes.add(pm.group(1))
            for m in RE_COND_PROP.finditer(src):
                for pm in RE_PREFIX_VAL.finditer(m.group(1)):
                    prefixes.add(pm.group(1))
            for m in RE_GETPROP.finditer(src):
                refs.setdefault(m.group(1), {"default": True, "files": set()})["files"].add(rel)
            for m in RE_GETENV.finditer(src):
                envs.add(m.group(1))
    return refs, prefixes, envs


def env_style(key):
    """스프링 relaxed binding: a.b-c ⇄ A_B_C (점→언더스코어, 하이픈 제거, 대문자)."""
    return key.upper().replace(".", "_").replace("-", "")


def load_allowlist(path):
    """의도적으로 읽히지 않는 키의 예외 목록. 없으면 빈 집합."""
    keys = set()
    if not os.path.exists(path):
        return keys
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                keys.add(line)
    return keys


def collect_helm_env(helm_root):
    """helm 차트가 주입하는 대문자 env 이름. helm_root 가 없으면 빈 집합."""
    helm_env = set()
    if not helm_root or not os.path.isdir(helm_root):
        return helm_env
    raw = ""
    patterns = [f"{helm_root}/charts/**/*.yaml", f"{helm_root}/charts/**/*.yml",
                f"{helm_root}/apps/**/*.yaml"]
    for pat in patterns:
        for p in glob.glob(pat, recursive=True):
            try:
                with open(p, encoding="utf-8", errors="replace") as fh:
                    raw += fh.read()
            except OSError:
                pass
    for m in re.finditer(r"name:\s*([A-Z][A-Z0-9_]{2,})", raw):
        helm_env.add(m.group(1))
    return helm_env


def audit(helm_root=None):
    # …/<module>/src/main/resources/application.yml → 4단계 위가 모듈 루트
    modules = sorted({
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(p))))
        for p in glob.glob(f"{ROOT}/*/src/main/resources/application*.y*ml")
    })
    if not modules:
        raise SystemExit(f"모듈을 하나도 못 찾았다 (ROOT={ROOT}) — 경로 규약이 바뀐 게 아닌지 확인할 것")

    helm_env = collect_helm_env(helm_root)
    allowed = load_allowlist(ALLOWLIST)

    # settlement 는 모노-MSA 하이브리드다. shared-common 이 각 서비스의 yml 키를 읽으므로
    # 참조 수집은 반드시 리포 전역이어야 한다. 모듈별로 스캔하면 shared-common 이 읽는 키가
    # 전부 "고아" 로 잡힌다(첫 실행에서 app.kafka.topic.owner 등 104건이 그렇게 잡혔다).
    refs, prefixes, envs = scan_code(ROOT)

    report = {
        "root": ROOT,
        "modules_scanned": len(modules),
        "helm_env_count": len(helm_env),
        "global_refs": len(refs),
        "global_prefixes": len(prefixes),
        "allowlisted": sorted(allowed),
        "orphans": [],
        "undefined": [],
    }
    all_defined = {}

    for mod in modules:
        name = os.path.basename(mod)
        ymls = sorted(glob.glob(f"{mod}/src/main/resources/application*.y*ml"))
        defined, all_raw = {}, ""
        for y in ymls:
            k, raw = load_yaml_keys(y)
            defined.update(k)
            all_raw += raw
        # yml 안에서 다른 키를 ${} 로 참조하는 경우도 '사용' 으로 친다.
        yml_selfrefs = {m.group(1) for m in RE_PLACEHOLDER.finditer(all_raw)}

        # ── ① 정의됐는데 아무도 읽지 않는 커스텀 키
        for key in sorted(defined):
            if key.split(".")[0] in FRAMEWORK_ROOTS:
                continue
            if key in allowed or key in refs or key in yml_selfrefs:
                continue
            # @ConfigurationProperties prefix 아래면 바인딩으로 소비된다
            if any(key == pf or key.startswith(pf + ".") for pf in prefixes):
                continue
            if env_style(key) in envs:
                continue
            report["orphans"].append({"module": name, "key": key,
                                      "value": str(defined[key])[:120]})
        all_defined.update(defined)

    # ── ② 코드가 읽는데 어느 yml 에도 정의가 없는 키 (전역 1회 판정)
    for key, info in sorted(refs.items()):
        if key in all_defined or key in allowed:
            continue
        if key in helm_env or env_style(key) in helm_env:
            continue          # 배포가 env 로 주입 중
        if env_style(key) in envs:
            continue
        files = sorted(info["files"])[:3]
        # 기본값이 없는 것만 진짜 위험이다. 그중에서도 등급을 나눈다.
        #   main + 커스텀 키 → 아무도 값을 주지 않으면 컨텍스트가 뜨지 않는다 (hard)
        #   test 소스        → 테스트 하네스(@EmbeddedKafka 등)가 런타임에 주입한다 (soft)
        #   framework 루트   → 스프링 자체 기본값이 있을 수 있다 (soft)
        if info["default"]:
            severity = "has-default"
        elif (files and all("/src/test/" in f for f in files)) or key.split(".")[0] in FRAMEWORK_ROOTS:
            severity = "soft"
        else:
            severity = "hard"
        report["undefined"].append({"key": key, "severity": severity, "files": files})

    report["totals"] = {
        "orphan": len(report["orphans"]),
        "undefined": len(report["undefined"]),
        "undefined_hard": sum(1 for u in report["undefined"] if u["severity"] == "hard"),
        "undefined_soft": sum(1 for u in report["undefined"] if u["severity"] == "soft"),
    }
    return report


def main():
    ap = argparse.ArgumentParser(description="설정 키 고아/미정의 감사")
    ap.add_argument("--check", action="store_true",
                    help="위반이 있으면 exit 1 (CI 게이트)")
    ap.add_argument("--json", metavar="PATH", help="리포트를 JSON 으로 저장")
    ap.add_argument("--helm", metavar="DIR",
                    help="helm-deploy 체크아웃 경로 (선택) — 배포 env 도 정의로 인정")
    args = ap.parse_args()

    report = audit(helm_root=args.helm)
    t = report["totals"]

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=1, default=str)

    print(f"모듈 {report['modules_scanned']}개 · 코드 참조 {report['global_refs']}개"
          f" · helm env {report['helm_env_count']}개")
    print(f"① 고아(정의만 있고 아무도 안 읽음)  : {t['orphan']}")
    print(f"② 미정의(코드가 읽는데 정의 없음)   : {t['undefined']}"
          f"  (기본값 없는 위험 = {t['undefined_hard']})")

    for o in report["orphans"]:
        print(f"  ① {o['module']}: {o['key']} = {o['value']}")
    for u in report["undefined"]:
        if u["severity"] == "hard":
            print(f"  ② {u['key']}  ← {', '.join(u['files'])}")

    if args.check and (t["orphan"] or t["undefined_hard"]):
        print()
        print("설정 감사 실패.")
        print("  ① 고아  → 읽는 코드를 배선하거나 키를 지운다. 값을 넣어도 아무 일도")
        print("            일어나지 않는 설정은 운영자를 속인다.")
        print("  ② 미정의 → yml 에 값을 정의하거나 ${key:default} 로 기본값을 준다.")
        print(f"  의도된 예외라면 이유를 적어 {os.path.relpath(ALLOWLIST, ROOT)} 에 등재한다.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
