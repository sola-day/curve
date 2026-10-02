// Drive a real headless Chrome over the DevTools protocol (no npm deps).
// usage: node scripts/browser-smoke.mjs http://localhost:8091/ [steps-module]
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = process.env.CHROME ||
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2] || 'http://localhost:8091/';
const port = 9300 + Math.floor(Math.random() * 500);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
  `--remote-debugging-port=${port}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'curve-'))}`,
  'about:blank'], { stdio: 'ignore' });

async function json(path) {
  for (let i = 0; i < 100; i++) {
    try { return await (await fetch(`http://127.0.0.1:${port}${path}`)).json(); }
    catch { await sleep(100); }
  }
  throw new Error('chrome did not start');
}

export async function connect() {
  const page = (await json('/json/list')).find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0; const pending = new Map(); const logs = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === 'Runtime.consoleAPICalled') logs.push(m.params.args.map((a) => a.value).join(' '));
    if (m.method === 'Runtime.exceptionThrown') logs.push('EXCEPTION ' + JSON.stringify(m.params.exceptionDetails.exception?.description));
  });
  const send = (method, params = {}) => new Promise((r) => {
    const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params }));
  });
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    return r.result?.result?.value;
  };
  const waitFor = async (expression, ms = 5000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { if (await evaluate(expression)) return true; await sleep(50); }
    return false;
  };
  await send('Runtime.enable'); await send('Page.enable');
  return { send, evaluate, waitFor, logs, close: () => ws.close() };
}

const checks = [];
const check = (name, ok, detail) => { checks.push({ name, ok }); console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`); };

try {
  const b = await connect();
  await b.send('Page.navigate', { url });
  const steps = process.argv[3] ? (await import(process.argv[3])).default : defaultSteps;
  await steps(b, check);
  if (b.logs.length) console.log('console:', b.logs.join('\n'));
  b.close();
} finally {
  chrome.kill();
}
process.exit(checks.every((c) => c.ok) ? 0 : 1);

async function defaultSteps(b, check) {
  const q = (sel) => `document.querySelector(${JSON.stringify(sel)})`;
  check('server value rendered', await b.waitFor(`(${q('b.count')}?.textContent ?? '') !== ''`));
  const before = Number(await b.evaluate(`${q('b.count')}.textContent`));
  await b.evaluate(`${q('#inc')}.click()`);
  check('click round-trips to server', await b.waitFor(`${q('b.count')}.textContent === '${before + 1}'`));
  await b.evaluate(`(() => { const i = ${q('#draft')}; i.value = 'hi there'; i.dispatchEvent(new Event('input', {bubbles: true})); })()`);
  check('client state updates locally', await b.waitFor(`${q('i')}.textContent === 'hi there'`));
  const n = await b.evaluate(`document.querySelectorAll('li').length`);
  await b.evaluate(`${q('#post')}.click()`);
  check('server closure called with client value', await b.waitFor(`document.querySelectorAll('li').length === ${n + 1}`));
  check('last item text', (await b.evaluate(`[...document.querySelectorAll('li')].at(-1).textContent`)) === 'hi there');
  check('input cleared', await b.waitFor(`${q('#draft')}.value === ''`));
}
