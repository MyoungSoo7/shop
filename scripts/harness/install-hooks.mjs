#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function git(cwd, args) {
  const result = spawnSync('git', ['-C', cwd, ...args], { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || 'git command failed').trim());
  }
  return result.stdout.trim();
}

export function findGitRoot(cwd) {
  return resolve(git(cwd, ['rev-parse', '--show-toplevel']));
}

export async function installHooks({
  cwd = process.cwd(),
  stdout = (message) => process.stdout.write(message),
  stderr = (message) => process.stderr.write(message),
} = {}) {
  try {
    const root = findGitRoot(cwd);
    git(root, ['config', 'core.hooksPath', 'scripts/harness/hooks']);
    stdout('core.hooksPath -> scripts/harness/hooks (harness guard active on commit)\n');
    return root;
  } catch (error) {
    stderr(`install-hooks: ${error.message}\n`);
    throw error;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  installHooks().catch(() => { process.exitCode = 1; });
}
