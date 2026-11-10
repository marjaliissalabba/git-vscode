'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { openDatabase } = require('../server/db');
const { createIntegrations, validOrgnr } = require('../server/integrations');

function setup(fetchImpl) {
  const db = openDatabase(':memory:');
  const integ = createIntegrations({ db, fetchImpl });
  for (const id of ['brreg', 'vegvesen', 'miljodir']) integ.updateConfig(id, { delayMs: 0 });
  return integ;
}

test('org.nr valideres med kontrollsiffer (mod 11)', () => {
  assert.equal(validOrgnr('923609016'), true);
  assert.equal(validOrgnr('923609017'), false);
  assert.equal(validOrgnr('12345'), false);
});

test('simulert Brønnøysund: kjente testfirma og generert firma', async () => {
  const integ = setup();
  const r = await integ.lookupCompany('912 345 688');
  assert.equal(r.name, 'Nordtrans AS');
  assert.equal(r.source, 'simulated');
  assert.match((await integ.lookupCompany('970000003')).name, /^Testfirma 0003 AS$/);
  assert.equal((await integ.lookupCompany('930000000')).konkurs, true);
  await assert.rejects(integ.lookupCompany('960000005'), { code: 'not_found' });
  await assert.rejects(integ.lookupCompany('950000007'), { code: 'deleted' });
  await assert.rejects(integ.lookupCompany('123456789'), { code: 'invalid' });
});

test('scenarier: nede, ikke funnet og normal', async () => {
  const integ = setup();
  integ.updateConfig('brreg', { scenario: 'down' });
  await assert.rejects(integ.lookupCompany('912345688'), { code: 'unavailable' });
  integ.updateConfig('brreg', { scenario: 'not_found' });
  await assert.rejects(integ.lookupCompany('912345688'), { code: 'not_found' });
  integ.updateConfig('brreg', { scenario: 'normal' });
  assert.equal((await integ.lookupCompany('930000000')).konkurs, false, 'normal overstyrer konkurs');
});

test('alle kall logges', async () => {
  const integ = setup();
  await integ.lookupCompany('912345688');
  await integ.lookupCompany('960000005').catch(() => {});
  const log = integ.recentLog();
  assert.equal(log.length, 2);
  assert.equal(log[0].ok, false);
  assert.equal(log[0].error_code, 'not_found');
  assert.equal(log[1].ok, true);
  assert.equal(log[1].response.name, 'Nordtrans AS');
});

test('ekte Brønnøysund-modus tolker svaret fra Enhetsregisteret', async () => {
  const calls = [];
  const fakeFetch = async url => {
    calls.push(url);
    if (url.endsWith('/960000005')) return { status: 404, ok: false };
    return { status: 200, ok: true, json: async () => ({
      organisasjonsnummer: '923609016', navn: 'EQUINOR ASA', konkurs: false, underAvvikling: false,
      naeringskode1: { kode: '06.100', beskrivelse: 'Utvinning av råolje' }, organisasjonsform: { kode: 'ASA' },
      forretningsadresse: { adresse: ['Forusbeen 50'], postnummer: '4035', poststed: 'STAVANGER', kommune: 'STAVANGER' },
    }) };
  };
  const integ = setup(fakeFetch);
  integ.updateConfig('brreg', { mode: 'live' });
  const r = await integ.lookupCompany('923609016');
  assert.equal(calls[0], 'https://data.brreg.no/enhetsregisteret/api/enheter/923609016');
  assert.equal(r.name, 'EQUINOR ASA');
  assert.equal(r.municipality, 'Stavanger');
  assert.equal(r.source, 'live');
  await assert.rejects(integ.lookupCompany('960000005'), { code: 'not_found' });
});

test('ekte modus gir tydelig feil når nettet er nede', async () => {
  const integ = setup(async () => { throw new Error('ECONNREFUSED'); });
  integ.updateConfig('brreg', { mode: 'live' });
  await assert.rejects(integ.lookupCompany('923609016'), { code: 'unavailable' });
});

test('Vegvesen og Miljødirektoratet kan bare simuleres', async () => {
  const integ = setup();
  assert.throws(() => integ.updateConfig('vegvesen', { mode: 'live' }), { code: 'not_configured' });
  assert.equal((await integ.checkLicence('912345688')).valid, true);
  assert.equal((await integ.checkWasteCarrier('920000002')).valid, false);
});
