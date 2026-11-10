'use strict';

/**
 * Integrasjoner mot offentlige registre (spesifikasjonen § 9), med testmodus.
 *
 * Hver integrasjon kan kjøres «live» (ekte API) eller «simulated» (lokal simulering).
 * I simulering kan man velge et scenario for å teste hvordan appen oppfører seg når
 * registeret svarer normalt, ikke finner firmaet, er tregt eller er nede.
 * Alle kall logges i tabellen integration_log og kan ses på testsiden.
 */

const INTEGRATIONS = {
  brreg: {
    name: 'Brønnøysundregistrene',
    description: 'Enhetsregisteret: navn, adresse, kommune, næringskode og konkursstatus ut fra org.nr.',
    live: true,
  },
  vegvesen: {
    name: 'Statens vegvesen – løyveregisteret',
    description: 'Kontroll av løyve for godstransport. Ekte oppslag krever avtale og API-nøkkel fra Statens vegvesen.',
    live: false,
  },
  miljodir: {
    name: 'Miljødirektoratet – avfallstransportører',
    description: 'Kontroll av registrering som avfallstransportør. Ekte oppslag krever avtale med Miljødirektoratet.',
    live: false,
  },
};

const SCENARIOS = {
  auto: 'Automatisk (svaret avhenger av org.nr)',
  normal: 'Alltid funnet og gyldig',
  not_found: 'Ikke funnet',
  timeout: 'Tidsavbrudd (tregt svar)',
  down: 'Tjenesten er nede',
};

/** Testfirmaer i simulert Brønnøysund. Andre gyldige org.nr gir et generert testfirma. */
const SIM_COMPANIES = {
  '910000004': { name: 'Demo Gjenvinning AS', municipality: 'Tana', address: 'Industriveien 2', postalCode: '9845', city: 'Tana', naeringskode: '38.110', naering: 'Innsamling av ikke-farlig avfall' },
  '912345688': { name: 'Nordtrans AS', municipality: 'Tromsø', address: 'Stakkevollvegen 30', postalCode: '9010', city: 'Tromsø', naeringskode: '49.410', naering: 'Godstransport på vei' },
  '923456783': { name: 'Polar Frakt AS', municipality: 'Alta', address: 'Altaveien 100', postalCode: '9510', city: 'Alta', naeringskode: '49.410', naering: 'Godstransport på vei' },
  '934567897': { name: 'Fjordbil Transport AS', municipality: 'Bodø', address: 'Havnegata 5', postalCode: '8006', city: 'Bodø', naeringskode: '49.410', naering: 'Godstransport på vei' },
  '920000002': { name: 'Testfrakt AS', municipality: 'Kirkenes', address: 'Storgata 1', postalCode: '9900', city: 'Kirkenes', naeringskode: '49.410', naering: 'Godstransport på vei' },
  '930000000': { name: 'Konkursbo Transport AS', municipality: 'Vadsø', address: 'Strandgata 9', postalCode: '9800', city: 'Vadsø', naeringskode: '49.410', naering: 'Godstransport på vei', konkurs: true },
  '940000009': { name: 'Avvikling Logistikk AS', municipality: 'Alta', address: 'Bossekopveien 3', postalCode: '9511', city: 'Alta', naeringskode: '52.290', naering: 'Andre tjenester tilknyttet transport', underAvvikling: true },
  '950000007': { deleted: true },
  '960000005': { notFound: true },
};
const SIM_MUNICIPALITIES = ['Oslo', 'Bergen', 'Trondheim', 'Tromsø', 'Bodø', 'Alta', 'Kirkenes', 'Stavanger', 'Hammerfest', 'Narvik'];

class IntegrationError extends Error {
  /** @param {'invalid'|'not_found'|'deleted'|'timeout'|'unavailable'|'not_configured'} code */
  constructor(code, message) { super(message); this.code = code; }
}

function validOrgnr(orgnr) {
  const s = String(orgnr || '').replace(/\s/g, '');
  if (!/^\d{9}$/.test(s)) return false;
  const w = [3, 2, 7, 6, 5, 4, 3, 2];
  const sum = w.reduce((acc, x, i) => acc + x * Number(s[i]), 0);
  const r = 11 - (sum % 11);
  const check = r === 11 ? 0 : r;
  return check !== 10 && check === Number(s[8]);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

function createIntegrations({ db, fetchImpl = globalThis.fetch, liveTimeoutMs = 6000 }) {
  const getSetting = (key, fallback) => {
    const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key);
    return row ? JSON.parse(row.value) : fallback;
  };
  const setSetting = (key, value) => db.prepare(
    'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
  ).run(key, JSON.stringify(value));

  const defaultMode = process.env.INTEGRATIONS_MODE === 'live' ? 'live' : 'simulated';

  function config(id) {
    const c = getSetting(`integration.${id}`, {});
    const mode = INTEGRATIONS[id].live ? (c.mode || defaultMode) : 'simulated';
    return { mode, scenario: c.scenario || 'auto', delayMs: Number.isFinite(c.delayMs) ? c.delayMs : 400 };
  }

  function listConfig() {
    return Object.entries(INTEGRATIONS).map(([id, meta]) => ({ id, ...meta, ...config(id) }));
  }

  function updateConfig(id, { mode, scenario, delayMs }) {
    if (!INTEGRATIONS[id]) throw new IntegrationError('invalid', 'Ukjent integrasjon');
    const cur = config(id);
    const next = { ...cur };
    if (mode !== undefined) {
      if (!['simulated', 'live'].includes(mode)) throw new IntegrationError('invalid', 'Ugyldig modus');
      if (mode === 'live' && !INTEGRATIONS[id].live) throw new IntegrationError('not_configured', `${INTEGRATIONS[id].name} har ikke ekte kobling ennå`);
      next.mode = mode;
    }
    if (scenario !== undefined) {
      if (!SCENARIOS[scenario]) throw new IntegrationError('invalid', 'Ugyldig scenario');
      next.scenario = scenario;
    }
    if (delayMs !== undefined) {
      const n = Number(delayMs);
      if (!Number.isInteger(n) || n < 0 || n > 10000) throw new IntegrationError('invalid', 'Forsinkelse må være 0–10000 ms');
      next.delayMs = n;
    }
    setSetting(`integration.${id}`, next);
    return { id, ...INTEGRATIONS[id], ...next };
  }

  function log(entry) {
    db.prepare(`INSERT INTO integration_log (integration, operation, input, mode, scenario, ok, error_code, message, duration_ms, response)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(entry.integration, entry.operation, entry.input, entry.mode, entry.scenario,
      entry.ok ? 1 : 0, entry.errorCode || null, entry.message || null, entry.durationMs, entry.response ? JSON.stringify(entry.response) : null);
  }

  function recentLog(limit = 50) {
    return db.prepare('SELECT * FROM integration_log ORDER BY id DESC LIMIT ?').all(limit)
      .map(r => ({ ...r, ok: !!r.ok, response: r.response ? JSON.parse(r.response) : null }));
  }

  /** Kjører et kall med logging, og felles håndtering av scenario og forsinkelse i simulering. */
  async function run(id, operation, input, { simulate, live }) {
    const cfg = config(id);
    const started = Date.now();
    const entry = { integration: id, operation, input, mode: cfg.mode, scenario: cfg.mode === 'simulated' ? cfg.scenario : null };
    try {
      let result;
      if (cfg.mode === 'live') {
        result = await live();
      } else {
        if (cfg.scenario === 'timeout') { await sleep(Math.max(cfg.delayMs, 1500)); throw new IntegrationError('timeout', `${INTEGRATIONS[id].name} svarte ikke i tide`); }
        if (cfg.delayMs) await sleep(cfg.delayMs);
        if (cfg.scenario === 'down') throw new IntegrationError('unavailable', `${INTEGRATIONS[id].name} er utilgjengelig (simulert feil 503)`);
        result = simulate(cfg.scenario);
      }
      log({ ...entry, ok: true, durationMs: Date.now() - started, response: result });
      return { ...result, source: cfg.mode, durationMs: Date.now() - started };
    } catch (err) {
      const e = err instanceof IntegrationError ? err : new IntegrationError('unavailable', `${INTEGRATIONS[id].name}: ${err.message}`);
      log({ ...entry, ok: false, errorCode: e.code, message: e.message, durationMs: Date.now() - started });
      throw e;
    }
  }

  // ---------------------------------------------------------------- Brønnøysund

  function simulatedCompany(orgnr, scenario) {
    const fx = SIM_COMPANIES[orgnr];
    if (scenario === 'not_found' || (scenario === 'auto' && fx && fx.notFound)) throw new IntegrationError('not_found', `Fant ikke org.nr ${orgnr} i Enhetsregisteret`);
    if (scenario === 'auto' && fx && fx.deleted) throw new IntegrationError('deleted', `Org.nr ${orgnr} er slettet fra Enhetsregisteret`);
    const base = fx && fx.name ? fx : {
      name: `Testfirma ${orgnr.slice(-4)} AS`,
      municipality: SIM_MUNICIPALITIES[Number(orgnr.slice(-2)) % SIM_MUNICIPALITIES.length],
      address: 'Testveien 1', postalCode: '0001', naeringskode: '49.410', naering: 'Godstransport på vei',
    };
    const forced = scenario === 'normal';
    return {
      orgnr, name: base.name, municipality: base.municipality, address: base.address, postalCode: base.postalCode,
      city: base.city || base.municipality, naeringskode: base.naeringskode, naering: base.naering, organisasjonsform: 'AS',
      konkurs: !forced && !!base.konkurs, underAvvikling: !forced && !!base.underAvvikling,
    };
  }

  async function liveCompany(orgnr) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), liveTimeoutMs);
    let res;
    try {
      res = await fetchImpl(`https://data.brreg.no/enhetsregisteret/api/enheter/${orgnr}`, {
        headers: { Accept: 'application/json' }, signal: ctrl.signal,
      });
    } catch (err) {
      if (err.name === 'AbortError') throw new IntegrationError('timeout', 'Brønnøysundregistrene svarte ikke i tide');
      throw new IntegrationError('unavailable', 'Fikk ikke kontakt med Brønnøysundregistrene');
    } finally { clearTimeout(timer); }
    if (res.status === 404) throw new IntegrationError('not_found', `Fant ikke org.nr ${orgnr} i Enhetsregisteret`);
    if (res.status === 410) throw new IntegrationError('deleted', `Org.nr ${orgnr} er slettet fra Enhetsregisteret`);
    if (!res.ok) throw new IntegrationError('unavailable', `Brønnøysundregistrene svarte med feil ${res.status}`);
    const e = await res.json();
    if (e.slettedato) throw new IntegrationError('deleted', `Org.nr ${orgnr} er slettet fra Enhetsregisteret`);
    const a = e.forretningsadresse || e.postadresse || {};
    return {
      orgnr, name: e.navn, municipality: a.kommune ? a.kommune.charAt(0) + a.kommune.slice(1).toLowerCase() : null,
      address: (a.adresse || []).join(', '), postalCode: a.postnummer || null,
      city: a.poststed ? a.poststed.charAt(0) + a.poststed.slice(1).toLowerCase() : null,
      naeringskode: e.naeringskode1?.kode || null, naering: e.naeringskode1?.beskrivelse || null,
      organisasjonsform: e.organisasjonsform?.kode || null, konkurs: !!e.konkurs, underAvvikling: !!(e.underAvvikling || e.underTvangsavviklingEllerTvangsopplosning),
    };
  }

  async function lookupCompany(orgnrRaw) {
    const orgnr = String(orgnrRaw || '').replace(/\s/g, '');
    if (!validOrgnr(orgnr)) throw new IntegrationError('invalid', 'Org.nr må være 9 siffer med gyldig kontrollsiffer');
    return run('brreg', 'Oppslag org.nr', orgnr, { simulate: s => simulatedCompany(orgnr, s), live: () => liveCompany(orgnr) });
  }

  // ---------------------------------------------------------------- Vegvesen og Miljødirektoratet (kun simulert)

  /** Simulerte registersvar: org.nr som slutter på oddetall har løyve/registrering, partall ikke (med mindre scenario = normal). */
  function simulatedRegister(orgnr, scenario, kind) {
    if (scenario === 'not_found') throw new IntegrationError('not_found', `Fant ikke org.nr ${orgnr} i registeret`);
    const known = ['912345688', '923456783', '934567897'].includes(orgnr);
    const ok = scenario === 'normal' || known || Number(orgnr[8]) % 2 === 1;
    const validUntil = `${new Date().getFullYear() + 2}-12-31`;
    return kind === 'lov'
      ? { orgnr, valid: ok, licenceNo: ok ? `GL-${orgnr.slice(-5)}` : null, validUntil: ok ? validUntil : null, note: ok ? 'Gyldig løyve for godstransport' : 'Ingen gyldige løyver registrert' }
      : { orgnr, valid: ok, registrationNo: ok ? `AT-${orgnr.slice(-4)}` : null, validUntil: null, note: ok ? 'Registrert som avfallstransportør' : 'Ikke registrert som avfallstransportør' };
  }

  async function checkLicence(orgnr) {
    if (!validOrgnr(orgnr)) throw new IntegrationError('invalid', 'Ugyldig org.nr');
    return run('vegvesen', 'Kontroll av løyve', orgnr, { simulate: s => simulatedRegister(orgnr, s, 'lov'), live: async () => {
      throw new IntegrationError('not_configured', 'Ekte kobling mot løyveregisteret er ikke satt opp');
    } });
  }

  async function checkWasteCarrier(orgnr) {
    if (!validOrgnr(orgnr)) throw new IntegrationError('invalid', 'Ugyldig org.nr');
    return run('miljodir', 'Kontroll av avfallstransportør', orgnr, { simulate: s => simulatedRegister(orgnr, s, 'avf'), live: async () => {
      throw new IntegrationError('not_configured', 'Ekte kobling mot Miljødirektoratet er ikke satt opp');
    } });
  }

  function clearLog() { db.prepare('DELETE FROM integration_log').run(); }

  return { listConfig, updateConfig, lookupCompany, checkLicence, checkWasteCarrier, recentLog, clearLog };
}

module.exports = { createIntegrations, IntegrationError, validOrgnr, INTEGRATIONS, SCENARIOS, SIM_COMPANIES };
