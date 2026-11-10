'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');
const { QUALIFICATION_TYPES } = require('./qualification');

const SCHEMA = `
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS companies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  org_nr TEXT NOT NULL UNIQUE,
  phone TEXT,
  address TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected','suspended')),
  status_comment TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  approved_at TEXT
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  phone TEXT,
  role TEXT NOT NULL CHECK (role IN ('admin','carrier')),
  company_id INTEGER REFERENCES companies(id),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS qualification_types (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  base INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS company_qualifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  company_id INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
  type_code TEXT NOT NULL REFERENCES qualification_types(code),
  reference TEXT,
  valid_until TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  review_comment TEXT,
  reviewed_by INTEGER REFERENCES users(id),
  reviewed_at TEXT,
  document_name TEXT,
  document_type TEXT,
  document BLOB,
  submitted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE (company_id, type_code)
);

CREATE TABLE IF NOT EXISTS loads (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ref TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  cargo_type TEXT NOT NULL,
  description TEXT,
  from_site TEXT NOT NULL,
  to_address TEXT NOT NULL,
  weight_kg INTEGER,
  units TEXT,
  pickup_from TEXT NOT NULL,
  pickup_to TEXT,
  deliver_by TEXT,
  required_quals TEXT NOT NULL DEFAULT '[]',
  mode TEXT NOT NULL DEFAULT 'tender' CHECK (mode IN ('tender','direct')),
  price_nok INTEGER,
  bid_deadline TEXT,
  status TEXT NOT NULL DEFAULT 'draft'
    CHECK (status IN ('draft','open','evaluation','awarded','picked_up','delivered','cancelled')),
  awarded_bid_id INTEGER,
  created_by INTEGER NOT NULL REFERENCES users(id),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  published_at TEXT
);

CREATE TABLE IF NOT EXISTS bids (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  load_id INTEGER NOT NULL REFERENCES loads(id) ON DELETE CASCADE,
  company_id INTEGER NOT NULL REFERENCES companies(id),
  user_id INTEGER NOT NULL REFERENCES users(id),
  price_nok INTEGER NOT NULL CHECK (price_nok > 0),
  pickup_eta TEXT,
  comment TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','withdrawn','won','lost')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE (load_id, company_id)
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
  message TEXT NOT NULL,
  read INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_loads_status ON loads(status);
CREATE INDEX IF NOT EXISTS idx_bids_load ON bids(load_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, read);
`;

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `${salt}:${hash}`;
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
  db.exec(SCHEMA);
  const upsertType = db.prepare(
    `INSERT INTO qualification_types (code, name, description, base) VALUES (?, ?, ?, ?)
     ON CONFLICT(code) DO UPDATE SET name = excluded.name, description = excluded.description, base = excluded.base`
  );
  for (const t of QUALIFICATION_TYPES) upsertType.run(t.code, t.name, t.description, t.base ? 1 : 0);
  return db;
}

function transaction(db, fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}

/** Demodata slik at appen kan prøves med en gang. */
function seed(db, { adminPassword = 'demo1234', carrierPassword = 'demo1234' } = {}) {
  const count = db.prepare('SELECT COUNT(*) AS n FROM users').get().n;
  if (count > 0) return false;

  const nextYear = `${new Date().getFullYear() + 1}-12-31`;
  const soon = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);

  transaction(db, () => {
    const adminId = Number(db.prepare(`INSERT INTO users (email, password_hash, name, role) VALUES (?, ?, ?, 'admin')`)
      .run('anlegg@demo.no', hashPassword(adminPassword), 'Transportleder Anlegg').lastInsertRowid);

    const addCompany = db.prepare(`INSERT INTO companies (name, org_nr, phone, address, status, approved_at) VALUES (?, ?, ?, ?, ?, ?)`);
    const addUser = db.prepare(`INSERT INTO users (email, password_hash, name, phone, role, company_id) VALUES (?, ?, ?, ?, 'carrier', ?)`);
    const addQual = db.prepare(`INSERT INTO company_qualifications (company_id, type_code, reference, valid_until, status, reviewed_by, reviewed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)`);
    const now = new Date().toISOString();

    const carriers = [
      { name: 'Nordtrans AS', org: '912345678', email: 'nordtrans@demo.no', contact: 'Kari Nordmann', status: 'approved',
        quals: { LOYVE: 'approved', FORSIKRING: 'approved', SKATT: 'approved', HMS: 'approved', AVFALL: 'approved', ADR: 'approved', KROKLOFT: 'approved' } },
      { name: 'Fjordbil Transport AS', org: '923456789', email: 'fjordbil@demo.no', contact: 'Ola Hansen', status: 'approved',
        quals: { LOYVE: 'approved', FORSIKRING: 'approved', SKATT: 'approved', HMS: 'approved', AVFALL: 'approved', KROKLOFT: 'approved' }, expiring: 'FORSIKRING' },
      { name: 'Nykommer Logistikk AS', org: '934567890', email: 'nykommer@demo.no', contact: 'Per Pending', status: 'pending',
        quals: { LOYVE: 'pending', FORSIKRING: 'pending', SKATT: 'pending' } },
    ];

    for (const c of carriers) {
      const companyId = Number(addCompany.run(c.name, c.org, '+47 900 00 000', 'Industriveien 1, 9000 Tromsø', c.status, c.status === 'approved' ? now : null).lastInsertRowid);
      addUser.run(c.email, hashPassword(carrierPassword), c.contact, '+47 900 00 000', companyId);
      for (const [code, status] of Object.entries(c.quals)) {
        const approved = status === 'approved';
        addQual.run(companyId, code, `${code}-${c.org.slice(-4)}`, c.expiring === code ? soon : nextYear, status,
          approved ? adminId : null, approved ? now : null);
      }
    }

    const addLoad = db.prepare(`INSERT INTO loads (ref, title, cargo_type, description, from_site, to_address, weight_kg, units,
      pickup_from, pickup_to, deliver_by, required_quals, mode, price_nok, bid_deadline, status, created_by, published_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    const h = hours => new Date(Date.now() + hours * 3600000).toISOString();
    const year = new Date().getFullYear();

    addLoad.run(`L-${year}-0001`, 'Sortert metall til smelteverk', 'Metall', '3 x 30 m³ containere med sortert jern/stål.',
      'Anlegget, Gassanjarga', 'Mottak Mo i Rana', 27000, '3 containere',
      h(24), h(48), h(96), JSON.stringify(['AVFALL', 'KROKLOFT']), 'tender', null, h(20), 'open', adminId, now);
    addLoad.run(`L-${year}-0002`, 'Spillolje og blybatterier', 'Farlig avfall', 'Godkjent emballasje. ADR påkrevd.',
      'Anlegget, Gassanjarga', 'Behandlingsanlegg Kirkenes', 4200, '12 pall',
      h(30), h(54), h(120), JSON.stringify(['AVFALL', 'ADR']), 'tender', null, h(36), 'open', adminId, now);
    addLoad.run(`L-${year}-0003`, 'Trevirke – flis', 'Trevirke', 'Løs last, 1 tur.',
      'Anlegget, Gassanjarga', 'Fjernvarme Alta', 12000, '1 tur',
      h(6), h(12), h(24), JSON.stringify(['AVFALL']), 'direct', 6500, null, 'open', adminId, now);
    addLoad.run(`L-${year}-0004`, 'Papp og papir – baller', 'Papp/papir', 'Pressede baller, lasting med truck på anlegget.',
      'Anlegget, Gassanjarga', 'Returkartong, Oslo', 18000, '36 baller',
      h(72), h(96), h(168), JSON.stringify([]), 'tender', null, null, 'draft', adminId, null);
  });
  return true;
}

module.exports = { openDatabase, seed, transaction, hashPassword, verifyPassword };
