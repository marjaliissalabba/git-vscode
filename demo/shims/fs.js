'use strict';
module.exports = { mkdirSync() {}, existsSync: () => false, statSync: () => ({ isFile: () => false, isDirectory: () => false }), createReadStream() { throw new Error('Ikke tilgjengelig i demoen'); } };
