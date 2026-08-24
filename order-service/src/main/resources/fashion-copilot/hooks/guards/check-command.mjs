#!/usr/bin/env node
/**
 * PreToolUse(Bash) guard — 운영 DB 직접 쓰기·토픽 직접 produce 등을 차단한다.
 */
import { checkCommand, formatViolations } from './rules.mjs';

let input = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) input += chunk;

let payload;
try { payload = JSON.parse(input); } catch { process.exit(0); }

const command = payload.tool_input?.command ?? '';
if (!command) process.exit(0);

const violations = checkCommand(command);
if (violations.length === 0) process.exit(0);

const hasBlock = violations.some(v => v.severity === 'BLOCK');
console.error(`fashion-copilot guard — command blocked?\n${formatViolations(violations)}`);
process.exit(hasBlock ? 2 : 0);
