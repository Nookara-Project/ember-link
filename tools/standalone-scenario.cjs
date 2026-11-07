const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const net = require('net');

const port = Number(process.argv[2] || 25603);
const sess = process.argv[3] || 'bfp121';
const rconPort = Number(process.argv[4] || 25613);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const tmux = (s, cmd) => execSync(`tmux send-keys -t ${s} ${JSON.stringify(cmd)} Enter`);
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

function rcon(cmd) {
  return new Promise(res => {
    const s = net.connect(rconPort, '127.0.0.1');
    const pkt = (id, type, body) => { const b = Buffer.from(body + '\0\0', 'utf8'); const h = Buffer.alloc(12); h.writeInt32LE(8 + b.length, 0); h.writeInt32LE(id, 4); h.writeInt32LE(type, 8); return Buffer.concat([h, b]); };
    s.on('connect', () => { s.write(pkt(1, 3, 'bonfiretest')); setTimeout(() => s.write(pkt(2, 2, cmd)), 200); });
    let got = Buffer.alloc(0);
    s.on('data', d => { got = Buffer.concat([got, d]); });
    setTimeout(() => { s.destroy(); res(got.subarray(12 + 14 + 12).toString('utf8').replace(/\0/g, '')); }, 800);
  });
}

(async () => {
  const b = mineflayer.createBot({ host: '127.0.0.1', port, username: 'BotC', version: '1.21.11', auth: 'offline' });
  b.on('messagestr', (m, pos) => { if (pos !== 'game_info') log(`[BotC sees] ${m}`); });
  b.on('kicked', r => log('kicked', JSON.stringify(r)));
  b.on('error', e => log('error', e.message));
  await new Promise(r => b.once('spawn', r));
  log('spawned');
  await sleep(3000);
  b.chat('hello from paper 1.21.11');
  await sleep(1000);
  tmux(sess, 'kill BotC');
  await sleep(1500);
  tmux(sess, 'advancement grant BotC only minecraft:story/mine_stone');
  await sleep(1500);
  tmux('bfmock', 'exec list');
  await sleep(1000);
  tmux('bfmock', 'exec bonfire status');
  await sleep(1000);
  log('rcon says:', JSON.stringify(await rcon('list')));
  await sleep(500);
  tmux('bfmock', 'chat hey paper');
  await sleep(1000);
  b.quit();
  await sleep(2500);
  process.exit(0);
})();
