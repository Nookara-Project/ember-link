#!/usr/bin/env node
import http from 'node:http';
import crypto from 'node:crypto';
import readline from 'node:readline';

const PORT = Number(process.argv[2] || 8765);
const HEARTBEAT_MS = Number(process.env.HEARTBEAT_MS || 30000);
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const CROCK = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const sessions = new Set();
const links = new Map();
let reqSeq = 1;

const ts = () => new Date().toISOString().slice(11, 23);
const log = (...a) => console.log(ts(), ...a);

function tagOf(h) {
  const bits = h.readUInt32BE(8) >>> 2;
  let t = '';
  for (let i = 0; i < 6; i++) { t += CROCK[(bits >>> (25 - 5 * i)) & 31]; if (i === 2) t += '-'; }
  return t;
}

function frame(op, data) {
  const payload = Buffer.from(data);
  let header;
  if (payload.length < 126) header = Buffer.from([0x80 | op, payload.length]);
  else if (payload.length < 65536) { header = Buffer.alloc(4); header[0] = 0x80 | op; header[1] = 126; header.writeUInt16BE(payload.length, 2); }
  else { header = Buffer.alloc(10); header[0] = 0x80 | op; header[1] = 127; header.writeBigUInt64BE(BigInt(payload.length), 2); }
  return Buffer.concat([header, payload]);
}

class Session {
  constructor(sock) {
    this.sock = sock; this.buf = Buffer.alloc(0); this.frag = []; this.iid = null; this.ready = false;
    this.nonce = crypto.randomBytes(32).toString('base64url');
    sock.on('data', d => { this.buf = Buffer.concat([this.buf, d]); this.parse(); });
    sock.on('close', () => { sessions.delete(this); log(`[${this.iid || '?'}] socket closed`); });
    sock.on('error', () => {});
    this.identifyTimer = setTimeout(() => this.close(4002, 'identify timeout'), 15000);
    this.send('hello', { v: 1, nonce: this.nonce, heartbeat_ms: HEARTBEAT_MS });
  }
  send(op, d, id) {
    const o = { op, d }; if (id !== undefined) o.id = id;
    this.sock.write(frame(1, JSON.stringify(o)));
  }
  close(code, reason = '') {
    const b = Buffer.alloc(2 + Buffer.byteLength(reason)); b.writeUInt16BE(code, 0); b.write(reason, 2);
    try { this.sock.write(frame(8, b)); } catch {}
    setTimeout(() => this.sock.destroy(), 200);
  }
  parse() {
    while (this.buf.length >= 2) {
      const b0 = this.buf[0], b1 = this.buf[1];
      const fin = (b0 & 0x80) !== 0, op = b0 & 0x0f, masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      const need = off + (masked ? 4 : 0) + len;
      if (this.buf.length < need) return;
      let payload = this.buf.subarray(off + (masked ? 4 : 0), need);
      if (masked) { const m = this.buf.subarray(off, off + 4); payload = Buffer.from(payload.map((x, i) => x ^ m[i % 4])); }
      this.buf = this.buf.subarray(need);
      if (op === 8) { log(`[${this.iid || '?'}] client closed ${payload.length >= 2 ? payload.readUInt16BE(0) : ''} ${payload.subarray(2)}`); this.sock.end(); return; }
      if (op === 9) { this.sock.write(frame(10, payload)); continue; }
      if (op === 10) continue;
      if (op === 1 || op === 0) {
        this.frag.push(payload);
        if (!fin) continue;
        const text = Buffer.concat(this.frag).toString('utf8'); this.frag = [];
        if (text.length > 65536) { this.close(1009, 'too big'); return; }
        try { this.onMessage(JSON.parse(text)); } catch (e) { log('bad frame', e.message); this.close(4001, 'malformed'); }
      }
    }
  }
  onMessage(m) {
    const { op, d = {}, id } = m;
    if (op !== 'identify' && !this.ready) { log('op before identify:', op); this.close(4001, 'not identified'); return; }
    switch (op) {
      case 'identify': {
        clearTimeout(this.identifyTimer);
        let ok = false, iid = '', tag = '';
        try {
          const der = Buffer.from(d.key, 'base64');
          const h = crypto.createHash('sha256').update(der).digest();
          iid = 'g' + h.toString('hex').slice(0, 16); tag = tagOf(h);
          const str = `bonfire-gw-v1|${this.nonce}|${iid}`;
          ok = crypto.verify(null, Buffer.from(str), crypto.createPublicKey({ key: der, format: 'der', type: 'spki' }), Buffer.from(d.sig, 'base64'));
        } catch (e) { log('identify error', e.message); }
        log(`identify platform=${d.platform} software="${d.software}" mc=${d.mc_version} mod=${d.mod_version} listing=${d.listing} iid=${d.iid} (computed ${iid}) sig=${ok ? 'OK' : 'BAD'} features=${JSON.stringify(d.features)} motd=${JSON.stringify(d.motd)} icon=${d.icon_sha256} max=${d.max_players} online_mode=${d.online_mode} addr="${d.public_address}"`);
        if (!ok || iid !== d.iid) { this.close(4003, 'bad signature'); return; }
        for (const s of sessions) if (s !== this && s.iid === iid) s.close(4006, 'replaced');
        this.iid = iid; this.ready = true;
        this.send('ready', {
          iid, pairing_tag: tag, linked: links.get(iid) || null, icon_needed: !!d.icon_sha256,
          relay: { chat: true, events: true }, console_stream: true, limits: { frames_per_s: 30, chat_per_10s: 20 },
        });
        break;
      }
      case 'heartbeat': this.send('heartbeat_ack', { seq: d.seq }); break;
      case 'console.log': for (const l of d.lines || []) log(`[${this.iid}] LOG ${l.level} ${l.logger}: ${l.msg}`); break;
      case 'icon': log(`[${this.iid}] icon ${Buffer.from(d.png || '', 'base64').length} bytes`); break;
      case 'link': {
        const code = String(d.code || '');
        log(`[${this.iid}] link code=${code} id=${id}`);
        if (code === 'TESTC0DE' || code === 'TESTCODE') {
          if (links.has(this.iid)) { this.send('link_result', { ok: false, error: 'already_linked', server_name: links.get(this.iid).server_name }, id); break; }
          const l = { server_name: 'Mock Ember Server', sid: 'smock', linked_by: 'MockUser', linked_at: Date.now() };
          links.set(this.iid, l);
          this.send('link_result', { ok: true, server_name: l.server_name }, id);
          this.send('linked', { server_name: l.server_name, sid: l.sid, linked_by: l.linked_by });
        } else this.send('link_result', { ok: false, error: 'invalid_code' }, id);
        break;
      }
      case 'unlink': links.delete(this.iid); log(`[${this.iid}] unlink`); this.send('unlinked', { by: 'console', username: null }); break;
      default: log(`[${this.iid}] ${op} ${JSON.stringify(d)}${id !== undefined ? ' id=' + id : ''}`);
    }
  }
}

const server = http.createServer((req, res) => { res.writeHead(426); res.end('websocket only'); });
server.on('upgrade', (req, sock) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) { sock.destroy(); return; }
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  log(`connection from ${sock.remoteAddress} ${req.url} ua="${req.headers['user-agent']}"`);
  sessions.add(new Session(sock));
});
server.listen(PORT, () => log(`mock gateway on ws://0.0.0.0:${PORT}/chat/game-gateway`));

const all = (op, d) => { for (const s of sessions) if (s.ready) s.send(op, d); };
readline.createInterface({ input: process.stdin }).on('line', line => {
  const [cmd, ...rest] = line.trim().split(' '); const arg = rest.join(' ');
  switch (cmd) {
    case 'chat': all('chat', { id: String(Date.now()), channel: 'mc-chat', author: { username: 'Ikelene', color: '#ff7ac6', bot: false, webhook: false },
      segments: [{ text: arg + ' ' }, { text: '@Nat', color: '#8ab4ff' }, { text: ' bold', b: true }, { text: ' [image]', link: 'https://velora.ikelene.net/', u: true }], reply: null }); break;
    case 'reply': all('chat', { id: String(Date.now()), channel: 'mc-chat', author: { username: 'BotUser', color: '#55ff55', bot: true, webhook: false },
      segments: [{ text: arg }], reply: { author: 'Nat', text: 'the message being replied to' } }); break;
    case 'exec': all('console.exec', { req: 'r' + (reqSeq++), command: arg, by: 'Ikelene' }); break;
    case 'stream': all('config', { relay: { chat: true, events: true }, console_stream: arg === 'on' }); break;
    case 'relay': all('config', { relay: { chat: arg === 'on', events: arg === 'on' }, console_stream: true }); break;
    case 'unlinked': for (const s of sessions) { links.delete(s.iid); if (s.ready) s.send('unlinked', { by: 'bonfire', username: 'Ikelene' }); } break;
    case 'close': for (const s of sessions) s.close(Number(arg) || 1000, 'mock close'); break;
    case 'sessions': for (const s of sessions) log(s.iid, s.ready, links.get(s.iid)); break;
    default: if (cmd) log('unknown command', cmd);
  }
});
