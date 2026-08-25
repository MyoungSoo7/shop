# ADR 0044 — 데모 배포는 당분간 Docker Compose 에 남긴다 (쿠버네티스 이관 보류)

- 상태: Accepted
- 일자: 2026-08-25
- 관련: [ADR 0043](0043-board-education-absorbed-into-operation.md)(서비스 통합으로 17개 compose 서비스 확정) ·
  `deploy/david/docker-compose.override.yml` · `k8s/buildkit/` · `monitoring/alert-rules.yml`

## 컨텍스트

settlement 에서 쇼핑몰 기능만 떼어 이 저장소를 만들었고, 데모(`shop.lemuel.co.kr`)는
david 노드에서 `docker compose` 로 돌고 있다. 같은 랜에 K3s 클러스터(5노드)가 이미 있고
ArgoCD·image-updater·kube-prometheus-stack 이 운영 중이므로, **"배포를 compose 밖으로
빼서 클러스터로 올릴 것인가"** 가 열려 있었다.

결론부터: **보류한다.** 대신 compose 배포가 실제로 안고 있던 두 결함(추적 안 되는 배포
디렉터리 · 노드에만 존재하는 오버라이드)을 이번에 닫는다. 근거는 아래 넷이다.

### ① 이관 비용이 "매니페스트 0에서 시작"이다

- `helm-deploy` 저장소 전체에서 `shop` 문자열은 **0건**이다. 차트도, ArgoCD Application 도 없다.
  (있는 것은 `settlement-prod` / `settlement-msa` 뿐)
- 이 저장소의 `k8s/` 에는 **buildkit 잡 템플릿 7개**만 있다. Deployment·Service·Ingress 는 한 개도 없다.

즉 이관은 "설정을 옮기는" 작업이 아니라 **17개 서비스의 K8s 매니페스트를 새로 쓰는** 작업이다.
그 대가로 얻는 것이 지금은 없다 — 아래 ②·③ 참고.

### ② compose 런타임 자체는 문제를 일으킨 적이 없다

실측(2026-08-25, david):

| 확인 항목 | 값 |
|---|---|
| `systemctl is-enabled docker` | `enabled` |
| `restart: unless-stopped` 선언 | 17/17 서비스 |
| 호스트 uptime | 11주 5일 |
| 컨테이너 | 10개 전부 `Up`, 헬스체크 있는 9개 전부 `healthy` |

재부팅 생존·자동 복구는 구조적으로 이미 보장돼 있다. 쿠버네티스로 옮겨서 **고쳐질 장애가
관측되지 않았다.** 단일 노드에 얹는 단일 데모에서 K8s 가 추가로 주는 것(다중 노드 스케줄링,
롤링 업데이트, HPA)은 지금 필요가 없는 기능이다.

### ③ 진짜 결함은 런타임이 아니라 **provenance** 였다

`/home/david/shop` 은 **git 체크아웃이 아니다** (`fatal: not a git repository`).
돌아가는 스택이 어느 커밋인지 확인할 방법이 구조적으로 없었다.

지금 이 순간은 우연히 일치한다 — `docker-compose.yml` 의 md5 가 david 와 이 저장소 HEAD
양쪽 모두 `1172a288239272c22f2e1f669d4e7da4` 다. 하지만 그건 *확인된 사실*이지 *보장*이 아니다.
누가 노드에서 한 줄 고치면 아무도 모른다.

그리고 `docker-compose.override.yml` 은 **david 에만 있었다**(저장소 미추적). 헤더에는
이관 전 도메인인 `dart.lemuel.co.kr` 이 그대로 남아 있었다. 이 파일이 없으면 프론트가
loopback 에만 묶여 Cloudflare Tunnel 이 붙지 못한다 — 즉 **데모 노출의 필수 조각이
저장소 밖에 있었다.**

이 두 가지는 쿠버네티스로 옮겨야 고쳐지는 문제가 아니다. 추적하면 고쳐진다.

### ④ 관측 스택은 뜬 적이 없다 — 이건 플랫폼 문제가 아니다

compose 는 17개 서비스를 정의하지만 david 에서 실행 중인 것은 **10개**다.
`prometheus` · `alertmanager` · `grafana` · `tempo` · `kafka-exporter` ·
`postgres-exporter` · `redis-exporter` 7개는 `docker ps -a` 에도 없다 = **한 번도 생성된 적 없다.**

따라서 `monitoring/alert-rules.yml` 은 이 노드에서 **평가되지 않는다.** 같은 날 그 파일에서
발화 불가 알람 5건을 고치거나 지웠지만, 그건 *저장소 정합성* 수정이지 운영 중인 알람을
고친 것이 아니다 — 이 문서는 그 구분을 남기려고 쓴다.

관측이 필요해지면 선택지는 두 개다: (a) compose 의 7개를 그냥 띄운다, (b) 클러스터의
기존 kube-prometheus-stack 에 david 를 스크레이프 대상으로 물린다. **(b) 가 이관의 진짜
동기가 될 수 있는 유일한 항목**이지만, 그것도 전체 이관 없이 exporter 만 노출해서 달성된다.

## 결정

1. **데모 배포는 Docker Compose 로 유지한다.** 쿠버네티스 이관은 아래 트리거 전까지 하지 않는다.
2. **오버라이드를 저장소로 들여온다** — `deploy/david/docker-compose.override.yml`.
   **저장소 루트에 두지 않는다.** compose 는 작업 디렉터리의 `docker-compose.override.yml` 을
   자동으로 읽으므로, 루트에 두면 모든 개발자의 로컬 기동이 프론트를 `0.0.0.0:3000` 으로 열게 된다.
   david 에서만 `-f` 로 명시해 쓴다:
   ```bash
   docker compose -f docker-compose.yml -f deploy/david/docker-compose.override.yml up -d
   ```
3. **`/home/david/shop` 을 git 체크아웃으로 바꾼다** — 단, 이 작업은 스택 재기동을 수반하므로
   이 ADR 에서는 *결정만* 하고 실행하지 않았다. 현재 파일이 HEAD 와 바이트 동일하다는 것은
   확인해 뒀으므로(위 ③), 재배포 없이 `git init` + `remote add` + `reset --hard` 로 붙일 수 있다.

## 재검토 트리거

아래 중 하나라도 참이 되면 이 결정을 다시 연다.

- 데모가 단일 노드로 감당 안 되는 트래픽을 받는다 (지금은 포트폴리오 데모다).
- 무중단 배포가 요구된다 (지금은 `docker compose up -d` 의 짧은 끊김이 허용된다).
- 관측 스택을 **실제로 운영**하기로 한다 → 클러스터의 kube-prometheus-stack 재사용 이득이 생긴다.
- 스테이징 등 **두 번째 환경**이 필요해진다 → 환경별 값 분리는 Helm 이 compose 보다 낫다.

## 하지 않은 것 (명시)

- david 의 실행 중인 스택을 재기동하지 않았다. 저장소에 추가한 오버라이드는 **현재 노드에 있는
  파일과 동등**하고(도메인 주석만 갱신), 다음 배포 때 자연히 이 경로를 쓰게 된다.
- `k8s/` 에 Deployment/Service/Ingress 를 추가하지 않았다. 쓰지 않을 매니페스트를 두면
  "쿠버네티스로 돌고 있다"는 잘못된 인상만 남는다.
