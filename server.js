'use strict';

const http = require('node:http');
const path = require('node:path');
const { openDatabase, seed } = require('./server/db');
const { createApp } = require('./server/app');

const PORT = Number(process.env.PORT) || 3000;
const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'data', 'lastebors.db');

const db = openDatabase(DB_FILE);
if (process.env.SEED !== 'false' && seed(db)) {
  console.log('Demodata lagt inn. Logg inn som anlegg@demo.no / nordtrans@demo.no / fjordbil@demo.no / nykommer@demo.no (passord: demo1234)');
}

http.createServer(createApp({ db })).listen(PORT, () => {
  console.log(`Lastebørs kjører på http://localhost:${PORT}`);
});
