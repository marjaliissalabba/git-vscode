'use strict';

/**
 * Prekvalifisering av transportører.
 *
 * En transportør er kvalifisert for en last når:
 *   1. Firmaet er godkjent (status = 'approved') av anlegget.
 *   2. Alle grunnkrav (BASE_REQUIREMENTS) er godkjent og ikke utløpt.
 *   3. Alle krav lasten spesifiserer (f.eks. ADR, avfallstransport) er godkjent og ikke utløpt.
 *
 * Logikken er ren (ingen database) slik at den kan testes isolert og brukes både
 * når laster vises, når bud legges inn og når oppdrag tildeles.
 */

const QUALIFICATION_TYPES = [
  { code: 'LOYVE', name: 'Løyve for godstransport', description: 'Gyldig løyve fra Statens vegvesen for godstransport på vei.', base: true },
  { code: 'FORSIKRING', name: 'Ansvars- og vareforsikring', description: 'Bekreftelse på gyldig ansvars- og vareforsikring (CMR).', base: true },
  { code: 'SKATT', name: 'Skatteattest', description: 'Skatteattest fra Skatteetaten, ikke eldre enn 6 måneder.', base: true },
  { code: 'HMS', name: 'HMS-egenerklæring', description: 'Signert HMS-egenerklæring og bekreftelse på at anleggets HMS-regler er lest.', base: true },
  { code: 'AVFALL', name: 'Registrert avfallstransportør', description: 'Registrert som transportør av avfall (avfallsforskriften).', base: false },
  { code: 'ADR', name: 'ADR / farlig gods', description: 'ADR-kompetansebevis for sjåfør og godkjent kjøretøy for farlig gods/farlig avfall.', base: false },
  { code: 'KROKLOFT', name: 'Krokløft / container', description: 'Kjøretøy med krokløft for containere.', base: false },
  { code: 'KRAN', name: 'Kranbil', description: 'Kranbil med sakkyndig kontroll og førerkort for kran.', base: false },
  { code: 'TANK', name: 'Tankbil / sug', description: 'Tank- eller slamsugbil for flytende last.', base: false },
];

const BASE_REQUIREMENTS = QUALIFICATION_TYPES.filter(t => t.base).map(t => t.code);
const TYPE_BY_CODE = Object.fromEntries(QUALIFICATION_TYPES.map(t => [t.code, t]));

const REASONS = {
  missing: 'mangler',
  pending: 'venter godkjenning',
  rejected: 'avvist',
  expired: 'utløpt',
};

function todayString(now = new Date()) {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Alle krav en last stiller: grunnkrav + lastens egne krav, uten duplikater. */
function requirementsFor(loadRequirements = []) {
  return [...new Set([...BASE_REQUIREMENTS, ...loadRequirements])];
}

/**
 * @param {{status:string}|null} company
 * @param {Array<{type_code:string,status:string,valid_until:string|null}>} quals
 * @param {string[]} loadRequirements  lastens ekstra krav (f.eks. ['ADR'])
 * @param {Date} now
 * @returns {{qualified:boolean, companyApproved:boolean, missing:Array<{code:string,name:string,reason:string}>}}
 */
function evaluate(company, quals, loadRequirements = [], now = new Date()) {
  const today = todayString(now);
  const byCode = new Map((quals || []).map(q => [q.type_code, q]));
  const missing = [];

  for (const code of requirementsFor(loadRequirements)) {
    const q = byCode.get(code);
    const name = TYPE_BY_CODE[code] ? TYPE_BY_CODE[code].name : code;
    let reason = null;
    if (!q) reason = REASONS.missing;
    else if (q.status === 'rejected') reason = REASONS.rejected;
    else if (q.status !== 'approved') reason = REASONS.pending;
    else if (q.valid_until && q.valid_until < today) reason = REASONS.expired;
    if (reason) missing.push({ code, name, reason });
  }

  const companyApproved = !!company && company.status === 'approved';
  return { qualified: companyApproved && missing.length === 0, companyApproved, missing };
}

/** Godkjente kvalifikasjoner som utløper innen `days` dager. */
function expiringSoon(quals, days = 30, now = new Date()) {
  const today = todayString(now);
  const limit = todayString(new Date(now.getTime() + days * 86400000));
  return (quals || []).filter(q => q.status === 'approved' && q.valid_until && q.valid_until >= today && q.valid_until <= limit);
}

module.exports = { QUALIFICATION_TYPES, BASE_REQUIREMENTS, TYPE_BY_CODE, evaluate, expiringSoon, requirementsFor, todayString };
