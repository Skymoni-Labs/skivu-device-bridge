#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';

export const PACKAGE = 'github:nidhoggfgg/PhoneUse';
const client = fileURLToPath(new URL('../tools/phoneuse_client.py', import.meta.url));

export function mcpConfig(host, platform = process.platform) {
  // On Windows, cmd is needed for npm's npx.cmd shim. All shell arguments here
  // are fixed constants; phone addresses and other user data go through MCP.
  const server = platform === 'win32'
    ? { command: 'cmd', args: ['/d', '/c', 'npx', '-y', `--package=${PACKAGE}`, 'phone-use'] }
    : { command: 'npx', args: ['-y', `--package=${PACKAGE}`, 'phone-use'] };
  if (host === 'codex') {
    return `[mcp_servers.phone-use]\ncommand = ${JSON.stringify(server.command)}\nargs = ${JSON.stringify(server.args)}\nstartup_timeout_sec = 120\ntool_timeout_sec = 120\n`;
  }
  if (host !== 'json') throw new Error('Host must be json or codex');
  return JSON.stringify({ mcpServers: { 'phone-use': server } }, null, 2) + '\n';
}

export function findPython(env = process.env, probe = spawnSync, platform = process.platform) {
  const candidates = env.PHONEUSE_PYTHON
    ? [[env.PHONEUSE_PYTHON, []]]
    : platform === 'win32'
      ? [['py', ['-3']], ['python', []], ['python3', []]]
      : [['python3', []], ['python', []]];
  for (const [command, prefix] of candidates) {
    const result = probe(command, [...prefix, '-c',
      'import json,sys; print(json.dumps(sys.executable)); sys.exit(0 if sys.version_info >= (3, 10) else 1)'],
    { env, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8', timeout: 5000, windowsHide: true, shell: false });
    if (!result.error && result.status === 0) {
      try {
        const executable = JSON.parse(result.stdout.trim());
        // Launch the actual interpreter, avoiding Windows Store alias filesystem
        // redirection that can hide npm's AppData cache from Python.
        if (typeof executable === 'string' && executable) return { command: executable, prefix: [] };
      } catch { /* Try the next interpreter if the probe output is unusable. */ }
    }
  }
  throw new Error('Python 3.10+ not found. Install Python or set PHONEUSE_PYTHON to its executable path (without arguments).');
}

export function bridgeArgs(args) {
  // The existing client accepts --config before its subcommand.
  const remaining = [...args];
  if (remaining[0] === 'mcp-stdio') remaining.shift();
  const global = [];
  for (let i = 0; i < remaining.length; i++) {
    if (remaining[i] === '--config') {
      if (!remaining[i + 1] || remaining[i + 1].startsWith('--')) throw new Error('--config requires a path');
      global.push(...remaining.splice(i, 2));
      i--;
    } else if (remaining[i].startsWith('--config=')) {
      global.push(...remaining.splice(i, 1));
      i--;
    }
  }
  return [client, ...global, 'mcp-stdio', ...remaining];
}

export function main(args = process.argv.slice(2)) {
  if (args[0] === '--help' || args[0] === '-h') {
    process.stdout.write(`Phone Use MCP\n\nUsage: phone-use [--url https://PHONE:8443] [--name NAME] [--config PATH]\n       phone-use print-config [--host json|codex]\n\nRequires Python 3.10+. PHONEUSE_PYTHON can select its executable.\nPair using phoneuse_connect in your AI client; approve on the phone.\n`);
    return;
  }
  if (args[0] === 'print-config') {
    if (args.length !== 1 && !(args.length === 3 && args[1] === '--host')) {
      throw new Error('Usage: phone-use print-config [--host json|codex]');
    }
    process.stdout.write(mcpConfig(args[2] || 'json'));
    return;
  }
  const { command, prefix } = findPython();
  const child = spawn(command, [...prefix, ...bridgeArgs(args)], {
    stdio: 'inherit', windowsHide: true, shell: false,
  });
  const signals = ['SIGINT', 'SIGTERM'];
  const handlers = signals.map(signal => {
    const handler = () => child.kill(signal);
    process.on(signal, handler);
    return handler;
  });
  child.on('error', error => {
    process.stderr.write(`Phone Use: ${error.message}\n`);
    process.exitCode = 1;
  });
  child.on('close', (code, signal) => {
    signals.forEach((name, i) => process.removeListener(name, handlers[i]));
    process.exitCode = code ?? (signal === 'SIGINT' ? 130 : signal === 'SIGTERM' ? 143 : 1);
  });
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`Phone Use: ${error.message}\n`);
    process.exitCode = 1;
  }
}
