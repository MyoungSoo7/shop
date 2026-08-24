# Fashion Copilot

패션 이커머스(무신사류) 도메인 특화 AI 에이전트 플러그인 — Lemuel 커머스 플랫폼(order-service)의
반품·드랍 재고·리뷰·쿠폰 도메인 규칙·검증 도구·가드레일을
**OpenAI Codex CLI** 와 **Claude Code** 에 주입한다.
`settlement-copilot`(settlement-service/src/main/resources/settlement-copilot) 의 자매편.
설계 문서: `docs/design/fashion-copilot-codex-plugin.md`(저장소 미포함)

## ⚡ 퀵스타트 (30초)

Node 18+ 만 있으면 된다. Windows PowerShell / macOS / Linux 동일 — Git Bash 불필요.

```bash
node fashion-copilot/install.mjs codex     # Codex CLI 에 설치 (멱등 — 재실행 = 동기화)
node fashion-copilot/install.mjs claude    # Claude Code 에 설치 (자동 시도 + 수동 안내)
node fashion-copilot/install.mjs doctor    # 설치·연결 진단 (읽기 전용, ✅/⚠️/❌)
```

설치 후 에이전트에서 **`/fashion-help`** 를 치면 커맨드·도구·skill 카탈로그가 안내된다.
뭔가 이상하면 항상 `doctor` 부터 — 누락·구버전·미연결을 조치 명령과 함께 알려준다.

| 서브커맨드 | 하는 일 |
|---|---|
| `codex` | AGENTS.md 마커 병합 + prompts/skills 복사 + config.toml MCP 등록 + pre-commit 가드. `--internal-key=KEY` 로 대사 API 키까지 한 번에 |
| `claude` | marketplace 등록 → 플러그인 설치 자동 시도, 실패 시 복붙 가능한 수동 안내 출력 |
| `doctor` | 무결성(MCP 왕복·가드 자가시험) + Codex/Claude 설치 상태 + order-service 연결 진단 |
| `smoke` | 스모크 테스트 47 어서션 (네트워크 불필요) |
| `uninstall-codex` | 설치 흔적 완전 제거 (타 플러그인 파일은 보존) |

## 해결하는 문제 (무신사형 4축)

| 축 | 문제 | 플러그인의 대응 |
|---|---|---|
| P1 | 사이즈·핏 반품 (패션 최대 비용) | 환불 3-Phase·멱등키·안분 규칙 skill + `refund_recon`/`refund_health`/`refund_simulate` |
| P2 | 한정판 드랍 오버셀 | 원자적 조건부 UPDATE 강제 skill·가드 + `stock_pulse` |
| P3 | 리뷰 어뷰징 | 1인1리뷰·구매검증 공백·패턴 카탈로그 skill + `/review-scan` |
| P4 | 쿠폰·프로모션 마진 누수 | FLOOR 절사·클램프·초과사용 방지 skill·가드 + `coupon_simulate` |

```
fashion-copilot/
├── AGENTS.md               # ① 상시 코어 규칙 (Codex 가 자동 로드)
├── skills/                 # ① 상황별 도메인 지식 4종 (SKILL.md)
├── commands/               # 진입점 5종 (/fashion-help, /drop-check, /return-audit, /coupon-audit, /review-scan)
├── hooks/                  # ③ 가드레일 (Claude Code 훅 + git pre-commit 폴백)
│   └── guards/rules.mjs    #    money/stock-atomicity/coupon-usage/pii/prod-db 규칙
├── mcp/server/index.mjs    # ② 읽기 전용 MCP 서버 (zero-dependency, Node 18+)
├── .claude-plugin/         # Claude Code 플러그인 + 마켓플레이스 매니페스트
├── .codex-plugin/          # Codex 플러그인 매니페스트 (제출 스펙 대응 — .claude-plugin 과 대칭)
├── .mcp.json               # Claude Code MCP 연결 (${ORDER_BASE_URL}/${INTERNAL_API_KEY} 환경변수 확장)
├── install.mjs             # ★ 단일 진입점 CLI — codex/claude/doctor/smoke/uninstall-codex
├── install-codex.sh        # (하위 호환 래퍼 → install.mjs codex)
└── test/smoke.mjs          # 스모크 테스트 47 어서션 (네트워크 불필요)
```

## MCP 도구 (전부 읽기 전용)

| 도구 | 백엔드 | 용도 |
|---|---|---|
| `refund_recon` | order `/internal/recon/daily-totals` + `daily-counts` | 일자별 캡처 vs 환불 금액·건수 — 반품 이상 신호 |
| `refund_health` | Prometheus `refund_*` | 환불 실패율·멱등키 재사용(재시도 폭풍)·처리시간 |
| `stock_pulse` | Prometheus `variant/product_stock_decrease_*` | 재고 차감 성공/거절 비율 — 드랍 품절 러시 vs 노출 버그 판별 |
| `coupon_simulate` | 로컬 계산 (CouponType/Coupon 미러) | 할인 FLOOR·클램프·환불 안분 dry-run |
| `refund_simulate` | 로컬 계산 (RefundPaymentUseCase 미러) | 환불 가능액·전액/부분·멱등키·주문 상태 dry-run |

환경변수: `ORDER_BASE_URL`(기본 :8088), `INTERNAL_API_KEY`(order 내부 API 키),
`COPILOT_ADMIN_TOKEN`(선택 — actuator 인증 환경용).
응답의 배송지/연락처/수취인/계좌 계열 필드는 **서버 측에서 마스킹**되어 에이전트 컨텍스트에 원문이 남지 않는다.

## 설치 상세

### Codex CLI

```bash
node fashion-copilot/install.mjs codex --internal-key=<KEY>   # 키는 선택 — 나중에 재실행해 추가 가능
```

모두 멱등: AGENTS.md 마커 블록 병합(기존 내용 보존) → commands → `$CODEX_HOME/prompts/` →
skills → `$CODEX_HOME/skills/` → config.toml MCP 블록 upsert(타 서버 블록 보존) →
`.git/hooks/pre-commit` 가드 폴백. skill 을 수정했으면 재실행 한 번이면 동기화 끝.
`--codex-home=PATH` 또는 `CODEX_HOME` 환경변수로 위치 변경. 제거는 `uninstall-codex`.

### Claude Code

```bash
node fashion-copilot/install.mjs claude
```

`claude plugin marketplace add` → `claude plugin install fashion-copilot@fashion-copilot` 을
자동 시도하고, CLI 가 없으면 세션 내 `/plugin` 명령을 복붙 가능한 형태로 출력한다.
설치되면 commands/skills/hooks/MCP 가 자동 등록된다. MCP env 는 `${ORDER_BASE_URL:-...}`,
`${INTERNAL_API_KEY:-}` 환경변수 확장이라 셸 환경변수만 설정하면 별도 파일 수정이 없다.

## 진단·검증

```bash
node fashion-copilot/install.mjs doctor   # ✅/⚠️/❌ + 조치 명령 — 뭔가 이상하면 여기부터
node fashion-copilot/install.mjs smoke    # 47 어서션 (네트워크 불필요)
node fashion-copilot/install.mjs package  # 제출물 — dist/accounting.zip (src/+README.md+logs/)
# [1] MCP 서버 왕복 (initialize/tools list/coupon·refund simulate 기대값)
# [2] 가드 규칙 (money/stock/coupon/pii/prod-db 차단·통과 케이스)
# [3] 설치기 멱등성 (2회 실행 수렴·타 설정 보존·uninstall 청소 — 임시 디렉터리)
```

doctor 는 읽기 전용이며 플러그인 무결성(MCP 왕복·가드 자가시험), Codex 동기화 상태(누락/구버전
파일 수), claude CLI 존재, order-service 연결, INTERNAL_API_KEY 설정 여부를 한 번에 점검한다.

## 보안 원칙

- MCP 서버는 **GET 만 라우팅** — 쓰기 API 는 코드에 존재하지 않는다 (read-only by construction).
- PII 마스킹은 에이전트가 아니라 서버 측에서 수행 (배송지·연락처·수취인 포함).
- `INTERNAL_API_KEY` 는 order 내부 대사 API 전용 최소 권한 — 사내 시크릿 매니저로 주입.
- 사내망 배포 전제 — 외부 인터넷 경유 금지.

## 로드맵 (설계서 §8 — 실측으로 확인된 데이터 공백)

- [ ] Phase 2: `return_reason_stats` — 반품 사유 enum/컬럼 신설 선행 (현재 `Refund.reason` 은 유형·실패 겸용)
- [ ] Phase 2: `review_integrity_scan` — reviews↔orders 구매검증 내부 API 신설 선행
- [ ] Phase 3: `oversell_check` — `/internal/stock/recon` 성격 읽기 API 신설 선행
- [ ] Phase 3: split 환불 원장 완결 (`TenderRefundExecutor` refundId=null 역분개 생략 해소)
