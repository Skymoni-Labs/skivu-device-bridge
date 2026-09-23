import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bridgeArgs, findPython, mcpConfig } from '../bin/phone-use.mjs';

test('real launcher carries MCP JSON through stdio without banners', () => {
  const directory = mkdtempSync(join(tmpdir(), 'phone-use-launch-'));
  try {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('../bin/phone-use.mjs', import.meta.url)),
      '--config', join(directory, 'registry.json')], {
      input: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize',
        params: { protocolVersion: '2025-11-25' } }) + '\n',
      encoding: 'utf8', timeout: 25000,
    });
    assert.equal(result.status, 0, result.stderr);
    const reply = JSON.parse(result.stdout);
    assert.equal(reply.id, 1);
    assert.equal(reply.result.protocolVersion, '2025-11-25');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('Python detection falls back and rejects older or broken runtimes', () => {
  const tried = [];
  const found = findPython({}, (command, args, options) => {
    tried.push([command, args]);
    assert.equal(options.shell, false);
    return { status: command === 'python' ? 0 : 1, stdout: JSON.stringify('C:/Real Python/python.exe') };
  }, 'win32');
  assert.equal(found.command, 'C:/Real Python/python.exe');
  assert.deepEqual(found.prefix, []);
  assert.equal(tried[0][0], 'py');
  assert.equal(tried[0][1][0], '-3');
});

test('explicit Python executable is one argument, with no silent fallback', () => {
  assert.throws(() => findPython({ PHONEUSE_PYTHON: 'C:/My Python/python.exe' }, command => {
    assert.equal(command, 'C:/My Python/python.exe');
    return { error: new Error('missing'), status: null };
  }), /Python 3.10/);
});

test('bridge preserves unicode, spaces and metacharacters without shell evaluation', () => {
  const args = bridgeArgs(['--name', '中文 & $(echo test)', '--config', 'C:/My Phone/配置.json', '--url', 'https://127.0.0.1:8443']);
  assert.deepEqual(args.slice(1), ['--config', 'C:/My Phone/配置.json', 'mcp-stdio', '--name',
    '中文 & $(echo test)', '--url', 'https://127.0.0.1:8443']);
  assert.throws(() => bridgeArgs(['--config']), /requires a path/);
});

test('configuration uses downloadable package instead of cache paths', () => {
  const unix = JSON.parse(mcpConfig('json', 'linux')).mcpServers['phone-use'];
  assert.equal(unix.command, 'npx');
  assert.deepEqual(unix.args, ['-y', '--package=github:nidhoggfgg/PhoneUse', 'phone-use']);
  const windows = JSON.parse(mcpConfig('json', 'win32')).mcpServers['phone-use'];
  assert.equal(windows.command, 'cmd');
  assert.equal(windows.args.at(-1), 'phone-use');
  assert.match(mcpConfig('codex', 'win32'), /startup_timeout_sec = 120/);
  assert.throws(() => mcpConfig('unsupported'), /Host must be/);
});
