#!/usr/bin/env node
/**
 * PreToolUse(Write|Edit) guard — stdin 으로 훅 JSON 을 받아 파일 내용을 검사한다.
 * BLOCK 발견 시 exit 2 (에이전트에 사유 전달·차단), WARN 만 있으면 stderr 안내 + 통과.
 */
import { checkFileContent, formatViolations } from './rules.mjs';

let input = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) input += chunk;

let payload;
try { payload = JSON.parse(input); } catch { process.exit(0); }

const ti = payload.tool_input ?? {};
const filePath = ti.file_path ?? '';
const content = ti.content ?? ti.new_string ?? '';
if (!filePath || !content) process.exit(0);

const violations = checkFileContent(filePath, content);
if (violations.length === 0) process.exit(0);

const hasBlock = violations.some(v => v.severity === 'BLOCK');
console.error(`fashion-copilot guard — ${filePath}\n${formatViolations(violations)}`);
process.exit(hasBlock ? 2 : 0);
