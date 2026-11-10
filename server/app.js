'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { transaction, insertLoad, hashPassword, verifyPassword } = require('./db');
const { evaluate, lockedText, expiringSoon, docStatus, normalizeRequirements, REQ_TYPES, REQ_BY_KEY } = require('./qualification');
const { LOAD_TYPES, LOAD_TYPE_BY_KEY, CATEGORIES, EQUIPMENT } = require('./catalog');
const { PLACES } = require('./places');
const { createIntegrations, IntegrationError, SCENARIOS, SIM_COMPANIES } = require('./integrations');

const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const SESSION_DAYS = 14;
const MAX_BODY = 8 * 1024 * 1024;
const MAX_FILE = 5 * 1024 * 1024;
const FILE_TYPES = new Set(['application/pdf', 'image/png', 'image/jpeg', 'image/webp', 'image/heic']);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

class HttpError extends Error {
  constructor(status, message, code) { super(message); this.status = status; this.code = code; }
}
const bad = msg => new HttpError(400, msg);
const INTEGRATION_HTTP = { invalid: 400, not_found: 404, deleted: 410, timeout: 504, unavailable: 503, not_configured: 501 };

const nowIso = () => new Date().toISOString();
const kr = n => `${Number(n).toLocaleString('nb-NO')} kr`;

function parseCookies(header = '') {
  const out = {};
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > MAX_BODY) { reject(new HttpError(413, 'Forespørselen er for stor')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(bad('Ugyldig JSON')); }
    });
    req.on('error', reject);
  });
}

function str(v, field, { required = false, max = 500 } = {}) {
  if (v === undefined || v === null || String(v).trim() === '') {
    if (required) throw bad(`${field} må fylles ut`);
    return null;
  }
  const s = String(v).trim();
  if (s.length > max) throw bad(`${field} er for langt (maks ${max} tegn)`);
  return s;
}

function num(v, field, { required = false, min = 0, integer = false } = {}) {
  if (v === undefined || v === null || v === '') {
    if (required) throw bad(`${field} må fylles ut`);
    return null;
  }
  const n = Number(String(v).replace(/\s/g, '').replace(',', '.'));
  if (!Number.isFinite(n) || n < min || (integer && !Number.isInteger(n))) throw bad(`${field} må være ${integer ? 'et heltall' : 'et tall'} ≥ ${min}`);
  return n;
}

function isoDate(v, field, opts = {}) {
  const s = str(v, field, { ...opts, max: 40 });
  if (s === null) return null;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) throw bad(`${field} er ikke en gyldig dato`);
  return d.toISOString();
}

function dayDate(v, field) {
  const s = str(v, field, { max: 10 });
  if (s === null) return null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) throw bad(`${field} må være på formen ÅÅÅÅ-MM-DD`);
  return s;
}

const parseJson = (v, fallback) => { try { return JSON.parse(v); } catch { return fallback; } };

// ======================================================================== app

function createApp({ db, integrations: injected, confirmHours = Number(process.env.CONFIRM_HOURS) || 2, imageBase = process.env.IMAGE_BASE_URL ?? '/imagery/' }) {
  const integrations = injected || createIntegrations({ db });
  const q = sql => db.prepare(sql);
  const one = (sql, ...a) => q(sql).get(...a);
  const all = (sql, ...a) => q(sql).all(...a);

  // ------------------------------------------------------------ domenehjelpere

  const getCompany = id => one('SELECT * FROM companies WHERE id = ?', id);
  const getLoad = id => one('SELECT * FROM loads WHERE id = ?', id);
  const companyDocs = id => all('SELECT type, status, valid_until FROM qualification_docs WHERE company_id = ?', id);
  const companyUserIds = id => all('SELECT id FROM users WHERE company_id = ?', id).map(r => r.id);
  const adminIds = () => all('SELECT id FROM users WHERE is_admin = 1').map(r => r.id);
  const jobsCompleted = id => one(`SELECT COUNT(*) AS n FROM loads WHERE awarded_to = ? AND status = 'completed'`, id).n;

  function logEvent(loadId, userId, message) {
    q('INSERT INTO events (load_id, user_id, message) VALUES (?, ?, ?)').run(loadId, userId, message);
  }
  function notify(userIds, message, { loadId = null, link = null } = {}) {
    const stmt = q('INSERT INTO notifications (user_id, load_id, link, message) VALUES (?, ?, ?, ?)');
    for (const id of new Set(userIds)) stmt.run(id, loadId, link, message);
  }
  const loadName = l => `${l.ref} ${l.type}`;

  function eligibility(companyId, load) {
    return evaluate(getCompany(companyId), companyDocs(companyId), parseJson(load.requirements, ['lov']));
  }

  /** Transportører som er kvalifisert for lasten (brukes til varsler). */
  function qualifiedCarriers(load) {
    return all('SELECT id FROM companies WHERE is_transport = 1 AND blocked = 0 AND id != ?', load.shipper_id)
      .filter(c => eligibility(c.id, load).qualified).map(c => c.id);
  }

  /** Frister og påminnelser. Kjøres ved hver forespørsel (billig, og trenger ingen egen jobbkø). */
  function tick() {
    const now = nowIso();
    // 1. Tildelinger som ikke ble bekreftet i tide
    for (const a of all(`SELECT a.*, l.ref, l.type, l.created_by FROM assignments a JOIN loads l ON l.id = a.load_id
      WHERE a.status = 'awaiting_confirmation' AND a.confirm_by <= ?`, now)) {
      transaction(db, () => {
        q(`UPDATE assignments SET status = 'expired' WHERE id = ?`).run(a.id);
        if (a.bid_id) q(`UPDATE bids SET status = 'withdrawn', updated_at = ? WHERE id = ?`).run(now, a.bid_id);
        q(`UPDATE loads SET status = 'open', awarded_to = NULL WHERE id = ?`).run(a.load_id);
        const carrier = getCompany(a.carrier_id);
        logEvent(a.load_id, null, `${carrier.name} bekreftet ikke innen fristen`);
        notify([a.created_by, ...companyUserIds(getLoad(a.load_id).shipper_id)], `${carrier.name} bekreftet ikke ${a.ref} ${a.type} i tide. Du kan tildele neste budgiver.`, { loadId: a.load_id });
        notify(companyUserIds(a.carrier_id), `Fristen for å bekrefte ${a.ref} ${a.type} gikk ut. Oppdraget er trukket tilbake.`, { loadId: a.load_id });
      });
    }
    // 2. Anbudsfrist utløpt uten tildeling
    for (const l of all(`SELECT * FROM loads WHERE status = 'open' AND price_mode = 'bid' AND deadline_notified = 0 AND bid_deadline <= ?`, now)) {
      const n = one(`SELECT COUNT(*) AS n FROM bids WHERE load_id = ? AND status = 'active'`, l.id).n;
      q('UPDATE loads SET deadline_notified = 1 WHERE id = ?').run(l.id);
      logEvent(l.id, null, `Anbudsfristen gikk ut med ${n} bud`);
      notify(companyUserIds(l.shipper_id), `Anbudsfristen for ${loadName(l)} er ute med ${n} bud. Velg hvem som skal få oppdraget.`, { loadId: l.id });
    }
    // 3. Anbudsfrist om 1 time – påminnelse til kvalifiserte som ikke har budt
    const inOneHour = new Date(Date.now() + 3600000).toISOString();
    for (const l of all(`SELECT * FROM loads WHERE status = 'open' AND price_mode = 'bid' AND reminder_sent = 0 AND bid_deadline > ? AND bid_deadline <= ?`, now, inOneHour)) {
      q('UPDATE loads SET reminder_sent = 1 WHERE id = ?').run(l.id);
      const bidders = new Set(all('SELECT carrier_id FROM bids WHERE load_id = ?', l.id).map(b => b.carrier_id));
      const targets = qualifiedCarriers(l).filter(id => !bidders.has(id));
      notify(targets.flatMap(companyUserIds), `Anbudsfristen for ${loadName(l)} går ut om under en time.`, { loadId: l.id });
    }
    // 4. Dokumenter som utløper innen 30 dager
    const docs = all(`SELECT * FROM qualification_docs WHERE status = 'approved' AND expiry_notified = 0 AND valid_until IS NOT NULL`);
    for (const d of expiringSoon(docs)) {
      q('UPDATE qualification_docs SET expiry_notified = 1 WHERE id = ?').run(d.id);
      notify(companyUserIds(d.company_id), `${REQ_BY_KEY[d.type].name} utløper ${d.valid_until.split('-').reverse().join('.')}. Last opp nytt dokument.`, { link: '#/profil' });
    }
  }

  // ------------------------------------------------------------ serialisering

  function carrierLoad(l, user) {
    const elig = eligibility(user.company_id, l);
    const myBid = one('SELECT id, amount, vehicle_id, status, updated_at FROM bids WHERE load_id = ? AND carrier_id = ?', l.id, user.company_id) || null;
    const shipper = getCompany(l.shipper_id);
    return {
      id: l.id, ref: l.ref, type: l.type, type_key: l.type_key, category: l.category, image_key: l.image_key, description: l.description,
      from_site: l.from_site, from_city: l.from_city, from_geo: l.from_lat != null ? [l.from_lat, l.from_lng] : null,
      to_site: l.to_site, to_city: l.to_city, to_geo: l.to_lat != null ? [l.to_lat, l.to_lng] : null,
      distance_km: l.distance_km, weight_t: l.weight_t, units: l.units, equipment: l.equipment,
      pickup_start: l.pickup_start, pickup_end: l.pickup_end, deliver_by: l.deliver_by,
      price_mode: l.price_mode, fixed_price: l.fixed_price, max_price: l.max_price, bid_deadline: l.bid_deadline,
      requirements: elig.items, qualified: elig.qualified, locked_text: lockedText(elig), blocked: elig.blocked,
      status: l.status,
      bid_count: l.price_mode === 'bid' ? one(`SELECT COUNT(*) AS n FROM bids WHERE load_id = ? AND status IN ('active','won','lost')`, l.id).n : null,
      my_bid: myBid,
      shipper: { name: shipper.name, verified: !!shipper.verified, municipality: shipper.municipality },
    };
  }

  function assignmentView(a) {
    const l = getLoad(a.load_id);
    const shipper = getCompany(l.shipper_id);
    const contact = one('SELECT name, phone FROM users WHERE id = ?', l.created_by);
    const vehicle = a.vehicle_id ? one('SELECT id, name, reg_no FROM vehicles WHERE id = ?', a.vehicle_id) : null;
    return {
      id: a.id, status: a.status, price: a.price, source: a.source, confirm_by: a.confirm_by, confirmed_at: a.confirmed_at,
      vehicle, load: {
        id: l.id, ref: l.ref, type: l.type, category: l.category, image_key: l.image_key, status: l.status,
        from_site: l.from_site, from_city: l.from_city, to_site: l.to_site, to_city: l.to_city, distance_km: l.distance_km,
        weight_t: l.weight_t, units: l.units, pickup_start: l.pickup_start, pickup_end: l.pickup_end, deliver_by: l.deliver_by,
        completed_at: l.completed_at,
      },
      shipper: { name: shipper.name, contact: contact?.name, phone: contact?.phone || shipper.phone },
    };
  }

  function shipperStatus(l) {
    if (l.status === 'cancelled') return 'Avlyst';
    if (l.status === 'completed') return 'Fullført';
    if (l.status === 'confirmed') return l.price_mode === 'fixed' ? 'Tatt' : 'Bekreftet';
    if (l.status === 'awarded') return 'Tildelt';
    if (l.price_mode === 'bid') {
      const n = one(`SELECT COUNT(*) AS n FROM bids WHERE load_id = ? AND status IN ('active','lost')`, l.id).n;
      return n ? `${n} bud` : 'Ledig';
    }
    return 'Ledig';
  }

  function shipperLoad(l) {
    const lowest = one(`SELECT MIN(amount) AS m FROM bids WHERE load_id = ? AND status IN ('active','won','lost')`, l.id).m;
    const awarded = l.awarded_to ? getCompany(l.awarded_to) : null;
    const a = one(`SELECT * FROM assignments WHERE load_id = ? ORDER BY id DESC LIMIT 1`, l.id);
    return {
      ...l, requirements: parseJson(l.requirements, ['lov']), status_text: shipperStatus(l), lowest_bid: lowest,
      deadline_passed: l.price_mode === 'bid' && l.bid_deadline && l.bid_deadline <= nowIso(),
      awarded: awarded ? { id: awarded.id, name: awarded.name, phone: awarded.phone } : null,
      assignment: a ? { id: a.id, status: a.status, price: a.price, confirm_by: a.confirm_by, decline_reason: a.decline_reason } : null,
    };
  }

  // ------------------------------------------------------------ autentisering

  function currentUser(req) {
    const token = parseCookies(req.headers.cookie).session;
    if (!token) return null;
    return one(`SELECT u.id, u.email, u.name, u.phone, u.company_id, u.is_admin, u.active_role, u.show_locked
      FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ? AND s.expires_at > ?`, token, nowIso()) || null;
  }

  function startSession(res, userId) {
    const token = crypto.randomBytes(32).toString('hex');
    q('INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)').run(token, userId, new Date(Date.now() + SESSION_DAYS * 86400000).toISOString());
    res.setHeader('Set-Cookie', `session=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_DAYS * 86400}${process.env.COOKIE_SECURE === 'true' ? '; Secure' : ''}`);
  }

  const requireUser = ctx => { if (!ctx.user) throw new HttpError(401, 'Du må logge inn'); return ctx.user; };
  const requireAdmin = ctx => { const u = requireUser(ctx); if (!u.is_admin) throw new HttpError(403, 'Kun for administrator'); return u; };
  function requireTransport(ctx) {
    const u = requireUser(ctx);
    const c = u.company_id && getCompany(u.company_id);
    if (!c || !c.is_transport) throw new HttpError(403, 'Firmaet ditt er ikke registrert som transportør');
    return u;
  }
  function requireShipper(ctx) {
    const u = requireUser(ctx);
    const c = u.company_id && getCompany(u.company_id);
    if (!c || !c.is_shipper) throw new HttpError(403, 'Firmaet ditt er ikke registrert som oppdragsgiver');
    return u;
  }

  // ------------------------------------------------------------ ruter

  const routes = [];
  const route = (method, pattern, handler) => {
    const keys = [];
    const re = new RegExp('^/api' + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
    routes.push({ method, re, keys, handler });
  };

  // ---- felles

  route('GET', '/meta', () => ({
    reqTypes: REQ_TYPES, loadTypes: LOAD_TYPES, categories: CATEGORIES, equipment: EQUIPMENT,
    places: Object.keys(PLACES).sort((a, b) => a.localeCompare(b, 'nb')), imageBase, confirmHours,
  }));

  route('GET', '/integrations/status', () => {
    const brreg = integrations.listConfig().find(i => i.id === 'brreg');
    return {
      brreg: { mode: brreg.mode, scenario: brreg.scenario },
      testOrgnrs: brreg.mode === 'simulated' ? Object.entries(SIM_COMPANIES).map(([orgnr, c]) => ({
        orgnr, label: c.name || (c.deleted ? 'Slettet firma' : 'Finnes ikke'),
        outcome: c.notFound ? 'Ikke funnet' : c.deleted ? 'Slettet' : c.konkurs ? 'Konkurs' : c.underAvvikling ? 'Under avvikling' : 'Aktivt',
      })) : [],
    };
  });

  route('POST', '/auth/login', ({ body, res }) => {
    const user = one('SELECT * FROM users WHERE email = ?', String(body.email || '').trim());
    if (!user || !verifyPassword(String(body.password || ''), user.password_hash)) throw new HttpError(401, 'Feil e-post eller passord');
    startSession(res, user.id);
    return { ok: true };
  });

  route('POST', '/auth/logout', ({ req, res }) => {
    const token = parseCookies(req.headers.cookie).session;
    if (token) q('DELETE FROM sessions WHERE token = ?').run(token);
    res.setHeader('Set-Cookie', 'session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0');
    return { ok: true };
  });

  function integrationError(err) {
    if (err instanceof IntegrationError) return new HttpError(INTEGRATION_HTTP[err.code] || 502, err.message, err.code);
    return err;
  }

  route('GET', '/companies/lookup', async ctx => {
    const orgnr = String(ctx.query.get('orgnr') || '').replace(/\s/g, '');
    let company;
    try { company = await integrations.lookupCompany(orgnr); } catch (err) { throw integrationError(err); }
    const existing = one('SELECT id FROM companies WHERE orgnr = ?', orgnr);
    return { ...company, alreadyRegistered: !!existing };
  });

  route('POST', '/auth/register', async ({ body, res }) => {
    const role = body.role === 'shipper' ? 'shipper' : body.role === 'transport' ? 'transport' : null;
    if (!role) throw bad('Velg om du kjører last eller har last som skal ut');
    const orgnr = String(body.orgnr || '').replace(/\s/g, '');
    const u = body.user || {};
    const user = {
      name: str(u.name, 'Navn', { required: true, max: 120 }),
      email: str(u.email, 'E-post', { required: true, max: 200 }),
      phone: str(u.phone, 'Mobil', { max: 30 }),
      password: String(u.password || ''),
    };
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(user.email)) throw bad('Ugyldig e-postadresse');
    if (user.password.length < 8) throw bad('Passordet må ha minst 8 tegn');
    if (one('SELECT 1 FROM users WHERE email = ?', user.email)) throw new HttpError(409, 'E-postadressen er allerede i bruk');
    if (one('SELECT 1 FROM companies WHERE orgnr = ?', orgnr)) throw new HttpError(409, 'Firmaet er allerede registrert. Be en kollega om tilgang.');

    // Firmaet slås opp på nytt på serveren. Klienten kan ikke selv bestemme firmanavn eller verifisering.
    let info = null, source = null;
    try {
      info = await integrations.lookupCompany(orgnr);
      source = info.source;
    } catch (err) {
      if (err instanceof IntegrationError && ['timeout', 'unavailable'].includes(err.code)) {
        info = null; // Registeret svarer ikke: firmaet registreres, men som ikke verifisert
      } else throw integrationError(err);
    }
    if (info && info.konkurs) throw new HttpError(422, `${info.name} er under konkursbehandling og kan ikke registreres`);
    if (info && info.underAvvikling) throw new HttpError(422, `${info.name} er under avvikling og kan ikke registreres`);
    const name = info ? info.name : str(body.fallbackName, 'Firmanavn', { required: true, max: 120 });

    const userId = transaction(db, () => {
      const companyId = Number(q(`INSERT INTO companies (orgnr, name, municipality, address, postal_code, city, naeringskode, naering,
        is_transport, is_shipper, verified, verified_at, verified_source, phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
        orgnr, name, info?.municipality ?? null, info?.address ?? null, info?.postalCode ?? null, info?.city ?? null,
        info?.naeringskode ?? null, info?.naering ?? null, role === 'transport' ? 1 : 0, role === 'shipper' ? 1 : 0,
        info ? 1 : 0, info ? nowIso() : null, source, user.phone).lastInsertRowid);
      const id = Number(q(`INSERT INTO users (email, password_hash, name, phone, company_id, active_role) VALUES (?, ?, ?, ?, ?, ?)`)
        .run(user.email, hashPassword(user.password), user.name, user.phone, companyId, role).lastInsertRowid);
      notify(adminIds(), `Nytt firma: ${name} (${orgnr}) som ${role === 'transport' ? 'transportør' : 'oppdragsgiver'}${info ? '' : ' – ikke verifisert, Brønnøysund svarte ikke'}`, { link: '#/admin/firma' });
      return id;
    });
    startSession(res, userId);
    return { ok: true, verified: !!info };
  });

  function meView(user) {
    const out = { user: { ...user, is_admin: !!user.is_admin, show_locked: !!user.show_locked } };
    out.unread = one('SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND read = 0', user.id).n;
    if (user.company_id) {
      const c = getCompany(user.company_id);
      const docs = all(`SELECT id, type, status, valid_until, reference, file_name, reject_reason, reviewed_at, submitted_at
        FROM qualification_docs WHERE company_id = ?`, c.id);
      out.company = { ...c, is_transport: !!c.is_transport, is_shipper: !!c.is_shipper, verified: !!c.verified, blocked: !!c.blocked, jobs_completed: jobsCompleted(c.id) };
      out.qualification = {
        docs: REQ_TYPES.map(t => {
          const d = docs.find(x => x.type === t.key);
          return { ...t, doc: d || null, status: docStatus(d) };
        }),
        approvedCount: REQ_TYPES.filter(t => docStatus(docs.find(x => x.type === t.key)) === 'approved').length,
        canBid: evaluate(c, docs, ['lov']).qualified,
        expiring: expiringSoon(docs).map(d => ({ type: d.type, name: REQ_BY_KEY[d.type].name, valid_until: d.valid_until })),
      };
      out.awaitingConfirmation = one(`SELECT COUNT(*) AS n FROM assignments WHERE carrier_id = ? AND status = 'awaiting_confirmation'`, c.id).n;
    }
    return out;
  }

  route('GET', '/me', ctx => meView(requireUser(ctx)));

  route('POST', '/me/role', ctx => {
    const user = requireUser(ctx);
    const role = ctx.body.role;
    if (role === 'admin') {
      if (!user.is_admin) throw new HttpError(403, 'Du er ikke administrator');
    } else if (['transport', 'shipper'].includes(role)) {
      if (!user.company_id) throw bad('Brukeren er ikke knyttet til et firma');
      const col = role === 'transport' ? 'is_transport' : 'is_shipper';
      q(`UPDATE companies SET ${col} = 1 WHERE id = ?`).run(user.company_id); // Et firma kan ha begge rollene
    } else throw bad('Ukjent rolle');
    q('UPDATE users SET active_role = ? WHERE id = ?').run(role, user.id);
    return meView(currentUser(ctx.req));
  });

  route('POST', '/me/settings', ctx => {
    const user = requireUser(ctx);
    if (ctx.body.show_locked !== undefined) q('UPDATE users SET show_locked = ? WHERE id = ?').run(ctx.body.show_locked ? 1 : 0, user.id);
    return meView(currentUser(ctx.req));
  });

  route('POST', '/me/company/verify', async ctx => {
    const user = requireUser(ctx);
    const c = getCompany(user.company_id);
    if (!c) throw bad('Ingen firma');
    let info;
    try { info = await integrations.lookupCompany(c.orgnr); } catch (err) { throw integrationError(err); }
    q(`UPDATE companies SET name = ?, municipality = ?, address = ?, postal_code = ?, city = ?, naeringskode = ?, naering = ?,
      verified = ?, verified_at = ?, verified_source = ? WHERE id = ?`).run(info.name, info.municipality, info.address, info.postalCode,
      info.city, info.naeringskode, info.naering, info.konkurs || info.underAvvikling ? 0 : 1, nowIso(), info.source, c.id);
    if (info.konkurs || info.underAvvikling) throw new HttpError(422, `${info.name} er ${info.konkurs ? 'under konkursbehandling' : 'under avvikling'} og kan ikke verifiseres`);
    return meView(user);
  });

  // ---- prekvalifisering

  async function registerCheck(companyId, type) {
    const c = getCompany(companyId);
    try {
      if (type === 'lov') return await integrations.checkLicence(c.orgnr);
      if (type === 'avf') return await integrations.checkWasteCarrier(c.orgnr);
      return null; // ADR-kompetansebevis er personlig og har ikke et åpent register
    } catch (err) {
      return { error: err.message, code: err.code };
    }
  }

  route('GET', '/me/qualifications', ctx => meView(requireTransport(ctx)).qualification);

  route('PUT', '/me/qualifications/:type', async ctx => {
    const user = requireTransport(ctx);
    const type = ctx.params.type;
    if (!REQ_BY_KEY[type]) throw new HttpError(404, 'Ukjent dokumenttype');
    const reference = str(ctx.body.reference, 'Dokumentnummer', { max: 200 });
    const validUntil = dayDate(ctx.body.valid_until, 'Gyldig til');
    const f = ctx.body.file;
    if (!f || !f.data) throw bad('Last opp dokumentet (PDF eller bilde)');
    const fileType = String(f.type || '');
    if (!FILE_TYPES.has(fileType)) throw bad('Dokumentet må være PDF eller bilde (PNG, JPG, WEBP, HEIC)');
    const data = Buffer.from(String(f.data), 'base64');
    if (!data.length) throw bad('Filen er tom');
    if (data.length > MAX_FILE) throw bad('Dokumentet kan maks være 5 MB');
    const fileName = str(f.name, 'Filnavn', { max: 200 }) || 'dokument';
    const check = await registerCheck(user.company_id, type);
    transaction(db, () => {
      q(`INSERT INTO qualification_docs (company_id, type, status, valid_until, reference, file_name, file_type, file, register_check, submitted_at)
         VALUES (?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(company_id, type) DO UPDATE SET status = 'pending', valid_until = excluded.valid_until, reference = excluded.reference,
           file_name = excluded.file_name, file_type = excluded.file_type, file = excluded.file, register_check = excluded.register_check,
           reject_reason = NULL, reviewed_by = NULL, reviewed_at = NULL, expiry_notified = 0, submitted_at = excluded.submitted_at`)
        .run(user.company_id, type, validUntil, reference, fileName, fileType, data, check ? JSON.stringify(check) : null, nowIso());
      notify(adminIds(), `${getCompany(user.company_id).name} har lastet opp ${REQ_BY_KEY[type].name.toLowerCase()}`, { link: '#/admin' });
    });
    return meView(user).qualification;
  });

  route('GET', '/qualifications/:id/file', ctx => {
    const user = requireUser(ctx);
    const row = one('SELECT company_id, file_name, file_type, file FROM qualification_docs WHERE id = ?', Number(ctx.params.id));
    if (!row || !row.file || (!user.is_admin && row.company_id !== user.company_id)) throw new HttpError(404, 'Fant ikke dokumentet');
    ctx.res.writeHead(200, {
      'Content-Type': row.file_type, 'X-Content-Type-Options': 'nosniff',
      'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(row.file_name)}`,
    });
    ctx.res.end(Buffer.from(row.file));
  });

  // ---- kjøretøy

  const vehicleRow = v => ({ ...v, equipment: parseJson(v.equipment, []) });

  route('GET', '/me/vehicles', ctx => {
    const user = requireTransport(ctx);
    return all('SELECT * FROM vehicles WHERE company_id = ? ORDER BY name', user.company_id).map(vehicleRow);
  });

  route('POST', '/me/vehicles', ctx => {
    const user = requireTransport(ctx);
    const name = str(ctx.body.name, 'Navn/modell', { required: true, max: 120 });
    const regNo = str(ctx.body.reg_no, 'Registreringsnummer', { required: true, max: 20 }).toUpperCase();
    const payload = num(ctx.body.payload_t, 'Nyttelast (tonn)');
    const equipment = (Array.isArray(ctx.body.equipment) ? ctx.body.equipment : []).filter(e => EQUIPMENT.includes(e));
    const id = Number(q('INSERT INTO vehicles (company_id, name, reg_no, payload_t, equipment) VALUES (?, ?, ?, ?, ?)')
      .run(user.company_id, name, regNo, payload, JSON.stringify(equipment)).lastInsertRowid);
    return vehicleRow(one('SELECT * FROM vehicles WHERE id = ?', id));
  });

  route('DELETE', '/me/vehicles/:id', ctx => {
    const user = requireTransport(ctx);
    const r = q('DELETE FROM vehicles WHERE id = ? AND company_id = ? AND id NOT IN (SELECT vehicle_id FROM bids WHERE vehicle_id IS NOT NULL AND status = \'active\')')
      .run(Number(ctx.params.id), user.company_id);
    if (!r.changes) throw new HttpError(409, 'Kjøretøyet finnes ikke, eller er brukt i et aktivt bud');
    return { ok: true };
  });

  function vehicleFor(user, vehicleId) {
    if (vehicleId === undefined || vehicleId === null || vehicleId === '') return null;
    const v = one('SELECT id FROM vehicles WHERE id = ? AND company_id = ?', Number(vehicleId), user.company_id);
    if (!v) throw bad('Velg et av firmaets kjøretøy');
    return v.id;
  }

  // ---- lastbørs (transportør)

  route('GET', '/loads', ctx => {
    const user = requireTransport(ctx);
    const filter = ctx.query.get('filter') || 'all';
    const now = nowIso();
    let rows = all(`SELECT * FROM loads WHERE status = 'open' AND shipper_id != ?
      AND (price_mode = 'fixed' OR bid_deadline > ?) ORDER BY pickup_start`, user.company_id, now)
      .map(l => carrierLoad(l, user));
    if (!user.show_locked) rows = rows.filter(l => l.qualified);
    if (filter === 'eligible') rows = rows.filter(l => l.qualified);
    if (filter === 'fixed') rows = rows.filter(l => l.price_mode === 'fixed');
    if (filter === 'bid') rows = rows.filter(l => l.price_mode === 'bid');
    if (filter === 'waste') rows = rows.filter(l => l.category.startsWith('Avfall'));
    return rows;
  });

  route('GET', '/loads/:id', ctx => {
    const user = requireTransport(ctx);
    const l = getLoad(Number(ctx.params.id));
    if (!l) throw new HttpError(404, 'Fant ikke lasten');
    const mine = one('SELECT 1 FROM bids WHERE load_id = ? AND carrier_id = ?', l.id, user.company_id)
      || one('SELECT 1 FROM assignments WHERE load_id = ? AND carrier_id = ?', l.id, user.company_id);
    if (l.status !== 'open' && !mine) throw new HttpError(404, 'Lasten er ikke lenger ledig');
    const out = carrierLoad(l, user);
    const a = one('SELECT * FROM assignments WHERE load_id = ? AND carrier_id = ? ORDER BY id DESC LIMIT 1', l.id, user.company_id);
    out.assignment = a ? assignmentView(a) : null;
    out.taken_by_other = l.status !== 'open' && l.awarded_to !== user.company_id;
    return out;
  });

  function assertCanAct(l, user) {
    if (l.shipper_id === user.company_id) throw new HttpError(403, 'Du kan ikke by på egne laster');
    const elig = eligibility(user.company_id, l);
    if (!elig.qualified) {
      throw new HttpError(403, elig.blocked ? 'Firmaet er sperret og kan ikke ta oppdrag' : `Du er ikke kvalifisert for denne lasten. ${lockedText(elig)}.`, 'not_qualified');
    }
  }

  route('POST', '/loads/:id/take', ctx => {
    const user = requireTransport(ctx);
    const vehicleId = vehicleFor(user, ctx.body.vehicleId);
    return transaction(db, () => {
      // Låses atomisk: transaksjonen leser og oppdaterer i ett, så bare én transportør kan få lasten.
      const l = getLoad(Number(ctx.params.id));
      if (!l) throw new HttpError(404, 'Fant ikke lasten');
      if (l.price_mode !== 'fixed') throw bad('Denne lasten er på anbud. Gi bud i stedet.');
      assertCanAct(l, user);
      const r = q(`UPDATE loads SET status = 'confirmed', awarded_to = ? WHERE id = ? AND status = 'open'`).run(user.company_id, l.id);
      if (!r.changes) throw new HttpError(409, 'Lasten er allerede tatt', 'taken');
      const aId = Number(q(`INSERT INTO assignments (load_id, carrier_id, vehicle_id, price, source, status, confirmed_at) VALUES (?, ?, ?, ?, 'fixed', 'confirmed', ?)`)
        .run(l.id, user.company_id, vehicleId, l.fixed_price, nowIso()).lastInsertRowid);
      const c = getCompany(user.company_id);
      logEvent(l.id, user.id, `${c.name} tok lasten til fastpris ${kr(l.fixed_price)}`);
      notify(companyUserIds(l.shipper_id), `${c.name} tok fastprislasten ${loadName(l)} (${kr(l.fixed_price)})`, { loadId: l.id });
      return assignmentView(one('SELECT * FROM assignments WHERE id = ?', aId));
    });
  });

  route('PUT', '/loads/:id/bid', ctx => {
    const user = requireTransport(ctx);
    const amount = num(ctx.body.amount, 'Beløp', { required: true, min: 1, integer: true });
    const vehicleId = vehicleFor(user, ctx.body.vehicleId);
    return transaction(db, () => {
      const l = getLoad(Number(ctx.params.id));
      if (!l) throw new HttpError(404, 'Fant ikke lasten');
      if (l.price_mode !== 'bid') throw bad('Denne lasten har fastpris. Bruk «Ta lasten».');
      if (l.status !== 'open') throw new HttpError(409, 'Lasten er ikke lenger åpen for bud');
      if (!l.bid_deadline || l.bid_deadline <= nowIso()) throw new HttpError(409, 'Anbudsfristen er ute');
      assertCanAct(l, user);
      if (l.max_price && amount > l.max_price) throw bad(`Budet kan ikke være over makspris ${kr(l.max_price)}`);
      const existing = one('SELECT * FROM bids WHERE load_id = ? AND carrier_id = ?', l.id, user.company_id);
      const c = getCompany(user.company_id);
      if (existing) {
        q(`UPDATE bids SET amount = ?, vehicle_id = ?, status = 'active', user_id = ?, updated_at = ? WHERE id = ?`).run(amount, vehicleId, user.id, nowIso(), existing.id);
        logEvent(l.id, user.id, `${c.name} endret budet`);
      } else {
        q('INSERT INTO bids (load_id, carrier_id, user_id, amount, vehicle_id) VALUES (?, ?, ?, ?, ?)').run(l.id, user.company_id, user.id, amount, vehicleId);
        logEvent(l.id, user.id, `${c.name} la inn bud`);
      }
      notify(companyUserIds(l.shipper_id), `${existing ? 'Endret' : 'Nytt'} bud på ${loadName(l)} fra ${c.name}`, { loadId: l.id });
      return carrierLoad(getLoad(l.id), user);
    });
  });

  route('DELETE', '/loads/:id/bid', ctx => {
    const user = requireTransport(ctx);
    const l = getLoad(Number(ctx.params.id));
    if (!l) throw new HttpError(404, 'Fant ikke lasten');
    if (l.status !== 'open' || !l.bid_deadline || l.bid_deadline <= nowIso()) throw new HttpError(409, 'Budet kan bare trekkes før anbudsfristen');
    const r = q(`UPDATE bids SET status = 'withdrawn', updated_at = ? WHERE load_id = ? AND carrier_id = ? AND status = 'active'`).run(nowIso(), l.id, user.company_id);
    if (!r.changes) throw new HttpError(404, 'Du har ikke et aktivt bud på denne lasten');
    logEvent(l.id, user.id, `${getCompany(user.company_id).name} trakk budet`);
    return carrierLoad(l, user);
  });

  // ---- mine oppdrag (transportør)

  route('GET', '/me/assignments', ctx => {
    const user = requireTransport(ctx);
    const tab = ctx.query.get('tab') || 'active';
    if (tab === 'active') {
      return all(`SELECT a.* FROM assignments a JOIN loads l ON l.id = a.load_id WHERE a.carrier_id = ?
        AND (a.status = 'awaiting_confirmation' OR (a.status = 'confirmed' AND l.status = 'confirmed'))
        ORDER BY CASE a.status WHEN 'awaiting_confirmation' THEN 0 ELSE 1 END, l.pickup_start`, user.company_id).map(assignmentView);
    }
    if (tab === 'done') {
      return all(`SELECT a.* FROM assignments a JOIN loads l ON l.id = a.load_id WHERE a.carrier_id = ? AND a.status = 'confirmed'
        AND l.status = 'completed' ORDER BY l.completed_at DESC`, user.company_id).map(assignmentView);
    }
    if (tab === 'bids') {
      return all(`SELECT b.*, l.status AS load_status, l.awarded_to FROM bids b JOIN loads l ON l.id = b.load_id
        WHERE b.carrier_id = ? AND b.status IN ('active','lost') ORDER BY b.updated_at DESC`, user.company_id).map(b => ({
        id: b.id, amount: b.amount, status: b.status === 'active' && b.load_status === 'cancelled' ? 'lost' : b.status,
        updated_at: b.updated_at, load: carrierLoad(getLoad(b.load_id), user),
      }));
    }
    throw bad('Ukjent fane');
  });

  function myAssignment(ctx) {
    const user = requireTransport(ctx);
    const a = one('SELECT * FROM assignments WHERE id = ? AND carrier_id = ?', Number(ctx.params.id), user.company_id);
    if (!a) throw new HttpError(404, 'Fant ikke oppdraget');
    return { user, a, l: getLoad(a.load_id), c: getCompany(user.company_id) };
  }

  route('POST', '/assignments/:id/confirm', ctx => {
    const { user, a, l, c } = myAssignment(ctx);
    if (a.status !== 'awaiting_confirmation') throw new HttpError(409, a.status === 'expired' ? 'Fristen for å bekrefte er ute' : 'Oppdraget venter ikke på bekreftelse');
    transaction(db, () => {
      q(`UPDATE assignments SET status = 'confirmed', confirmed_at = ? WHERE id = ?`).run(nowIso(), a.id);
      q(`UPDATE loads SET status = 'confirmed' WHERE id = ?`).run(l.id);
      logEvent(l.id, user.id, `${c.name} bekreftet oppdraget`);
      notify(companyUserIds(l.shipper_id), `${c.name} bekreftet ${loadName(l)}`, { loadId: l.id });
    });
    return assignmentView(one('SELECT * FROM assignments WHERE id = ?', a.id));
  });

  route('POST', '/assignments/:id/decline', ctx => {
    const { user, a, l, c } = myAssignment(ctx);
    if (a.status !== 'awaiting_confirmation') throw new HttpError(409, 'Oppdraget kan ikke avslås nå');
    const reason = str(ctx.body.reason, 'Årsak', { max: 500 });
    transaction(db, () => {
      q(`UPDATE assignments SET status = 'declined', decline_reason = ? WHERE id = ?`).run(reason, a.id);
      if (a.bid_id) q(`UPDATE bids SET status = 'withdrawn', updated_at = ? WHERE id = ?`).run(nowIso(), a.bid_id);
      q(`UPDATE loads SET status = 'open', awarded_to = NULL WHERE id = ?`).run(l.id);
      q('UPDATE companies SET declines = declines + 1 WHERE id = ?').run(c.id); // Avslag etter tildeling registreres på firmaprofilen
      logEvent(l.id, user.id, `${c.name} avslo oppdraget${reason ? ': ' + reason : ''}`);
      notify(companyUserIds(l.shipper_id), `${c.name} avslo ${loadName(l)}. Du kan tildele neste budgiver.`, { loadId: l.id });
    });
    return { ok: true };
  });

  route('POST', '/assignments/:id/complete', ctx => {
    const { user, a, l, c } = myAssignment(ctx);
    if (a.status !== 'confirmed' || l.status !== 'confirmed') throw new HttpError(409, 'Bare bekreftede oppdrag kan meldes levert');
    transaction(db, () => {
      q(`UPDATE loads SET status = 'completed', completed_at = ? WHERE id = ?`).run(nowIso(), l.id);
      logEvent(l.id, user.id, `${c.name} meldte lasten levert`);
      notify(companyUserIds(l.shipper_id), `${loadName(l)} er levert av ${c.name}`, { loadId: l.id });
    });
    return assignmentView(a);
  });

  // ---- oppdragsgiver

  route('POST', '/loads', ctx => {
    const user = requireShipper(ctx);
    const company = getCompany(user.company_id);
    if (!company.verified) throw new HttpError(403, 'Firmaet må være verifisert i Brønnøysundregistrene før du kan legge ut last');
    if (company.blocked) throw new HttpError(403, 'Firmaet er sperret');
    const b = ctx.body;
    const t = LOAD_TYPE_BY_KEY[b.type_key];
    if (!t) throw bad('Velg lasttype');
    const priceMode = b.price_mode === 'fixed' ? 'fixed' : b.price_mode === 'bid' ? 'bid' : null;
    if (!priceMode) throw bad('Velg prismodell');
    const data = {
      shipper_id: company.id, created_by: user.id, type_key: t.key,
      type: str(b.type, 'Beskrivelse av lasten', { max: 120 }) || t.name,
      description: str(b.description, 'Merknad', { max: 2000 }),
      from_site: str(b.from_site, 'Hentested', { max: 200 }) || str(b.from_city, 'Hentes i', { required: true, max: 120 }),
      from_city: str(b.from_city, 'Hentes i', { required: true, max: 120 }),
      to_site: str(b.to_site, 'Mottak', { max: 200 }) || str(b.to_city, 'Leveres til', { required: true, max: 120 }),
      to_city: str(b.to_city, 'Leveres til', { required: true, max: 120 }),
      weight_t: num(b.weight_t, 'Vekt (tonn)', { required: true, min: 0.1 }),
      units: str(b.units, 'Enheter', { max: 80 }),
      equipment: str(b.equipment, 'Utstyr', { max: 120 }),
      pickup_start: isoDate(b.pickup_start, 'Hentes fra') || nowIso(),
      pickup_end: isoDate(b.pickup_end, 'Hentes innen', { required: true }),
      deliver_by: isoDate(b.deliver_by, 'Levert innen'),
      price_mode: priceMode,
      fixed_price: priceMode === 'fixed' ? num(b.fixed_price, 'Fastpris', { required: true, min: 1, integer: true }) : null,
      max_price: priceMode === 'bid' ? num(b.max_price, 'Makspris', { min: 1, integer: true }) : null,
      bid_deadline: priceMode === 'bid' ? isoDate(b.bid_deadline, 'Anbudsfrist', { required: true }) : null,
      requirements: normalizeRequirements(Array.isArray(b.requirements) ? b.requirements : t.requirements),
    };
    if (data.pickup_end < data.pickup_start) throw bad('«Hentes innen» må være etter «Hentes fra»');
    if (data.pickup_end <= nowIso()) throw bad('«Hentes innen» må være fram i tid');
    if (data.bid_deadline && data.bid_deadline <= nowIso()) throw bad('Anbudsfristen må være fram i tid');
    const id = transaction(db, () => {
      const loadId = insertLoad(db, data);
      const l = getLoad(loadId);
      logEvent(loadId, user.id, priceMode === 'fixed' ? `Lagt ut til fastpris ${kr(l.fixed_price)}` : 'Lagt ut på anbud');
      const msg = priceMode === 'fixed'
        ? `Ny last til fastpris: ${l.type}, ${l.from_city} – ${l.to_city}, ${kr(l.fixed_price)}`
        : `Ny last på anbud: ${l.type}, ${l.from_city} – ${l.to_city}`;
      notify(qualifiedCarriers(l).flatMap(companyUserIds), msg, { loadId });
      return loadId;
    });
    return shipperLoad(getLoad(id));
  });

  function ownLoad(ctx) {
    const user = requireShipper(ctx);
    const l = getLoad(Number(ctx.params.id));
    if (!l || l.shipper_id !== user.company_id) throw new HttpError(404, 'Fant ikke lasten');
    return { user, l };
  }

  route('GET', '/shipper/loads', ctx => {
    const user = requireShipper(ctx);
    return all(`SELECT * FROM loads WHERE shipper_id = ? ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'awarded' THEN 1 WHEN 'confirmed' THEN 2 ELSE 3 END, created_at DESC`, user.company_id)
      .map(shipperLoad);
  });

  route('GET', '/shipper/loads/:id', ctx => {
    const { l } = ownLoad(ctx);
    const out = shipperLoad(l);
    out.events = all(`SELECT e.message, e.created_at, u.name AS user_name FROM events e LEFT JOIN users u ON u.id = e.user_id WHERE e.load_id = ? ORDER BY e.id DESC`, l.id);
    return out;
  });

  route('GET', '/shipper/loads/:id/bids', ctx => {
    const { l } = ownLoad(ctx);
    const bids = all(`SELECT b.*, c.name AS company_name, c.municipality, c.declines, c.phone
      FROM bids b JOIN companies c ON c.id = b.carrier_id WHERE b.load_id = ? AND b.status != 'withdrawn' ORDER BY b.amount`, l.id);
    const lowest = bids.length ? Math.min(...bids.map(b => b.amount)) : null;
    return bids.map(b => {
      const elig = eligibility(b.carrier_id, l);
      const docs = companyDocs(b.carrier_id);
      return {
        id: b.id, amount: b.amount, status: b.status, updated_at: b.updated_at,
        company: { id: b.carrier_id, name: b.company_name, municipality: b.municipality, phone: b.phone, declines: b.declines, jobs_completed: jobsCompleted(b.carrier_id) },
        approved: REQ_TYPES.filter(t => docStatus(docs.find(d => d.type === t.key)) === 'approved').map(t => t.key),
        vehicle: b.vehicle_id ? one('SELECT name, reg_no, payload_t FROM vehicles WHERE id = ?', b.vehicle_id) : null,
        lowest: b.amount === lowest, diff: b.amount - lowest, qualified: elig.qualified, locked_text: lockedText(elig),
      };
    });
  });

  route('POST', '/shipper/loads/:id/award', ctx => {
    const { user, l } = ownLoad(ctx);
    const bidId = num(ctx.body.bidId, 'Bud', { required: true, integer: true });
    return transaction(db, () => {
      const load = getLoad(l.id);
      if (load.price_mode !== 'bid') throw bad('Fastprislaster tildeles ikke');
      if (load.status !== 'open') throw new HttpError(409, load.status === 'awarded' ? 'Lasten er allerede tildelt og venter på bekreftelse' : 'Lasten kan ikke tildeles nå');
      const bid = one('SELECT * FROM bids WHERE id = ? AND load_id = ?', bidId, load.id);
      if (!bid || !['active', 'lost'].includes(bid.status)) throw bad('Budet finnes ikke eller er trukket');
      const elig = eligibility(bid.carrier_id, load);
      if (!elig.qualified) throw new HttpError(409, `Transportøren er ikke lenger kvalifisert. ${lockedText(elig)}.`);
      const c = getCompany(bid.carrier_id);
      const confirmBy = new Date(Date.now() + confirmHours * 3600000).toISOString();
      const losers = all(`SELECT DISTINCT carrier_id FROM bids WHERE load_id = ? AND status = 'active' AND id != ?`, load.id, bid.id).map(r => r.carrier_id);
      q(`UPDATE bids SET status = 'lost', updated_at = ? WHERE load_id = ? AND status = 'active' AND id != ?`).run(nowIso(), load.id, bid.id);
      q(`UPDATE bids SET status = 'won', updated_at = ? WHERE id = ?`).run(nowIso(), bid.id);
      q(`UPDATE loads SET status = 'awarded', awarded_to = ? WHERE id = ?`).run(c.id, load.id);
      q(`INSERT INTO assignments (load_id, carrier_id, bid_id, vehicle_id, price, source, status, confirm_by) VALUES (?, ?, ?, ?, ?, 'bid', 'awaiting_confirmation', ?)`)
        .run(load.id, c.id, bid.id, bid.vehicle_id, bid.amount, confirmBy);
      logEvent(load.id, user.id, `Tildelt ${c.name} for ${kr(bid.amount)}`);
      notify(companyUserIds(c.id), `Budet ditt vant: ${loadName(load)} for ${kr(bid.amount)}. Bekreft innen ${confirmHours} timer.`, { loadId: load.id });
      notify(losers.flatMap(companyUserIds), `Budet ditt på ${loadName(load)} ble ikke valgt`, { loadId: load.id });
      return shipperLoad(getLoad(load.id));
    });
  });

  route('POST', '/shipper/loads/:id/cancel', ctx => {
    const { user, l } = ownLoad(ctx);
    if (['completed', 'cancelled'].includes(l.status)) throw new HttpError(409, 'Lasten kan ikke avlyses');
    const reason = str(ctx.body.reason, 'Årsak', { max: 500 });
    transaction(db, () => {
      const affected = all(`SELECT DISTINCT carrier_id FROM bids WHERE load_id = ? AND status IN ('active','won')`, l.id).map(r => r.carrier_id);
      if (l.awarded_to) affected.push(l.awarded_to);
      q(`UPDATE loads SET status = 'cancelled' WHERE id = ?`).run(l.id);
      q(`UPDATE bids SET status = 'lost', updated_at = ? WHERE load_id = ? AND status IN ('active','won')`).run(nowIso(), l.id);
      q(`UPDATE assignments SET status = 'declined', decline_reason = 'Avlyst av oppdragsgiver' WHERE load_id = ? AND status IN ('awaiting_confirmation','confirmed')`).run(l.id);
      logEvent(l.id, user.id, `Avlyst${reason ? ': ' + reason : ''}`);
      notify([...new Set(affected)].flatMap(companyUserIds), `${loadName(l)} er avlyst${reason ? ': ' + reason : ''}`, { loadId: l.id });
    });
    return { ok: true };
  });

  // ---- admin: dokumentkontroll, firma og integrasjoner

  route('GET', '/admin/docs', ctx => {
    requireAdmin(ctx);
    const status = ctx.query.get('status') || 'pending';
    return all(`SELECT d.id, d.type, d.status, d.valid_until, d.reference, d.file_name, d.reject_reason, d.register_check, d.submitted_at, d.reviewed_at,
      c.id AS company_id, c.name AS company_name, c.orgnr FROM qualification_docs d JOIN companies c ON c.id = d.company_id
      WHERE d.status = ? ORDER BY d.submitted_at`, status).map(d => ({ ...d, name: REQ_BY_KEY[d.type].name, register_check: parseJson(d.register_check, null) }));
  });

  route('POST', '/admin/docs/:id/review', ctx => {
    const admin = requireAdmin(ctx);
    const d = one('SELECT * FROM qualification_docs WHERE id = ?', Number(ctx.params.id));
    if (!d) throw new HttpError(404, 'Fant ikke dokumentet');
    const status = ctx.body.status;
    if (!['approved', 'rejected'].includes(status)) throw bad('Status må være approved eller rejected');
    const reason = str(ctx.body.reason, 'Begrunnelse', { max: 500 });
    if (status === 'rejected' && !reason) throw bad('Skriv en begrunnelse når du avviser');
    const validUntil = ctx.body.valid_until !== undefined ? dayDate(ctx.body.valid_until, 'Gyldig til') : d.valid_until;
    transaction(db, () => {
      q(`UPDATE qualification_docs SET status = ?, reject_reason = ?, valid_until = ?, reviewed_by = ?, reviewed_at = ?, expiry_notified = 0 WHERE id = ?`)
        .run(status, status === 'rejected' ? reason : null, validUntil, admin.id, nowIso(), d.id);
      const name = REQ_BY_KEY[d.type].name;
      notify(companyUserIds(d.company_id), status === 'approved' ? `${name} er godkjent` : `${name} ble avvist: ${reason}`, { link: '#/profil' });
    });
    return { ok: true };
  });

  route('POST', '/admin/docs/:id/check', async ctx => {
    requireAdmin(ctx);
    const d = one('SELECT * FROM qualification_docs WHERE id = ?', Number(ctx.params.id));
    if (!d) throw new HttpError(404, 'Fant ikke dokumentet');
    const check = await registerCheck(d.company_id, d.type);
    q('UPDATE qualification_docs SET register_check = ? WHERE id = ?').run(check ? JSON.stringify(check) : null, d.id);
    return check;
  });

  route('GET', '/admin/companies', ctx => {
    requireAdmin(ctx);
    return all('SELECT * FROM companies ORDER BY name').map(c => {
      const docs = companyDocs(c.id);
      return { ...c, jobs_completed: jobsCompleted(c.id), docs: REQ_TYPES.map(t => ({ key: t.key, status: docStatus(docs.find(d => d.type === t.key)) })) };
    });
  });

  route('POST', '/admin/companies/:id/block', ctx => {
    requireAdmin(ctx);
    const c = getCompany(Number(ctx.params.id));
    if (!c) throw new HttpError(404, 'Fant ikke firmaet');
    const blocked = !!ctx.body.blocked;
    const reason = str(ctx.body.reason, 'Begrunnelse', { max: 500 });
    if (blocked && !reason) throw bad('Skriv en begrunnelse for sperringen');
    q('UPDATE companies SET blocked = ?, blocked_reason = ? WHERE id = ?').run(blocked ? 1 : 0, blocked ? reason : null, c.id);
    notify(companyUserIds(c.id), blocked ? `Firmaet er sperret: ${reason}` : 'Sperringen av firmaet er opphevet');
    return { ok: true };
  });

  route('GET', '/admin/integrations', ctx => {
    requireAdmin(ctx);
    return { integrations: integrations.listConfig(), scenarios: SCENARIOS, log: integrations.recentLog(50),
      testOrgnrs: Object.entries(SIM_COMPANIES).map(([orgnr, c]) => ({ orgnr, name: c.name || null, notFound: !!c.notFound, deleted: !!c.deleted, konkurs: !!c.konkurs, underAvvikling: !!c.underAvvikling })) };
  });

  route('PUT', '/admin/integrations/:id', ctx => {
    requireAdmin(ctx);
    try { return integrations.updateConfig(ctx.params.id, ctx.body); } catch (err) { throw integrationError(err); }
  });

  route('POST', '/admin/integrations/:id/test', async ctx => {
    requireAdmin(ctx);
    const orgnr = String(ctx.body.orgnr || '').replace(/\s/g, '');
    const fn = { brreg: integrations.lookupCompany, vegvesen: integrations.checkLicence, miljodir: integrations.checkWasteCarrier }[ctx.params.id];
    if (!fn) throw new HttpError(404, 'Ukjent integrasjon');
    const started = Date.now();
    try {
      return { ok: true, result: await fn(orgnr) };
    } catch (err) {
      const e = integrationError(err);
      return { ok: false, error: e.message, code: e.code || err.code, status: e.status, durationMs: Date.now() - started };
    }
  });

  route('DELETE', '/admin/integrations/log', ctx => { requireAdmin(ctx); integrations.clearLog(); return { ok: true }; });

  // ---- varsler

  route('GET', '/notifications', ctx => {
    const user = requireUser(ctx);
    return all('SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 100', user.id);
  });

  route('POST', '/notifications/read', ctx => {
    const user = requireUser(ctx);
    q('UPDATE notifications SET read = 1 WHERE user_id = ?').run(user.id);
    return { ok: true };
  });

  // ------------------------------------------------------------ statiske filer

  function serveStatic(req, res, pathname) {
    let file = path.normalize(path.join(PUBLIC_DIR, decodeURIComponent(pathname === '/' ? 'index.html' : pathname)));
    if (!file.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end(); }
    const exists = fs.existsSync(file) && fs.statSync(file).isFile();
    if (!exists) {
      // Bilder og andre filer med filendelse gir 404 (slik at fotoflisen kan falle tilbake), sider går til appen
      if (path.extname(file)) { res.writeHead(404); return res.end(); }
      file = path.join(PUBLIC_DIR, 'index.html');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  }

  // ------------------------------------------------------------ handler

  return async function handler(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/')) {
      if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
      return serveStatic(req, res, url.pathname);
    }
    try {
      let match = null;
      for (const r of routes) {
        if (r.method !== req.method) continue;
        const m = r.re.exec(url.pathname);
        if (m) { match = { r, m }; break; }
      }
      if (!match) throw new HttpError(404, 'Ukjent API-endepunkt');
      // Skrivende kall må være JSON: et vanlig HTML-skjema fra et annet nettsted kan ikke sende det (enkel CSRF-beskyttelse).
      if (req.method !== 'GET' && !String(req.headers['content-type'] || '').startsWith('application/json')) {
        throw new HttpError(415, 'Content-Type må være application/json');
      }
      const params = Object.fromEntries(match.r.keys.map((k, i) => [k, decodeURIComponent(match.m[i + 1])]));
      const body = req.method === 'GET' ? {} : await readBody(req);
      tick();
      const ctx = { req, res, params, body, query: url.searchParams, user: currentUser(req) };
      const result = await match.r.handler(ctx);
      if (res.headersSent) return;
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(result ?? { ok: true }));
    } catch (err) {
      const status = err.status || 500;
      if (status === 500) console.error(err);
      if (!res.headersSent) res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: status === 500 ? 'Noe gikk galt på serveren' : err.message, code: err.code }));
    }
  };
}

module.exports = { createApp };
