'use strict';
const http = require('node:http');
const assert = require('node:assert/strict');
const { openDatabase, seed } = require('../server/db');
const { createApp } = require('../server/app');
const { createIntegrations } = require('../server/integrations');

async function setup() {
  const db = openDatabase(':memory:');
  seed(db);
  const integrations = createIntegrations({ db });
  for (const id of ['brreg', 'vegvesen', 'miljodir']) integrations.updateConfig(id, { delayMs: 0 });
  const server = http.createServer(createApp({ db, integrations }));
  await new Promise(r => server.listen(0, r));
  return { db, integrations, server, base: `http://127.0.0.1:${server.address().port}` };
}

function client(base) {
  let cookie = '';
  return async function call(method, path, body) {
    const res = await fetch(base + '/api' + path, {
      method,
      headers: { ...(cookie ? { cookie } : {}), ...(method !== 'GET' ? { 'content-type': 'application/json' } : {}) },
      body: method !== 'GET' ? JSON.stringify(body || {}) : undefined,
    });
    const set = res.headers.get('set-cookie');
    if (set) cookie = set.split(';')[0];
    return { status: res.status, data: await res.json().catch(() => null) };
  };
}

async function login(base, email) {
  const c = client(base);
  const r = await c('POST', '/auth/login', { email, password: 'demo1234' });
  assert.equal(r.status, 200);
  return c;
}

const inHours = h => new Date(Date.now() + h * 3600000).toISOString();
const pdf = { name: 'loyve.pdf', type: 'application/pdf', data: Buffer.from('%PDF-1.4 test').toString('base64') };

module.exports = { setup, client, login, inHours, pdf };
