'use strict';
const S = process.env.EMBER_SERVER || require('path').resolve(__dirname, '../../../ember-server');
const { spawn } = require('child_process');
const fs = require('fs'), os = require('os'), path = require('path');
process.env.BONFIRE_ACCOUNTS_MODE = 'local';
delete process.env.BONFIRE_OWNER;
const { startServer } = require(S + '/src/index');
const { tmpDir, api, wsClient } = require(S + '/test/helpers');
const { execFileSync } = require('child_process');
function classpath(tmp) {
  const core = path.resolve(__dirname, '../../core/build/classes/java/main');
  if (!fs.existsSync(core)) throw new Error('build core first: gradle :core:compileJava');
  let gson = process.env.GSON;
  if (!gson) {
    const root = path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson');
    const find = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? find(path.join(d, e.name)) : /^gson-[\d.]+\.jar$/.test(e.name) ? [path.join(d, e.name)] : []);
    gson = fs.existsSync(root) && find(root)[0];
    if (!gson) throw new Error('set GSON=/path/to/gson.jar');
  }
  const out = path.join(tmp, 'classes');
  const javac = process.env.JAVA ? path.join(path.dirname(process.env.JAVA), 'javac') : 'javac';
  execFileSync(javac, ['-cp', `${core}${path.delimiter}${gson}`, '-d', out, path.join(__dirname, 'Harness.java')], { stdio: 'inherit' });
  return [core, gson, out].join(path.delimiter);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function until(fn, ms = 15000, what = '') { const t = Date.now(); for (;;) { const v = await fn(); if (v) return v; if (Date.now() - t > ms) throw new Error('timeout: ' + what); await sleep(100); } }
(async () => {
  const dir = tmpDir();
  const inst = await startServer({ dataDir: dir, port: 0, host: '127.0.0.1', restart: () => {} });
  require(S + '/src/game/gateway')._test.set({ listMinAge: 0 });
  const base = inst.url;
  console.log('instance', base);
  const r = await api(base).post('/api/v1/auth/register', { username: 'alice', password: 'correct horse battery' });
  const a = api(base, r.body.session);
  await a.post('/api/v1/instance/claim', { code: inst.setup.current() });
  const sid = (await a.post('/api/v1/servers', { name: 'Craft', join_policy: 'open' })).body.server.sid;
  const ws = await wsClient(base).login(r.body.session);

  const mcDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mc-'));
  const java = spawn(process.env.JAVA || 'java', ['-cp', classpath(mcDir), 'Harness', mcDir], { stdio: ['pipe', 'pipe', 'inherit'] });
  let out = '';
  java.stdout.on('data', d => { out += d; process.stdout.write('  [mc] ' + String(d).replace(/\n(?=.)/g, '\n  [mc] ')); });
  const send = l => java.stdin.write(l + '\n');
  const ok = (c, m) => { if (!c) { console.log('FAIL', m); process.exitCode = 1; } else console.log('PASS', m); };
  try {
    await until(() => /TAG (\S+) IID (\S+)/.test(out), 10000, 'harness start');
    const [, tag, iid] = out.match(/TAG (\S+) IID (\S+)/);
    send('server ' + base.replace('127.0.0.1', 'localhost') + '/');
    await until(() => out.includes('Waiting for an admin to pick this server'), 15000, 'ready banner');
    ok(true, 'connected to the self-hosted instance and printed the waiting banner');
    const list = await until(async () => { const x = await a.get(`/api/v1/game/connected?sid=${sid}`); return x.body.instances?.find(i => i.iid === iid) && x.body; }, 15000, 'connected list');
    ok(list.instances.find(i => i.iid === iid).pairing_tag === tag, 'shows up in Connected game servers with its pairing tag');
    const ch = (await a.post(`/api/v1/servers/${sid}/channels`, { name: 'mc-chat', type: 'game' })).body.channel;
    const lr = await a.post(`/api/v1/game/connected/${iid}/link`, { sid, cid: ch.cid });
    ok(lr.status === 200, 'admin picks it from the list');
    await until(() => /Linked to Craft|linked/i.test(out.split('Waiting for an admin')[1] || ''), 10000, 'linked frame');
    const ch1 = (await a.get(`/api/v1/servers/${sid}`)).body.channels.find(c => c.cid === ch.cid);
    await a.patch(`/api/v1/channels/${ch.cid}`, { config: { ...ch1.config, purpose: 'chat' } });
    await sleep(500);
    send('chat hello from minecraft');
    const m = await ws.waitFor(f => f.type === 'chat_message' && f.message.channel === ch.cid && f.message.author?.bridge === 'minecraft');
    ok(m.message.body === 'hello from minecraft', 'Minecraft chat reaches the Ember channel');
    await a.post(`/api/v1/channels/${ch.cid}/messages`, { message: 'hi miners' });
    await until(() => /BROADCAST .*hi miners/.test(out), 10000, 'broadcast');
    ok(true, 'Ember chat reaches Minecraft');
    send('status');
    await until(() => /CMD Ember: localhost:\d+/.test(out) && /CMD Linked: Craft/.test(out), 5000, 'status');
    ok(true, '`bonfire status` shows the instance and the link');
    const cfg = fs.readFileSync(path.join(mcDir, 'config.properties'), 'utf8');
    ok(/^server=http:\/\/localhost:\d+$/m.test(cfg), 'server= saved in config.properties');
    send('server velora');
    await until(() => /Now connecting to Velora/.test(out), 5000, 'reset');
    ok(/^server=$/m.test(fs.readFileSync(path.join(mcDir, 'config.properties'), 'utf8')), '`bonfire server velora` resets');
    send('server ftp://nope');
    await until(() => /doesn't look like an Ember address/.test(out), 5000, 'bad url');
    ok(true, 'bad URLs are refused');
  } catch (e) { console.log('FAIL', e.message); process.exitCode = 1; }
  java.kill(); ws.close(); await inst.close(); fs.rmSync(dir, { recursive: true, force: true }); fs.rmSync(mcDir, { recursive: true, force: true });
  process.exit();
})();
