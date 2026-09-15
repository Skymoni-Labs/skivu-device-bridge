// Browser contract test with a simulated phone. Requires playwright; no Android device.
// Run: node tools/test_web_console.cjs. Set PHONEUSE_BROWSER_CHANNEL=msedge when needed.
const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '../app/src/main/assets/web');
let pairingState = 'pending', revoked = false, calls = [], polls = 0;
const tool = (name, required = [], properties = {}) => ({ name, description: name, inputSchema: { type: 'object', required, properties } });
const catalog = [tool('get_capabilities'), tool('home', ['lease_id', 'request_id'], {
  lease_id: { type: 'string' }, request_id: { type: 'string' }
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
  if (req.url === '/pair/request') {
    res.statusCode = 202;
    return reply({ pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', expires_in: 120, poll_interval: 2 });
  }
  if (req.url === '/pair/status') {
    assert.equal(payload.pairing_secret, 'test-secret'); polls++;
    return reply({ status: pairingState, ...(pairingState === 'approved' ? {
      token: 'test-token', client_id: 'test-client'
    } : {}) });
  }
  if (revoked || req.headers.authorization !== 'Bearer test-token') {
    res.statusCode = 401; return reply({ error: 'Invalid or revoked token' });
  }
  if (req.url === '/api/tools') return reply({ tools: catalog });
  if (req.url === '/api') {
    calls.push(payload);
    if (payload.tool === 'get_status') return reply({ lease_id: null });
    if (payload.tool === 'acquire_control') return reply({ lease_id: 'test-lease', expires_in_ms: 60000 });
    return reply({ state: 'executed', request_id: payload.arguments.request_id, available: true });
  }
  res.statusCode = 404; reply({ error: 'Unknown endpoint' });
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP phoneuse.test 127.0.0.1', '--no-proxy-server'], ...(process.env.PHONEUSE_BROWSER_CHANNEL ? { channel: process.env.PHONEUSE_BROWSER_CHANNEL } : {}) });
    const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    // A non-loopback HTTP origin exposes the same secure-context limitations as LAN IP access.
    await page.goto(`http://phoneuse.test:${server.address().port}/`);
    assert.equal(await page.evaluate(() => window.isSecureContext), false);
    assert.equal(await page.title(), 'Phone Use · 控制台');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.reload(); // Pending request is restored, not recreated.
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    pairingState = 'approved';
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    await page.locator('#run').click();
    await page.waitForFunction(() => document.getElementById('result').textContent.includes('available'));
    await page.locator('#acquire').click();
    await page.waitForFunction(() => document.getElementById('lease').textContent.includes('test-lease'));
    await page.locator('#tool').selectOption('home');
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const first = calls.at(-1);
    assert.equal(first.arguments.lease_id, 'test-lease'); assert.ok(first.arguments.request_id);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.request_id, first.arguments.request_id);
    await page.locator('#new-id').click();
    assert.notEqual(JSON.parse(await page.locator('#arguments').inputValue()).request_id, first.arguments.request_id);
    const downloadPromise = page.waitForEvent('download'); await page.locator('#export').click();
    const download = await downloadPromise;
    assert.equal(JSON.parse(fs.readFileSync(await download.path(), 'utf8')).token, 'test-token');
    assert.equal('fingerprint' in JSON.parse(fs.readFileSync(await download.path(), 'utf8')), false);
    const reportDir = path.join(__dirname, '../app/build/reports'); fs.mkdirSync(reportDir, { recursive: true });
    await page.screenshot({ path: path.join(reportDir, 'web-console-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(reportDir, 'web-console-mobile.png'), fullPage: true });
    revoked = true;
    await page.locator('#run').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    assert.equal(await page.locator('#run').isDisabled(), true);
    pairingState = 'denied'; revoked = false;
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('拒绝'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    await page.evaluate(() => sessionStorage.setItem('phoneuse.pairing', JSON.stringify({
      pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', deadline: Date.now() - 1
    })));
    await page.reload();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('过期'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    assert.deepEqual(errors, []);
    console.log(`PASS: pairing, pending reload, approval, API, lease, action IDs, credential export, revocation, denial, expiry, responsive layout and CSP; ${polls} polls; no page errors.`);
  } finally { if (browser) await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
