// QuestCast signaling server.
// Serves the viewer/sender pages and relays WebRTC handshakes between a
// host (the headset) and the viewers that typed its session code.
// Video never passes through here: it flows peer to peer.

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { randomInt, randomUUID } from 'node:crypto';
import { networkInterfaces } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT) || 8787;
const PUBLIC_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');
const MAX_VIEWERS = 8;
const MAX_BAD_CODES = 10;
const MAX_MESSAGE_BYTES = 64 * 1024;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const ROUTES = { '/': 'index.html', '/cast': 'cast.html' };

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
    return;
  }
  const name = ROUTES[url.pathname] ?? url.pathname.slice(1);
  const file = path.join(PUBLIC_DIR, path.normalize(name));
  if (!file.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403).end();
    return;
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] ?? 'application/octet-stream',
      'cache-control': 'no-cache',
    }).end(body);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('Not found');
  }
});

/** code -> { host: WebSocket, viewers: Map<viewerId, WebSocket> } */
const sessions = new Map();

function newCode() {
  for (;;) {
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    if (!sessions.has(code)) return code;
  }
}

function send(ws, msg) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function endSession(code) {
  const session = sessions.get(code);
  if (!session) return;
  sessions.delete(code);
  for (const viewer of session.viewers.values()) {
    send(viewer, { type: 'ended' });
    viewer.role = null;
    viewer.code = null;
  }
}

function leave(ws) {
  if (ws.role === 'host') {
    endSession(ws.code);
  } else if (ws.role === 'viewer') {
    const session = sessions.get(ws.code);
    if (session?.viewers.delete(ws.viewerId)) {
      send(session.host, { type: 'viewer-left', viewerId: ws.viewerId });
    }
  }
  ws.role = null;
  ws.code = null;
}

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_MESSAGE_BYTES });

wss.on('connection', (ws) => {
  ws.alive = true;
  ws.role = null;
  ws.badCodes = 0;
  ws.on('pong', () => { ws.alive = true; });
  ws.on('error', () => {});
  ws.on('close', () => leave(ws));

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    switch (msg.type) {
      case 'host': {
        if (ws.role) return;
        const code = newCode();
        sessions.set(code, { host: ws, viewers: new Map() });
        ws.role = 'host';
        ws.code = code;
        send(ws, { type: 'hosted', code });
        break;
      }

      case 'join': {
        if (ws.role) return;
        const code = String(msg.code ?? '').replace(/\D/g, '');
        const session = sessions.get(code);
        if (!session) {
          send(ws, { type: 'error', reason: 'not-found', message: 'No session with that code.' });
          if (++ws.badCodes >= MAX_BAD_CODES) ws.close(1008, 'too many attempts');
          return;
        }
        if (session.viewers.size >= MAX_VIEWERS) {
          send(ws, { type: 'error', reason: 'full', message: 'That session is full.' });
          return;
        }
        ws.role = 'viewer';
        ws.code = code;
        ws.viewerId = randomUUID();
        session.viewers.set(ws.viewerId, ws);
        send(ws, { type: 'joined' });
        send(session.host, { type: 'viewer-joined', viewerId: ws.viewerId });
        break;
      }

      case 'signal': {
        const session = sessions.get(ws.code);
        if (!session || msg.data == null) return;
        if (ws.role === 'host') {
          const viewer = session.viewers.get(msg.to);
          if (viewer) send(viewer, { type: 'signal', data: msg.data });
        } else if (ws.role === 'viewer') {
          send(session.host, { type: 'signal', from: ws.viewerId, data: msg.data });
        }
        break;
      }

      case 'leave':
        leave(ws);
        break;
    }
  });
});

// Drop connections that stopped answering (headset went to sleep, Wi-Fi lost).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.alive) { ws.terminate(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 15_000);
wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, () => {
  console.log(`QuestCast is running.`);
  console.log(`  This computer:  http://localhost:${PORT}`);
  for (const addrs of Object.values(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family === 'IPv4' && !a.internal) {
        console.log(`  Same Wi-Fi:     http://${a.address}:${PORT}`);
      }
    }
  }
});
