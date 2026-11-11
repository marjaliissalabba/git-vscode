'use strict';
/**
 * Bygger demoversjonen: én HTML-fil der server og app kjører i nettleseren.
 *   npm run build:demo   →   demo/dist/lastbors-demo.html
 */
const fs = require('node:fs');
const path = require('node:path');
const esbuild = require('esbuild');

const root = path.join(__dirname, '..');
const shim = name => path.join(__dirname, 'shims', name);
const NODE_SHIMS = { 'node:sqlite': 'sqlite.js', 'node:crypto': 'crypto.js', 'node:fs': 'fs.js', 'node:path': 'path.js' };

async function main() {
  const result = await esbuild.build({
    entryPoints: [path.join(__dirname, 'entry.js')],
    bundle: true, write: false, format: 'iife', platform: 'browser', target: 'es2020', minify: true,
    inject: [shim('process.js'), shim('buffer-global.js')],
    define: { __dirname: '"/app/server"' },
    plugins: [{
      name: 'node-shims',
      setup(build) {
        build.onResolve({ filter: /^node:/ }, args => {
          const file = NODE_SHIMS[args.path];
          if (!file) throw new Error(`Mangler shim for ${args.path}`);
          return { path: shim(file) };
        });
      },
    }],
  });
  const bundle = result.outputFiles[0].text;
  const read = f => fs.readFileSync(path.join(root, f), 'utf8');
  const inline = s => s.replace(/<\/script/gi, '<\\/script');
  const sprite = read('public/icons.svg').replace('<svg xmlns="http://www.w3.org/2000/svg">', '<svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" style="position:absolute;width:0;height:0;overflow:hidden">');

  const html = `<title>Lastbørs Norden</title>
<meta name="description" content="Demo av Lastbørs Norden: last ut til prekvalifiserte transportører, til fastpris eller på lukket anbud.">
<meta name="theme-color" content="#003462">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@800;900&family=Barlow:wght@400;500;600;700&display=swap">
<style>
${read('public/vendor/leaflet/leaflet.css')}
${read('public/styles.css')}
/* Demoversjonen: appen har ett bevisst lyst uttrykk etter designsystemet */
:root { color-scheme: light; }
html, body { background: var(--surface-page); color: var(--text-body); }
main { padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
</style>
${sprite}
<div id="app"><main><p class="meta">Starter Lastbørs Norden …</p></main></div>
<nav id="tabbar" class="tabbar" hidden></nav>
<div id="toast" class="toast" role="status" aria-live="polite"></div>
<div id="sheet-root"></div>
<script src="https://cdn.jsdelivr.net/npm/sql.js@1.10.3/dist/sql-asm-memory-growth.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
<script>window.__LASTBORS_EMBED = true;</script>
<script>${inline(bundle)}</script>
<script>${inline(read('public/app.js'))}</script>
`;
  const out = path.join(__dirname, 'dist', 'lastbors-demo.html');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, html);
  console.log(`Skrev ${path.relative(root, out)} (${Math.round(html.length / 1024)} KB)`);
}

main().catch(err => { console.error(err); process.exit(1); });
