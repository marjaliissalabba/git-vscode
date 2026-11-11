'use strict';
/**
 * node:sqlite (DatabaseSync) oppå sql.js, slik at serverkoden kan kjøre uendret i nettleseren.
 * sql.js lastes som globalt SQL-objekt før bunten (se demo/build.js).
 */
const toParam = v => (v === undefined ? null : typeof v === 'boolean' ? (v ? 1 : 0) : v);

class StatementSync {
  constructor(db, sql) { this.db = db; this.sql = sql; }
  _rows(params, limit) {
    const stmt = this.db.prepare(this.sql);
    try {
      stmt.bind(params.map(toParam));
      const rows = [];
      while (stmt.step()) {
        rows.push(stmt.getAsObject());
        if (rows.length === limit) break;
      }
      return rows;
    } finally { stmt.free(); }
  }
  get(...params) { return this._rows(params, 1)[0]; }
  all(...params) { return this._rows(params, Infinity); }
  run(...params) {
    this.db.run(this.sql, params.map(toParam));
    const changes = this.db.getRowsModified();
    const id = this.db.exec('SELECT last_insert_rowid() AS id')[0].values[0][0];
    return { changes, lastInsertRowid: id };
  }
}

class DatabaseSync {
  constructor() {
    const bytes = globalThis.__LASTBORS_DB_BYTES;
    this.db = bytes ? new globalThis.__SQL.Database(bytes) : new globalThis.__SQL.Database();
  }
  exec(sql) { this.db.exec(sql); }
  prepare(sql) { return new StatementSync(this.db, sql); }
  export() { return this.db.export(); }
}

module.exports = { DatabaseSync };
