// ============================================================================
// Numen Linux 测试：主人假玩家（mineflayer）
//
// 替代 Windows 侧的图形客户端——Linux 上没有 PCL/客户端可借，但大多数服务端
// 用例只需要「有一个真人在线当 owner」+ 服务端控制台能发命令。这个假玩家就干这个：
// 以离线身份连进测试服，常驻、自动重连。
//
//   用法：MC_HOST=127.0.0.1 MC_PORT=25987 MC_USER=T node owner-bot.js
//
// 环境变量：MC_HOST / MC_PORT / MC_USER / MC_VERSION（默认 1.16.5）。
// 输出：每行一条 JSON（connecting/spawned/chat/left/kicked/error），便于驱动抓。
// ============================================================================
const mineflayer = require('mineflayer');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const USER = process.env.MC_USER || 'T';
const VERSION = process.env.MC_VERSION || '1.16.5';

let backoff = 3000;
let bot = null;

function log(kind, extra) {
  console.log(JSON.stringify({ t: new Date().toISOString(), kind, ...extra }));
}

function connect() {
  log('connecting', { host: HOST, port: PORT, user: USER, version: VERSION });
  bot = mineflayer.createBot({ host: HOST, port: PORT, username: USER, version: VERSION, auth: 'offline' });

  bot.once('spawn', () => {
    backoff = 3000;
    log('spawned', { pos: bot.entity && bot.entity.position, uuid: bot.player && bot.player.uuid });
  });

  bot.on('chat', (username, message) => {
    if (username !== bot.username) log('chat', { from: username, message });
  });
  bot.on('message', (json) => { try { log('message', { raw: JSON.stringify(json) }); } catch (_) {} });
  bot.on('kicked', (reason) => log('kicked', { reason: String(reason) }));
  bot.on('error', (err) => log('error', { error: String(err) }));
  bot.on('end', (reason) => {
    log('left', { reason: String(reason) });
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  });
}

connect();
