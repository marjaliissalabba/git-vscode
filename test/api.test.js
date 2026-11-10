'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { openDatabase, seed } = require('../server/db');
const { createApp } = require('../server/app');

async function setup() {
  const db = openDatabase(':memory:');
  seed(db);
  const server = http.createServer(createApp({ db }));
  await new Promise(r => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { db, server, base };
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
    const data = await res.json().catch(() => null);
    return { status: res.status, data };
  };
}

async function login(base, email) {
  const c = client(base);
  const r = await c('POST', '/auth/login', { email, password: 'demo1234' });
  assert.equal(r.status, 200);
  return c;
}

const inHours = h => new Date(Date.now() + h * 3600000).toISOString();

test('hele flyten: last på anbud → bud → tildeling → levering', async t => {
  const { server, base } = await setup();
  t.after(() => server.close());
  const admin = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const fjord = await login(base, 'fjordbil@demo.no');
  const ny = await login(base, 'nykommer@demo.no');

  // Anlegget legger ut en ADR-last på anbud
  const created = await admin('POST', '/loads', {
    title: 'Spraybokser', cargo_type: 'Farlig avfall', from_site: 'Anlegget', to_address: 'Mottak',
    pickup_from: inHours(10), required_quals: ['ADR', 'AVFALL'], mode: 'tender', bid_deadline: inHours(5), publish: true,
  });
  assert.equal(created.status, 200, JSON.stringify(created.data));
  assert.equal(created.data.status, 'open');
  const id = created.data.id;

  // Transportør uten ADR ser lasten som låst og kan ikke gi bud
  const fjordView = await fjord('GET', `/loads/${id}`);
  assert.equal(fjordView.data.eligibility.qualified, false);
  assert.deepEqual(fjordView.data.eligibility.missing.map(m => m.code), ['ADR']);
  const fjordBid = await fjord('POST', `/loads/${id}/bids`, { price_nok: 1000 });
  assert.equal(fjordBid.status, 403);

  // Ikke-prekvalifisert firma kan ikke gi bud
  assert.equal((await ny('POST', `/loads/${id}/bids`, { price_nok: 900 })).status, 403);

  // Kvalifisert transportør gir bud og endrer det
  assert.equal((await nord('POST', `/loads/${id}/bids`, { price_nok: 12000 })).status, 200);
  const upd = await nord('POST', `/loads/${id}/bids`, { price_nok: 11000, comment: 'ADR-bil klar' });
  assert.equal(upd.data.my_bid.price_nok, 11000);
  assert.equal(upd.data.bid_count, undefined, 'lukket anbud: transportør ser ikke antall bud');

  // Transportør ser ikke andres bud, anlegget gjør
  assert.equal((await nord('GET', `/loads/${id}`)).data.bids, undefined);
  const adminView = await admin('GET', `/loads/${id}`);
  assert.equal(adminView.data.bids.length, 1);
  assert.equal(adminView.data.bids[0].eligibility.qualified, true);

  // Transportør kan ikke tildele
  assert.equal((await nord('POST', `/loads/${id}/award`, { bid_id: adminView.data.bids[0].id })).status, 403);

  // Tildeling
  const award = await admin('POST', `/loads/${id}/award`, { bid_id: adminView.data.bids[0].id });
  assert.equal(award.status, 200);
  assert.equal(award.data.status, 'awarded');

  // Vinneren får varsel og ser oppdraget
  const notes = await nord('GET', '/notifications');
  assert.ok(notes.data.some(n => n.message.includes('Du har fått oppdraget')));
  const jobs = await nord('GET', '/loads?scope=mine');
  assert.ok(jobs.data.some(l => l.id === id && l.is_mine));

  // Andre transportører kan ikke oppdatere status
  assert.equal((await fjord('POST', `/loads/${id}/status`, { status: 'picked_up' })).status, 404);
  // Statusflyt kan ikke hoppe over steg
  assert.equal((await nord('POST', `/loads/${id}/status`, { status: 'delivered' })).status, 409);
  assert.equal((await nord('POST', `/loads/${id}/status`, { status: 'picked_up', note: 'Vektseddel 123' })).data.status, 'picked_up');
  assert.equal((await nord('POST', `/loads/${id}/status`, { status: 'delivered' })).data.status, 'delivered');
});

test('fastpris: første kvalifiserte transportør får oppdraget', async t => {
  const { server, base } = await setup();
  t.after(() => server.close());
  const admin = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const fjord = await login(base, 'fjordbil@demo.no');

  const { data: load } = await admin('POST', '/loads', {
    title: 'Containerbytte', cargo_type: 'Restavfall', from_site: 'Anlegget', to_address: 'Kunde', pickup_from: inHours(2),
    required_quals: ['KROKLOFT'], mode: 'direct', price_nok: 3500, publish: true,
  });
  const first = await fjord('POST', `/loads/${load.id}/accept`, {});
  assert.equal(first.status, 200, JSON.stringify(first.data));
  assert.equal(first.data.is_mine, true);
  const second = await nord('POST', `/loads/${load.id}/accept`, {});
  assert.equal(second.status, 404, 'lasten er ikke lenger synlig for andre');
});

test('anbudsfrist som går ut flytter lasten til tildeling', async t => {
  const { server, base, db } = await setup();
  t.after(() => server.close());
  const admin = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const { data: load } = await admin('POST', '/loads', {
    title: 'Metall', cargo_type: 'Metall', from_site: 'A', to_address: 'B', pickup_from: inHours(30),
    required_quals: [], mode: 'tender', bid_deadline: inHours(1), publish: true,
  });
  await nord('POST', `/loads/${load.id}/bids`, { price_nok: 5000 });
  db.prepare('UPDATE loads SET bid_deadline = ? WHERE id = ?').run(inHours(-1), load.id);
  const after = await admin('GET', `/loads/${load.id}`);
  assert.equal(after.data.status, 'evaluation');
  assert.equal((await nord('POST', `/loads/${load.id}/bids`, { price_nok: 4000 })).status, 409);
});

test('prekvalifisering: registrering → dokumenter → godkjenning gir rett til å by', async t => {
  const { server, base } = await setup();
  t.after(() => server.close());
  const admin = await login(base, 'anlegg@demo.no');
  const carrier = client(base);
  const reg = await carrier('POST', '/auth/register', {
    company: { name: 'Test Transport AS', org_nr: '987 654 321' },
    user: { name: 'Test', email: 'test@transport.no', password: 'hemmelig123' },
  });
  assert.equal(reg.status, 200, JSON.stringify(reg.data));
  assert.equal((await carrier('GET', '/me')).data.company.status, 'pending');

  // Duplikat org.nr avvises
  assert.equal((await client(base)('POST', '/auth/register', {
    company: { name: 'X', org_nr: '987654321' }, user: { name: 'X', email: 'x@x.no', password: 'hemmelig123' },
  })).status, 409);

  const pdf = Buffer.from('%PDF-1.4 test').toString('base64');
  for (const code of ['LOYVE', 'FORSIKRING', 'SKATT', 'HMS']) {
    const r = await carrier('PUT', `/my/qualifications/${code}`, { reference: code + '-1', valid_until: '2099-01-01', document: { name: 'a.pdf', type: 'application/pdf', data: pdf } });
    assert.equal(r.status, 200, JSON.stringify(r.data));
  }
  // Ugyldig filtype avvises
  assert.equal((await carrier('PUT', '/my/qualifications/ADR', { document: { name: 'a.exe', type: 'application/x-msdownload', data: pdf } })).status, 400);

  const { data: open } = await carrier('GET', '/loads');
  const target = open.find(l => l.required_quals.length === 1 && l.required_quals[0] === 'AVFALL' && l.mode === 'tender') || open.find(l => l.mode === 'tender');
  assert.equal(target.eligibility.qualified, false);

  const companies = (await admin('GET', '/companies')).data;
  const company = companies.find(c => c.org_nr === '987654321');
  const detail = (await admin('GET', `/companies/${company.id}`)).data;
  for (const q of detail.qualifications) assert.equal((await admin('POST', `/qualifications/${q.id}/review`, { status: 'approved' })).status, 200);
  // Avvisning krever begrunnelse
  assert.equal((await admin('POST', `/qualifications/${detail.qualifications[0].id}/review`, { status: 'rejected' })).status, 400);

  // Dokumentene er godkjent, men firmaet er ikke prekvalifisert ennå
  assert.equal((await carrier('GET', '/me')).data.base_status.qualified, false);
  assert.equal((await admin('POST', `/companies/${company.id}/status`, { status: 'approved' })).status, 200);
  const me = (await carrier('GET', '/me')).data;
  assert.equal(me.base_status.qualified, true);

  // Grunnkrav ok, men lasten med AVFALL-krav krever fortsatt avfallsregistrering
  const l = (await carrier('GET', `/loads/${target.id}`)).data;
  assert.deepEqual(l.eligibility.missing.map(m => m.code), target.required_quals);

  // Når et dokument sendes inn på nytt, må det godkjennes igjen
  await carrier('PUT', '/my/qualifications/HMS', { reference: 'HMS-2', valid_until: '2099-01-01' });
  assert.equal((await carrier('GET', '/me')).data.base_status.qualified, false);
});

test('tildeling blokkeres hvis transportøren har mistet kvalifikasjonen', async t => {
  const { server, base, db } = await setup();
  t.after(() => server.close());
  const admin = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const { data: load } = await admin('POST', '/loads', {
    title: 'Olje', cargo_type: 'Farlig avfall', from_site: 'A', to_address: 'B', pickup_from: inHours(30),
    required_quals: ['ADR'], mode: 'tender', bid_deadline: inHours(5), publish: true,
  });
  await nord('POST', `/loads/${load.id}/bids`, { price_nok: 5000 });
  db.prepare(`UPDATE company_qualifications SET valid_until = '2000-01-01' WHERE type_code = 'ADR'`).run();
  const bid = (await admin('GET', `/loads/${load.id}`)).data.bids[0];
  const r = await admin('POST', `/loads/${load.id}/award`, { bid_id: bid.id });
  assert.equal(r.status, 409);
  assert.match(r.data.error, /ADR/);
});

test('skrivende kall krever JSON og innlogging', async t => {
  const { server, base } = await setup();
  t.after(() => server.close());
  assert.equal((await client(base)('GET', '/loads')).status, 401);
  const res = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'email=a' });
  assert.equal(res.status, 415);
  const html = await fetch(base + '/');
  assert.match(await html.text(), /Lastebørs/);
});
