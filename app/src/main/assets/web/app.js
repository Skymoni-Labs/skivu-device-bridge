'use strict';
const $ = id => document.getElementById(id);
// randomUUID requires a secure context; LAN HTTP still supports getRandomValues.
function newRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
let credential = null, pending = null, tools = [], lease = '', busy = false, generation = 0;
try {
  credential = JSON.parse(sessionStorage.getItem('phoneuse.credential') || 'null');
  pending = JSON.parse(sessionStorage.getItem('phoneuse.pairing') || 'null');
} catch { sessionStorage.removeItem('phoneuse.credential'); sessionStorage.removeItem('phoneuse.pairing'); }
$('endpoints').textContent = `API: ${location.origin}/api\n工具: ${location.origin}/api/tools\nMCP: ${location.origin}/mcp\nAuthorization: Bearer TOKEN`;
function notice(error) { $('notice').textContent = error ? error.message : ''; }
function controls() {
  $('connection').textContent = credential ? '已配对' : pending ? '等待手机授权' : '尚未配对';
  $('pair').disabled = !!credential || !!pending || busy;
  for (const id of ['export', 'forget', 'acquire', 'run', 'tool', 'new-id']) $(id).disabled = !credential || busy;
  $('release').disabled = !credential || !lease || busy;
  $('lease').textContent = lease ? `当前租约：${lease}（到期后需重新获取）` : '读取状态无需控制权；输入动作需要有效租约。';
}
async function request(path, payload, authenticated = true) {
  const headers = {};
  if (payload !== undefined) headers['Content-Type'] = 'application/json';
  if (authenticated) headers.Authorization = `Bearer ${credential.token}`;
  const response = await fetch(path, { method: payload === undefined ? 'GET' : 'POST', headers,
    body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(30000) });
  const value = await response.json();
  if (response.status === 401) {
    credential = null; lease = ''; sessionStorage.removeItem('phoneuse.credential'); controls();
  }
  if (!response.ok) throw new Error(value.error?.message || value.error || `HTTP ${response.status}`);
  return value;
}
function selectTool() {
  const tool = tools.find(t => t.name === $('tool').value);
  if (!tool) return;
  $('description').textContent = tool.description;
  $('schema').textContent = JSON.stringify(tool.inputSchema, null, 2);
  const args = {};
  for (const key of tool.inputSchema.required || []) {
    if (key === 'lease_id' || key === 'request_id') continue;
    const field = tool.inputSchema.properties[key];
    args[key] = field.type === 'string' ? '' : field.type === 'object' ? {} : field.type === 'array' ? [] : field.type === 'boolean' ? false : 0;
  }
  if (tool.name === 'observe') args.mode = 'tree';
  $('arguments').value = JSON.stringify(args, null, 2);
}
async function loadTools() {
  tools = (await request('/api/tools')).tools;
  $('tool').replaceChildren(...tools.map(t => new Option(t.name, t.name)));
  selectTool(); controls();
}
function saveCredential(value) {
  credential = { url: location.origin, token: value.token, client_id: value.client_id };
  sessionStorage.setItem('phoneuse.credential', JSON.stringify(credential));
}
async function pollPairing() {
  const current = ++generation;
  $('verification').textContent = pending.verification_code;
  $('pair-status').textContent = '请打开手机 App，核对两端显示的核对码并授权。请求两分钟有效。';
  controls();
  try {
    while (pending && current === generation) {
      if (Date.now() >= pending.deadline) throw new Error('配对请求已过期，请重新发起。');
      await new Promise(resolve => setTimeout(resolve, 2000));
      if (current !== generation) return;
      const value = await request('/pair/status', { pairing_id: pending.pairing_id, pairing_secret: pending.pairing_secret }, false);
      if (value.status === 'denied') throw new Error('手机已拒绝或撤销此次授权。');
      if (value.status === 'approved') {
        saveCredential(value);
        $('pair-status').textContent = '手机已授权，可以调用 API 或导出 MCP 连接凭证。';
        break;
      }
    }
  } catch (error) { notice(error); }
  finally { pending = null; sessionStorage.removeItem('phoneuse.pairing'); $('verification').textContent = ''; controls(); }
  if (credential) await loadTools().catch(notice);
}
$('pair').onclick = async () => {
  busy = true; notice(null); controls();
  try {
    pending = await request('/pair/request', { client_name: $('client-name').value.trim() }, false);
    pending.deadline = Date.now() + pending.expires_in * 1000;
    sessionStorage.setItem('phoneuse.pairing', JSON.stringify(pending));
    void pollPairing();
  } catch (error) { notice(error); }
  finally { busy = false; controls(); }
};
function showResult(value) {
  const copy = JSON.parse(JSON.stringify(value));
  const shot = (copy.observation || copy).screenshot;
  $('screenshot').hidden = true;
  if (shot?.available && shot.mime_type === 'image/jpeg' && shot.data_base64) {
    $('screenshot').src = `data:image/jpeg;base64,${shot.data_base64}`;
    $('screenshot').hidden = false; shot.data_base64 = '(截图已在上方显示)';
  }
  $('result').textContent = JSON.stringify(copy, null, 2);
  $('result-status').textContent = value.error ? `调用失败：${value.error.code}` : `已返回 · ${value.state || '完成'}`;
}
async function call(tool, args) {
  const result = await request('/api', { tool, arguments: args });
  showResult(result);
  if (result.error) {
    if (['LEASE_EXPIRED', 'PAUSED', 'CANCELLED'].includes(result.error.code)) lease = '';
    throw new Error(result.error.message);
  }
  if (tool === 'acquire_control') lease = result.lease_id;
  if (tool === 'release_control') lease = '';
  return result;
}
async function execute(work) {
  busy = true; notice(null); controls(); $('result-status').textContent = '调用中…';
  try { await work(); } catch (error) {
    notice(error); $('result-status').textContent = '调用未成功；若为网络异常，动作结果可能未知，请用原 request_id 查询 get_status。';
  } finally { busy = false; controls(); }
}
$('tool').onchange = selectTool;
$('acquire').onclick = () => execute(async () => {
  const status = await request('/api', { tool: 'get_status', arguments: {} });
  lease = status.lease_id || '';
  await call('acquire_control', { ttl_seconds: 60, ...(lease ? { lease_id: lease } : {}) });
});
$('release').onclick = () => execute(() => call('release_control', { lease_id: lease }));
$('run').onclick = () => execute(async () => {
  const args = JSON.parse($('arguments').value);
  if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('参数必须是 JSON 对象。');
  const schema = tools.find(t => t.name === $('tool').value).inputSchema;
  if (schema.properties.lease_id && lease && !args.lease_id) args.lease_id = lease;
  if (schema.required?.includes('request_id') && !args.request_id) args.request_id = newRequestId();
  $('arguments').value = JSON.stringify(args, null, 2);
  await call($('tool').value, args);
});
$('new-id').onclick = () => {
  try {
    const args = JSON.parse($('arguments').value);
    if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('参数必须是 JSON 对象。');
    if (!tools.find(t => t.name === $('tool').value)?.inputSchema.required?.includes('request_id')) throw new Error('当前工具不需要动作 ID。');
    args.request_id = newRequestId();
    if (lease) args.lease_id = lease;
    $('arguments').value = JSON.stringify(args, null, 2); notice(null);
  } catch (error) { notice(error); }
};
$('export').onclick = () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(credential, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'phoneuse.json'; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$('forget').onclick = () => {
  credential = null; lease = ''; sessionStorage.removeItem('phoneuse.credential');
  $('pair-status').textContent = '已清除本页凭证。如需撤销授权，请在手机 App 中撤销客户端。';
  $('result').textContent = '{}'; $('screenshot').hidden = true; controls();
};
controls();
if (credential) loadTools().catch(notice);
else if (pending) void pollPairing();
