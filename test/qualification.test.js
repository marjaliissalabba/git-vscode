'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluate, lockedText, docStatus, expiringSoon, normalizeRequirements } = require('../server/qualification');

const now = new Date('2026-06-01T12:00:00');
const ok = (type, valid_until = '2027-01-01') => ({ type, status: 'approved', valid_until });
const company = { blocked: 0 };

test('løyve er alltid et krav', () => {
  assert.deepEqual(normalizeRequirements([]), ['lov']);
  assert.deepEqual(normalizeRequirements(['adr', 'avf', 'xxx']), ['lov', 'avf', 'adr']);
});

test('godkjent løyve gir tilgang til laster uten ekstra krav', () => {
  assert.equal(evaluate(company, [ok('lov')], [], now).qualified, true);
  assert.equal(evaluate(company, [], [], now).qualified, false);
});

test('alle lastens krav må være godkjent', () => {
  const r = evaluate(company, [ok('lov'), ok('avf')], ['avf', 'adr'], now);
  assert.equal(r.qualified, false);
  assert.equal(lockedText(r), 'Krever ADR');
  assert.equal(lockedText(evaluate(company, [], ['avf'], now)), 'Krever løyve + avfallstransportør');
});

test('status: til kontroll, avvist og utløpt teller ikke', () => {
  assert.equal(docStatus({ status: 'pending' }, now), 'pending');
  assert.equal(docStatus({ status: 'rejected' }, now), 'rejected');
  assert.equal(docStatus(ok('lov', '2026-05-31'), now), 'expired');
  assert.equal(docStatus(ok('lov', '2026-06-01'), now), 'approved', 'gyldig ut dagen');
  assert.equal(docStatus(undefined, now), 'missing');
  assert.equal(evaluate(company, [ok('lov', '2026-05-31')], [], now).qualified, false);
});

test('sperret firma er ikke kvalifisert', () => {
  const r = evaluate({ blocked: 1 }, [ok('lov')], [], now);
  assert.equal(r.qualified, false);
  assert.equal(lockedText(r), 'Firmaet er sperret');
});

test('expiringSoon finner dokumenter som utløper innen 30 dager', () => {
  const list = expiringSoon([ok('lov', '2026-06-20'), ok('avf', '2026-12-01'), ok('adr', '2026-05-01')], 30, now);
  assert.deepEqual(list.map(d => d.type), ['lov']);
});
