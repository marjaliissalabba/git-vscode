'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { setup, client, login, inHours, pdf } = require('./helpers');

async function ctx(t) {
  const s = await setup();
  t.after(() => s.server.close());
  return s;
}

const newLoad = (over = {}) => ({
  type_key: 'papp', from_city: 'Alta', to_city: 'Tromsø', weight_t: 10, pickup_end: inHours(48),
  price_mode: 'bid', bid_deadline: inHours(5), ...over,
});

test('onboarding: oppslag i Brønnøysund og registrering av transportør', async t => {
  const { base } = await ctx(t);
  const c = client(base);
  const look = await c('GET', '/companies/lookup?orgnr=920000002');
  assert.equal(look.status, 200);
  assert.equal(look.data.name, 'Testfrakt AS');
  assert.equal(look.data.alreadyRegistered, false);
  assert.equal((await c('GET', '/companies/lookup?orgnr=912345688')).data.alreadyRegistered, true);
  assert.equal((await c('GET', '/companies/lookup?orgnr=960000005')).status, 404);

  const reg = await c('POST', '/auth/register', { role: 'transport', orgnr: '920000002',
    user: { name: 'Test', email: 'test@frakt.no', password: 'hemmelig123' } });
  assert.equal(reg.status, 200, JSON.stringify(reg.data));
  const me = (await c('GET', '/me')).data;
  assert.equal(me.company.name, 'Testfrakt AS', 'navnet kommer fra registeret, ikke klienten');
  assert.equal(me.company.verified, true);
  assert.equal(me.qualification.canBid, false);

  // Ikke godkjent: kan se lastbørsen, men ikke by
  const loads = (await c('GET', '/loads')).data;
  assert.ok(loads.length > 0);
  assert.ok(loads.every(l => !l.qualified));
  assert.equal((await c('PUT', `/loads/${loads.find(l => l.price_mode === 'bid').id}/bid`, { amount: 1000 })).status, 403);

  // Laster opp løyve → til kontroll med registersjekk
  const up = await c('PUT', '/me/qualifications/lov', { reference: 'GL-1', valid_until: '2099-01-01', file: pdf });
  assert.equal(up.status, 200, JSON.stringify(up.data));
  assert.equal(up.data.docs.find(d => d.key === 'lov').status, 'pending');
});

test('registrering avvises for konkursfirma, og fungerer uverifisert når Brønnøysund er nede', async t => {
  const { base, integrations } = await ctx(t);
  const konk = await client(base)('POST', '/auth/register', { role: 'transport', orgnr: '930000000', user: { name: 'X', email: 'x@x.no', password: 'hemmelig123' } });
  assert.equal(konk.status, 422);
  assert.match(konk.data.error, /konkurs/);

  integrations.updateConfig('brreg', { scenario: 'down' });
  const c = client(base);
  assert.equal((await c('GET', '/companies/lookup?orgnr=970000003')).status, 503);
  const reg = await c('POST', '/auth/register', { role: 'shipper', orgnr: '970000003', fallbackName: 'Manuelt Navn AS',
    user: { name: 'Y', email: 'y@y.no', password: 'hemmelig123' } });
  assert.equal(reg.status, 200);
  assert.equal(reg.data.verified, false);
  // Uverifisert oppdragsgiver kan ikke legge ut last
  assert.equal((await c('POST', '/loads', newLoad())).status, 403);
  // Når registeret er oppe igjen, kan firmaet verifiseres
  integrations.updateConfig('brreg', { scenario: 'auto' });
  const v = await c('POST', '/me/company/verify');
  assert.equal(v.data.company.verified, true);
  assert.equal(v.data.company.name, 'Testfirma 0003 AS');
  assert.equal((await c('POST', '/loads', newLoad())).status, 200);
});

test('lukket anbud: bud, ingen innsyn i andres beløp, tildeling og bekreftelse', async t => {
  const { base } = await ctx(t);
  const shipper = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const polar = await login(base, 'polar@demo.no');
  const fjord = await login(base, 'fjordbil@demo.no');

  const created = await shipper('POST', '/loads', newLoad({ max_price: 30000 }));
  assert.equal(created.status, 200, JSON.stringify(created.data));
  const id = created.data.id;
  assert.deepEqual(created.data.requirements, ['lov', 'avf'], 'avfallskrav forhåndsvalgt ut fra lasttype');

  // Kvalifiserte får varsel om ny last, ikke-godkjente får ikke
  assert.ok((await nord('GET', '/notifications')).data.some(n => n.message.startsWith('Ny last på anbud')));
  assert.ok(!(await fjord('GET', '/notifications')).data.some(n => n.message.startsWith('Ny last')));

  assert.equal((await fjord('PUT', `/loads/${id}/bid`, { amount: 9000 })).status, 403, 'løyve til kontroll');
  assert.equal((await nord('PUT', `/loads/${id}/bid`, { amount: 31000 })).status, 400, 'over makspris');
  assert.equal((await nord('PUT', `/loads/${id}/bid`, { amount: 25000, vehicleId: 1 })).status, 200);
  assert.equal((await nord('PUT', `/loads/${id}/bid`, { amount: 99, vehicleId: 3 })).status, 400, 'annet firmas kjøretøy');
  const p = await polar('PUT', `/loads/${id}/bid`, { amount: 23000, vehicleId: 3 });
  assert.equal(p.data.bid_count, 2, 'transportør ser antall bud');
  const nordView = (await nord('GET', `/loads/${id}`)).data;
  assert.equal(nordView.my_bid.amount, 25000);
  assert.ok(!JSON.stringify(nordView).includes('23000'), 'aldri andres beløp');

  const bids = (await shipper('GET', `/shipper/loads/${id}/bids`)).data;
  assert.deepEqual(bids.map(b => b.amount), [23000, 25000]);
  assert.equal(bids[0].lowest, true);
  assert.equal(bids[1].diff, 2000);

  // Oppdragsgiver kan tildele før fristen, og trenger ikke velge laveste
  const nordBid = bids.find(b => b.company.name === 'Nordtrans AS');
  const award = await shipper('POST', `/shipper/loads/${id}/award`, { bidId: nordBid.id });
  assert.equal(award.status, 200, JSON.stringify(award.data));
  assert.equal(award.data.status, 'awarded');
  assert.equal(award.data.assignment.status, 'awaiting_confirmation');
  assert.ok((await polar('GET', '/notifications')).data.some(n => n.message.includes('ble ikke valgt')));
  assert.equal((await polar('GET', '/loads')).data.some(l => l.id === id), false, 'tildelt last forsvinner fra lastbørsen');

  const active = (await nord('GET', '/me/assignments?tab=active')).data;
  const a = active.find(x => x.load.id === id);
  assert.equal(a.status, 'awaiting_confirmation');
  assert.equal((await polar('POST', `/assignments/${a.id}/confirm`)).status, 404);
  assert.equal((await nord('POST', `/assignments/${a.id}/confirm`)).data.status, 'confirmed');
  assert.equal((await shipper('GET', `/shipper/loads/${id}`)).data.status_text, 'Bekreftet');
  assert.equal((await nord('POST', `/assignments/${a.id}/complete`)).status, 200);
  assert.ok((await nord('GET', '/me/assignments?tab=done')).data.some(x => x.load.id === id));
});

test('avslag gir oppdragsgiver mulighet til å tildele neste budgiver', async t => {
  const { base, db } = await ctx(t);
  const shipper = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  // Demodata: L-2601 har bud fra Nordtrans og Polar
  const bids = (await shipper('GET', '/shipper/loads/1/bids')).data;
  const polarBid = bids.find(b => b.company.name === 'Polar Frakt AS');
  const nordBid = bids.find(b => b.company.name === 'Nordtrans AS');
  await shipper('POST', '/shipper/loads/1/award', { bidId: nordBid.id });
  const a = (await nord('GET', '/me/assignments?tab=active')).data.find(x => x.load.id === 1);
  assert.equal((await nord('POST', `/assignments/${a.id}/decline`, { reason: 'Bilen er på verksted' })).status, 200);
  assert.equal(db.prepare('SELECT declines FROM companies WHERE id = ?').get(nordBid.company.id).declines, 1);
  const after = (await shipper('GET', '/shipper/loads/1/bids')).data;
  assert.equal(after.length, 1, 'avslått bud vises ikke lenger');
  assert.equal((await shipper('POST', '/shipper/loads/1/award', { bidId: polarBid.id })).status, 200, 'tapt bud kan tildeles');
});

test('bekreftelsesfrist som går ut åpner lasten igjen', async t => {
  const { base, db } = await ctx(t);
  const shipper = await login(base, 'anlegg@demo.no');
  // Demodata: L-2606 er tildelt Nordtrans og venter på bekreftelse
  db.prepare(`UPDATE assignments SET confirm_by = ? WHERE status = 'awaiting_confirmation'`).run(inHours(-0.1));
  const l = (await shipper('GET', '/shipper/loads/6')).data;
  assert.equal(l.status, 'open');
  assert.equal(l.assignment.status, 'expired');
  assert.ok((await shipper('GET', '/notifications')).data.some(n => n.message.includes('bekreftet ikke')));
});

test('fastpris: atomisk, den andre får «Lasten er allerede tatt»', async t => {
  const { base } = await ctx(t);
  const shipper = await login(base, 'anlegg@demo.no');
  const nord = await login(base, 'nordtrans@demo.no');
  const polar = await login(base, 'polar@demo.no');
  const { data: l } = await shipper('POST', '/loads', newLoad({ price_mode: 'fixed', fixed_price: 14500 }));
  const [r1, r2] = await Promise.all([nord('POST', `/loads/${l.id}/take`, { vehicleId: 1 }), polar('POST', `/loads/${l.id}/take`, { vehicleId: 3 })]);
  const statuses = [r1.status, r2.status].sort();
  assert.deepEqual(statuses, [200, 409]);
  const loser = r1.status === 409 ? r1 : r2;
  assert.equal(loser.data.error, 'Lasten er allerede tatt');
  const winner = r1.status === 200 ? r1 : r2;
  assert.equal(winner.data.status, 'confirmed', 'fastpris er bekreftet med en gang');
  assert.ok((await shipper('GET', '/notifications')).data.some(n => n.message.includes('tok fastprislasten')));
});

test('ADR-last er låst for transportør uten ADR, og «visLaste» skjuler låste laster', async t => {
  const { base } = await ctx(t);
  const polar = await login(base, 'polar@demo.no');
  const loads = (await polar('GET', '/loads')).data;
  const adr = loads.find(l => l.category === 'Avfall · ADR');
  assert.equal(adr.qualified, false);
  assert.equal(adr.locked_text, 'Krever ADR');
  assert.equal((await polar('PUT', `/loads/${adr.id}/bid`, { amount: 5000 })).status, 403);
  await polar('POST', '/me/settings', { show_locked: false });
  assert.ok((await polar('GET', '/loads')).data.every(l => l.qualified));
  assert.ok((await polar('GET', '/loads?filter=fixed')).data.every(l => l.price_mode === 'fixed'));
});

test('admin godkjenner dokument, og transportøren kan by', async t => {
  const { base } = await ctx(t);
  const admin = await login(base, 'admin@demo.no');
  const fjord = await login(base, 'fjordbil@demo.no');
  const docs = (await admin('GET', '/admin/docs')).data;
  const lov = docs.find(d => d.company_name === 'Fjordbil Transport AS');
  assert.equal((await admin('POST', `/admin/docs/${lov.id}/review`, { status: 'rejected' })).status, 400, 'avvisning krever begrunnelse');
  assert.equal((await admin('POST', `/admin/docs/${lov.id}/review`, { status: 'approved' })).status, 200);
  const me = (await fjord('GET', '/me')).data;
  assert.equal(me.qualification.canBid, true);
  assert.ok((await fjord('GET', '/notifications')).data.some(n => n.message === 'Løyve for godstransport er godkjent'));
  const gips = (await fjord('GET', '/loads')).data.find(l => l.type === 'Gipsplater');
  assert.equal(gips.qualified, true);
  // Sperring
  const comp = (await admin('GET', '/admin/companies')).data.find(c => c.name === 'Fjordbil Transport AS');
  await admin('POST', `/admin/companies/${comp.id}/block`, { blocked: true, reason: 'Test' });
  assert.equal((await fjord('PUT', `/loads/${gips.id}/bid`, { amount: 5000 })).status, 403);
});

test('admin: testpanel for integrasjoner med scenario og logg', async t => {
  const { base } = await ctx(t);
  const admin = await login(base, 'admin@demo.no');
  const shipper = await login(base, 'anlegg@demo.no');
  assert.equal((await shipper('GET', '/admin/integrations')).status, 403);
  const ok = await admin('POST', '/admin/integrations/brreg/test', { orgnr: '923456783' });
  assert.equal(ok.data.ok, true);
  assert.equal(ok.data.result.name, 'Polar Frakt AS');
  await admin('PUT', '/admin/integrations/brreg', { scenario: 'down' });
  const down = await admin('POST', '/admin/integrations/brreg/test', { orgnr: '923456783' });
  assert.equal(down.data.ok, false);
  assert.equal(down.data.code, 'unavailable');
  const panel = (await admin('GET', '/admin/integrations')).data;
  assert.equal(panel.integrations.find(i => i.id === 'brreg').scenario, 'down');
  assert.equal(panel.log.length, 2);
  assert.equal((await admin('PUT', '/admin/integrations/vegvesen', { mode: 'live' })).status, 501);
});

test('rollebytte: et firma kan ha begge rollene', async t => {
  const { base } = await ctx(t);
  const nord = await login(base, 'nordtrans@demo.no');
  assert.equal((await nord('POST', '/loads', newLoad())).status, 403);
  const me = (await nord('POST', '/me/role', { role: 'shipper' })).data;
  assert.equal(me.company.is_shipper, true);
  assert.equal(me.company.is_transport, true);
  const { data: l } = await nord('POST', '/loads', newLoad());
  await nord('POST', '/me/role', { role: 'transport' });
  assert.equal((await nord('GET', '/loads')).data.some(x => x.id === l.id), false, 'egne laster vises ikke i lastbørsen');
});

test('sikkerhet: innlogging kreves, JSON kreves, oppdragsgiver ser bare egne laster', async t => {
  const { base } = await ctx(t);
  assert.equal((await client(base)('GET', '/loads')).status, 401);
  const res = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'content-type': 'text/plain' }, body: '{}' });
  assert.equal(res.status, 415);
  const nord = await login(base, 'nordtrans@demo.no');
  await nord('POST', '/me/role', { role: 'shipper' });
  assert.equal((await nord('GET', '/shipper/loads/1/bids')).status, 404);
  assert.equal((await fetch(base + '/imagery/finnes-ikke.jpg')).status, 404);
});
