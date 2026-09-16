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
let selectedTool = '';
let credential = null, pending = null, tools = [], busy = false, generation = 0, reconnectTimer;
try {
  pending = JSON.parse(sessionStorage.getItem('phoneuse.pairing') || 'null');
} catch { sessionStorage.removeItem('phoneuse.pairing'); }
$('endpoints').textContent = `API: ${location.origin}/api\n工具: ${location.origin}/api/tools\nMCP: python tools/phoneuse_client.py mcp-stdio --url ${location.origin}`;
const messages = {
  PAUSED: '手机已暂停控制，请在手机上恢复后重试。',
  CANCELLED: '本次操作已取消，请检查手机上的控制状态。',
  UNAUTHORIZED: '授权已撤销，请重新连接手机。',
  STALE_OBSERVATION: '界面已变化，请重新调用 observe 后再试。'
};
function notice(error) { $('notice').textContent = error ? messages[error.code] || error.message : ''; }
function controls() {
  $('connection').textContent = credential ? '已配对' : pending ? '等待手机授权' : '尚未配对';
  $('pair').disabled = !!credential || !!pending || busy;
  $('pair-form').hidden = !!credential;
  $('forget').disabled = !credential || busy;
  $('run').disabled = !credential || !selectedTool || busy;
  $('new-id').disabled = !credential || busy || !tools.find(t => t.name === selectedTool)?.inputSchema.required?.includes('request_id');
  for (const field of $('fields').querySelectorAll('input, textarea, select')) field.disabled = busy || !credential;
  for (const button of $('tool-list').querySelectorAll('button')) button.disabled = busy;
}
async function request(path, payload, legacyToken) {
  const headers = {};
  if (payload !== undefined) headers['Content-Type'] = 'application/json';
  if (legacyToken) headers.Authorization = `Bearer ${legacyToken}`;
  const response = await fetch(path, { method: payload === undefined ? 'GET' : 'POST', headers,
    credentials: 'same-origin', body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(payload?.tool === 'observe' ? 90000 : 30000) });
  const value = await response.json();
  if (response.status === 401) {
    credential = null; clearResult(); sessionStorage.removeItem('phoneuse.credential'); controls();
    $('pair-status').textContent = '授权已撤销或浏览器配对数据已清除，请重新配对。';
  }
  if (!response.ok) throw new Error(response.status === 401 ? '授权已失效，请重新连接手机。' : value.error?.message || value.error || `HTTP ${response.status}`);
  return value;
}
const labels = {
  get_capabilities: '设备能力', get_status: '状态 / 请求结果',
  cancel: '取消请求', observe: '观察界面', list_apps: '应用列表', wait_for_change: '等待界面变化',
  tap: '点击', long_press: '长按', swipe: '滑动', scroll: '滚动', input_text: '输入文字',
  back: '返回', home: '主屏幕', recents: '最近任务', launch_app: '启动应用'
};
function renderList() {
  const query = $('search').value.trim().toLowerCase();
  const matches = tools.filter(t => (t.name + ' ' + t.description + ' ' + (labels[t.name] || '')).toLowerCase().includes(query));
  $('tool-list').replaceChildren(...matches.map(tool => {
    const button = document.createElement('button'); button.className = 'tool-item';
    button.dataset.tool = tool.name; button.setAttribute('aria-pressed', String(selectedTool === tool.name));
    const name = document.createElement('strong'); name.textContent = tool.name;
    const label = document.createElement('small'); label.textContent = labels[tool.name] || tool.description;
    button.append(name, label); button.onclick = () => selectTool(tool.name); return button;
  }));
  if (!matches.length) $('tool-list').textContent = tools.length ? '没有匹配的 API。' : '配对后加载 API 列表。';
  controls();
}
function readArguments() {
  const args = JSON.parse($('arguments').value);
  if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('参数必须是 JSON 对象。');
  return args;
}
function renderFields() {
  const tool = tools.find(t => t.name === selectedTool);
  if (!tool) return;
  const args = readArguments(), required = tool.inputSchema.required || [];
  const fields = Object.entries(tool.inputSchema.properties).filter(([key]) => !(key === 'request_id' && required.includes(key)));
  $('fields').replaceChildren(...fields.map(([key, schema]) => {
    const wrapper = document.createElement('div'); wrapper.className = 'field';
    const label = document.createElement('label'); label.htmlFor = 'param-' + key; label.textContent = key + (required.includes(key) ? ' *' : '');
    let input;
    if (schema.type === 'boolean') {
      input = document.createElement('select'); input.append(new Option('不传入', ''), new Option('true', 'true'), new Option('false', 'false'));
    } else {
      input = document.createElement(['object', 'array'].includes(schema.type) ? 'textarea' : 'input');
      if (schema.type === 'number' || schema.type === 'integer') { input.type = 'number'; input.step = schema.type === 'integer' ? '1' : 'any'; }
    }
    input.id = 'param-' + key;
    input.value = args[key] === undefined ? '' : typeof args[key] === 'object' ? JSON.stringify(args[key]) : String(args[key]);
    input.placeholder = required.includes(key) ? '必填 · ' + schema.type : '可选 · ' + schema.type;
    input.oninput = () => {
      try {
        const value = readArguments();
        if (input.value === '' && !required.includes(key)) delete value[key];
        else value[key] = schema.type === 'string' ? input.value : JSON.parse(input.value);
        $('arguments').value = JSON.stringify(value, null, 2); input.setCustomValidity(''); notice(null);
      } catch { input.setCustomValidity('请输入有效的 ' + schema.type + ' 值，并检查 JSON 参数。'); }
    };
    const help = document.createElement('small'); help.textContent = schema.description || schema.type;
    wrapper.append(label, input, help); return wrapper;
  }));
  $('no-fields').hidden = fields.length !== 0;
  controls();
}
function selectTool(name) {
  selectedTool = name;
  const tool = tools.find(t => t.name === name);
  if (!tool) return;
  $('tool-title').textContent = tool.name;
  $('description').textContent = tool.description;
  $('schema').textContent = JSON.stringify(tool.inputSchema, null, 2);
  const args = {};
  for (const key of tool.inputSchema.required || []) {
    if (key === 'request_id') continue;
    const field = tool.inputSchema.properties[key];
    args[key] = field.type === 'string' ? '' : field.type === 'object' ? {} : field.type === 'array' ? [] : field.type === 'boolean' ? false : 0;
  }
  if (name === 'observe') args.mode = 'tree';
  $('arguments').value = JSON.stringify(args, null, 2);
  renderList(); renderFields();
}
async function loadTools() {
  tools = (await request('/api/tools')).tools;
  if (tools.some(t => t.name === selectedTool)) { renderList(); renderFields(); }
  else if (tools.length) selectTool(tools[0].name);
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
      const value = await request('/browser/pair/status', { pairing_id: pending.pairing_id, pairing_secret: pending.pairing_secret });
      if (value.status === 'denied') throw new Error('手机已拒绝或撤销此次授权。');
      if (value.status === 'approved') {
        credential = { client_id: value.client_id };
        $('pair-status').textContent = '已记住此浏览器，下次打开会自动连接。';
        break;
      }
    }
  } catch (error) { notice(error); }
  finally { pending = null; sessionStorage.removeItem('phoneuse.pairing'); $('verification').textContent = ''; controls(); }
  if (credential) await connect();
}
$('pair').onclick = async () => {
  busy = true; notice(null); controls();
  try {
    pending = await request('/browser/pair/request', { client_name: $('client-name').value.trim() });
    pending.deadline = Date.now() + pending.expires_in * 1000;
    sessionStorage.setItem('phoneuse.pairing', JSON.stringify(pending));
    void pollPairing();
  } catch (error) { notice(error); }
  finally { busy = false; controls(); }
};
function resultObjects(value, path = []) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return [{ value, path }, ...['request', 'observation', 'recovery_observation', 'last_observation']
    .flatMap(key => resultObjects(value[key], [...path, key]))];
}
function activeObservations(value) {
  return [
    ...(['observation_id', 'tree', 'screenshot', 'condition'].some(key => key in value) ? [value] : []),
    value.observation, value.recovery_observation
  ].filter(item => item && typeof item === 'object');
}
function observationIncomplete(value) {
  return ['observing', 'failed', 'condition_timeout'].includes(value.observation_status) || activeObservations(value).some(item =>
    item.error || ['observing', 'failed', 'condition_timeout'].includes(item.observation_status) ||
    (item.condition && (item.condition.status !== 'satisfied' || item.condition.timed_out)) ||
    item.tree?.available === false || item.screenshot?.available === false);
}
const recoveryLabels = {
  refresh_observation: '重新观察并重新选择目标。',
  wait_original_request: '使用原 request_id 查询 get_status，等待原请求完成。',
  reconcile_result: '核对原请求结果和当前界面。',
  choose_explicit_target: '根据新观察选择唯一、明确的目标。'
};
function resultStatus(value) {
  const result = !value.error && value.request && typeof value.request === 'object' ? value.request : value;
  if (result.state === 'accepted') return '动作仍在排队；使用原 request_id 查询 get_status，请勿重新发起动作。';
  if (result.state === 'executing') {
    const progress = result.observation_purpose === 'after_rejection' && result.action_executed === false ? '动作未执行，正在读取恢复观察' : result.action_executed === true ? '动作已执行，正在等待观察' : '动作正在执行';
    return progress + '；使用原 request_id 查询 get_status，请勿重新发起动作。';
  }
  if (result.state === 'unknown') return '动作结果未知；查询原 request_id 并观察现场，请勿重放动作。';
  const recovery = recoveryLabels[result.recovery?.category];
  const hint = recovery ? ` 恢复建议：${recovery}建议不保证下一次操作成功。` : '';
  if (result.error) return `操作失败：${messages[result.error.code] || result.error.message}` +
    (result.recovery_observation ? (observationIncomplete(result.recovery_observation) ? ' 恢复观察未完成。' : ' 已附带恢复观察，请检查其采集时间。') : '') + hint;
  if (observationIncomplete(result)) {
    const conditions = activeObservations(result).map(item => item.condition).filter(Boolean);
    const detail = conditions.some(item => item.status === 'unknown') ? '当前条件未知' : conditions.some(item => item.status === 'ambiguous') ? '目标匹配有歧义' : conditions.some(item => item.status !== 'satisfied') ? '条件未满足' : activeObservations(result).some(item => item.error) ? '观察采集失败' : '请求的树或截图不可用';
    return `${result.action_executed === true || result.state === 'executed' ? '动作已执行；' : ''}观察未完成（${detail}）。请独立调用 observe；请勿重放动作。` + hint;
  }
  return '已返回 · ' + (result.state || '完成') + hint;
}
function showResult(value) {
  const copy = JSON.parse(JSON.stringify(value));
  const observations = resultObjects(copy);
  const candidates = observations.filter(item => item.value.screenshot?.available && item.value.screenshot.mime_type === 'image/jpeg' && item.value.screenshot.data_base64);
  candidates.sort((a, b) => (b.value.captured_at_ms || 0) - (a.value.captured_at_ms || 0));
  const selected = candidates[0];
  const shot = selected?.value.screenshot;
  $('image-result').hidden = true; $('image-result').open = false; $('screenshot').removeAttribute('src');
  if (shot) {
    $('screenshot').src = `data:image/jpeg;base64,${shot.data_base64}`;
    $('image-result').hidden = false;
    copy.screenshot_display_source = [...selected.path, 'screenshot'].join('.');
  }
  for (const item of observations) if (item.value.screenshot?.data_base64) item.value.screenshot.data_base64 = item.value.screenshot === shot ? '(见下方截图附件)' : '(已省略其他截图数据)';
  $('result').textContent = JSON.stringify(copy, null, 2);
  $('result-status').textContent = resultStatus(value);
}
async function call(tool, args) {
  const result = await request('/api', { tool, arguments: args });
  showResult(result);
  if (result.error) {
    throw Object.assign(new Error(result.error.message), { code: result.error.code, state: result.state, resultShown: true });
  }
  return result;
}
async function execute(work) {
  if (busy) return;
  busy = true; notice(null); controls(); $('result-status').textContent = '正在处理…';
  try { await work(); } catch (error) {
    notice(error);
    if (!error.resultShown) $('result-status').textContent = '调用未成功。网络异常时请用原 request_id 调用 get_status 查询结果，请勿重放动作。';
  } finally { busy = false; controls(); }
}
$('search').oninput = renderList;
$('arguments').onchange = () => { try { renderFields(); notice(null); } catch (error) { notice(error); } };

$('run').onclick = () => execute(async () => {
  for (const input of $('fields').querySelectorAll('input, textarea, select')) if (!input.validity.valid) throw new Error(input.id + '：参数格式无效');
  const args = readArguments();
  const schema = tools.find(t => t.name === selectedTool).inputSchema;
  if (schema.required?.includes('request_id') && !args.request_id) args.request_id = newRequestId();
  $('arguments').value = JSON.stringify(args, null, 2);
  await call(selectedTool, args);
});
$('new-id').onclick = () => {
  try {
    const args = readArguments();
    if (!tools.find(t => t.name === selectedTool)?.inputSchema.required?.includes('request_id')) throw new Error('当前工具不需要动作 ID。');
    args.request_id = newRequestId();
    $('arguments').value = JSON.stringify(args, null, 2); notice(null);
  } catch (error) { notice(error); }
};
$('forget').onclick = () => execute(async () => {
  await request('/browser/forget', {});
  credential = null; sessionStorage.removeItem('phoneuse.credential');
  $('pair-status').textContent = '已解除此浏览器的配对，再次使用需在手机授权。';
  $('result').textContent = '{}'; clearResult(); controls();
});
controls();
void restoreConnection();
window.addEventListener('online', () => void restoreConnection());
window.addEventListener('focus', () => void restoreConnection());

function clearResult() {
  $('image-result').hidden = true; $('screenshot').removeAttribute('src'); $('result').textContent = '{}';
  selectedTool = ''; tools = []; $('arguments').value = '{}'; $('fields').replaceChildren();
  $('schema').textContent = '尚未加载'; $('tool-title').textContent = '选择 API';
  $('description').textContent = '从左侧选择接口，填写参数并调用。'; $('no-fields').hidden = true; renderList();
}
async function connect() {
  $('pair-status').textContent = '已记住此浏览器，关闭页面或重启 App 后仍可连接。';
  await execute(loadTools);
}
async function restoreConnection() {
  if (busy) return;
  clearTimeout(reconnectTimer);
  busy = true; controls();
  let restored = false;
  try {
    // Upgrade an existing tab in place, then discard its JavaScript-readable token.
    let legacy = null;
    try { legacy = JSON.parse(sessionStorage.getItem('phoneuse.credential') || 'null'); }
    catch { sessionStorage.removeItem('phoneuse.credential'); }
    const value = legacy?.token
      ? await request('/browser/session', {}, legacy.token)
      : await request('/browser/session');
    sessionStorage.removeItem('phoneuse.credential');
    notice(null);
    const wasPaired = !!credential;
    credential = value.paired ? { client_id: value.client_id } : null;
    if (credential) {
      if (pending) {
        generation++; pending = null; sessionStorage.removeItem('phoneuse.pairing'); $('verification').textContent = '';
      }
      await loadTools();
      $('pair-status').textContent = '已自动连接。此浏览器已配对，无需再次授权。';
    } else if (!pending) {
      clearResult();
      $('pair-status').textContent = wasPaired ? '授权已失效，请重新配对。' : '首次配对在手机上批准，之后自动连接。';
    }
    restored = true;
  } catch (error) {
    notice(error);
    $('pair-status').textContent = '暂时无法连接手机，正在等待服务恢复；已有配对会保留。';
    reconnectTimer = setTimeout(() => void restoreConnection(), 5000);
  } finally { busy = false; controls(); }
  if (restored && pending && !credential && !$('verification').textContent) void pollPairing();
}
