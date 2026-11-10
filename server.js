'use strict';

const http = require('node:http');
const path = require('node:path');
const { openDatabase, seed } = require('./server/db');
const { createApp } = require('./server/app');

const PORT = Number(process.env.PORT) || 3000;
const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'data', 'lastbors-norden.db');

const db = openDatabase(DB_FILE);
if (process.env.SEED !== 'false' && seed(db)) {
  console.log('Demodata lagt inn (passord demo1234): anlegg@demo.no, nordtrans@demo.no, polar@demo.no, fjordbil@demo.no, admin@demo.no');
}

http.createServer(createApp({ db })).listen(PORT, () => {
  console.log(`Lastbørs Norden kjører på http://localhost:${PORT}`);
  console.log(`Integrasjoner: ${process.env.INTEGRATIONS_MODE === 'live' ? 'ekte (live)' : 'simulert'} – endres under Admin > Integrasjoner`);
});
