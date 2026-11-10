'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { transaction, hashPassword, verifyPassword } = require('./db');
const { evaluate, expiringSoon, TYPE_BY_CODE } = require('./qualification');

const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const SESSION_DAYS = 14;
const MAX_BODY = 8 * 1024 * 1024; // 8 MB (dokumenter sendes base64)
const MAX_DOC = 5 * 1024 * 1024;
const DOC_TYPES = new Set(['application/pdf', 'image/png', 'image/jpeg', 'image/webp']);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
};

const STATUS_TEXT = {
  draft: 'Utkast', open: 'Ute på anbud', evaluation: 'Frist ute – venter tildeling', awarded: 'Tildelt',
  picked_up: 'Hentet', delivered: 'Levert', cancelled: 'Avlyst',
};

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const bad = msg => new HttpError(400, msg);

// ---------------------------------------------------------------- hjelpere

function nowIso() { return new Date().toISOString(); }

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
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(bad('Ugyldig JSON')); }
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

function int(v, field, { required = false, min = 0 } = {}) {
  if (v === undefined || v === null || v === '') {
    if (required) throw bad(`${field} må fylles ut`);
    return null;
  }
  const n = Number(v);
  if (!Number.isInteger(n) || n < min) throw bad(`${field} må være et heltall ≥ ${min}`);
  return n;
}

function isoDate(v, field, { required = false } = {}) {
  const s = str(v, field, { required, max: 40 });
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

function parseQuals(q) {
  try { return JSON.parse(q || '[]'); } catch { return []; }
}

// ---------------------------------------------------------------- app

function createApp({ db }) {
  const q = sql => db.prepare(sql);

  // ---- domene-hjelpere

  function logEvent(loadId, userId, message) {
    q('INSERT INTO events (load_id, user_id, message) VALUES (?, ?, ?)').run(loadId, userId, message);
  }

  function notify(userIds, loadId, message) {
    const stmt = q('INSERT INTO notifications (user_id, load_id, message) VALUES (?, ?, ?)');
    for (const id of new Set(userIds)) stmt.run(id, loadId, message);
  }

  const adminIds = () => q(`SELECT id FROM users WHERE role = 'admin'`).all().map(r => r.id);
  const companyUserIds = companyId => q('SELECT id FROM users WHERE company_id = ?').all(companyId).map(r => r.id);
  const companyQuals = companyId => q('SELECT type_code, status, valid_until FROM company_qualifications WHERE company_id = ?').all(companyId);
  const getCompany = id => q('SELECT * FROM companies WHERE id = ?').get(id);

  function eligibility(companyId, load) {
    return evaluate(getCompany(companyId), companyQuals(companyId), parseQuals(load.required_quals));
  }

  function nextRef() {
    const year = new Date().getFullYear();
    const row = q(`SELECT ref FROM loads WHERE ref LIKE ? ORDER BY ref DESC LIMIT 1`).get(`L-${year}-%`);
    const n = row ? Number(row.ref.split('-')[2]) + 1 : 1;
    return `L-${year}-${String(n).padStart(4, '0')}`;
  }

  /** Lukker anbud der fristen har gått ut. Kalles ved hver forespørsel (billig). */
  function refreshStatuses() {
    const expired = q(`SELECT id, ref, title FROM loads WHERE status = 'open' AND mode = 'tender' AND bid_deadline IS NOT NULL AND bid_deadline <= ?`).all(nowIso());
    for (const l of expired) {
      q(`UPDATE loads SET status = 'evaluation' WHERE id = ?`).run(l.id);
      const n = q(`SELECT COUNT(*) AS n FROM bids WHERE load_id = ? AND status = 'active'`).get(l.id).n;
      logEvent(l.id, null, `Anbudsfristen gikk ut med ${n} bud`);
      notify(adminIds(), l.id, `Anbudsfrist ute for ${l.ref} ${l.title}: ${n} bud klare for tildeling`);
    }
  }

  function notifyQualifiedCarriers(load, message) {
    const companies = q(`SELECT id FROM companies WHERE status = 'approved'`).all();
    const users = [];
    for (const c of companies) if (eligibility(c.id, load).qualified) users.push(...companyUserIds(c.id));
    notify(users, load.id, message);
  }

  function serializeLoad(load, user) {
    const statusText = load.status === 'open' && load.mode === 'direct' ? 'Ledig – fastpris' : STATUS_TEXT[load.status];
    const out = { ...load, required_quals: parseQuals(load.required_quals), status_text: statusText };
    out.bid_count = q(`SELECT COUNT(*) AS n FROM bids WHERE load_id = ? AND status IN ('active','won','lost')`).get(load.id).n;
    if (load.awarded_bid_id) {
      out.awarded = q(`SELECT b.id, b.price_nok, b.pickup_eta, c.id AS company_id, c.name AS company_name, c.phone AS company_phone
        FROM bids b JOIN companies c ON c.id = b.company_id WHERE b.id = ?`).get(load.awarded_bid_id) || null;
    }
    if (user.role === 'carrier') {
      out.eligibility = eligibility(user.company_id, load);
      out.my_bid = q('SELECT id, price_nok, pickup_eta, comment, status, updated_at FROM bids WHERE load_id = ? AND company_id = ?').get(load.id, user.company_id) || null;
      out.is_mine = !!(out.awarded && out.awarded.company_id === user.company_id);
      if (!out.is_mine && out.awarded) out.awarded = { company_name: 'Annen transportør' };
      if (load.mode === 'tender') out.bid_count = undefined; // lukket anbud: antall bud vises ikke
      delete out.created_by;
    }
    return out;
  }

  function loadForUser(id, user) {
    const load = q('SELECT * FROM loads WHERE id = ?').get(id);
    if (!load) throw new HttpError(404, 'Fant ikke lasten');
    if (user.role === 'carrier') {
      const visible = ['open', 'evaluation'].includes(load.status)
        || q('SELECT 1 FROM bids WHERE load_id = ? AND company_id = ?').get(id, user.company_id);
      if (!visible) throw new HttpError(404, 'Fant ikke lasten');
    }
    return load;
  }

  // ---- auth

  function currentUser(req) {
    const token = parseCookies(req.headers.cookie).session;
    if (!token) return null;
    return q(`SELECT u.id, u.email, u.name, u.phone, u.role, u.company_id FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token = ? AND s.expires_at > ?`).get(token, nowIso()) || null;
  }

  function startSession(res, userId) {
    const token = crypto.randomBytes(32).toString('hex');
    const expires = new Date(Date.now() + SESSION_DAYS * 86400000);
    q('INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)').run(token, userId, expires.toISOString());
    res.setHeader('Set-Cookie', `session=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_DAYS * 86400}`);
  }

  const requireUser = ctx => { if (!ctx.user) throw new HttpError(401, 'Du må logge inn'); return ctx.user; };
  const requireAdmin = ctx => { const u = requireUser(ctx); if (u.role !== 'admin') throw new HttpError(403, 'Kun for anlegget'); return u; };
  const requireCarrier = ctx => { const u = requireUser(ctx); if (u.role !== 'carrier') throw new HttpError(403, 'Kun for transportører'); return u; };

  // ---- ruter

  const routes = [];
  const route = (method, pattern, handler) => {
    const keys = [];
    const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
    routes.push({ method, re, keys, handler });
  };

  route('POST', '/api/auth/login', ({ body, res }) => {
    const user = q('SELECT * FROM users WHERE email = ?').get(String(body.email || '').trim());
    if (!user || !verifyPassword(String(body.password || ''), user.password_hash)) throw new HttpError(401, 'Feil e-post eller passord');
    startSession(res, user.id);
    return { ok: true };
  });

  route('POST', '/api/auth/logout', ({ req, res }) => {
    const token = parseCookies(req.headers.cookie).session;
    if (token) q('DELETE FROM sessions WHERE token = ?').run(token);
    res.setHeader('Set-Cookie', 'session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0');
    return { ok: true };
  });

  route('POST', '/api/auth/register', ({ body, res }) => {
    const c = body.company || {}, u = body.user || {};
    const company = {
      name: str(c.name, 'Firmanavn', { required: true, max: 120 }),
      org_nr: str(c.org_nr, 'Organisasjonsnummer', { required: true, max: 20 }).replace(/\s/g, ''),
      phone: str(c.phone, 'Telefon', { max: 30 }),
      address: str(c.address, 'Adresse', { max: 200 }),
    };
    if (!/^\d{9}$/.test(company.org_nr)) throw bad('Organisasjonsnummer må være 9 siffer');
    const user = {
      name: str(u.name, 'Navn', { required: true, max: 120 }),
      email: str(u.email, 'E-post', { required: true, max: 200 }),
      phone: str(u.phone, 'Mobil', { max: 30 }),
      password: String(u.password || ''),
    };
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(user.email)) throw bad('Ugyldig e-postadresse');
    if (user.password.length < 8) throw bad('Passordet må ha minst 8 tegn');
    if (q('SELECT 1 FROM companies WHERE org_nr = ?').get(company.org_nr)) throw new HttpError(409, 'Firmaet er allerede registrert – kontakt anlegget for tilgang');
    if (q('SELECT 1 FROM users WHERE email = ?').get(user.email)) throw new HttpError(409, 'E-postadressen er allerede i bruk');

    const userId = transaction(db, () => {
      const companyId = Number(q('INSERT INTO companies (name, org_nr, phone, address) VALUES (?, ?, ?, ?)')
        .run(company.name, company.org_nr, company.phone, company.address).lastInsertRowid);
      const id = Number(q(`INSERT INTO users (email, password_hash, name, phone, role, company_id) VALUES (?, ?, ?, ?, 'carrier', ?)`)
        .run(user.email, hashPassword(user.password), user.name, user.phone, companyId).lastInsertRowid);
      notify(adminIds(), null, `Ny transportør registrert: ${company.name} (${company.org_nr}) venter prekvalifisering`);
      return id;
    });
    startSession(res, userId);
    return { ok: true };
  });

  route('GET', '/api/me', ctx => {
    const user = requireUser(ctx);
    const unread = q('SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND read = 0').get(user.id).n;
    const out = { user, unread };
    if (user.role === 'carrier') {
      const company = getCompany(user.company_id);
      const quals = companyQuals(user.company_id);
      out.company = company;
      out.base_status = evaluate(company, quals, []);
      out.expiring = expiringSoon(quals).map(x => ({ ...x, name: TYPE_BY_CODE[x.type_code]?.name }));
    }
    return out;
  });

  route('GET', '/api/qualification-types', () => q('SELECT * FROM qualification_types ORDER BY base DESC, name').all());

  // ---- laster

  route('GET', '/api/loads', ctx => {
    const user = requireUser(ctx);
    const scope = ctx.query.get('scope') || '';
    let rows;
    if (user.role === 'admin') {
      const status = ctx.query.get('status');
      rows = status
        ? q('SELECT * FROM loads WHERE status = ? ORDER BY pickup_from').all(status)
        : q(`SELECT * FROM loads ORDER BY CASE status WHEN 'evaluation' THEN 0 WHEN 'open' THEN 1 WHEN 'draft' THEN 2 WHEN 'awarded' THEN 3 WHEN 'picked_up' THEN 4 ELSE 5 END, pickup_from`).all();
    } else if (scope === 'mine') {
      rows = q(`SELECT l.* FROM loads l JOIN bids b ON b.id = l.awarded_bid_id WHERE b.company_id = ?
        ORDER BY CASE l.status WHEN 'awarded' THEN 0 WHEN 'picked_up' THEN 1 ELSE 2 END, l.pickup_from DESC`).all(user.company_id);
    } else if (scope === 'bids') {
      rows = q(`SELECT l.* FROM loads l JOIN bids b ON b.load_id = l.id WHERE b.company_id = ? ORDER BY b.updated_at DESC`).all(user.company_id);
    } else {
      rows = q(`SELECT * FROM loads WHERE status = 'open' ORDER BY pickup_from`).all();
    }
    return rows.map(l => serializeLoad(l, user));
  });

  function loadInput(body) {
    const mode = body.mode === 'direct' ? 'direct' : 'tender';
    const required = Array.isArray(body.required_quals) ? body.required_quals.filter(c => TYPE_BY_CODE[c]) : [];
    const data = {
      title: str(body.title, 'Tittel', { required: true, max: 120 }),
      cargo_type: str(body.cargo_type, 'Lasttype', { required: true, max: 80 }),
      description: str(body.description, 'Beskrivelse', { max: 2000 }),
      from_site: str(body.from_site, 'Hentested', { required: true, max: 200 }),
      to_address: str(body.to_address, 'Leveringsadresse', { required: true, max: 200 }),
      weight_kg: int(body.weight_kg, 'Vekt (kg)'),
      units: str(body.units, 'Antall/enheter', { max: 80 }),
      pickup_from: isoDate(body.pickup_from, 'Henting fra', { required: true }),
      pickup_to: isoDate(body.pickup_to, 'Henting til'),
      deliver_by: isoDate(body.deliver_by, 'Levert innen'),
      required_quals: JSON.stringify([...new Set(required)]),
      mode,
      price_nok: mode === 'direct' ? int(body.price_nok, 'Fastpris (kr)', { required: true, min: 1 }) : null,
      bid_deadline: mode === 'tender' ? isoDate(body.bid_deadline, 'Anbudsfrist') : null,
    };
    if (data.pickup_to && data.pickup_to < data.pickup_from) throw bad('«Henting til» kan ikke være før «Henting fra»');
    return data;
  }

  function validateForPublish(load) {
    if (load.mode === 'tender') {
      if (!load.bid_deadline) throw bad('Anbudsfrist må settes før publisering');
      if (load.bid_deadline <= nowIso()) throw bad('Anbudsfristen må være fram i tid');
    }
  }

  function publish(load, user) {
    validateForPublish(load);
    q(`UPDATE loads SET status = 'open', published_at = ? WHERE id = ?`).run(nowIso(), load.id);
    logEvent(load.id, user.id, load.mode === 'direct' ? `Publisert med fastpris ${load.price_nok} kr` : 'Lagt ut på anbud');
    const what = load.mode === 'direct' ? `Ny last med fastpris ${load.price_nok.toLocaleString('nb-NO')} kr` : 'Ny last på anbud';
    notifyQualifiedCarriers(load, `${what}: ${load.ref} ${load.title} (${load.from_site} → ${load.to_address})`);
  }

  route('POST', '/api/loads', ctx => {
    const user = requireAdmin(ctx);
    const data = loadInput(ctx.body);
    return transaction(db, () => {
      const ref = nextRef();
      const id = Number(q(`INSERT INTO loads (ref, title, cargo_type, description, from_site, to_address, weight_kg, units, pickup_from,
        pickup_to, deliver_by, required_quals, mode, price_nok, bid_deadline, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(ref, data.title, data.cargo_type, data.description, data.from_site,
        data.to_address, data.weight_kg, data.units, data.pickup_from, data.pickup_to, data.deliver_by, data.required_quals, data.mode,
        data.price_nok, data.bid_deadline, user.id).lastInsertRowid);
      logEvent(id, user.id, 'Last opprettet');
      const load = q('SELECT * FROM loads WHERE id = ?').get(id);
      if (ctx.body.publish) publish(load, user);
      return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(id), user);
    });
  });

  route('GET', '/api/loads/:id', ctx => {
    const user = requireUser(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    const out = serializeLoad(load, user);
    if (user.role === 'admin') {
      out.bids = q(`SELECT b.*, c.name AS company_name, c.org_nr, c.phone AS company_phone, u.name AS user_name
        FROM bids b JOIN companies c ON c.id = b.company_id JOIN users u ON u.id = b.user_id
        WHERE b.load_id = ? ORDER BY CASE b.status WHEN 'won' THEN 0 WHEN 'active' THEN 1 WHEN 'lost' THEN 2 ELSE 3 END, b.price_nok`).all(load.id)
        .map(b => ({ ...b, eligibility: eligibility(b.company_id, load) }));
      out.events = q(`SELECT e.message, e.created_at, u.name AS user_name FROM events e LEFT JOIN users u ON u.id = e.user_id
        WHERE e.load_id = ? ORDER BY e.id DESC`).all(load.id);
    } else if (out.is_mine) {
      out.events = q(`SELECT message, created_at FROM events WHERE load_id = ? ORDER BY id DESC`).all(load.id);
    }
    return out;
  });

  route('PUT', '/api/loads/:id', ctx => {
    const user = requireAdmin(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (load.status !== 'draft') throw new HttpError(409, 'Kun utkast kan redigeres');
    const d = loadInput(ctx.body);
    q(`UPDATE loads SET title=?, cargo_type=?, description=?, from_site=?, to_address=?, weight_kg=?, units=?, pickup_from=?, pickup_to=?,
      deliver_by=?, required_quals=?, mode=?, price_nok=?, bid_deadline=? WHERE id = ?`).run(d.title, d.cargo_type, d.description, d.from_site,
      d.to_address, d.weight_kg, d.units, d.pickup_from, d.pickup_to, d.deliver_by, d.required_quals, d.mode, d.price_nok, d.bid_deadline, load.id);
    logEvent(load.id, user.id, 'Utkast redigert');
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  route('POST', '/api/loads/:id/publish', ctx => {
    const user = requireAdmin(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (load.status !== 'draft') throw new HttpError(409, 'Lasten er allerede publisert');
    transaction(db, () => publish(load, user));
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  route('POST', '/api/loads/:id/extend', ctx => {
    const user = requireAdmin(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (load.mode !== 'tender' || !['open', 'evaluation'].includes(load.status)) throw new HttpError(409, 'Fristen kan bare forlenges på aktive anbud');
    const deadline = isoDate(ctx.body.bid_deadline, 'Ny anbudsfrist', { required: true });
    if (deadline <= nowIso()) throw bad('Ny frist må være fram i tid');
    transaction(db, () => {
      q(`UPDATE loads SET bid_deadline = ?, status = 'open' WHERE id = ?`).run(deadline, load.id);
      logEvent(load.id, user.id, `Anbudsfrist endret til ${deadline}`);
      notifyQualifiedCarriers(load, `Anbudsfristen for ${load.ref} ${load.title} er forlenget`);
    });
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  route('POST', '/api/loads/:id/cancel', ctx => {
    const user = requireAdmin(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (['delivered', 'cancelled'].includes(load.status)) throw new HttpError(409, 'Lasten kan ikke avlyses');
    const reason = str(ctx.body.reason, 'Årsak', { max: 500 });
    transaction(db, () => {
      q(`UPDATE loads SET status = 'cancelled' WHERE id = ?`).run(load.id);
      const bidders = q(`SELECT DISTINCT company_id FROM bids WHERE load_id = ? AND status IN ('active','won')`).all(load.id);
      q(`UPDATE bids SET status = 'lost', updated_at = ? WHERE load_id = ? AND status IN ('active','won')`).run(nowIso(), load.id);
      logEvent(load.id, user.id, `Avlyst${reason ? ': ' + reason : ''}`);
      notify(bidders.flatMap(b => companyUserIds(b.company_id)), load.id, `${load.ref} ${load.title} er avlyst${reason ? ': ' + reason : ''}`);
    });
    return { ok: true };
  });

  route('POST', '/api/loads/:id/award', ctx => {
    const user = requireAdmin(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (!['open', 'evaluation'].includes(load.status)) throw new HttpError(409, 'Lasten kan ikke tildeles nå');
    const bid = q(`SELECT * FROM bids WHERE id = ? AND load_id = ?`).get(int(ctx.body.bid_id, 'Bud', { required: true }), load.id);
    if (!bid || bid.status !== 'active') throw bad('Budet finnes ikke eller er trukket');
    const elig = eligibility(bid.company_id, load);
    if (!elig.qualified) throw new HttpError(409, `Transportøren er ikke lenger kvalifisert: ${elig.missing.map(m => `${m.name} (${m.reason})`).join(', ') || 'firmaet er ikke godkjent'}`);
    const company = getCompany(bid.company_id);
    transaction(db, () => awardBid(load, bid, company, user));
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  function awardBid(load, bid, company, user) {
    q(`UPDATE loads SET status = 'awarded', awarded_bid_id = ? WHERE id = ?`).run(bid.id, load.id);
    q(`UPDATE bids SET status = 'won', updated_at = ? WHERE id = ?`).run(nowIso(), bid.id);
    const losers = q(`SELECT company_id FROM bids WHERE load_id = ? AND status = 'active'`).all(load.id);
    q(`UPDATE bids SET status = 'lost', updated_at = ? WHERE load_id = ? AND status = 'active'`).run(nowIso(), load.id);
    logEvent(load.id, user.id, `Tildelt ${company.name} for ${bid.price_nok} kr`);
    notify(companyUserIds(company.id), load.id, `Du har fått oppdraget ${load.ref} ${load.title} for ${bid.price_nok.toLocaleString('nb-NO')} kr`);
    notify(losers.flatMap(l => companyUserIds(l.company_id)), load.id, `${load.ref} ${load.title} ble tildelt en annen transportør`);
    if (user.role === 'carrier') notify(adminIds(), load.id, `${company.name} tok fastprisoppdraget ${load.ref} ${load.title}`);
  }

  function assertCanBid(load, user) {
    if (load.status !== 'open') throw new HttpError(409, 'Lasten er ikke åpen for bud');
    const elig = eligibility(user.company_id, load);
    if (!elig.qualified) {
      const why = elig.companyApproved ? elig.missing.map(m => `${m.name} (${m.reason})`).join(', ') : 'firmaet er ikke prekvalifisert ennå';
      throw new HttpError(403, `Dere er ikke prekvalifisert for denne lasten: ${why}`);
    }
  }

  route('POST', '/api/loads/:id/bids', ctx => {
    const user = requireCarrier(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (load.mode !== 'tender') throw bad('Denne lasten har fastpris – bruk «Ta oppdraget»');
    assertCanBid(load, user);
    const price = int(ctx.body.price_nok, 'Pris (kr)', { required: true, min: 1 });
    const eta = isoDate(ctx.body.pickup_eta, 'Planlagt henting');
    const comment = str(ctx.body.comment, 'Kommentar', { max: 1000 });
    const existing = q('SELECT * FROM bids WHERE load_id = ? AND company_id = ?').get(load.id, user.company_id);
    const company = getCompany(user.company_id);
    transaction(db, () => {
      if (existing) {
        q(`UPDATE bids SET price_nok = ?, pickup_eta = ?, comment = ?, status = 'active', user_id = ?, updated_at = ? WHERE id = ?`)
          .run(price, eta, comment, user.id, nowIso(), existing.id);
        logEvent(load.id, user.id, `${company.name} endret bud`);
      } else {
        q('INSERT INTO bids (load_id, company_id, user_id, price_nok, pickup_eta, comment) VALUES (?, ?, ?, ?, ?, ?)')
          .run(load.id, user.company_id, user.id, price, eta, comment);
        logEvent(load.id, user.id, `${company.name} la inn bud`);
        notify(adminIds(), load.id, `Nytt bud på ${load.ref} ${load.title} fra ${company.name}`);
      }
    });
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  route('DELETE', '/api/loads/:id/bids/mine', ctx => {
    const user = requireCarrier(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    if (load.status !== 'open') throw new HttpError(409, 'Bud kan bare trekkes mens anbudet er åpent');
    const bid = q(`SELECT * FROM bids WHERE load_id = ? AND company_id = ? AND status = 'active'`).get(load.id, user.company_id);
    if (!bid) throw new HttpError(404, 'Ingen aktivt bud å trekke');
    q(`UPDATE bids SET status = 'withdrawn', updated_at = ? WHERE id = ?`).run(nowIso(), bid.id);
    logEvent(load.id, user.id, `${getCompany(user.company_id).name} trakk budet`);
    return serializeLoad(load, user);
  });

  route('POST', '/api/loads/:id/accept', ctx => {
    const user = requireCarrier(ctx);
    const loadId = Number(ctx.params.id);
    return transaction(db, () => {
      // Leses på nytt inne i transaksjonen så to transportører ikke kan ta samme last.
      const load = loadForUser(loadId, user);
      if (load.mode !== 'direct') throw bad('Denne lasten er på anbud – legg inn et bud');
      assertCanBid(load, user);
      const eta = isoDate(ctx.body.pickup_eta, 'Planlagt henting');
      const bidId = Number(q(`INSERT INTO bids (load_id, company_id, user_id, price_nok, pickup_eta, comment) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(load_id, company_id) DO UPDATE SET status = 'active', price_nok = excluded.price_nok, pickup_eta = excluded.pickup_eta
        RETURNING id`).get(load.id, user.company_id, user.id, load.price_nok, eta, str(ctx.body.comment, 'Kommentar', { max: 1000 })).id);
      const bid = q('SELECT * FROM bids WHERE id = ?').get(bidId);
      awardBid(load, bid, getCompany(user.company_id), user);
      return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
    });
  });

  route('POST', '/api/loads/:id/status', ctx => {
    const user = requireUser(ctx);
    const load = loadForUser(Number(ctx.params.id), user);
    const target = ctx.body.status;
    const allowed = { awarded: 'picked_up', picked_up: 'delivered' };
    if (allowed[load.status] !== target) throw new HttpError(409, `Kan ikke gå fra «${STATUS_TEXT[load.status]}» til «${STATUS_TEXT[target] || target}»`);
    const bid = q('SELECT * FROM bids WHERE id = ?').get(load.awarded_bid_id);
    if (user.role === 'carrier' && bid.company_id !== user.company_id) throw new HttpError(403, 'Oppdraget er ikke ditt');
    const note = str(ctx.body.note, 'Merknad', { max: 1000 });
    transaction(db, () => {
      q('UPDATE loads SET status = ? WHERE id = ?').run(target, load.id);
      logEvent(load.id, user.id, `${STATUS_TEXT[target]}${note ? ': ' + note : ''}`);
      const recipients = user.role === 'carrier' ? adminIds() : companyUserIds(bid.company_id);
      notify(recipients, load.id, `${load.ref} ${load.title}: ${STATUS_TEXT[target].toLowerCase()}${note ? ' – ' + note : ''}`);
    });
    return serializeLoad(q('SELECT * FROM loads WHERE id = ?').get(load.id), user);
  });

  // ---- prekvalifisering

  function qualRows(companyId) {
    return q(`SELECT cq.id, cq.type_code, qt.name, cq.reference, cq.valid_until, cq.status, cq.review_comment, cq.reviewed_at,
      cq.document_name, cq.submitted_at FROM company_qualifications cq JOIN qualification_types qt ON qt.code = cq.type_code
      WHERE cq.company_id = ? ORDER BY qt.base DESC, qt.name`).all(companyId);
  }

  route('GET', '/api/my/qualifications', ctx => {
    const user = requireCarrier(ctx);
    return qualRows(user.company_id);
  });

  route('PUT', '/api/my/qualifications/:code', ctx => {
    const user = requireCarrier(ctx);
    const code = ctx.params.code;
    if (!TYPE_BY_CODE[code]) throw new HttpError(404, 'Ukjent kvalifikasjon');
    const reference = str(ctx.body.reference, 'Referanse/dokumentnr.', { max: 200 });
    const validUntil = dayDate(ctx.body.valid_until, 'Gyldig til');
    let doc = null;
    if (ctx.body.document && ctx.body.document.data) {
      const type = String(ctx.body.document.type || '');
      if (!DOC_TYPES.has(type)) throw bad('Dokumentet må være PDF, PNG, JPG eller WEBP');
      const data = Buffer.from(String(ctx.body.document.data), 'base64');
      if (data.length > MAX_DOC) throw bad('Dokumentet kan maks være 5 MB');
      doc = { name: str(ctx.body.document.name, 'Filnavn', { max: 200 }) || 'dokument', type, data };
    }
    const existing = q('SELECT id, document_name FROM company_qualifications WHERE company_id = ? AND type_code = ?').get(user.company_id, code);
    if (!doc && !existing?.document_name && !reference) throw bad('Legg ved dokument eller oppgi referanse/dokumentnummer');
    const company = getCompany(user.company_id);
    transaction(db, () => {
      if (existing) {
        q(`UPDATE company_qualifications SET reference = ?, valid_until = ?, status = 'pending', review_comment = NULL, reviewed_by = NULL,
          reviewed_at = NULL, submitted_at = ? WHERE id = ?`).run(reference, validUntil, nowIso(), existing.id);
        if (doc) q('UPDATE company_qualifications SET document_name = ?, document_type = ?, document = ? WHERE id = ?').run(doc.name, doc.type, doc.data, existing.id);
      } else {
        q(`INSERT INTO company_qualifications (company_id, type_code, reference, valid_until, document_name, document_type, document)
          VALUES (?, ?, ?, ?, ?, ?, ?)`).run(user.company_id, code, reference, validUntil, doc?.name ?? null, doc?.type ?? null, doc?.data ?? null);
      }
      notify(adminIds(), null, `${company.name} har sendt inn «${TYPE_BY_CODE[code].name}» til godkjenning`);
    });
    return qualRows(user.company_id);
  });

  route('GET', '/api/qualifications/:id/document', ctx => {
    const user = requireUser(ctx);
    const row = q('SELECT company_id, document_name, document_type, document FROM company_qualifications WHERE id = ?').get(Number(ctx.params.id));
    if (!row || !row.document || (user.role !== 'admin' && row.company_id !== user.company_id)) throw new HttpError(404, 'Fant ikke dokumentet');
    ctx.res.writeHead(200, {
      'Content-Type': row.document_type,
      'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(row.document_name)}`,
      'X-Content-Type-Options': 'nosniff',
    });
    ctx.res.end(Buffer.from(row.document));
    return undefined;
  });

  route('POST', '/api/qualifications/:id/review', ctx => {
    const user = requireAdmin(ctx);
    const row = q('SELECT * FROM company_qualifications WHERE id = ?').get(Number(ctx.params.id));
    if (!row) throw new HttpError(404, 'Fant ikke kvalifikasjonen');
    const status = ctx.body.status;
    if (!['approved', 'rejected'].includes(status)) throw bad('Status må være approved eller rejected');
    const comment = str(ctx.body.comment, 'Kommentar', { max: 500 });
    const validUntil = ctx.body.valid_until !== undefined ? dayDate(ctx.body.valid_until, 'Gyldig til') : row.valid_until;
    if (status === 'rejected' && !comment) throw bad('Skriv en begrunnelse når du avviser');
    transaction(db, () => {
      q(`UPDATE company_qualifications SET status = ?, review_comment = ?, valid_until = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ?`)
        .run(status, comment, validUntil, user.id, nowIso(), row.id);
      const name = TYPE_BY_CODE[row.type_code].name;
      notify(companyUserIds(row.company_id), null, status === 'approved' ? `«${name}» er godkjent` : `«${name}» ble avvist: ${comment}`);
    });
    return { ok: true };
  });

  route('GET', '/api/companies', ctx => {
    requireAdmin(ctx);
    return q(`SELECT c.*, (SELECT COUNT(*) FROM company_qualifications WHERE company_id = c.id AND status = 'pending') AS pending_quals,
      (SELECT COUNT(*) FROM bids WHERE company_id = c.id AND status = 'won') AS won_count
      FROM companies c ORDER BY CASE c.status WHEN 'pending' THEN 0 ELSE 1 END, pending_quals DESC, c.name`).all()
      .map(c => ({ ...c, base_status: evaluate(c, companyQuals(c.id), []) }));
  });

  route('GET', '/api/companies/:id', ctx => {
    requireAdmin(ctx);
    const company = getCompany(Number(ctx.params.id));
    if (!company) throw new HttpError(404, 'Fant ikke firmaet');
    return {
      ...company,
      base_status: evaluate(company, companyQuals(company.id), []),
      users: q('SELECT id, name, email, phone FROM users WHERE company_id = ?').all(company.id),
      qualifications: qualRows(company.id),
    };
  });

  route('POST', '/api/companies/:id/status', ctx => {
    const user = requireAdmin(ctx);
    const company = getCompany(Number(ctx.params.id));
    if (!company) throw new HttpError(404, 'Fant ikke firmaet');
    const status = ctx.body.status;
    if (!['approved', 'rejected', 'suspended', 'pending'].includes(status)) throw bad('Ugyldig status');
    const comment = str(ctx.body.comment, 'Kommentar', { max: 500 });
    if (['rejected', 'suspended'].includes(status) && !comment) throw bad('Skriv en begrunnelse');
    q('UPDATE companies SET status = ?, status_comment = ?, approved_at = CASE WHEN ? = \'approved\' THEN ? ELSE approved_at END WHERE id = ?')
      .run(status, comment, status, nowIso(), company.id);
    const text = { approved: 'Firmaet er prekvalifisert og kan nå gi bud på laster', rejected: `Prekvalifisering avslått: ${comment}`,
      suspended: `Firmaet er suspendert: ${comment}`, pending: 'Prekvalifiseringen er satt til ny vurdering' }[status];
    notify(companyUserIds(company.id), null, text);
    void user;
    return { ok: true };
  });

  // ---- oversikt og varsler

  route('GET', '/api/dashboard', ctx => {
    requireAdmin(ctx);
    const counts = Object.fromEntries(q('SELECT status, COUNT(*) AS n FROM loads GROUP BY status').all().map(r => [r.status, r.n]));
    return {
      counts,
      pending_companies: q(`SELECT COUNT(*) AS n FROM companies WHERE status = 'pending'`).get().n,
      pending_quals: q(`SELECT COUNT(*) AS n FROM company_qualifications WHERE status = 'pending'`).get().n,
      approved_companies: q(`SELECT COUNT(*) AS n FROM companies WHERE status = 'approved'`).get().n,
      delivered_value: q(`SELECT COALESCE(SUM(b.price_nok), 0) AS v FROM loads l JOIN bids b ON b.id = l.awarded_bid_id WHERE l.status = 'delivered'`).get().v,
    };
  });

  route('GET', '/api/notifications', ctx => {
    const user = requireUser(ctx);
    return q('SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 100').all(user.id);
  });

  route('POST', '/api/notifications/read', ctx => {
    const user = requireUser(ctx);
    q('UPDATE notifications SET read = 1 WHERE user_id = ?').run(user.id);
    return { ok: true };
  });

  // ---- statiske filer

  function serveStatic(req, res, pathname) {
    let file = path.normalize(path.join(PUBLIC_DIR, pathname === '/' ? 'index.html' : pathname));
    if (!file.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end(); }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(PUBLIC_DIR, 'index.html');
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  }

  // ---- handler

  return async function handler(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/')) {
      if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
      return serveStatic(req, res, url.pathname);
    }
    try {
      const match = routes.map(r => r.method === req.method && { r, m: r.re.exec(url.pathname) }).find(x => x && x.m);
      if (!match) throw new HttpError(404, 'Ukjent API-endepunkt');
      // Enkel CSRF-beskyttelse: skrivende kall må være JSON (kan ikke sendes fra et vanlig HTML-skjema på et annet nettsted).
      if (req.method !== 'GET' && !String(req.headers['content-type'] || '').startsWith('application/json')) {
        throw new HttpError(415, 'Content-Type må være application/json');
      }
      const params = Object.fromEntries(match.r.keys.map((k, i) => [k, decodeURIComponent(match.m[i + 1])]));
      const body = req.method === 'GET' ? {} : await readBody(req);
      refreshStatuses();
      const ctx = { req, res, params, body, query: url.searchParams, user: currentUser(req) };
      const result = await match.r.handler(ctx);
      if (result === undefined) return;
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(result));
    } catch (err) {
      const status = err.status || 500;
      if (status === 500) console.error(err);
      if (!res.headersSent) res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: status === 500 ? 'Noe gikk galt på serveren' : err.message }));
    }
  };
}

module.exports = { createApp, STATUS_TEXT };
