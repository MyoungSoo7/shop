# AI Dev Team Orchestrator

요구사항을 받아 8개 Agent를 순서대로 실행하고 결과를 파일로 저장합니다.

## 사용법
```
/ai-dev-team <요구사항>
```

## 실행 순서

$ARGUMENTS를 요구사항으로 사용하세요.

---

### PHASE 1 — PM/BA (필수, 선행)

아래 프롬프트로 PM/BA 분석을 수행하세요:

@.claude/commands/agents/pm-ba.md

요구사항: $ARGUMENTS

결과를 **`docs/ai-dev-team/01-pm-output.json`** 에 저장하세요.
저장 전 JSON 유효성을 반드시 확인하세요. 실패 시 전체 중단.

---

### PHASE 2 — Architect (PM 완료 후)

`docs/ai-dev-team/01-pm-output.json` 을 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/architect.md

결과를 **`docs/ai-dev-team/02-arch-output.json`** 에 저장하세요.
저장 전 JSON 유효성을 반드시 확인하세요. 실패 시 전체 중단.

---

### PHASE 3 — Specialists (Arch 완료 후, 병렬)

`01-pm-output.json` 과 `02-arch-output.json` 을 읽어 아래 5개를 순서대로 실행하세요.
(Claude Code는 순차 실행하되, 각 결과는 독립적으로 저장)

| Agent | 프롬프트 | 저장 경로 |
|-------|---------|---------|
| Backend | @.claude/commands/agents/backend.md | `docs/ai-dev-team/03-backend.md` |
| Frontend | @.claude/commands/agents/frontend.md | `docs/ai-dev-team/04-frontend.md` |
| DA | @.claude/commands/agents/da.md | `docs/ai-dev-team/05-da.md` + `docs/ai-dev-team/V1__init.sql` |
| QA | @.claude/commands/agents/qa.md | `docs/ai-dev-team/06-qa.md` |
| Prototype | @.claude/commands/agents/prototype.md | `docs/ai-dev-team/07-prototype.html` |
---

### PHASE 4 — Reviewer (Specialists 완료 후)

`docs/ai-dev-team/` 하위 모든 파일을 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/reviewer.md

결과를 **`docs/ai-dev-team/08-review.md`** 에 저장하세요.

---

### PHASE 5 — Sequence Diagram (Specialists 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `03-backend.md`, `05-da.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/sequence-diagram.md

결과를 **`docs/ai-dev-team/09-sequence-diagram.md`** 에 저장하세요.

---

### PHASE 6 — Functional Spec (Specialists 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `03-backend.md`, `04-frontend.md`, `05-da.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/functional-spec.md

결과를 **`docs/ai-dev-team/10-functional-spec.md`** 에 저장하세요.

---

### PHASE 7 — Test Plan (Functional Spec + QA 완료 후)

`01-pm-output.json`, `03-backend.md`, `04-frontend.md`, `05-da.md`, `06-qa.md`, `10-functional-spec.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/test-plan.md

결과를 **`docs/ai-dev-team/11-test-plan.md`** 에 저장하세요.

---

### PHASE 7 — Reviewer (위 전체 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `03-backend.md`, `04-frontend.md`, `05-da.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/security.md

결과를 **`docs/ai-dev-team/12-security.md`** 에 저장하세요.

> ⚠️ Critical / High 항목이 발견되면 해당 내용을 Phase 10 DevOps에 반드시 전달하세요.

---

### PHASE 10 — DevOps (Security 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `11-review.md`, **`12-security.md`** 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/devops.md

결과를 **`docs/ai-dev-team/13-devops.md`** 에 저장하세요.

추가로 아래 파일도 생성하세요:
- **`docs/ai-dev-team/Dockerfile.backend`**
- **`docs/ai-dev-team/Dockerfile.frontend`**
- **`docs/ai-dev-team/docker-compose.yml`**
- **`docs/ai-dev-team/.github/workflows/ci.yml`**
- **`docs/ai-dev-team/.github/workflows/cd.yml`**

---

### PHASE 13 — Troubleshooting (DevOps 완료 후)

`02-arch-output.json`, `03-backend.md`, `05-da.md`, `12-security.md`, `13-devops.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/troubleshooting.md

결과를 **`docs/ai-dev-team/17-troubleshooting.md`** 에 저장하세요.

---

### PHASE 14 — Cost Estimation (DevOps 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `13-devops.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/cost-estimation.md

결과를 **`docs/ai-dev-team/18-cost.md`** 에 저장하세요.

---

### PHASE 15 — Roadmap (Cost 완료 후)

`01-pm-output.json`, `02-arch-output.json`, `03-backend.md`, `04-frontend.md`, `05-da.md`, `10-functional-spec.md`, `11-test-plan.md`, `12-security.md`, `13-devops.md`, `18-cost.md` 를 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/roadmap.md

결과를 **`docs/ai-dev-team/19-roadmap.md`** 에 저장하세요.

---

### PHASE 16 — Docs (전체 완료 후)

`docs/ai-dev-team/` 하위 전체 파일을 읽어 아래 프롬프트로 실행하세요:

@.claude/commands/agents/docs.md

결과 파일:
- **`/CLAUDE.md`** ← 프로젝트 루트 (최우선)
- **`/README.md`** ← 프로젝트 루트
- **`/CONTRIBUTING.md`** ← 프로젝트 루트
- **`../../docs/adr/ADR-00{N}-{결정명}.md`** ← key_decisions 수만큼
- **`docs/ai-dev-team/openapi.yaml`**

추가로 아래 파일도 생성하세요:
- **`docs/ai-dev-team/Dockerfile.backend`** — Backend 멀티스테이지 Dockerfile
- **`docs/ai-dev-team/Dockerfile.frontend`** — Frontend 멀티스테이지 Dockerfile
- **`docs/ai-dev-team/docker-compose.yml`** — 로컬 개발용 전체 스택
- **`docs/ai-dev-team/.github/workflows/ci.yml`** — CI 파이프라인
- **`docs/ai-dev-team/.github/workflows/cd.yml`** — CD 파이프라인

---

### 완료 후 출력

모든 단계 완료 시 아래 형식으로 요약 출력:

```
✅ AI Dev Team 분석 완료
─────────────────────────────
도메인   : {domain}
저장 위치 : docs/ai-dev-team/
─────────────────────────────
01 PM/BA        ✅
02 Architect    ✅
03 Backend      ✅
04 Frontend     ✅
05 DA           ✅
06 QA           ✅
07 Prototype    ✅
08 Reviewer          ✅  docs/ai-dev-team/08-review.md
09 Specialist 병렬   ✅  (위 Phase 3 포함)
10 Docs (Phase 5~6) —   (아래 참조)
11 Security          ✅  docs/ai-dev-team/11-security.md
12 DevOps            ✅  docs/ai-dev-team/12-devops.md
13 Cost Estimation   ✅  docs/ai-dev-team/13-cost.md
14 Docs              ✅  CLAUDE.md · README.md · CONTRIBUTING.md · ADR
─────────────────────────────
생성 파일:
  /
  ├── CLAUDE.md                         ← Claude Code 컨텍스트
  ├── README.md
  ├── CONTRIBUTING.md
  └── docs/
      ├── adr/
      │   ├── ADR-001-{결정명}.md
      │   └── ADR-00N-{결정명}.md
      └── ai-dev-team/
          ├── 01-pm-output.json
          ├── 02-arch-output.json
          ├── 03-backend.md
          ├── 04-frontend.md
          ├── 05-da.md
          ├── V1__init.sql
          ├── 06-qa.md
          ├── 07-prototype.html
          ├── 08-review.md
          ├── 11-security.md
          ├── 12-devops.md
          ├── 13-cost.md
          ├── 14-roadmap.md
          ├── openapi.yaml
          ├── Dockerfile.backend
          ├── Dockerfile.frontend
          ├── docker-compose.yml
          └── .github/workflows/
              ├── ci.yml
              └── cd.yml
─────────────────────────────
```
