'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');
const { findPlace, estimateDistance } = require('./places');
const { LOAD_TYPE_BY_KEY } = require('./catalog');

const SCHEMA_VERSION = 2;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS companies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  orgnr TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  municipality TEXT,
  address TEXT,
  postal_code TEXT,
  city TEXT,
  naeringskode TEXT,
  naering TEXT,
  is_transport INTEGER NOT NULL DEFAULT 0,
  is_shipper INTEGER NOT NULL DEFAULT 0,
  verified INTEGER NOT NULL DEFAULT 0,
  verified_at TEXT,
  verified_source TEXT,
  blocked INTEGER NOT NULL DEFAULT 0,
  blocked_reason TEXT,
  declines INTEGER NOT NULL DEFAULT 0,
  phone TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  phone TEXT,
  company_id INTEGER REFERENCES companies(id),
  is_admin INTEGER NOT NULL DEFAULT 0,
  active_role TEXT NOT NULL CHECK (active_role IN ('transport','shipper','admin')),
  show_locked INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS qualification_docs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN ('lov','avf','adr')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  valid_until TEXT,
  reference TEXT,
  file_name TEXT,
  file_type TEXT,
  file BLOB,
  reject_reason TEXT,
  reviewed_by INTEGER REFERENCES users(id),
  reviewed_at TEXT,
  register_check TEXT,
  expiry_notified INTEGER NOT NULL DEFAULT 0,
  submitted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE (company_id, type)
);

CREATE TABLE IF NOT EXISTS vehicles (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  reg_no TEXT NOT NULL,
  payload_t REAL,
  equipment TEXT NOT NULL DEFAULT '[]',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS loads (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ref TEXT UNIQUE,
  shipper_id INTEGER NOT NULL REFERENCES companies(id),
  created_by INTEGER NOT NULL REFERENCES users(id),
  type_key TEXT NOT NULL,
  type TEXT NOT NULL,
  category TEXT NOT NULL,
  image_key TEXT,
  description TEXT,
  from_site TEXT NOT NULL, from_city TEXT NOT NULL, from_lat REAL, from_lng REAL,
  to_site TEXT NOT NULL, to_city TEXT NOT NULL, to_lat REAL, to_lng REAL,
  distance_km INTEGER,
  weight_t REAL NOT NULL,
  units TEXT,
  equipment TEXT,
  pickup_start TEXT NOT NULL,
  pickup_end TEXT NOT NULL,
  deliver_by TEXT,
  price_mode TEXT NOT NULL CHECK (price_mode IN ('fixed','bid')),
  fixed_price INTEGER,
  max_price INTEGER,
  bid_deadline TEXT,
  requirements TEXT NOT NULL DEFAULT '["lov"]',
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','awarded','confirmed','cancelled','completed')),
  awarded_to INTEGER REFERENCES companies(id),
  reminder_sent INTEGER NOT NULL DEFAULT 0,
  deadline_notified INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  completed_at TEXT
);

CREATE TABLE IF NOT EXISTS bids (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  load_id INTEGER NOT NULL REFERENCES loads(id) ON DELETE CASCADE,
  carrier_id INTEGER NOT NULL REFERENCES companies(id),
  user_id INTEGER NOT NULL REFERENCES users(id),
  amount INTEGER NOT NULL CHECK (amount > 0),
  vehicle_id INTEGER REFERENCES vehicles(id),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','won','lost','withdrawn')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE (load_id, carrier_id)
);

CREATE TABLE IF NOT EXISTS assignments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  load_id INTEGER NOT NULL REFERENCES loads(id) ON DELETE CASCADE,
  carrier_id INTEGER NOT NULL REFERENCES companies(id),
  bid_id INTEGER REFERENCES bids(id),
  vehicle_id INTEGER REFERENCES vehicles(id),
  price INTEGER NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('fixed','bid')),
  status TEXT NOT NULL CHECK (status IN ('awaiting_confirmation','confirmed','declined','expired')),
  confirm_by TEXT,
  confirmed_at TEXT,
  decline_reason TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  load_id INTEGER REFERENCES loads(id) ON DELETE CASCADE,
  user_id INTEGER REFERENCES users(id),
  message TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  load_id INTEGER REFERENCES loads(id) ON DELETE CASCADE,
  link TEXT,
  message TEXT NOT NULL,
  read INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS integration_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  integration TEXT NOT NULL,
  operation TEXT NOT NULL,
  input TEXT,
  mode TEXT NOT NULL,
  scenario TEXT,
  ok INTEGER NOT NULL,
  error_code TEXT,
  message TEXT,
  duration_ms INTEGER,
  response TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_loads_status ON loads(status);
CREATE INDEX IF NOT EXISTS idx_bids_load ON bids(load_id);
CREATE INDEX IF NOT EXISTS idx_assign_carrier ON assignments(carrier_id, status);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, read);
`;

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  return `${salt}:${crypto.scryptSync(password, salt, 64).toString('hex')}`;
}

function verifyPassword(password, stored) {
  const [salt, hash] = String(stored).split(':');
  if (!salt || !hash) return false;
  const candidate = crypto.scryptSync(password, salt, 64);
  const expected = Buffer.from(hash, 'hex');
  return expected.length === candidate.length && crypto.timingSafeEqual(candidate, expected);
}

function openDatabase(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA foreign_keys = ON');
  const version = db.prepare('PRAGMA user_version').get().user_version;
  const hasTables = db.prepare(`SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table'`).get().n > 0;
  if (hasTables && version !== SCHEMA_VERSION) {
    throw new Error(`Databasen ${file} har et eldre skjema (versjon ${version}). Flytt eller slett filen, så opprettes en ny.`);
  }
  db.exec(SCHEMA);
  db.exec(`PRAGMA user_version = ${SCHEMA_VERSION}`);
  return db;
}

function transaction(db, fn) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}

/** Legger inn en last og setter referanse «L-26xx». Brukes av både API og demodata. */
function insertLoad(db, l) {
  const t = LOAD_TYPE_BY_KEY[l.type_key];
  const from = findPlace(l.from_city), to = findPlace(l.to_city);
  const id = Number(db.prepare(`INSERT INTO loads (shipper_id, created_by, type_key, type, category, image_key, description,
    from_site, from_city, from_lat, from_lng, to_site, to_city, to_lat, to_lng, distance_km, weight_t, units, equipment,
    pickup_start, pickup_end, deliver_by, price_mode, fixed_price, max_price, bid_deadline, requirements, status)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
    l.shipper_id, l.created_by, t.key, l.type || t.name, t.category, t.image, l.description ?? null,
    l.from_site, from ? from.name : l.from_city, from?.geo[0] ?? null, from?.geo[1] ?? null,
    l.to_site, to ? to.name : l.to_city, to?.geo[0] ?? null, to?.geo[1] ?? null,
    estimateDistance(from?.geo, to?.geo), l.weight_t, l.units ?? null, l.equipment || t.equipment,
    l.pickup_start, l.pickup_end, l.deliver_by ?? null, l.price_mode, l.fixed_price ?? null, l.max_price ?? null,
    l.bid_deadline ?? null, JSON.stringify(l.requirements), l.status || 'open').lastInsertRowid);
  db.prepare('UPDATE loads SET ref = ? WHERE id = ?').run(`L-${2600 + id}`, id);
  return id;
}

/** Demodata slik at appen kan prøves med en gang. */
function seed(db, { password = 'demo1234' } = {}) {
  if (db.prepare('SELECT COUNT(*) AS n FROM users').get().n > 0) return false;
  const now = new Date().toISOString();
  const h = hours => new Date(Date.now() + hours * 3600000).toISOString();
  const nextYear = `${new Date().getFullYear() + 1}-12-31`;
  const soon = new Date(Date.now() + 18 * 86400000).toISOString().slice(0, 10);

  transaction(db, () => {
    const addCompany = db.prepare(`INSERT INTO companies (orgnr, name, municipality, address, postal_code, city, naeringskode, naering,
      is_transport, is_shipper, verified, verified_at, verified_source, phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'simulated', ?)`);
    const addUser = db.prepare(`INSERT INTO users (email, password_hash, name, phone, company_id, is_admin, active_role) VALUES (?, ?, ?, ?, ?, ?, ?)`);
    const addDoc = db.prepare(`INSERT INTO qualification_docs (company_id, type, status, valid_until, reference, reviewed_at) VALUES (?, ?, ?, ?, ?, ?)`);
    const addVehicle = db.prepare(`INSERT INTO vehicles (company_id, name, reg_no, payload_t, equipment) VALUES (?, ?, ?, ?, ?)`);

    const adminId = Number(addUser.run('admin@demo.no', hashPassword(password), 'Dokumentkontroll', null, null, 1, 'admin').lastInsertRowid);
    void adminId;

    const shipper = Number(addCompany.run('910000004', 'Demo Gjenvinning AS', 'Tana', 'Industriveien 2', '9845', 'Tana', '38.110',
      'Innsamling av ikke-farlig avfall', 0, 1, now, '911 08 150').lastInsertRowid);
    const shipperUser = Number(addUser.run('anlegg@demo.no', hashPassword(password), 'Transportleder Anlegg', '911 08 150', shipper, 0, 'shipper').lastInsertRowid);

    const carriers = [
      { orgnr: '912345688', name: 'Nordtrans AS', muni: 'Tromsø', email: 'nordtrans@demo.no', contact: 'Kari Nordmann', phone: '922 33 444',
        docs: { lov: ['approved', nextYear], avf: ['approved', nextYear], adr: ['approved', nextYear] },
        vehicles: [['Scania R500 krokbil', 'TR 12345', 18, ['Krokbil', 'ADR-merket']], ['Volvo FH semitrailer', 'TR 67890', 28, ['Semitrailer', 'Kran']]] },
      { orgnr: '923456783', name: 'Polar Frakt AS', muni: 'Alta', email: 'polar@demo.no', contact: 'Ola Hansen', phone: '933 44 555',
        docs: { lov: ['approved', soon], avf: ['approved', nextYear] },
        vehicles: [['MAN TGX semitrailer', 'ZX 11223', 26, ['Semitrailer']]] },
      { orgnr: '934567897', name: 'Fjordbil Transport AS', muni: 'Bodø', email: 'fjordbil@demo.no', contact: 'Per Pending', phone: '944 55 666',
        docs: { lov: ['pending', nextYear] },
        vehicles: [] },
    ];
    const ids = {};
    for (const c of carriers) {
      const id = Number(addCompany.run(c.orgnr, c.name, c.muni, 'Testveien 1', '0001', c.muni, '49.410', 'Godstransport på vei', 1, 0, now, c.phone).lastInsertRowid);
      ids[c.email] = { company: id, user: Number(addUser.run(c.email, hashPassword(password), c.contact, c.phone, id, 0, 'transport').lastInsertRowid) };
      for (const [type, [status, until]] of Object.entries(c.docs)) addDoc.run(id, type, status, until, `${type.toUpperCase()}-${c.orgnr.slice(-4)}`, status === 'approved' ? now : null);
      for (const [name, reg, payload, eq] of c.vehicles) addVehicle.run(id, name, reg, payload, JSON.stringify(eq));
    }

    const base = { shipper_id: shipper, created_by: shipperUser, from_site: 'Demo Gjenvinning, Gassanjarga', from_city: 'Gassanjarga' };
    insertLoad(db, { ...base, type_key: 'papp', type: 'Balert papp', to_site: 'Returkartong mottak', to_city: 'Mo i Rana', weight_t: 18,
      units: '42 baller', pickup_start: h(20), pickup_end: h(44), deliver_by: h(96), price_mode: 'bid', max_price: 32000, bid_deadline: h(18), requirements: ['lov', 'avf'] });
    insertLoad(db, { ...base, type_key: 'farlig', type: 'Spillolje og blybatterier', to_site: 'Behandlingsanlegg', to_city: 'Kirkenes', weight_t: 4.2,
      units: '12 pall', pickup_start: h(30), pickup_end: h(54), deliver_by: h(120), price_mode: 'bid', bid_deadline: h(0.75), requirements: ['lov', 'avf', 'adr'] });
    insertLoad(db, { ...base, type_key: 'tre', type: 'Trevirke – flis', to_site: 'Fjernvarmesentralen', to_city: 'Alta', weight_t: 12,
      units: '1 tur', pickup_start: h(6), pickup_end: h(12), deliver_by: h(24), price_mode: 'fixed', fixed_price: 14500, requirements: ['lov', 'avf'] });
    insertLoad(db, { ...base, type_key: 'krok', type: 'Krokcontainer restavfall', to_site: 'Forbrenningsanlegg', to_city: 'Tromsø', weight_t: 9,
      units: '2 krokcontainere', pickup_start: h(10), pickup_end: h(30), deliver_by: h(72), price_mode: 'fixed', fixed_price: 28500, requirements: ['lov', 'avf'] });
    insertLoad(db, { ...base, type_key: 'bygg', type: 'Gipsplater', from_site: 'Byggvarelager', from_city: 'Alta', to_site: 'Byggeplass Storgata', to_city: 'Hammerfest',
      weight_t: 14, units: '20 pall', pickup_start: h(48), pickup_end: h(60), deliver_by: h(80), price_mode: 'bid', bid_deadline: h(40), requirements: ['lov'] });

    // Bud på pappen fra to transportører
    const addBid = db.prepare('INSERT INTO bids (load_id, carrier_id, user_id, amount, vehicle_id) VALUES (?, ?, ?, ?, ?)');
    addBid.run(1, ids['nordtrans@demo.no'].company, ids['nordtrans@demo.no'].user, 28900, 2);
    addBid.run(1, ids['polar@demo.no'].company, ids['polar@demo.no'].user, 26400, 3);

    // Et anbud der Nordtrans har vunnet og må bekrefte
    const won = insertLoad(db, { ...base, type_key: 'metall', type: 'Sortert jern og stål', to_site: 'Smelteverk mottak', to_city: 'Mo i Rana', weight_t: 27,
      units: '3 krokcontainere', pickup_start: h(26), pickup_end: h(50), deliver_by: h(100), price_mode: 'bid', bid_deadline: h(-2), requirements: ['lov', 'avf'], status: 'awarded' });
    const wonBid = Number(addBid.run(won, ids['nordtrans@demo.no'].company, ids['nordtrans@demo.no'].user, 21400, 1).lastInsertRowid);
    db.prepare(`UPDATE bids SET status = 'won' WHERE id = ?`).run(wonBid);
    db.prepare('UPDATE loads SET awarded_to = ?, deadline_notified = 1 WHERE id = ?').run(ids['nordtrans@demo.no'].company, won);
    db.prepare(`INSERT INTO assignments (load_id, carrier_id, bid_id, vehicle_id, price, source, status, confirm_by) VALUES (?, ?, ?, 1, 21400, 'bid', 'awaiting_confirmation', ?)`)
      .run(won, ids['nordtrans@demo.no'].company, wonBid, h(2));
    db.prepare('INSERT INTO notifications (user_id, load_id, message) VALUES (?, ?, ?)')
      .run(ids['nordtrans@demo.no'].user, won, `Budet ditt vant: L-${2600 + won} Sortert jern og stål. Bekreft innen 2 timer.`);

    // Et fullført oppdrag
    const done = insertLoad(db, { ...base, type_key: 'glass', type: 'Glass- og metallemballasje', to_site: 'Sorteringsanlegg', to_city: 'Narvik', weight_t: 8,
      units: '1 krokcontainer', pickup_start: h(-120), pickup_end: h(-110), deliver_by: h(-90), price_mode: 'fixed', fixed_price: 19800, requirements: ['lov', 'avf'], status: 'completed' });
    db.prepare('UPDATE loads SET awarded_to = ?, completed_at = ? WHERE id = ?').run(ids['polar@demo.no'].company, h(-95), done);
    db.prepare(`INSERT INTO assignments (load_id, carrier_id, vehicle_id, price, source, status, confirmed_at) VALUES (?, ?, 3, 19800, 'fixed', 'confirmed', ?)`)
      .run(done, ids['polar@demo.no'].company, h(-119));
  });
  return true;
}

module.exports = { openDatabase, seed, transaction, insertLoad, hashPassword, verifyPassword, SCHEMA_VERSION };
