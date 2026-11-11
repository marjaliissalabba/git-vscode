'use strict';
/**
 * Demoversjon: kjører hele serveren (server/app.js) i nettleseren.
 * Databasen er SQLite (sql.js) og lagres i localStorage, så dataene blir liggende til du starter demoen på nytt.
 * Kall til /api/... fanges opp og sendes til serveren i stedet for nettverket.
 */
const { openDatabase, seed } = require('../server/db');
const { createApp } = require('../server/app');

const DB_KEY = 'lastbors-demo-db-v2';
const SESSION_KEY = 'lastbors-demo-session';
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); return true; } catch { return false; } },
  del(k) { try { localStorage.removeItem(k); } catch { /* ignorert */ } },
};
let session = store.get(SESSION_KEY);

function toBase64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function fromBase64(b64) {
  const s = atob(b64), out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

let db, handler, saveTimer;
const ready = (async () => {
  globalThis.__SQL = await globalThis.initSqlJs();
  const saved = store.get(DB_KEY);
  if (saved) {
    try { globalThis.__LASTBORS_DB_BYTES = fromBase64(saved); db = openDatabase(':memory:'); } catch { db = null; }
  }
  if (!db) { globalThis.__LASTBORS_DB_BYTES = null; db = openDatabase(':memory:'); }
  seed(db);
  handler = createApp({ db });
})();

function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    // Rå sql.js-database ligger i db.db (se demo/shims/sqlite.js)
    const bytes = db.db.export ? db.db.export() : db.export();
    store.set(DB_KEY, toBase64(bytes));
  }, 150);
}

async function apiFetch(url, opts = {}) {
  await ready;
  const method = String(opts.method || 'GET').toUpperCase();
  const headers = {};
  for (const [k, v] of Object.entries(opts.headers || {})) headers[k.toLowerCase()] = v;
  if (session) headers.cookie = `session=${session}`;
  const body = opts.body == null ? null : String(opts.body);
  const listeners = {};
  const req = {
    url, method, headers,
    on(ev, cb) {
      listeners[ev] = cb;
      if (ev === 'end') queueMicrotask(() => { if (body !== null && listeners.data) listeners.data(Buffer.from(body)); cb(); });
      return req;
    },
    destroy() {},
  };
  return new Promise(resolve => {
    const out = {};
    let status = 200;
    const res = {
      headersSent: false,
      setHeader(k, v) { out[k.toLowerCase()] = v; },
      writeHead(s, h = {}) { status = s; for (const [k, v] of Object.entries(h)) out[k.toLowerCase()] = v; res.headersSent = true; },
      end(data) {
        const cookie = out['set-cookie'];
        if (cookie) {
          const m = /^session=([^;]*)/.exec(cookie);
          session = m && m[1] && !/Max-Age=0/.test(cookie) ? m[1] : null;
          if (session) store.set(SESSION_KEY, session); else store.del(SESSION_KEY);
          delete out['set-cookie'];
        }
        const payload = data == null ? null : typeof data === 'string' ? data : new Uint8Array(data);
        resolve(new Response(payload, { status, headers: out }));
        save();
      },
    };
    Promise.resolve(handler(req, res)).catch(err => {
      console.error(err);
      resolve(new Response(JSON.stringify({ error: 'Noe gikk galt i demoen' }), { status: 500 }));
    });
  });
}

const realFetch = globalThis.fetch.bind(globalThis);
globalThis.fetch = (input, opts) => {
  const url = typeof input === 'string' ? input : input.url;
  return url.startsWith('/api/') ? apiFetch(url, opts) : realFetch(input, opts);
};

globalThis.__LASTBORS_RESET = () => {
  store.del(DB_KEY); store.del(SESSION_KEY);
  location.reload();
};
