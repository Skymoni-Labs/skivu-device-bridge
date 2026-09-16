// Browser contract test with a simulated phone. Requires playwright; no Android device.
// Run: node tools/test_web_console.cjs. Set PHONEUSE_BROWSER_CHANNEL=msedge when needed.
const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '../app/src/main/assets/web');
let failNextAction = false;
let pairingState = 'pending', revoked = false, calls = [], polls = 0, pairRequests = 0, offline = false;
const tool = (name, required = [], properties = {}) => ({ name, description: name, inputSchema: { type: 'object', required, properties } });
const catalog = [tool('get_capabilities'), tool('get_status', [], { request_id: { type: 'string' } }), tool('launch_app', ['request_id', 'package_name'], { request_id: { type: 'string' }, package_name: { type: 'string', description: 'Android package name' } }), tool('home', ['request_id'], {
  request_id: { type: 'string' }
})];
const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  if (req.url === '/' || req.url === '/app.js') {
    res.setHeader('Content-Type', req.url === '/' ? 'text/html; charset=utf-8' : 'text/javascript');
    res.end(fs.readFileSync(path.join(root, req.url === '/' ? 'index.html' : 'app.js'))); return;
  }
  res.setHeader('Content-Type', 'application/json');
  let body = ''; for await (const chunk of req) body += chunk;
  const payload = body ? JSON.parse(body) : {};
  const reply = value => res.end(JSON.stringify(value));
  const cookieName = `phoneuse_client_${server.address().port}`;
  const cookie = token => res.setHeader('Set-Cookie', `${cookieName}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${token ? 34560000 : 0}`);
  const authorized = !revoked && (req.headers.cookie || '').includes(`${cookieName}=test-token`);
  if (offline) { res.statusCode = 503; return reply({ error: 'Service restarting' }); }
  if (req.url === '/browser/session') {
    if (req.method === 'POST' && req.headers.authorization === 'Bearer legacy-token') {
      cookie('test-token'); return reply({ paired: true, client_id: 'test-client' });
    }
    return reply({ paired: authorized, client_id: authorized ? 'test-client' : null });
  }
  if (req.url === '/browser/pair/request') {
    pairRequests++;
    res.statusCode = 202;
    return reply({ pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', expires_in: 120, poll_interval: 2 });
  }
  if (req.url === '/browser/pair/status') {
    assert.equal(payload.pairing_secret, 'test-secret'); polls++;
    if (pairingState === 'approved') cookie('test-token');
    return reply({ status: pairingState, ...(pairingState === 'approved' ? {
      client_id: 'test-client'
    } : {}) });
  }
  if (!authorized) {
    res.statusCode = 401; return reply({ error: 'Invalid or revoked token' });
  }
  if (req.url === '/browser/forget') { cookie(''); return reply({ paired: false }); }
  if (req.url === '/api/tools') return reply({ tools: catalog });
  if (req.url === '/api') {
    calls.push(payload);
    if (payload.tool === 'get_status') return reply({ ...(payload.arguments.request_id ? { request: { state: 'executed' } } : {}) });
    if (failNextAction) { failNextAction = false; return reply({ state: 'failed', error: { code: 'ACTION_REJECTED', message: 'Action rejected' } }); }
    return reply({ state: 'executed', request_id: payload.arguments.request_id, available: true });
  }
  res.statusCode = 404; reply({ error: 'Unknown endpoint' });
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP phoneuse.test 127.0.0.1', '--no-proxy-server'], ...(process.env.PHONEUSE_BROWSER_CHANNEL ? { channel: process.env.PHONEUSE_BROWSER_CHANNEL } : {}) });
    let context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
    let page = await context.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    // A non-loopback HTTP origin exposes the same secure-context limitations as LAN IP access.
    await page.goto(`http://phoneuse.test:${server.address().port}/`);
    assert.equal(await page.evaluate(() => window.isSecureContext), false);
    assert.equal(await page.title(), 'Phone Use · API');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.reload(); // Pending request is restored, not recreated.
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    pairingState = 'approved';
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.length, 0); // Pairing only loads the catalog; no automatic tool calls.
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(await page.evaluate(() => document.cookie), '');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('phoneuse.credential')), null);
    assert.equal((await context.cookies())[0].httpOnly, true);
    // Simulate browser restart: restore persisted cookies into a new browser process.
    const savedState = await context.storageState();
    await browser.close();
    browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP phoneuse.test 127.0.0.1', '--no-proxy-server'], ...(process.env.PHONEUSE_BROWSER_CHANNEL ? { channel: process.env.PHONEUSE_BROWSER_CHANNEL } : {}) });
    context = await browser.newContext({ storageState: savedState, viewport: { width: 1280, height: 1000 } });
    page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    await page.goto(`http://phoneuse.test:${server.address().port}/`);
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, 1);
    offline = true; await page.reload();
    await page.waitForFunction(() => document.getElementById('pair-status').textContent.includes('等待服务恢复'));
    offline = false;
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, 1);
    await page.locator('#search').fill('home');
    assert.equal(await page.locator('.tool-item').count(), 1);
    await page.locator('[data-tool="home"]').click();
    await page.locator('#search').fill('');
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const first = calls.at(-1);
    assert.equal('lease_id' in first.arguments, false); assert.ok(first.arguments.request_id);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.request_id, first.arguments.request_id);
    await page.locator('#new-id').click();
    assert.notEqual(JSON.parse(await page.locator('#arguments').inputValue()).request_id, first.arguments.request_id);
    await page.locator('[data-tool="get_status"]').click();
    await page.locator('#param-request_id').fill(first.arguments.request_id);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.request_id, first.arguments.request_id);
    await page.locator('#integration > summary').click();
    assert.match(await page.locator('#endpoints').textContent(), /mcp-stdio --url/);
    assert.equal(await page.locator('#export').count(), 0);
    await page.locator('[data-tool="launch_app"]').click();
    await page.locator('#param-package_name').fill('ai.example.app');
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.package_name, 'ai.example.app');
    failNextAction = true; await page.locator('#new-id').click(); await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.match(await page.locator('#notice').textContent(), /Action rejected/);
    await page.locator('#new-id').click(); await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const reportDir = path.join(__dirname, '../app/build/reports'); fs.mkdirSync(reportDir, { recursive: true });
    await page.locator('#integration > summary').click();
    await page.screenshot({ path: path.join(reportDir, 'web-console-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(reportDir, 'web-console-mobile.png'), fullPage: true });
    await page.locator('#forget').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    assert.equal(await page.locator('#image-result').isHidden(), true);
    await page.locator('#pair').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    revoked = true;
    await page.locator('#run').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    assert.equal(await page.locator('#run').isDisabled(), true);
    pairingState = 'denied'; revoked = false;
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('拒绝'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    // Upgrade an old open tab without asking the phone to approve again.
    await page.evaluate(() => sessionStorage.setItem('phoneuse.credential', JSON.stringify({ token: 'legacy-token' })));
    const beforeMigration = pairRequests;
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, beforeMigration);
    assert.equal(await page.evaluate(() => sessionStorage.getItem('phoneuse.credential')), null);
    await page.locator('#forget').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    await page.evaluate(() => sessionStorage.setItem('phoneuse.pairing', JSON.stringify({
      pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', deadline: Date.now() - 1
    })));
    await page.reload();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('过期'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    assert.deepEqual(errors, []);
    console.log(`PASS: HttpOnly pairing, browser restart, server reconnect, legacy migration, API tools/forms, action IDs, unpair, revocation, denial, expiry, responsive layout and CSP; ${polls} polls; no page errors.`);
  } finally { if (browser) await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
