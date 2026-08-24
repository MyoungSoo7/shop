#!/usr/bin/env node
// JaCoCo XML 리포트를 "어느 클래스가 몇 줄 비었는지" 표로 바꾼다.
//
// 커버리지 작업의 첫 단계는 항상 "어디가 비었는가"인데, HTML 리포트는 브라우저가 있어야 하고
// 게이트 출력은 총합만 준다. 이 스크립트는 미달 클래스를 놓친 줄 수 내림차순으로 뽑아,
// 테스트를 붙일 순서를 그대로 알려준다.
//
//   node scripts/coverage/jacoco-report.mjs <build/reports/jacoco/test/jacocoTestReport.xml> [--gate]
//
// `--gate` 는 게이트(jacocoTestCoverageVerification)와 **같은 범위**로 좁혀서 다시 센다.
// 리포트 XML 자체에는 게이트의 제외 패턴이 적용돼 있지 않아, 그냥 읽으면 게이트가 보지도 않는
// 어댑터·설정 클래스가 목록 맨 위를 채운다 — 고칠 곳을 정반대로 가리킨다.
import { readFileSync } from 'node:fs';

const file = process.argv[2];
const gateScope = process.argv.includes('--gate');
if (!file) {
  console.error('usage: node scripts/coverage/jacoco-report.mjs <jacocoTestReport.xml> [--gate]');
  process.exit(2);
}
const xml = readFileSync(file, 'utf8');

// 루트 build.gradle.kts 의 jacocoTestCoverageVerification 제외 패턴과 1:1 대응.
// 여기를 고치면 저쪽도 같이 고칠 것 — 어긋나면 이 스크립트가 게이트를 오보한다.
const GATE_EXCLUDED = [
  'adapter/out/persistence/', 'adapter/out/readmodel/', 'adapter/out/search/',
  'adapter/out/event/', 'adapter/out/pdf/', 'adapter/out/external/',
  'adapter/out/notification/', 'adapter/out/mail/', 'adapter/out/security/',
  'adapter/out/monitoring/', 'adapter/out/user/', 'adapter/out/pg/', 'adapter/out/llm/',
  'adapter/in/web/', 'adapter/in/kafka/', 'adapter/in/batch/', 'adapter/in/api/',
  'adapter/in/dto/', 'config/', 'util/',
];
// 부트스트랩 클래스는 **이름을 하나씩** 적어 둔 목록이다(게이트도 그렇다). 와일드카드로 뭉뚱그리면
// 목록에 없는 모듈(organization·card)의 Application 까지 빼게 되어 게이트보다 후한 수치가 나온다.
const GATE_EXCLUDED_CLASSES = [
  'LemuelApplication', 'SettlementServiceApplication', 'GatewayServiceApplication',
  'FinancialStatementsApplication', 'CompanyServiceApplication', 'OperationServiceApplication',
  'EconomicsApplication', 'MarketApplication', 'CommonDataApplication', 'AiServiceApplication',
  'InvestmentServiceApplication', 'AccountServiceApplication', 'InsuranceServiceApplication',
  'DepositServiceApplication', 'BoardServiceApplication', 'EducationServiceApplication',
];
const isExcluded = (name) => {
  if (GATE_EXCLUDED.some((segment) => `${name}/`.includes(`/${segment}`))) return true;
  const simple = name.split('/').pop().split('$')[0];
  return GATE_EXCLUDED_CLASSES.includes(simple);
};

const classRe = /<class name="([^"]+)"[^>]*>([\s\S]*?)<\/class>/g;
const lineCounterRe = /<counter type="LINE" missed="(\d+)" covered="(\d+)"\s*\/>/g;
const rows = [];
let m;
while ((m = classRe.exec(xml)) !== null) {
  // class 요소 안에는 method 별 counter 가 먼저 나오고 클래스 총합이 맨 뒤에 온다.
  // 첫 번째를 집으면 메서드 하나의 수치를 클래스 수치로 착각한다.
  const counters = [...m[2].matchAll(lineCounterRe)];
  if (counters.length === 0) continue;
  const lc = counters[counters.length - 1];
  const missed = Number(lc[1]);
  const covered = Number(lc[2]);
  const name = m[1];
  if (gateScope && isExcluded(name)) continue;
  rows.push({ name, missed, covered, total: missed + covered });
}

let totalMissed;
let totalCovered;
if (gateScope) {
  totalMissed = rows.reduce((sum, r) => sum + r.missed, 0);
  totalCovered = rows.reduce((sum, r) => sum + r.covered, 0);
} else {
  // 보고서 말미의 최상위 counter 가 번들 총합이다.
  const all = [...xml.matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\s*\/>/g)];
  if (all.length === 0) {
    console.error('LINE counter 가 없다 — 측정 대상이 0개인지 확인할 것.');
    process.exit(1);
  }
  const last = all[all.length - 1];
  totalMissed = Number(last[1]);
  totalCovered = Number(last[2]);
}
const totalLines = totalMissed + totalCovered;
if (totalLines === 0) {
  console.error('측정 대상이 0줄이다 — 게이트가 공전하는 상태인지 확인할 것.');
  process.exit(1);
}

rows.sort((a, b) => b.missed - a.missed);
for (const r of rows) {
  if (r.missed === 0) continue;
  const pct = r.total ? ((r.covered / r.total) * 100).toFixed(1) : '0.0';
  console.log(`${String(r.missed).padStart(5)} missed  ${pct.padStart(6)}%  ${r.name}`);
}
console.log('---');
console.log(
  `TOTAL LINE: covered=${totalCovered} missed=${totalMissed} total=${totalLines} => ` +
    `${totalLines ? ((totalCovered / totalLines) * 100).toFixed(2) : '0.00'}%`,
);
console.log(`90% 까지 추가로 덮어야 할 라인 수: ${Math.max(0, Math.ceil(0.9 * totalLines - totalCovered))}`);
