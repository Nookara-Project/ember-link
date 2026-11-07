const mineflayer = require('mineflayer');
const { execSync } = require('child_process');

const sleep = ms => new Promise(r => setTimeout(r, ms));
const tmux = (s, cmd) => execSync(`tmux send-keys -t ${s} ${JSON.stringify(cmd)} Enter`);
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

function bot(name) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25600, username: name, version: '1.20.4', auth: 'offline' });
  b.on('messagestr', (m, pos) => { if (pos !== 'game_info') log(`[${name} sees] ${m}`); });
  b.on('kicked', r => log(`[${name}] kicked`, JSON.stringify(r)));
  b.on('error', e => log(`[${name}] error`, e.message));
  return new Promise(res => b.once('spawn', () => { log(`[${name}] spawned`); res(b); }));
}

(async () => {
  const a = await bot('BotA');
  await sleep(4000);
  tmux('bfpaper', 'lp user BotA meta setprefix "&c[Admin] "');
  await sleep(7000);
  a.chat('hello from BotA on survival');
  await sleep(1500);

  const b = await bot('BotB');
  await sleep(5000);
  b.chat('BotB is vanished but chatting');
  await sleep(1500);

  log('--- BotA switches to lobby');
  a.chat('/server lobby');
  await sleep(5000);
  b.chat('cross-server hello from BotB on survival');
  await sleep(1500);

  log('--- death + advancement on lobby');
  tmux('bflobby', 'kill BotA');
  await sleep(2000);
  tmux('bflobby', 'advancement grant BotA only minecraft:story/mine_stone');
  await sleep(2000);
  log('--- death of vanished BotB on survival (should be suppressed)');
  tmux('bfpaper', 'kill BotB');
  await sleep(2000);

  log('--- spoofed bonfire:events from a client (must be dropped by the proxy)');
  const json = JSON.stringify({ v: 1, kind: 'death', uuid: a.player.uuid, name: 'BotA', text: 'BotA was SPOOFED' });
  const body = Buffer.from(json, 'utf8');
  const data = Buffer.concat([Buffer.from([body.length & 0x7f | (body.length > 127 ? 0x80 : 0)]), body.length > 127 ? Buffer.from([body.length >> 7]) : Buffer.alloc(0), body]);
  a._client.write('custom_payload', { channel: 'bonfire:events', data });
  a._client.write('custom_payload', { channel: 'bonfire:hello', data });
  await sleep(1500);

  log('--- Ember -> game');
  tmux('bfmock', 'chat hi from bonfire');
  await sleep(1000);
  tmux('bfmock', 'exec glist');
  await sleep(1000);
  tmux('bfvelo', 'glist');
  await sleep(1500);

  log('--- leaves');
  a.quit();
  await sleep(1500);
  b.quit();
  await sleep(3000);
  process.exit(0);
})();
