/**
 * fashion-copilot guard rules — 파일 내용/명령어에 대한 정규식 1차 검사.
 * check-write.mjs(에이전트 훅), pre-commit.mjs(git 폴백) 가 공유한다.
 *
 * 반환 violation: { rule, severity: 'BLOCK'|'WARN', line, message }
 */

const MONEY_WORD = '(amount|price|discount|refund|fee|total|balance|subtotal|krw|money|할인|환불|금액)';

// 금액 스코프 판단 — 커머스 금전 경로 패키지의 Java/Kotlin 파일만 money 규칙 적용
export function isMoneyScope(filePath) {
  return /\.(java|kt)$/.test(filePath)
    && /(order|payment|coupon|product|cart|shipping|checkout)/i.test(filePath);
}

export function checkFileContent(filePath, content) {
  const violations = [];
  const lines = content.split('\n');
  const path = filePath.replace(/\\/g, '/');

  const push = (rule, severity, i, message) =>
    violations.push({ rule, severity, line: i + 1, message });

  lines.forEach((raw, i) => {
    const line = raw.trim();
    if (line.startsWith('//') || line.startsWith('*') || line.startsWith('#')) return;

    // ── money-type-guard ──────────────────────────────────────────────
    if (isMoneyScope(path)) {
      const floatDecl = new RegExp(`\\b(float|double|Float|Double)\\s+\\w*${MONEY_WORD}\\w*`, 'i');
      const floatParse = new RegExp(`${MONEY_WORD}\\w*\\s*=[^=].*\\.(parseDouble|parseFloat|doubleValue|floatValue)\\s*\\(`, 'i');
      const bdFromDouble = /new\s+BigDecimal\s*\(\s*\d+\.\d+\s*\)/;
      if (floatDecl.test(line) || floatParse.test(line)) {
        push('money-type-guard', 'BLOCK', i,
          '금액(가격/할인/환불액)을 float/double 로 다루고 있습니다. BigDecimal 을 사용하세요 (쿠폰 절사는 FLOOR 가 표준).');
      }
      if (bdFromDouble.test(line)) {
        push('money-type-guard', 'BLOCK', i,
          'new BigDecimal(double 리터럴) 은 정밀도 오염 — new BigDecimal("문자열") 로 생성하세요.');
      }
    }

    // ── stock-atomicity-guard (오버셀 방지) ───────────────────────────
    if (/UPDATE\s+(products|product_variants)\b/i.test(line)
        && /\bstock/i.test(line)
        && !/stock(_quantity)?\s*>=/.test(line)) {
      push('stock-atomicity-guard', 'BLOCK', i,
        '재고 UPDATE 에 stock >= :qty 조건이 없습니다 — 드랍 동시성에서 오버셀로 직결. 원자적 조건부 UPDATE(decreaseStockIfAvailable 패턴)를 사용하세요.');
    }
    if (/\.(java|kt)$/.test(path) && /src\/main\//.test(path)
        && /\w+\.setStockQuantity\s*\(/.test(line)) {
      push('stock-atomicity-guard', 'WARN', i,
        'setStockQuantity 직접 호출 — read-modify-write 재고 변경은 차감 유실(오버셀) 위험. 조건부 UPDATE 경로(decreaseStockIfAvailable/increaseStock)를 경유하세요.');
    }

    // ── coupon-usage-guard (초과 사용 방지) ───────────────────────────
    if (/\w+\.setUsedCount\s*\(/.test(line)
        || /usedCount\s*(\+\+|\+=|=\s*\w*[Uu]sedCount\s*\+)/.test(line)) {
      push('coupon-usage-guard', 'BLOCK', i,
        '쿠폰 usedCount 직접 증감 감지 — 동시 요청에서 초과 사용(중복 할인)됩니다. 원자적 incrementUsageIfAvailable 을 경유하세요.');
    }

    // ── pii-logging-guard (배송지·연락처) ─────────────────────────────
    if (/\blog(ger)?\.(info|debug|warn|error|trace)\s*\(/.test(line)
        && /(phone|mobile|address|recipient|receiver.?[Nn]ame|zipcode|배송지|수취인|연락처|주소)/i.test(line)
        && !/mask/i.test(line)) {
      push('pii-logging-guard', 'BLOCK', i,
        '배송지/연락처/수취인 정보가 로그에 평문으로 들어갑니다 — common.audit 마스킹 유틸을 경유하세요.');
    }
  });

  // ── migration-guard (파일 단위) ─────────────────────────────────────
  const mig = path.match(/db\/migration\/(V[^/]+)\.sql$/);
  if (mig) {
    if (!/^V\d{14}__/.test(mig[1]) && !/^V\d{1,3}__/.test(mig[1])) {
      violations.push({ rule: 'migration-guard', severity: 'WARN', line: 0,
        message: `마이그레이션 파일명 규칙 위반: ${mig[1]} — 신규는 V{timestamp}__ (예: V20260707120000__) 권장.` });
    }
    lines.forEach((raw, i) => {
      if (/DROP\s+(TABLE|COLUMN|INDEX)/i.test(raw) && !/IF\s+EXISTS.*_tmp|_backup/i.test(raw)) {
        violations.push({ rule: 'migration-guard', severity: 'WARN', line: i + 1,
          message: '파괴적 DDL(DROP) — 주문/결제 데이터 보존 및 리뷰/쿠폰 UNIQUE 제약 영향 확인 후 expand-contract 절차로 진행하세요.' });
      }
    });
  }

  return violations;
}

export function checkCommand(command) {
  const violations = [];
  const push = (rule, severity, message) => violations.push({ rule, severity, line: 0, message });

  // prod-db-guard: 커머스 DB(opslab) 직접 쓰기
  // write-verb 는 단어 경계 + 후행 공백 필수 — 'updated_at' 같은 컬럼명 오탐 방지
  if (/\b(psql|pgcli|pg_dump)\b/.test(command)
      && /\bopslab\b/.test(command)
      && /\b(UPDATE|DELETE|INSERT|TRUNCATE|ALTER)\s/i.test(command)) {
    push('prod-db-guard', 'BLOCK',
      '커머스 DB(opslab)에 직접 쓰기 시도 — 재고/환불/쿠폰 정정은 서비스 API 경로로만. 조회는 MCP 도구(refund_recon, stock_pulse)를 사용하세요.');
  }
  if (/kubectl\s+exec/.test(command) && /(psql|pg_dump)/.test(command)) {
    push('prod-db-guard', 'BLOCK',
      '운영 파드에서 DB 직접 접속 시도 — MCP 도구 또는 승인된 러너북 절차를 사용하세요.');
  }
  // 이벤트 직접 produce (멱등/Outbox 우회 위험)
  if (/(rpk\s+topic\s+produce|kafka-console-producer)/.test(command) && /lemuel\./.test(command)) {
    push('event-produce-guard', 'WARN',
      '운영 토픽에 직접 produce — Outbox 를 우회하면 event_id 멱등 체계가 깨질 수 있습니다. 테스트 목적이면 -z none 및 테스트 토픽을 사용하세요.');
  }
  return violations;
}

export function formatViolations(violations) {
  return violations
    .map(v => `[${v.severity}] ${v.rule}${v.line ? ` (line ${v.line})` : ''}: ${v.message}`)
    .join('\n');
}
