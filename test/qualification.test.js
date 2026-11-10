'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluate, expiringSoon, BASE_REQUIREMENTS } = require('../server/qualification');

const now = new Date('2026-06-01T12:00:00');
const approved = (code, valid_until = '2027-01-01') => ({ type_code: code, status: 'approved', valid_until });
const base = BASE_REQUIREMENTS.map(c => approved(c));

test('godkjent firma med alle grunnkrav er kvalifisert', () => {
  const r = evaluate({ status: 'approved' }, base, [], now);
  assert.equal(r.qualified, true);
  assert.deepEqual(r.missing, []);
});

test('firma som ikke er godkjent er ikke kvalifisert selv med alle dokumenter', () => {
  const r = evaluate({ status: 'pending' }, base, [], now);
  assert.equal(r.qualified, false);
  assert.equal(r.companyApproved, false);
});

test('lastens ekstra krav må være oppfylt', () => {
  const r = evaluate({ status: 'approved' }, base, ['ADR'], now);
  assert.equal(r.qualified, false);
  assert.deepEqual(r.missing.map(m => [m.code, m.reason]), [['ADR', 'mangler']]);
  assert.equal(evaluate({ status: 'approved' }, [...base, approved('ADR')], ['ADR'], now).qualified, true);
});

test('utløpte, avviste og ventende dokumenter teller ikke', () => {
  const quals = [
    ...base.filter(q => !['FORSIKRING', 'SKATT', 'HMS'].includes(q.type_code)),
    approved('FORSIKRING', '2026-05-31'),
    { type_code: 'SKATT', status: 'pending', valid_until: null },
    { type_code: 'HMS', status: 'rejected', valid_until: null },
  ];
  const r = evaluate({ status: 'approved' }, quals, [], now);
  assert.equal(r.qualified, false);
  assert.deepEqual(Object.fromEntries(r.missing.map(m => [m.code, m.reason])), { FORSIKRING: 'utløpt', SKATT: 'venter godkjenning', HMS: 'avvist' });
});

test('dokument som utløper i dag er fortsatt gyldig', () => {
  const quals = base.map(q => q.type_code === 'LOYVE' ? approved('LOYVE', '2026-06-01') : q);
  assert.equal(evaluate({ status: 'approved' }, quals, [], now).qualified, true);
});

test('expiringSoon finner godkjente dokumenter som utløper innen 30 dager', () => {
  const list = expiringSoon([approved('LOYVE', '2026-06-20'), approved('SKATT', '2026-12-01'), approved('HMS', '2026-05-01')], 30, now);
  assert.deepEqual(list.map(q => q.type_code), ['LOYVE']);
});
