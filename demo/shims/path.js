'use strict';
const join = (...p) => p.join('/').replace(/\/+/g, '/');
module.exports = { join, normalize: s => s, dirname: s => s.split('/').slice(0, -1).join('/') || '/', extname: s => { const m = /\.[^./]*$/.exec(s); return m ? m[0] : ''; } };
