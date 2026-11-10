'use strict';

/**
 * Prekvalifisering (spesifikasjonen § 3).
 *
 * Dokumenttyper i v1: lov (løyve, alltid påkrevd), avf (avfallstransportør), adr (ADR).
 * En transportør er kvalifisert for en last når alle kravene har status «approved»
 * og ikke er utløpt, og firmaet ikke er sperret.
 * Ren logikk uten database, slik at den kan testes isolert.
 */

const REQ_TYPES = [
  { key: 'lov', name: 'Løyve for godstransport', short: 'løyve', required: true, unlocks: 'Alle laster',
    description: 'Gyldig løyve fra Statens vegvesen for godstransport på vei.' },
  { key: 'avf', name: 'Registrert avfallstransportør', short: 'avfallstransportør', required: false, unlocks: 'Avfallslaster',
    description: 'Registrering som transportør av avfall hos Miljødirektoratet.' },
  { key: 'adr', name: 'ADR-kompetansebevis', short: 'ADR', required: false, unlocks: 'Farlig gods og farlig avfall',
    description: 'ADR-kompetansebevis for sjåfør og godkjent kjøretøy for farlig gods.' },
];
const REQ_KEYS = REQ_TYPES.map(t => t.key);
const REQ_BY_KEY = Object.fromEntries(REQ_TYPES.map(t => [t.key, t]));

function todayString(now = new Date()) {
  const p = n => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/** Lastens krav: «lov» er alltid med, ukjente nøkler fjernes. */
function normalizeRequirements(reqs = []) {
  return ['lov', ...REQ_KEYS.filter(k => k !== 'lov' && reqs.includes(k))];
}

/** Effektiv status for ett dokument: missing | pending | approved | rejected | expired. */
function docStatus(doc, now = new Date()) {
  if (!doc) return 'missing';
  if (doc.status === 'approved' && doc.valid_until && doc.valid_until < todayString(now)) return 'expired';
  return doc.status;
}

/**
 * @param {{blocked?:number|boolean}|null} company
 * @param {Array<{type:string,status:string,valid_until?:string|null}>} docs
 * @param {string[]} requirements
 */
function evaluate(company, docs, requirements = [], now = new Date()) {
  const byType = new Map((docs || []).map(d => [d.type, d]));
  const items = normalizeRequirements(requirements).map(key => ({
    key, name: REQ_BY_KEY[key].name, short: REQ_BY_KEY[key].short, status: docStatus(byType.get(key), now),
  }));
  const missing = items.filter(i => i.status !== 'approved');
  const blocked = !!(company && company.blocked);
  return { qualified: !!company && !blocked && missing.length === 0, blocked, items, missing };
}

/** «Krever løyve + ADR» for låste laster. */
function lockedText(evaluation) {
  const names = evaluation.missing.map(m => m.short);
  return names.length ? 'Krever ' + names.join(' + ') : evaluation.blocked ? 'Firmaet er sperret' : '';
}

/** Godkjente dokumenter som utløper innen `days` dager. */
function expiringSoon(docs, days = 30, now = new Date()) {
  const today = todayString(now);
  const limit = todayString(new Date(now.getTime() + days * 86400000));
  return (docs || []).filter(d => d.status === 'approved' && d.valid_until && d.valid_until >= today && d.valid_until <= limit);
}

module.exports = { REQ_TYPES, REQ_KEYS, REQ_BY_KEY, evaluate, docStatus, lockedText, expiringSoon, normalizeRequirements, todayString };
