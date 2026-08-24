# k3s BuildKit 빌드 — GitHub Actions 하이브리드

이미지 빌드·푸시만 k3s 홈랩으로 내리고, 테스트·게이트는 GitHub Actions 에 남기는 구성이다.

| 단계 | 어디서 | 근거 |
| --- | --- | --- |
| `Detect changed paths` · `Backend - Build/Test/JaCoCo` · `Frontend` 2종 · `guard` · `Semgrep` | **GitHub Actions (유지)** | `main` 보호 규칙의 **필수 체크 6종**이다. 여기 손대면 PR 이 머지 불가가 된다 |
| Testcontainers 통합테스트 | **GitHub Actions (유지)** | 이 저장소는 Testcontainers 를 142개 파일에서 쓴다. k3s 는 containerd 라 Docker 소켓이 없어 DinD(privileged) 사이드카가 필요하고, PG 17 + ES 8.11 `services:` 도 전부 재현해야 한다 |
| **이미지 빌드 + GHCR 푸시** (백엔드 17 + 프론트엔드) | **k3s (여기)** | `backend-ghcr`·`frontend-ghcr` 잡은 필수 체크가 아니다. 러너 시간 절감 효과가 가장 크고 실패해도 머지를 막지 않는다 |
| Trivy CRITICAL 스캔 | **k3s (여기, `--scan`)** | `backend-ghcr` 를 끄면 거기 붙어 있던 이미지 CVE 게이트도 같이 사라진다. Snyk 은 Gradle 의존성만 본다 |

## 왜 Kaniko 가 아니라 BuildKit 인가

루트 `Dockerfile` 이 Gradle 의존성 캐시를 `RUN --mount=type=cache` 로 잡는다(`Dockerfile:34`, `Dockerfile:57`).
**Kaniko 는 이 문법을 지원하지 않는다.** BuildKit(또는 buildah)만 이 Dockerfile 을 수정 없이 빌드할 수 있다.

## 구성

```
00-namespace.yaml              build 네임스페이스
10-buildkitd.yaml              rootless buildkitd Deployment + 캐시 PVC(50Gi) + Service(:1234)
20-build-job.template.yaml     백엔드 서비스 1개 빌드 Job (build.sh 가 치환)
21-frontend-job.template.yaml  프론트엔드 빌드 Job (컨텍스트·빌드인자가 달라 별도 템플릿)
30-trivy-scan-job.template.yaml 푸시된 이미지 CVE 스캔 Job (build.sh --scan)
build.sh                       대상 팬아웃 + 태그 계산 + 로그 추적
```

데몬은 **상주형**이다. Job 마다 데몬을 새로 띄우면 `--mount=type=cache` 가 매번 비어서 의존성을 전량 다시
받는다. 데몬을 살려 두고 PVC 를 붙여야 두 번째 빌드부터 실제로 빨라진다.

## 최초 설치

```bash
# 1) GHCR 자격 (write:packages 스코프 PAT). 시크릿은 저장소에 커밋하지 않는다.
kubectl create namespace build
kubectl -n build create secret docker-registry ghcr-auth \
  --docker-server=ghcr.io \
  --docker-username=MyoungSoo7 \
  --docker-password="$GHCR_PAT"

# 2) 데몬
kubectl apply -f k8s/buildkit/00-namespace.yaml -f k8s/buildkit/10-buildkitd.yaml
kubectl -n build rollout status deploy/buildkitd

# 3) (선택) 프론트엔드 빌드 인자 — 없으면 ci.yml 과 같은 기본값으로 빌드된다
kubectl -n build create secret generic frontend-build-args \
  --from-literal=VITE_API_BASE_URL=https://jen.lemuel.co.kr \
  --from-literal=VITE_TOSS_CLIENT_KEY="$TOSS_CLIENT_KEY"
```

## 사용

```bash
./k8s/buildkit/build.sh order-service operation-service      # 특정 서비스만
./k8s/buildkit/build.sh frontend                              # 프론트엔드만
./k8s/buildkit/build.sh --all --wait                          # 백엔드 17 + 프론트엔드, 로그 추적
./k8s/buildkit/build.sh --ref main --all --wait --scan        # main + Trivy 게이트
./k8s/buildkit/build.sh --dry-run --all                       # YAML 만 출력
```

태그 규칙은 `ci.yml` 의 `docker/metadata-action` 과 동일하게 맞췄다:
`<branch>`, `<branch>-<sha7>`, 그리고 `main` 일 때만 `latest`.
이미지 이름 매핑(`order-service` → suffix 없음, `operation-service` → `-operation` …)도 `ci.yml` 의
`mapping` 블록을 그대로 옮긴 것이다. **한쪽만 고치면 같은 서비스가 두 이미지로 갈라진다.**

## 반드시 알아야 할 것

- **빌드 컨텍스트는 git 원격이다.** 로컬 워킹트리가 아니라 `origin/<ref>` 의 커밋이 구워진다.
  `build.sh` 는 `git ls-remote` 로 원격 SHA 를 읽어 태그를 만들고, 로컬 HEAD 와 다르면 경고한다.
  미커밋·미푸시 변경은 절대 이미지에 들어가지 않는다.
- **캐시 PVC 는 노드에 못 박힌다.** `local-path`(default, `WaitForFirstConsumer`)라 최초 스케줄된 노드에
  바인딩되고, Deployment 는 그래서 `Recreate` 전략이다. 노드를 옮기려면 PVC 를 지우고 다시 만들어야 한다.
- **PVC 는 나중에 못 늘린다.** 이 클러스터의 StorageClass 7종은 전부 `allowVolumeExpansion=false` 다.
  50Gi 가 모자라면 PVC 재생성(=캐시 유실)이 유일한 방법이다.
- **노드 고정은 `isagal`.** 40코어라 Gradle 빌드에 압도적으로 유리하다(david 6 / louise 8 / ilwon 12).
  메모리 limit 6Gi 는 isagal 의 실측 여유(15.7Gi 중 61% 사용 중)에 맞춘 값이다.
- **rootless 인데 privileged 가 아니다.** 대신 seccomp/apparmor 프로파일만 해제한다(user namespace 생성용).
  커널 요구(5.11+)는 이 클러스터가 6.8~7.0 이라 충족한다.
- **`ARG MODULE` 이 비면 빌드가 깨진다** (`gradle ::bootJar` 로 빈 세그먼트가 된다). `build.sh` 가 모듈명을
  화이트리스트로 검증해 빈 값이 넘어가지 않게 막는다.
- **Trivy 스캔은 푸시 뒤에 돈다.** CRITICAL 이 나와도 이미지는 이미 GHCR 에 올라간 상태이며, 롤백 판단은
  사람이 한다. `ci.yml` 의 기존 동작과 같다.
- 저장소 루트의 `.trivyignore.yaml` 은 스캔 Job 에서 읽히지 않는다(컨텍스트가 없다). 예외가 필요하면
  ConfigMap 으로 올리고 `30-trivy-scan-job.template.yaml` 의 `TRIVY_IGNOREFILE` 을 켠다.

### 프론트엔드에만 해당하는 것

- **`VITE_*` 는 런타임 설정이 아니라 번들에 굽히는 값이다.** 백엔드 이미지처럼 "한 번 굽고 환경별로
  재사용"이 안 된다. 운영용 API 주소·토스 키로 빌드하려면 `frontend-build-args` Secret 을 먼저 만들고
  다시 구워야 한다. Secret 이 없으면 `VITE_API_BASE_URL=""`(같은 오리진) + 토스 공개 테스트 키로 빌드된다 —
  `ci.yml` 의 기본값과 동일하다.
- 빌드 인자를 Job 스펙에 직접 박지 않고 Secret 을 `envFrom` 으로 받는 이유는, 박아 두면
  `kubectl get job -o yaml` 로 값이 그대로 노출되기 때문이다.
- 컨텍스트가 `#<ref>:frontend` 서브디렉터리라 루트 `.dockerignore` 가 아니라 `frontend/.dockerignore` 가
  적용된다(`node_modules`·`dist` 제외).
- **Vitest 게이트는 여전히 GitHub Actions 에 있다.** `ci.yml` 의 `frontend-ghcr` 는 `needs` 에
  `frontend-tests` 를 달아 "테스트 빨간불이면 이미지도 안 나간다"를 강제하는데, 여기서 수동으로 굽는 경로엔
  그 연결이 없다. 테스트가 깨진 커밋도 그냥 구워진다 — push 후 Actions 결과를 보고 돌리는 것이 전제다.

## GitHub Actions 쪽 정리

k3s 빌드가 안정되면 `ci.yml` 의 `backend-ghcr`·`frontend-ghcr` 잡을 끈다. 둘 다 `main` 필수 체크 6종에
포함돼 있지 않으므로 PR 흐름은 영향받지 않는다. 다만 이 잡들에 딸린 것이 있으니 옮겨진 뒤에 끌 것:

1. Trivy CRITICAL 게이트(백엔드·프론트 각 1개) → `build.sh --scan` 으로 대체됨
2. ArgoCD image updater 가 보는 태그 생산 → 태그 규칙을 동일하게 맞췄으므로 `helm-deploy` 쪽은 수정 불필요
3. `frontend-ghcr` 의 `needs: [frontend-ci, frontend-tests]` 테스트 게이트 → **대체물이 없다.**
   위 "프론트엔드에만 해당하는 것" 마지막 항목 참고. 이걸 감수할 수 없으면 `frontend-ghcr` 는 Actions 에 남긴다.

## 문제 해결

```bash
kubectl -n build get jobs                                  # 진행 상황
kubectl -n build logs -f job/<job-name>                    # 빌드 로그(--progress=plain)
kubectl -n build logs deploy/buildkitd --tail=100          # 데몬 이상
kubectl -n build describe job/<job-name>                   # 파드가 아예 안 만들어질 때
kubectl -n build exec deploy/buildkitd -- buildctl du       # 캐시 사용량
kubectl -n build exec deploy/buildkitd -- buildctl prune    # 캐시 비우기
```

- Job 파드가 `Pending` 이면 먼저 데몬 파드와 PVC 를 본다(→ `k8s-storage-pvc` 스킬).
- `failed to solve: failed to fetch` = git 컨텍스트 접근 실패. 브랜치가 origin 에 있는지 확인한다.
- `unexpected status: 403` = GHCR 자격 문제. `ghcr-auth` 시크릿의 PAT 스코프(`write:packages`)를 확인한다.
