'use strict';
/* Lastbørs Norden – klient (ren JavaScript, ingen byggesteg). */

const $app = document.getElementById('app');
const $tabs = document.getElementById('tabbar');
const $sheetRoot = document.getElementById('sheet-root');
const S = { me: null, meta: null, integ: null, wizard: null, lastbors: { view: 'list', filter: 'all', pin: null }, oppdragTab: 'active', loadsCache: [] };

// ======================================================================== hjelpere

const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const icon = (name, cls = '') => `<svg class="ic ${cls}" aria-hidden="true"><use href="/icons.svg#i-${name}"/></svg>`;
const NBSP = ' ';
const kr = n => (n == null || n === '' ? '–' : Math.round(Number(n)).toLocaleString('nb-NO').replace(/\s/g, NBSP) + NBSP + 'kr');
const tonn = t => (t == null ? '–' : `${String(Number(t)).replace('.', ',')}${NBSP}t`);
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'mai', 'jun', 'jul', 'aug', 'sep', 'okt', 'nov', 'des'];
const pad = n => String(n).padStart(2, '0');
const dDate = iso => { const d = new Date(iso); return `${d.getDate()}. ${MONTHS[d.getMonth()]}`; };
const dTime = iso => { const d = new Date(iso); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
const dFull = iso => (iso ? `${dDate(iso)} kl. ${dTime(iso)}` : '–');
const dDay = ymd => (ymd ? dDate(ymd + 'T12:00:00') + ' ' + ymd.slice(0, 4) : '–');
/** «12.–14. nov», «30. nov – 2. des» eller «12. nov» */
function dRange(a, b) {
  if (!a) return '–';
  const x = new Date(a), y = new Date(b || a);
  if (x.toDateString() === y.toDateString()) return dDate(a);
  if (x.getMonth() === y.getMonth() && x.getFullYear() === y.getFullYear()) return `${x.getDate()}.–${y.getDate()}. ${MONTHS[x.getMonth()]}`;
  return `${dDate(a)} – ${dDate(b)}`;
}
/** Hentevindu: «12. nov kl. 08:00–16:00» eller «12. nov kl. 08:00 – 14. nov kl. 16:00» */
function dWindow(a, b) {
  if (!b || a === b) return dFull(a);
  return new Date(a).toDateString() === new Date(b).toDateString() ? `${dDate(a)} kl. ${dTime(a)}–${dTime(b)}` : `${dFull(a)} – ${dFull(b)}`;
}
const place = (site, city) => (site && site.toLowerCase().includes(String(city).toLowerCase()) ? site : `${site}, ${city}`);
function timeLeft(iso) {
  const ms = new Date(iso) - Date.now();
  if (ms <= 0) return 'Frist ute';
  const h = Math.floor(ms / 3600000), m = Math.floor((ms % 3600000) / 60000);
  if (h >= 48) return `${Math.floor(h / 24)} dager igjen`;
  return h ? `${h} t ${m} min igjen` : `${m} min igjen`;
}
function toLocalInput(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
const fromLocalInput = v => (v ? new Date(v).toISOString() : null);
const initials = s => String(s || '?').split(/[\s/–-]+/).filter(Boolean).slice(0, 2).map(w => w[0].toUpperCase()).join('');

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch('/api' + path, {
    method, credentials: 'same-origin',
    headers: method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    body: method === 'GET' ? undefined : JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || `Feil (${res.status})`);
    err.status = res.status; err.code = data.code;
    if (res.status === 401 && !['/auth/login', '/me'].includes(path)) { S.me = null; go('#/'); }
    throw err;
  }
  return data;
}

let toastTimer;
function toast(msg, isError = false) {
  const t = document.getElementById('toast');
  t.innerHTML = `${icon(isError ? 'circle-x' : 'circle-check')}<span>${esc(msg)}</span>`;
  t.className = 'toast show' + (isError ? ' err' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isError ? 4000 : 2600);
}

function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }
async function guard(fn) { try { await fn(); } catch (err) { toast(err.message, true); } }
function busy(btn, fn) {
  return async (...args) => {
    if (btn) btn.disabled = true;
    try { await fn(...args); } catch (err) { toast(err.message, true); } finally { if (btn && btn.isConnected) btn.disabled = false; }
  };
}

const typeOf = key => S.meta.loadTypes.find(t => t.key === key);
function tile(load, size = '') {
  const cat = load.category === 'Avfall · ADR' ? 'cat-adr' : load.category === 'Byggevarer' ? 'cat-bygg' : load.category === 'Bulk' ? 'cat-bulk' : load.category === 'Gods' ? 'cat-gods' : '';
  const img = load.image_key && S.meta.imageBase !== '' ? `<img src="${esc(S.meta.imageBase + load.image_key)}" alt="" loading="lazy" onerror="this.remove()">` : '';
  return `<div class="tile ${size} ${cat}" aria-hidden="true"><span>${esc(initials(load.type))}</span>${img}</div>`;
}

const REQ_STATUS = {
  approved: ['green', 'circle-check', 'Godkjent'], pending: ['amber', 'clock', 'Til kontroll'], missing: ['red', 'circle-minus', 'Mangler'],
  rejected: ['red', 'circle-x', 'Avvist'], expired: ['red', 'circle-x', 'Utløpt'],
};
const reqBadge = st => { const [c, i, t] = REQ_STATUS[st]; return `<span class="badge ${c}">${icon(i)}${t}</span>`; };

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1]);
    r.onerror = () => reject(new Error('Kunne ikke lese filen'));
    r.readAsDataURL(file);
  });
}

// ------------------------------------------------------------------------ bunnark

function openSheet(html, onMount) {
  closeSheet(true);
  $sheetRoot.innerHTML = `<div class="overlay"></div><div class="sheet" role="dialog" aria-modal="true"><div class="grabber"></div><div class="inner">${html}</div></div>`;
  const overlay = $sheetRoot.querySelector('.overlay'), sheet = $sheetRoot.querySelector('.sheet');
  requestAnimationFrame(() => { overlay.classList.add('show'); sheet.classList.add('show'); });
  overlay.addEventListener('click', () => closeSheet());
  sheet.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => closeSheet()));
  document.addEventListener('keydown', escClose);
  if (onMount) onMount(sheet);
  sheet.querySelector('input:not([type=radio]):not([type=checkbox]), textarea')?.focus({ preventScroll: true });
  return sheet;
}
function escClose(e) { if (e.key === 'Escape') closeSheet(); }
function closeSheet(immediate = false) {
  document.removeEventListener('keydown', escClose);
  const overlay = $sheetRoot.querySelector('.overlay'), sheet = $sheetRoot.querySelector('.sheet');
  if (!sheet) return;
  if (immediate) { $sheetRoot.innerHTML = ''; return; }
  overlay.classList.remove('show'); sheet.classList.remove('show');
  setTimeout(() => { if (!sheet.classList.contains('show')) $sheetRoot.innerHTML = ''; }, 210);
}

// ======================================================================== ramme

async function refreshMe() { try { S.me = await api('/me'); } catch { S.me = null; } }
const role = () => S.me?.user.active_role;

function setChrome({ tabs = true, bar = false } = {}) {
  document.body.classList.toggle('has-tabs', tabs && !!S.me);
  document.body.classList.toggle('has-bar', bar);
  if (!tabs || !S.me) { $tabs.hidden = true; return; }
  const r = role();
  const items = r === 'transport'
    ? [['#/', 'package-search', 'Lastbørs'], ['#/oppdrag', 'truck', 'Mine oppdrag', S.me.awaitingConfirmation > 0], ['#/profil', 'building-2', 'Profil']]
    : r === 'shipper'
      ? [['#/', 'boxes', 'Mine laster'], ['#/legg-ut', 'circle-plus', 'Legg ut'], ['#/profil', 'building-2', 'Profil']]
      : [['#/', 'file-check', 'Dokumenter'], ['#/admin/firma', 'building-2', 'Firma'], ['#/admin/integrasjoner', 'plug', 'Integrasjoner'], ['#/profil', 'user', 'Profil']];
  const cur = (location.hash || '#/').split('?')[0];
  const section = { '#/last': '#/', '#/mine': '#/', '#/oppdrag': '#/oppdrag', '#/profil': '#/profil', '#/legg-ut': '#/legg-ut' };
  const curRoot = Object.keys(section).find(k => cur === k || cur.startsWith(k + '/')) ? section[Object.keys(section).find(k => cur === k || cur.startsWith(k + '/'))] : cur;
  $tabs.innerHTML = items.map(([href, ic, label, dot]) =>
    `<a href="${href}" class="${curRoot === href ? 'active' : ''}">${icon(ic)}<span>${label}</span>${dot ? '<span class="dot" aria-label="Venter på bekreftelse"></span>' : ''}</a>`).join('');
  $tabs.hidden = false;
}

const bell = () => `<a class="icon-btn" href="#/varsler" aria-label="Varsler${S.me?.unread ? ` (${S.me.unread} uleste)` : ''}">${icon('bell')}${S.me?.unread ? '<span class="dot"></span>' : ''}</a>`;
const head = (title, overline = '', extra = '') => `<div class="screen-head"><div>${overline ? `<div class="overline">${esc(overline)}</div>` : ''}<h1>${esc(title)}</h1></div><div class="actions-inline">${extra}${bell()}</div></div>`;
const back = (href, label = 'Tilbake') => `<a class="back" href="${href}">${icon('arrow-left')}${esc(label)}</a>`;
const empty = (ic, text) => `<div class="empty">${icon(ic)}<p>${text}</p></div>`;

// ======================================================================== onboarding

function viewWelcome() {
  setChrome({ tabs: false });
  const sim = S.integ?.brreg.mode === 'simulated';
  $app.innerHTML = `
    <div class="welcome"><div class="inner">
      <div class="logo"><span class="mark">${icon('truck')}</span>Lastbørs Norden</div>
      <div class="spacer"></div>
      <div class="overline">For hele transportnæringa</div>
      <h1>Last som står igjen, ut på veien</h1>
      <p class="lead">Legg ut last til prekvalifiserte transportører, til fastpris eller på lukket anbud. Transportører finner last som passer bilen og papirene sine.</p>
      <div class="spacer"></div>
      <div class="stack">
        <a class="btn white block" href="#/start">Kom i gang</a>
        <a class="btn outline-white block" href="#/login">Jeg har allerede konto</a>
      </div>
      ${sim ? `<div class="testmode">${icon('flask-conical', 'sm')}Testmodus: Brønnøysundregistrene simuleres</div>` : ''}
    </div></div>`;
}

function viewLogin() {
  setChrome({ tabs: false });
  const demo = [['anlegg@demo.no', 'Oppdragsgiver', 'Demo Gjenvinning AS'], ['nordtrans@demo.no', 'Transportør', 'Nordtrans AS – alle krav godkjent'],
    ['polar@demo.no', 'Transportør', 'Polar Frakt AS – uten ADR, løyve utløper snart'], ['fjordbil@demo.no', 'Transportør', 'Fjordbil Transport AS – løyve til kontroll'],
    ['admin@demo.no', 'Admin', 'Dokumentkontroll og testpanel']];
  $app.innerHTML = `<main>
    ${back('#/')}
    <h1 style="margin:12px 0 4px">Logg inn</h1>
    <p class="meta">BankID og Vipps kommer. Inntil videre logger du inn med e-post.</p>
    <form id="f">
      <label class="field"><span>E-post</span><input name="email" type="email" autocomplete="username" required></label>
      <label class="field"><span>Passord</span><input name="password" type="password" autocomplete="current-password" required></label>
      <div class="btn-row"><button class="btn block">Logg inn</button></div>
    </form>
    <h2>Demokontoer</h2>
    <p class="meta">Passord for alle: <b>demo1234</b></p>
    ${demo.map(([e, r, d]) => `<button class="card row" data-email="${e}"><div><div class="overline">${r}</div><b>${esc(d)}</b><div class="mono">${e}</div></div><span class="go"></span></button>`).join('')}
  </main>`;
  const login = async (email, password) => { await api('/auth/login', { method: 'POST', body: { email, password } }); await refreshMe(); go('#/'); };
  const f = document.getElementById('f');
  f.addEventListener('submit', e => { e.preventDefault(); busy(f.querySelector('button'), () => login(f.email.value, f.password.value))(); });
  $app.querySelectorAll('[data-email]').forEach(b => b.addEventListener('click', busy(b, () => login(b.dataset.email, 'demo1234'))));
}

/* Onboarding-veiviser: 1 Rolle → 2 Firma → 3 Dokumenter (kun transportør) → 4 Ferdig */
function wizardSteps() { return S.wizard.role === 'shipper' ? [1, 2, 4] : [1, 2, 3, 4]; }
function wizardTop(step) {
  const steps = wizardSteps(), idx = steps.indexOf(step) + 1, n = steps.length;
  const prev = { 1: '#/', 2: 1, 3: null, 4: S.wizard.role === 'transport' ? 3 : null }[step];
  const backBtn = prev === null ? '<span style="width:44px"></span>' : `<button class="icon-btn" id="wback" aria-label="Tilbake">${icon('arrow-left')}</button>`;
  return `<div class="wizard-top">${backBtn}<div class="progress"><i style="width:${(idx / n) * 100}%"></i></div><span class="meta">${idx} av ${n}</span></div>`;
}
function bindWizardBack(step) {
  document.getElementById('wback')?.addEventListener('click', () => {
    if (step === 1) go('#/'); else { S.wizard.step = step === 4 ? 3 : 1; render(); }
  });
}

async function viewWizard() {
  setChrome({ tabs: false });
  if (!S.wizard) S.wizard = { step: 1, role: null, company: null, account: {} };
  const step = S.wizard.step;
  if (step === 1) return wizardRole();
  if (step === 2) return wizardCompany();
  if (step === 3) return wizardDocs();
  return wizardDone();
}

function wizardRole() {
  $app.innerHTML = `<main>${wizardTop(1)}
    <h1 style="margin:16px 0 6px">Hva skal du bruke Lastbørs til?</h1>
    <p class="meta">Du kan legge til den andre rollen senere i Profil.</p>
    ${[['transport', 'truck', 'Jeg kjører last', 'Finn last, gi bud og ta oppdrag.'], ['shipper', 'package', 'Jeg har last som skal ut', 'Legg ut last til fastpris eller på anbud.']]
      .map(([r, ic, t, d]) => `<button class="card role-card ${S.wizard.role === r ? 'selected' : ''}" data-role="${r}"><span class="icon-wrap">${icon(ic)}</span><span style="flex:1"><h3>${t}</h3><span class="meta">${d}</span></span><span class="go"></span></button>`).join('')}
  </main>`;
  bindWizardBack(1);
  $app.querySelectorAll('[data-role]').forEach(b => b.addEventListener('click', () => { S.wizard.role = b.dataset.role; S.wizard.step = 2; render(); }));
}

function wizardCompany() {
  const w = S.wizard, c = w.company, a = w.account;
  const sim = S.integ?.brreg.mode === 'simulated';
  const testBox = sim && S.integ.testOrgnrs.length ? `<div class="test-box">
      <div class="row-start">${icon('flask-conical', 'sm')}<b>Testmodus – Brønnøysund er simulert</b></div>
      <p class="micro" style="margin:4px 0 0">Trykk på et testnummer for å se hvordan appen reagerer:</p>
      <div class="chips">${S.integ.testOrgnrs.filter(t => !['912345688', '923456783', '934567897', '910000004'].includes(t.orgnr)).map(t =>
        `<button type="button" data-test="${t.orgnr}"><span class="mono">${t.orgnr}</span> ${esc(t.outcome)}</button>`).join('')}</div>
      <p class="micro" style="margin:8px 0 0">Andre gyldige org.nr gir et generert testfirma. Feil og nedetid simuleres under Admin › Integrasjoner.</p>
    </div>` : '';
  $app.innerHTML = `<main>${wizardTop(2)}
    <h1 style="margin:16px 0 6px">Firmaet ditt</h1>
    <p class="meta">Vi henter firmaopplysningene fra Brønnøysundregistrene.</p>
    <form id="lookup">
      <label class="field"><span>Organisasjonsnummer</span>
        <div class="row-start"><input name="orgnr" inputmode="numeric" autocomplete="off" placeholder="9 siffer" value="${esc(w.orgnr || '')}" maxlength="11" required>
        <button class="btn sm" id="lookbtn">${icon('search', 'sm')}Søk</button></div></label>
      <div id="lookup-msg"></div>
    </form>
    ${testBox}
    <div id="company">${c ? companyCard(c) : ''}</div>
    <form id="acct" ${c ? '' : 'hidden'}>
      <h2>Din konto</h2>
      <label class="field"><span>Navn</span><input name="name" autocomplete="name" value="${esc(a.name)}" required></label>
      <label class="field"><span>E-post</span><input name="email" type="email" autocomplete="email" value="${esc(a.email)}" required></label>
      <label class="field"><span>Mobil</span><input name="phone" type="tel" autocomplete="tel" placeholder="911 08 150" value="${esc(a.phone)}"></label>
      <label class="field"><span>Passord</span><input name="password" type="password" minlength="8" autocomplete="new-password" required><div class="hint">Minst 8 tegn</div></label>
      <div class="btn-row"><button class="btn block" id="next" disabled>Fortsett</button></div>
    </form>
  </main>`;
  bindWizardBack(2);
  const lookup = document.getElementById('lookup'), acct = document.getElementById('acct'), msg = document.getElementById('lookup-msg');
  const nextBtn = document.getElementById('next');
  const validate = () => { nextBtn.disabled = !(S.wizard.company && acct.checkValidity()); };
  acct.addEventListener('input', validate);

  async function doLookup() {
    const orgnr = lookup.orgnr.value.replace(/\s/g, '');
    w.orgnr = orgnr; w.company = null; msg.innerHTML = '';
    document.getElementById('company').innerHTML = `<div class="card tint row-start">${icon('clock')}<span>Søker i Brønnøysundregistrene …</span></div>`;
    acct.hidden = true;
    try {
      const r = await api(`/companies/lookup?orgnr=${encodeURIComponent(orgnr)}`);
      if (r.alreadyRegistered) {
        document.getElementById('company').innerHTML = '';
        msg.innerHTML = `<div class="banner warn">${icon('circle-minus')}<div><b>${esc(r.name)} er allerede registrert</b>Be en kollega om tilgang, eller <a href="#/login">logg inn</a>.</div></div>`;
        return;
      }
      if (r.konkurs || r.underAvvikling) {
        document.getElementById('company').innerHTML = '';
        msg.innerHTML = `<div class="banner err">${icon('ban')}<div><b>${esc(r.name)} kan ikke registreres</b>Firmaet er ${r.konkurs ? 'under konkursbehandling' : 'under avvikling'} ifølge Enhetsregisteret.</div></div>`;
        return;
      }
      w.company = r;
      document.getElementById('company').innerHTML = companyCard(r);
      acct.hidden = false; validate();
    } catch (err) {
      document.getElementById('company').innerHTML = '';
      if (['timeout', 'unavailable'].includes(err.code)) {
        msg.innerHTML = `<div class="banner warn">${icon('clock')}<div><b>Brønnøysundregistrene svarer ikke</b>${esc(err.message)}. Prøv igjen, eller fortsett uten verifisering. Du kan verifisere firmaet senere i Profil.</div></div>
          <label class="field"><span>Firmanavn</span><input id="fallback" placeholder="Firmanavn AS"></label>
          <div class="btn-row"><button type="button" class="btn secondary" id="retry">${icon('refresh-cw', 'sm')}Prøv igjen</button><button type="button" class="btn" id="unverified">Fortsett uten verifisering</button></div>`;
        document.getElementById('retry').addEventListener('click', doLookup);
        document.getElementById('unverified').addEventListener('click', () => {
          const name = document.getElementById('fallback').value.trim();
          if (!name) return toast('Skriv inn firmanavnet', true);
          w.company = { orgnr, name, unverified: true };
          document.getElementById('company').innerHTML = companyCard(w.company);
          msg.innerHTML = ''; acct.hidden = false; validate();
        });
      } else {
        msg.innerHTML = `<div class="err-text">${esc(err.message)}</div>`;
      }
    }
  }
  lookup.addEventListener('submit', e => { e.preventDefault(); busy(document.getElementById('lookbtn'), doLookup)(); });
  $app.querySelectorAll('[data-test]').forEach(b => b.addEventListener('click', () => { lookup.orgnr.value = b.dataset.test; busy(document.getElementById('lookbtn'), doLookup)(); }));
  acct.addEventListener('submit', e => {
    e.preventDefault();
    busy(nextBtn, async () => {
      const d = Object.fromEntries(new FormData(acct));
      w.account = { name: d.name, email: d.email, phone: d.phone };
      const r = await api('/auth/register', { method: 'POST', body: { role: w.role, orgnr: w.company.orgnr, fallbackName: w.company.unverified ? w.company.name : undefined, user: d } });
      w.verified = r.verified;
      await refreshMe();
      w.step = w.role === 'transport' ? 3 : 4;
      render();
    })();
  });
}

function companyCard(c) {
  return `<div class="card company-card">
    <div class="row"><div><div class="overline">${c.unverified ? 'Ikke verifisert' : 'Funnet i Enhetsregisteret'}</div><h3>${esc(c.name)}</h3></div>
    ${c.unverified ? `<span class="badge amber">${icon('clock')}Uverifisert</span>` : `<span class="badge green">${icon('shield-check')}Verifisert</span>`}</div>
    <div class="meta"><span class="mono">${esc(c.orgnr)}</span>${c.municipality ? ` · ${esc(c.municipality)}` : ''}${c.naering ? ` · ${esc(c.naering)}` : ''}</div>
    ${c.address ? `<div class="meta">${esc(c.address)}${c.postalCode ? `, ${esc(c.postalCode)} ${esc(c.city || '')}` : ''}</div>` : ''}
    ${c.source === 'simulated' ? `<div class="micro" style="margin-top:6px">${icon('flask-conical', 'sm')} Simulert svar</div>` : ''}
  </div>`;
}

function docRow(d) {
  const sub = d.doc
    ? d.status === 'rejected' ? `Avvist: ${esc(d.doc.reject_reason)}` : d.doc.valid_until ? `Gyldig til ${dDay(d.doc.valid_until)}` : 'Lastet opp'
    : d.required ? 'Påkrevd for alle laster' : `Låser opp: ${esc(d.unlocks.toLowerCase())}`;
  const primary = ['missing', 'rejected', 'expired'].includes(d.status);
  return `<div class="card"><div class="row"><div><h3>${esc(d.name)}</h3><div class="meta">${sub}</div></div>${reqBadge(d.status)}</div>
    <div class="btn-row" style="margin-top:10px"><button class="btn sm ${primary ? '' : 'secondary'}" data-upload="${d.key}">${icon(d.doc ? 'repeat' : 'upload', 'sm')}${d.doc ? 'Last opp ny' : 'Last opp'}</button></div></div>`;
}

function uploadSheet(key, onDone) {
  const t = S.meta.reqTypes.find(x => x.key === key);
  openSheet(`<h2>${esc(t.name)}</h2><p class="meta">${esc(t.description)}</p>
    <form id="up">
      <label class="field"><span>Dokument</span><input type="file" name="file" accept="application/pdf,image/*" required>
        <div class="hint">PDF eller bilde, maks 5 MB. På mobil kan du ta bilde med kameraet.</div></label>
      <div class="grid2">
        <label class="field"><span>Dokumentnummer</span><input name="reference" placeholder="Valgfritt"></label>
        <label class="field"><span>Gyldig til</span><input name="valid_until" type="date"></label>
      </div>
      <div class="btn-row"><button class="btn block">${icon('upload', 'sm')}Send til kontroll</button></div>
      <p class="micro" style="text-align:center;margin-top:8px">Vi kontrollerer dokumentet innen én arbeidsdag.</p>
    </form>`, sheet => {
    const f = sheet.querySelector('#up');
    f.addEventListener('submit', e => {
      e.preventDefault();
      busy(f.querySelector('button'), async () => {
        const file = f.file.files[0];
        if (!file) throw new Error('Velg en fil');
        if (file.size > 5 * 1024 * 1024) throw new Error('Filen er større enn 5 MB');
        await api(`/me/qualifications/${key}`, { method: 'PUT', body: {
          reference: f.reference.value, valid_until: f.valid_until.value || null,
          file: { name: file.name, type: file.type || 'application/pdf', data: await fileToBase64(file) },
        } });
        closeSheet(); toast(`${t.name} er sendt til kontroll`); await refreshMe(); onDone();
      })();
    });
  });
}

function wizardDocs() {
  const q = S.me.qualification;
  const lovUploaded = q.docs.find(d => d.key === 'lov').status !== 'missing';
  $app.innerHTML = `<main>${wizardTop(3)}
    <h1 style="margin:16px 0 6px">Dokumenter</h1>
    <p class="meta">Løyve er påkrevd. Avfallstransportør og ADR låser opp flere laster. Du kan laste opp resten senere.</p>
    ${q.docs.map(d => docRow(d)).join('')}
    <div class="btn-row"><button class="btn block" id="next" ${lovUploaded ? '' : 'disabled'}>Fortsett</button></div>
    ${lovUploaded ? '' : '<p class="micro" style="text-align:center">Last opp løyve for å fortsette</p>'}
  </main>`;
  $app.querySelectorAll('[data-upload]').forEach(b => b.addEventListener('click', () => uploadSheet(b.dataset.upload, render)));
  document.getElementById('next').addEventListener('click', () => { S.wizard.step = 4; render(); });
}

function wizardDone() {
  const t = S.wizard.role === 'transport';
  const c = S.me.company;
  $app.innerHTML = `<main>${wizardTop(4)}
    <div style="text-align:center;margin:28px 0 18px"><span class="badge green" style="padding:10px">${icon('circle-check')}</span></div>
    <h1 style="text-align:center">${t ? 'Du er klar' : 'Firmaet er registrert'}</h1>
    <p class="meta" style="text-align:center;margin:8px 0 18px">${t ? 'Du kan se lastbørsen nå. Du kan gi bud og ta last når løyvet er godkjent.' : c.verified ? 'Du kan legge ut den første lasten nå.' : 'Firmaet må verifiseres i Brønnøysundregistrene før du kan legge ut last. Det gjør du i Profil.'}</p>
    <div class="card">
      <div class="kv"><span>Firma</span><span>${esc(c.name)}</span></div>
      <div class="kv"><span>Org.nr</span><span class="mono">${esc(c.orgnr)}</span></div>
      <div class="kv"><span>Rolle</span><span>${t ? 'Transportør' : 'Oppdragsgiver'}</span></div>
      <div class="kv"><span>Brønnøysund</span><span>${c.verified ? 'Verifisert' : 'Ikke verifisert'}</span></div>
      ${t ? `<div class="kv"><span>Dokumenter</span><span>${S.me.qualification.docs.filter(d => d.status !== 'missing').length} av 3 lastet opp</span></div>` : ''}
    </div>
    <div class="btn-row"><button class="btn block" id="done">${t ? 'Til lastbørsen' : 'Legg ut første last'}</button></div>
  </main>`;
  document.getElementById('done').addEventListener('click', () => { S.wizard = null; go(t ? '#/' : '#/legg-ut'); });
}

// ======================================================================== transportør

function loadCard(l) {
  const right = l.price_mode === 'fixed'
    ? `<div class="price">${kr(l.fixed_price)}</div><span class="micro">eks. mva</span>`
    : `<div class="price" style="font-size:16px">${esc(timeLeft(l.bid_deadline))}</div><span class="micro">${l.bid_count} bud</span>`;
  const tags = [
    l.price_mode === 'fixed' ? '<span class="badge green">Fastpris</span>' : '<span class="badge blue">Anbud</span>',
    `<span class="badge">${esc(l.category)}</span>`,
    !l.qualified ? `<span class="badge red">${icon('ban')}${esc(l.locked_text)}</span>` : '',
    l.my_bid && l.my_bid.status === 'active' ? `<span class="badge navy">Bud gitt ${kr(l.my_bid.amount)}</span>` : '',
  ].join('');
  return `<a class="card load" href="#/last/${l.id}" style="${l.qualified ? '' : 'opacity:.82'}">
    ${tile(l)}
    <div class="body">
      <div class="load-title">${esc(l.type)}</div>
      <div class="route-line">${esc(l.from_city)} <span class="go sm"></span> ${esc(l.to_city)}</div>
      <div class="meta"><span class="mono">${esc(l.ref)}</span> · ${tonn(l.weight_t)}${l.distance_km ? ` · ${l.distance_km} km` : ''} · ${dRange(l.pickup_start, l.pickup_end)}</div>
      <div class="chips">${tags}</div>
    </div>
    <div class="right">${right}</div>
  </a>`;
}

function prequalBanner() {
  const q = S.me.qualification;
  let html = '';
  if (!q.canBid || q.approvedCount < 3) {
    const lov = q.docs.find(d => d.key === 'lov').status;
    const text = q.canBid
      ? `${q.approvedCount} av 3 godkjent. Last opp flere dokumenter for å låse opp flere laster.`
      : lov === 'pending' ? 'Løyvet er til kontroll. Du kan gi bud når det er godkjent.' : 'Last opp løyve for å gi bud og ta last.';
    html += `<a class="prequal" href="#/profil"><div class="overline">Prekvalifisering</div>
      <div class="row"><h3>${q.canBid ? 'Lås opp flere laster' : 'Du kan ikke by ennå'}</h3><span class="go"></span></div>
      <div class="progress"><i style="width:${(q.approvedCount / 3) * 100}%"></i></div><div class="micro" style="color:rgba(255,255,255,.8)">${text}</div></a>`;
  }
  for (const e of q.expiring) html += `<div class="banner warn">${icon('clock')}<div><b>${esc(e.name)} utløper ${dDay(e.valid_until)}</b><a href="#/profil">Last opp nytt dokument</a></div></div>`;
  if (S.me.company.blocked) html = `<div class="banner err">${icon('ban')}<div><b>Firmaet er sperret</b>${esc(S.me.company.blocked_reason || '')}</div></div>` + html;
  return html;
}

let map = null;
async function viewLastbors() {
  setChrome();
  const st = S.lastbors;
  const loads = await api(`/loads?filter=${st.filter}`);
  S.loadsCache = loads;
  const filters = [['all', 'Alle'], ['eligible', 'Kan kjøre'], ['fixed', 'Fastpris'], ['bid', 'Anbud'], ['waste', 'Avfall']];
  const seg = `<div class="seg" style="width:120px"><button class="${st.view === 'list' ? 'on' : ''}" data-view="list" aria-label="Liste">${icon('list')}</button><button class="${st.view === 'map' ? 'on' : ''}" data-view="map" aria-label="Kart">${icon('map')}</button></div>`;
  const shown = st.view === 'map' && st.pin ? loads.filter(l => l.from_city === st.pin) : loads;
  $app.innerHTML = `<main>
    ${head('Lastbørs', `${loads.length} ledige laster`, seg)}
    ${prequalBanner()}
    <div class="filters">${filters.map(([k, t]) => `<button class="filter ${st.filter === k ? 'on' : ''}" data-filter="${k}">${t}</button>`).join('')}</div>
    ${st.view === 'map' ? `<div id="map" class="map"></div>${st.pin ? `<button class="btn ghost sm" id="clearpin">${icon('x', 'sm')}Vis alle laster</button>` : '<p class="micro">Trykk på en nål for å se lastene derfra.</p>'}` : ''}
    <div id="list">${shown.length ? shown.map(loadCard).join('') : empty('package-search', st.filter === 'eligible' ? 'Ingen laster du kan kjøre akkurat nå. Du får varsel når nye legges ut.' : 'Ingen ledige laster med dette filteret.')}</div>
    ${!S.me.user.show_locked ? '<p class="micro" style="text-align:center">Laster du ikke kan kjøre, er skjult. Endres i Profil.</p>' : ''}
  </main>`;
  $app.querySelectorAll('[data-filter]').forEach(b => b.addEventListener('click', () => { st.filter = b.dataset.filter; st.pin = null; render(); }));
  $app.querySelectorAll('[data-view]').forEach(b => b.addEventListener('click', () => { st.view = b.dataset.view; st.pin = null; render(); }));
  document.getElementById('clearpin')?.addEventListener('click', () => { st.pin = null; render(); });
  if (st.view === 'map') drawMap(loads);
}

function drawMap(loads) {
  if (map) { map.remove(); map = null; }
  if (!window.L) { document.getElementById('map').innerHTML = empty('map', 'Kartet kunne ikke lastes.'); return; }
  map = L.map('map', { zoomControl: true, attributionControl: true });
  L.tileLayer('https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/{z}/{y}/{x}.png', {
    maxZoom: 17, attribution: '© <a href="https://www.kartverket.no/">Kartverket</a>',
  }).addTo(map);
  // Laster fra samme sted samles i én nål, så de ikke ligger oppå hverandre
  const groups = new Map();
  for (const l of loads.filter(x => x.from_geo)) {
    if (!groups.has(l.from_city)) groups.set(l.from_city, []);
    groups.get(l.from_city).push(l);
  }
  const pts = [];
  for (const [city, ls] of groups) {
    const label = ls.length > 1 ? `${ls.length} laster` : ls[0].price_mode === 'fixed' ? kr(ls[0].fixed_price) : 'Anbud';
    const cls = S.lastbors.pin === city ? 'on' : ls.some(l => l.qualified) ? '' : 'locked';
    const m = L.marker(ls[0].from_geo, { icon: L.divIcon({ className: '', html: `<span class="pin ${cls}">${esc(city)} · ${esc(label)}</span>`, iconSize: [0, 0] }) }).addTo(map);
    m.on('click', () => { S.lastbors.pin = city; render(); });
    pts.push(ls[0].from_geo);
  }
  if (pts.length) map.fitBounds(pts, { padding: [40, 40], maxZoom: 9 }); else map.setView([65, 15], 4);
}

async function viewLoad(id) {
  setChrome({ tabs: false, bar: true });
  const l = await api(`/loads/${id}`);
  if (l.assignment && ['awaiting_confirmation', 'confirmed'].includes(l.assignment.status)) return go(`#/oppdrag/${l.assignment.id}`);
  const vehicles = await api('/me/vehicles');
  const reqs = l.requirements;
  const missing = reqs.filter(r => r.status !== 'approved');
  const facts = [['Vekt', tonn(l.weight_t)], ['Enheter', l.units || '–'], ['Utstyr', l.equipment || '–'], ['Avstand', l.distance_km ? `ca. ${l.distance_km} km` : '–']];
  if (l.price_mode === 'bid' && l.max_price) facts.push(['Makspris', kr(l.max_price)]);

  let bar;
  const takenByOther = l.taken_by_other || l.status !== 'open';
  if (takenByOther) {
    bar = `<div><b>Lasten er ikke lenger ledig</b><div class="micro">${l.my_bid?.status === 'lost' ? 'Budet ditt ble ikke valgt' : 'Tildelt en annen transportør'}</div></div>`;
  } else if (l.blocked) {
    bar = `<div><b>Firmaet er sperret</b><div class="micro">Kontakt Lastbørs Norden</div></div>`;
  } else if (missing.length) {
    const first = missing[0];
    bar = `<div><div class="micro">${esc(l.locked_text)}</div><div class="price">${l.price_mode === 'fixed' ? kr(l.fixed_price) : 'Anbud'}</div></div>
      <a class="btn" href="#/profil">${icon('upload', 'sm')}${first.status === 'pending' ? 'Til kontroll' : `Last opp ${esc(first.short)}`}</a>`;
  } else if (l.price_mode === 'fixed') {
    bar = `<div><div class="micro">Fastpris eks. mva</div><div class="price">${kr(l.fixed_price)}</div></div><button class="btn" id="take">Ta lasten</button>`;
  } else {
    const mine = l.my_bid && l.my_bid.status === 'active' ? l.my_bid : null;
    bar = `<div><div class="micro">${mine ? 'Ditt bud' : `Frist ${dFull(l.bid_deadline)}`}</div><div class="price">${mine ? kr(mine.amount) : esc(timeLeft(l.bid_deadline))}</div>${mine ? `<div class="micro">${esc(timeLeft(l.bid_deadline))}</div>` : ''}</div>
      <button class="btn" id="bid">${mine ? 'Endre bud' : 'Gi bud'}</button>`;
  }

  $app.innerHTML = `<main>
    ${back('#/', 'Lastbørs')}
    <div class="detail-head">${tile(l, 'lg')}<div><div class="overline">${esc(l.category)}</div><h1 style="font-size:24px">${esc(l.type)}</h1>
      <div class="row-start" style="margin-top:4px"><span class="mono">${esc(l.ref)}</span>${l.price_mode === 'fixed' ? '<span class="badge green">Fastpris</span>' : `<span class="badge blue">Anbud · ${l.bid_count} bud</span>`}</div></div></div>
    <div class="card"><div class="route">
      <div class="stop"><span class="micro">Hentes</span><b>${esc(l.from_site)}</b><span class="meta">${esc(l.from_city)} · ${dWindow(l.pickup_start, l.pickup_end)}</span></div>
      <div class="stop to"><span class="micro">Leveres</span><b>${esc(l.to_site)}</b><span class="meta">${esc(l.to_city)}${l.deliver_by ? ` · innen ${dFull(l.deliver_by)}` : ''}</span></div>
    </div></div>
    <dl class="facts">${facts.map(([k, v]) => `<div><dt>${k}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>
    ${l.description ? `<p style="margin-top:14px;white-space:pre-wrap">${esc(l.description)}</p>` : ''}
    <h2>Krav</h2>
    <div class="card">${reqs.map(r => `<div class="req">${icon('file-check')}<span class="grow">${esc(r.name)}</span>${reqBadge(r.status)}</div>`).join('')}</div>
    <h2>Oppdragsgiver</h2>
    <div class="card row"><div><b>${esc(l.shipper.name)}</b><div class="meta">${esc(l.shipper.municipality || '')}</div></div>
      ${l.shipper.verified ? `<span class="badge green">${icon('shield-check')}Verifisert</span>` : '<span class="badge amber">Ikke verifisert</span>'}</div>
  </main>
  <div class="bottom-bar"><div class="inner">${bar}</div></div>`;

  document.getElementById('take')?.addEventListener('click', () => takeSheet(l, vehicles));
  document.getElementById('bid')?.addEventListener('click', () => bidSheet(l, vehicles));
}

function vehiclePicker(vehicles, selected) {
  if (!vehicles.length) return `<div class="banner info">${icon('truck')}<div>Du har ikke registrert kjøretøy. <a href="#/profil" data-close>Legg til i Profil</a> (valgfritt).</div></div>`;
  const sel = selected ?? vehicles[0].id;
  return `<div class="field"><span style="display:block;font-size:14px;font-weight:600;color:var(--text-heading);margin-top:14px">Kjøretøy</span>
    ${vehicles.map(v => `<label class="vehicle-opt"><input type="radio" name="vehicle" value="${v.id}" ${v.id === sel ? 'checked' : ''}>
      <span style="flex:1"><b>${esc(v.name)}</b><span class="meta" style="display:block">${esc(v.reg_no)}${v.payload_t ? ` · ${String(v.payload_t).replace('.', ',')} t` : ''}${v.equipment.length ? ' · ' + esc(v.equipment.join(', ')) : ''}</span></span></label>`).join('')}</div>`;
}

function takeSheet(l, vehicles) {
  openSheet(`<div class="overline">Fastpris</div><h2>Ta lasten?</h2>
    <div class="card tint">
      <div class="kv"><span>Last</span><span>${esc(l.type)}</span></div>
      <div class="kv"><span>Rute</span><span>${esc(l.from_city)} – ${esc(l.to_city)}</span></div>
      <div class="kv"><span>Henting</span><span>${dRange(l.pickup_start, l.pickup_end)}</span></div>
      <div class="kv"><span>Pris eks. mva</span><span>${kr(l.fixed_price)}</span></div>
    </div>
    ${vehiclePicker(vehicles)}
    <p class="micro" style="margin-top:12px">Oppdraget er bindende og bekreftet med en gang.</p>
    <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn accent" id="confirm">Ta lasten</button></div>`, sheet => {
    const btn = sheet.querySelector('#confirm');
    btn.addEventListener('click', busy(btn, async () => {
      const v = sheet.querySelector('input[name=vehicle]:checked')?.value;
      try {
        const a = await api(`/loads/${l.id}/take`, { method: 'POST', body: { vehicleId: v ? Number(v) : null } });
        closeSheet(); toast('Oppdraget er ditt – bekreftelse sendt'); await refreshMe(); go(`#/oppdrag/${a.id}`);
      } catch (err) {
        if (err.code === 'taken') { closeSheet(); toast('Lasten er allerede tatt', true); go('#/'); return; }
        throw err;
      }
    }));
  });
}

function bidSheet(l, vehicles) {
  const mine = l.my_bid && l.my_bid.status === 'active' ? l.my_bid : null;
  openSheet(`<div class="overline">Lukket anbud · ${esc(timeLeft(l.bid_deadline))}</div><h2>${mine ? 'Endre bud' : 'Gi bud'}</h2>
    <p class="meta">${esc(l.type)}, ${esc(l.from_city)} – ${esc(l.to_city)}. Andre transportører ser ikke beløpet ditt.</p>
    <form id="bf" novalidate>
      <label class="field"><span>Beløp eks. mva (kr)</span><input class="big" name="amount" inputmode="numeric" autocomplete="off" placeholder="0" value="${mine ? mine.amount : ''}"></label>
      ${l.max_price ? `<div class="hint">Makspris: ${kr(l.max_price)}</div>` : ''}
      <div class="err-text" id="amount-err" hidden></div>
      ${vehiclePicker(vehicles, mine?.vehicle_id)}
      <p class="micro" style="margin-top:12px">Budet er bindende til fristen går ut ${dFull(l.bid_deadline)}. Du kan endre det før fristen.</p>
      <div class="btn-row">${mine ? '<button type="button" class="btn danger" id="withdraw">Trekk bud</button>' : '<button type="button" class="btn secondary" data-close>Avbryt</button>'}<button class="btn" id="send">Send bud</button></div>
    </form>`, sheet => {
    const f = sheet.querySelector('#bf'), amountErr = sheet.querySelector('#amount-err'), send = sheet.querySelector('#send');
    const parse = () => Number(String(f.amount.value).replace(/[\s ]/g, ''));
    const check = () => {
      const n = parse();
      let msg = '';
      if (!f.amount.value.trim()) msg = 'Skriv inn et beløp';
      else if (!Number.isInteger(n) || n <= 0) msg = 'Beløpet må være et helt tall i kroner';
      else if (l.max_price && n > l.max_price) msg = `Budet kan ikke være over makspris ${kr(l.max_price)}`;
      amountErr.textContent = msg; amountErr.hidden = !msg; send.disabled = !!msg;
      return !msg;
    };
    f.amount.addEventListener('input', check);
    if (!mine) send.disabled = true; else check();
    f.addEventListener('submit', e => {
      e.preventDefault();
      if (!check()) return;
      busy(send, async () => {
        const v = f.querySelector('input[name=vehicle]:checked')?.value;
        await api(`/loads/${l.id}/bid`, { method: 'PUT', body: { amount: parse(), vehicleId: v ? Number(v) : null } });
        closeSheet(); toast(`Bud på ${kr(parse())} er sendt`); render();
      })();
    });
    sheet.querySelector('#withdraw')?.addEventListener('click', busy(sheet.querySelector('#withdraw'), async () => {
      await api(`/loads/${l.id}/bid`, { method: 'DELETE' });
      closeSheet(); toast('Budet er trukket'); render();
    }));
  });
}

function assignmentCard(a) {
  const waiting = a.status === 'awaiting_confirmation';
  return `<a class="card load ${waiting ? 'alert' : ''}" href="#/oppdrag/${a.id}">
    ${tile(a.load)}
    <div class="body"><div class="load-title">${esc(a.load.type)}</div>
      <div class="route-line">${esc(a.load.from_city)} <span class="go sm"></span> ${esc(a.load.to_city)}</div>
      <div class="meta"><span class="mono">${esc(a.load.ref)}</span> · ${dRange(a.load.pickup_start, a.load.pickup_end)} · ${tonn(a.load.weight_t)}</div>
      <div class="chips">${waiting ? `<span class="badge red">${icon('clock')}Bekreft innen ${dTime(a.confirm_by)}</span>` : a.load.status === 'completed' ? '<span class="badge green">Levert</span>' : `<span class="badge green">${icon('circle-check')}Bekreftet</span>`}</div>
    </div>
    <div class="right"><div class="price">${kr(a.price)}</div><span class="micro">${a.source === 'fixed' ? 'Fastpris' : 'Anbud'}</span></div>
  </a>`;
}

async function viewOppdrag() {
  setChrome();
  const tab = S.oppdragTab;
  const items = await api(`/me/assignments?tab=${tab}`);
  const tabs = [['active', 'Aktive', S.me.awaitingConfirmation > 0], ['bids', 'Bud'], ['done', 'Fullført']];
  let list;
  if (tab === 'bids') {
    list = items.length ? items.map(b => loadCard(b.load).replace('<div class="chips">', `<div class="chips">${b.status === 'lost' ? '<span class="badge red">Tapt</span>' : '<span class="badge amber">Venter</span>'}`)).join('')
      : empty('package-search', 'Du har ingen aktive bud.');
  } else {
    list = items.length ? items.map(assignmentCard).join('') : empty('truck', tab === 'active' ? 'Ingen aktive oppdrag. Finn last i lastbørsen.' : 'Ingen fullførte oppdrag ennå.');
  }
  $app.innerHTML = `<main>
    ${head('Mine oppdrag')}
    <div class="seg" style="margin-bottom:14px">${tabs.map(([k, t, dot]) => `<button class="${tab === k ? 'on' : ''}" data-tab="${k}">${t}${dot ? '<span class="badge red" style="padding:0 6px;min-width:8px;height:8px"></span>' : ''}</button>`).join('')}</div>
    ${list}
  </main>`;
  $app.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => { S.oppdragTab = b.dataset.tab; render(); }));
}

async function viewAssignment(id) {
  setChrome({ tabs: false, bar: true });
  const list = [...await api('/me/assignments?tab=active'), ...await api('/me/assignments?tab=done')];
  const a = list.find(x => x.id === Number(id));
  if (!a) { $app.innerHTML = `<main>${back('#/oppdrag', 'Mine oppdrag')}${empty('truck', 'Fant ikke oppdraget. Det kan ha utløpt eller blitt avlyst.')}</main>`; setChrome(); return; }
  const waiting = a.status === 'awaiting_confirmation';
  const done = a.load.status === 'completed';
  const rows = [['Pris eks. mva', kr(a.price)], ['Fra', place(a.load.from_site, a.load.from_city)], ['Til', place(a.load.to_site, a.load.to_city)],
    ['Henting', dWindow(a.load.pickup_start, a.load.pickup_end)],
    ['Levering', a.load.deliver_by ? `innen ${dFull(a.load.deliver_by)}` : '–'], ['Kjøretøy', a.vehicle ? `${a.vehicle.name} (${a.vehicle.reg_no})` : 'Ikke valgt'],
    ['Kontakt', `${a.shipper.contact || a.shipper.name}${a.shipper.phone ? ', ' + a.shipper.phone : ''}`]];
  $app.innerHTML = `<main>
    ${back('#/oppdrag', 'Mine oppdrag')}
    <div class="won-head" style="${done ? 'background:var(--mg-blue-800)' : ''}">
      <div class="overline">${esc(a.load.ref)} · ${a.source === 'fixed' ? 'Fastpris' : 'Anbud'}</div>
      <h1>${done ? 'Levert' : waiting ? 'Du har fått oppdraget' : 'Oppdraget er bekreftet'}</h1>
      <p style="margin:6px 0 0">${waiting ? `Bekreft innen ${dFull(a.confirm_by)} (${esc(timeLeft(a.confirm_by))}). Ellers går oppdraget til neste budgiver.` : esc(a.load.type)}</p>
    </div>
    <div class="card">${rows.map(([k, v]) => `<div class="kv"><span>${k}</span><span>${esc(v)}</span></div>`).join('')}</div>
    ${a.shipper.phone ? `<a class="btn secondary block" href="tel:${esc(a.shipper.phone.replace(/\s/g, ''))}">Ring ${esc(a.shipper.phone)}</a>` : ''}
  </main>
  ${waiting ? `<div class="bottom-bar"><div class="inner" style="gap:10px"><button class="btn danger" id="decline">Avslå</button><button class="btn accent" id="confirm" style="flex:1">Bekreft oppdraget</button></div></div>`
    : !done ? `<div class="bottom-bar"><div class="inner"><button class="btn block" id="complete">${icon('check', 'sm')}Merk som levert</button></div></div>` : ''}`;
  if (done) document.body.classList.remove('has-bar');
  const c = document.getElementById('confirm');
  c?.addEventListener('click', busy(c, async () => { await api(`/assignments/${a.id}/confirm`, { method: 'POST' }); toast('Oppdraget er bekreftet'); await refreshMe(); render(); }));
  document.getElementById('decline')?.addEventListener('click', () => openSheet(`<h2>Avslå oppdraget?</h2>
    <p class="meta">Avslag etter tildeling registreres på firmaprofilen. Oppdragsgiver kan da tildele neste budgiver.</p>
    <label class="field"><span>Årsak (valgfritt)</span><textarea id="reason" placeholder="F.eks. bilen er på verksted"></textarea></label>
    <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn danger" id="yes">Avslå oppdraget</button></div>`, sheet => {
    const y = sheet.querySelector('#yes');
    y.addEventListener('click', busy(y, async () => {
      await api(`/assignments/${a.id}/decline`, { method: 'POST', body: { reason: sheet.querySelector('#reason').value } });
      closeSheet(); toast('Oppdraget er avslått'); await refreshMe(); go('#/oppdrag');
    }));
  }));
  const cp = document.getElementById('complete');
  cp?.addEventListener('click', busy(cp, async () => { await api(`/assignments/${a.id}/complete`, { method: 'POST' }); toast('Lasten er meldt levert'); S.oppdragTab = 'done'; go('#/oppdrag'); }));
}

// ======================================================================== oppdragsgiver

const SHIPPER_BADGE = { Ledig: 'blue', Tatt: 'green', Tildelt: 'amber', Bekreftet: 'green', Fullført: 'green', Avlyst: 'red' };

async function viewMineLaster() {
  setChrome();
  const loads = await api('/shipper/loads');
  const c = S.me.company;
  $app.innerHTML = `<main>
    ${head('Mine laster', c.name)}
    ${!c.verified ? `<div class="banner warn">${icon('shield-check')}<div><b>Firmaet er ikke verifisert</b>Du må verifisere firmaet i Brønnøysundregistrene før du kan legge ut last. <a href="#/profil">Gå til Profil</a></div></div>` : ''}
    <a class="card row" href="#/legg-ut" style="background:var(--surface-tint-blue);border-color:var(--mg-blue-600)">
      <div class="row-start"><span class="icon-btn" style="background:var(--mg-blue-600);color:#fff;border:0">${icon('circle-plus')}</span><div><h3>Legg ut en last</h3><span class="meta">Fastpris eller lukket anbud</span></div></div><span class="go"></span></a>
    ${loads.length ? loads.map(l => {
      const badge = SHIPPER_BADGE[l.status_text] || 'navy';
      const right = l.price_mode === 'fixed' ? `<div class="price">${kr(l.fixed_price)}</div><span class="micro">Fastpris</span>`
        : l.lowest_bid ? `<div class="price">${kr(l.lowest_bid)}</div><span class="micro">Laveste bud</span>` : `<span class="micro">${l.deadline_passed ? 'Frist ute' : esc(timeLeft(l.bid_deadline))}</span>`;
      return `<a class="card load" href="#/mine/${l.id}">${tile(l)}<div class="body">
        <div class="load-title">${esc(l.type)}</div>
        <div class="route-line">${esc(l.from_city)} <span class="go sm"></span> ${esc(l.to_city)}</div>
        <div class="meta"><span class="mono">${esc(l.ref)}</span> · ${tonn(l.weight_t)} · ${dRange(l.pickup_start, l.pickup_end)}</div>
        <div class="chips"><span class="badge ${badge}">${esc(l.status_text)}</span>${l.awarded ? `<span class="badge">${esc(l.awarded.name)}</span>` : ''}</div></div>
        <div class="right">${right}</div></a>`;
    }).join('') : empty('boxes', 'Du har ikke lagt ut noen laster ennå.')}
  </main>`;
}

async function viewShipperLoad(id) {
  setChrome({ tabs: false });
  const [l, bids] = await Promise.all([api(`/shipper/loads/${id}`), api(`/shipper/loads/${id}/bids`)]);
  let banner = '';
  const a = l.assignment;
  if (l.status === 'open' && l.price_mode === 'bid') {
    const declined = a && ['declined', 'expired'].includes(a.status);
    banner = declined
      ? `<div class="banner warn">${icon('circle-x')}<div><b>${a.status === 'declined' ? 'Transportøren avslo' : 'Transportøren bekreftet ikke i tide'}</b>Velg neste budgiver nedenfor.</div></div>`
      : `<div class="banner info">${icon('clock')}<div><b>${l.deadline_passed ? 'Anbudsfristen er ute' : `Anbud åpent · ${esc(timeLeft(l.bid_deadline))}`}</b>${bids.length} bud. Du kan tildele når som helst, og laveste bud trenger ikke vinne.</div></div>`;
  } else if (l.status === 'open') {
    banner = `<div class="banner info">${icon('clock')}<div><b>Ledig til fastpris ${kr(l.fixed_price)}</b>Første kvalifiserte transportør som tar lasten, får oppdraget.</div></div>`;
  } else if (l.status === 'awarded') {
    banner = `<div class="banner warn">${icon('clock')}<div><b>Tildelt ${esc(l.awarded.name)}</b>Venter på bekreftelse innen ${dFull(a.confirm_by)}.</div></div>`;
  } else if (l.status === 'confirmed') {
    banner = `<div class="banner ok">${icon('circle-check')}<div><b>${l.price_mode === 'fixed' ? 'Tatt' : 'Bekreftet'} av ${esc(l.awarded.name)}</b>${kr(a.price)}${l.awarded.phone ? ` · ${esc(l.awarded.phone)}` : ''}</div></div>`;
  } else if (l.status === 'completed') {
    banner = `<div class="banner ok">${icon('circle-check')}<div><b>Levert av ${esc(l.awarded?.name || '')}</b>${a ? kr(a.price) : ''}</div></div>`;
  } else if (l.status === 'cancelled') {
    banner = `<div class="banner err">${icon('circle-x')}<div><b>Avlyst</b></div></div>`;
  }
  const canAward = l.status === 'open' && l.price_mode === 'bid';
  const bidHtml = bids.map(b => `<div class="card bid-card ${b.lowest ? 'selected' : ''}">
      <div class="row"><div><h3>${esc(b.company.name)}</h3><div class="meta">${esc(b.company.municipality || '')} · ${b.company.jobs_completed} oppdrag${b.company.declines ? ` · ${b.company.declines} avslag` : ''}</div></div>
        <div style="text-align:right"><div class="price">${kr(b.amount)}</div>${b.lowest ? '<span class="badge green">Laveste</span>' : `<span class="diff">+${kr(b.diff)}</span>`}</div></div>
      <div class="chips">${b.approved.map(k => `<span class="badge green">${icon('check')}${esc(S.meta.reqTypes.find(t => t.key === k).short)}</span>`).join('')}
        ${b.vehicle ? `<span class="badge">${icon('truck')}${esc(b.vehicle.name)}</span>` : ''}
        ${b.status === 'won' ? '<span class="badge navy">Tildelt</span>' : b.status === 'lost' ? '<span class="badge">Ikke valgt</span>' : ''}
        ${!b.qualified ? `<span class="badge red">${esc(b.locked_text)}</span>` : ''}</div>
      ${canAward && ['active', 'lost'].includes(b.status) ? `<div class="btn-row"><button class="btn ${b.lowest ? '' : 'secondary'} sm block" data-award="${b.id}" ${b.qualified ? '' : 'disabled'}>Tildel</button></div>` : ''}
    </div>`).join('');

  $app.innerHTML = `<main>
    ${back('#/', 'Mine laster')}
    <div class="detail-head">${tile(l, 'lg')}<div><div class="overline">${esc(l.category)}</div><h1 style="font-size:24px">${esc(l.type)}</h1>
      <div class="row-start"><span class="mono">${esc(l.ref)}</span><span class="meta">${esc(l.from_city)} – ${esc(l.to_city)} · ${tonn(l.weight_t)}</span></div></div></div>
    ${banner}
    ${l.price_mode === 'bid' ? `<h2>Bud (${bids.length})</h2>${bids.length ? bidHtml : empty('package-search', 'Ingen bud ennå. Kvalifiserte transportører har fått varsel.')}` : ''}
    <h2>Historikk</h2>
    <div class="card">${l.events.length ? l.events.map(e => `<div class="kv"><span>${esc(e.message)}</span><span class="micro">${dFull(e.created_at)}</span></div>`).join('') : '<p class="meta">Ingen hendelser ennå.</p>'}</div>
    ${!['completed', 'cancelled'].includes(l.status) ? '<div class="btn-row"><button class="btn danger block" id="cancel">Avlys lasten</button></div>' : ''}
  </main>`;
  $app.querySelectorAll('[data-award]').forEach(btn => btn.addEventListener('click', () => {
    const b = bids.find(x => x.id === Number(btn.dataset.award));
    openSheet(`<h2>Tildel ${esc(b.company.name)}?</h2>
      <div class="card tint"><div class="kv"><span>Beløp eks. mva</span><span>${kr(b.amount)}</span></div>
        <div class="kv"><span>Fullførte oppdrag</span><span>${b.company.jobs_completed}</span></div>
        <div class="kv"><span>Bekreftelsesfrist</span><span>${S.meta.confirmHours} timer</span></div></div>
      <p class="micro" style="margin-top:10px">Transportøren må bekrefte innen fristen. De andre budgiverne får beskjed automatisk.</p>
      <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn" id="yes">Tildel</button></div>`, sheet => {
      const y = sheet.querySelector('#yes');
      y.addEventListener('click', busy(y, async () => {
        await api(`/shipper/loads/${l.id}/award`, { method: 'POST', body: { bidId: b.id } });
        closeSheet(); toast(`Tildelt ${b.company.name}`); render();
      }));
    });
  }));
  document.getElementById('cancel')?.addEventListener('click', () => openSheet(`<h2>Avlys lasten?</h2>
    <p class="meta">Budgivere og eventuell tildelt transportør får beskjed.</p>
    <label class="field"><span>Årsak (valgfritt)</span><textarea id="reason"></textarea></label>
    <div class="btn-row"><button class="btn secondary" data-close>Behold</button><button class="btn danger" id="yes">Avlys</button></div>`, sheet => {
    const y = sheet.querySelector('#yes');
    y.addEventListener('click', busy(y, async () => {
      await api(`/shipper/loads/${l.id}/cancel`, { method: 'POST', body: { reason: sheet.querySelector('#reason').value } });
      closeSheet(); toast('Lasten er avlyst'); render();
    }));
  }));
}

function viewLeggUt() {
  setChrome();
  const c = S.me.company;
  if (!c.verified) {
    $app.innerHTML = `<main>${head('Legg ut last')}<div class="banner warn">${icon('shield-check')}<div><b>Firmaet er ikke verifisert</b>Verifiser firmaet i Brønnøysundregistrene først. <a href="#/profil">Gå til Profil</a></div></div></main>`;
    return;
  }
  const f = { type_key: null, price_mode: 'bid', requirements: ['lov'] };
  const places = S.meta.places.map(p => `<option value="${esc(p)}">`).join('');
  const in24 = toLocalInput(new Date(Date.now() + 24 * 3600000).toISOString());
  const in72 = toLocalInput(new Date(Date.now() + 72 * 3600000).toISOString());
  $app.innerHTML = `<main>
    ${head('Legg ut last', c.name)}
    <form id="f" novalidate>
      <div class="overline">Lasttype</div>
      <div class="type-grid">${S.meta.loadTypes.map(t => `<button type="button" class="type-opt" data-type="${t.key}">${tile({ type: t.name, image_key: t.image, category: t.category })}${esc(t.name)}</button>`).join('')}</div>
      <label class="field"><span>Beskrivelse</span><input name="type" placeholder="F.eks. Balert papp"><div class="hint">Vises som tittel. Står tom: lasttypen brukes.</div></label>
      <h2>Rute</h2>
      <div class="grid2">
        <label class="field"><span>Hentes i</span><input name="from_city" list="places" required autocomplete="off"></label>
        <label class="field"><span>Hentested</span><input name="from_site" placeholder="Anlegg/adresse"></label>
        <label class="field"><span>Leveres til</span><input name="to_city" list="places" required autocomplete="off"></label>
        <label class="field"><span>Mottak</span><input name="to_site" placeholder="Mottak/adresse"></label>
      </div>
      <datalist id="places">${places}</datalist>
      <div class="hint">Steder fra lista vises på kartet og får beregnet avstand.</div>
      <div class="grid2">
        <label class="field"><span>Vekt (tonn)</span><input name="weight_t" inputmode="decimal" required placeholder="0"></label>
        <label class="field"><span>Enheter</span><input name="units" placeholder="42 baller"></label>
        <label class="field"><span>Hentes fra</span><input name="pickup_start" type="datetime-local" value="${in24}"></label>
        <label class="field"><span>Hentes innen</span><input name="pickup_end" type="datetime-local" value="${in72}" required></label>
      </div>
      <label class="field"><span>Utstyr</span><input name="equipment" placeholder="Fylles ut fra lasttype"></label>
      <h2>Prismodell</h2>
      <div class="seg"><label><input type="radio" name="price_mode" value="bid" checked>Anbud</label><label><input type="radio" name="price_mode" value="fixed">Fastpris</label></div>
      <div id="bidfields" class="grid2">
        <label class="field"><span>Anbudsfrist</span><input name="bid_deadline" type="datetime-local" value="${in24}"></label>
        <label class="field"><span>Makspris (valgfri)</span><input name="max_price" inputmode="numeric" placeholder="kr eks. mva"></label>
      </div>
      <div id="fixedfields" hidden><label class="field"><span>Fastpris eks. mva (kr)</span><input name="fixed_price" inputmode="numeric" class="big" placeholder="0"></label></div>
      <h2>Krav til transportør</h2>
      <div class="card">${S.meta.reqTypes.map(t => `<label class="check"><input type="checkbox" name="req" value="${t.key}" ${t.key === 'lov' ? 'checked disabled' : ''}><span class="grow"><b>${esc(t.name)}</b><span class="meta" style="display:block">${t.required ? 'Alltid påkrevd' : esc(t.unlocks)}</span></span></label>`).join('')}</div>
      <div class="btn-row"><button class="btn block" id="submit" disabled>Legg ut lasten</button></div>
      <p class="micro" style="text-align:center" id="missing"></p>
    </form>
  </main>`;
  const form = document.getElementById('f'), submit = document.getElementById('submit'), missingEl = document.getElementById('missing');
  const val = n => form.elements[n].value.trim();
  const update = () => {
    const mode = form.querySelector('[name=price_mode]:checked').value;
    form.querySelector('#bidfields').hidden = mode !== 'bid';
    form.querySelector('#fixedfields').hidden = mode !== 'fixed';
    const need = [];
    if (!f.type_key) need.push('lasttype');
    if (!val('from_city')) need.push('hentes i');
    if (!val('to_city')) need.push('leveres til');
    if (!(Number(val('weight_t').replace(',', '.')) > 0)) need.push('vekt');
    if (!val('pickup_end')) need.push('hentes innen');
    if (mode === 'fixed' && !(Number(val('fixed_price').replace(/\s/g, '')) > 0)) need.push('fastpris');
    if (mode === 'bid' && !val('bid_deadline')) need.push('anbudsfrist');
    submit.disabled = need.length > 0;
    missingEl.textContent = need.length ? `Mangler: ${need.join(', ')}` : '';
  };
  form.addEventListener('input', update);
  form.addEventListener('change', update);
  $app.querySelectorAll('[data-type]').forEach(b => b.addEventListener('click', () => {
    f.type_key = b.dataset.type;
    const t = typeOf(f.type_key);
    $app.querySelectorAll('[data-type]').forEach(x => x.classList.toggle('on', x === b));
    form.querySelectorAll('[name=req]').forEach(cb => { if (cb.value !== 'lov') cb.checked = t.requirements.includes(cb.value); });
    form.equipment.placeholder = t.equipment;
    update();
  }));
  form.addEventListener('submit', e => {
    e.preventDefault();
    busy(submit, async () => {
      const mode = form.querySelector('[name=price_mode]:checked').value;
      const l = await api('/loads', { method: 'POST', body: {
        type_key: f.type_key, type: val('type'), from_city: val('from_city'), from_site: val('from_site'), to_city: val('to_city'), to_site: val('to_site'),
        weight_t: val('weight_t'), units: val('units'), equipment: val('equipment'),
        pickup_start: fromLocalInput(val('pickup_start')), pickup_end: fromLocalInput(val('pickup_end')),
        price_mode: mode, fixed_price: mode === 'fixed' ? val('fixed_price').replace(/\s/g, '') : null,
        max_price: mode === 'bid' ? val('max_price').replace(/\s/g, '') || null : null,
        bid_deadline: mode === 'bid' ? fromLocalInput(val('bid_deadline')) : null,
        requirements: [...form.querySelectorAll('[name=req]:checked')].map(x => x.value),
      } });
      toast(`${l.ref} er lagt ut – kvalifiserte transportører er varslet`);
      go(`#/mine/${l.id}`);
    })();
  });
}

// ======================================================================== profil

async function viewProfil() {
  setChrome();
  await refreshMe();
  const me = S.me, c = me.company, r = role();
  const roleSeg = c ? `<div class="seg" style="margin-bottom:16px">
      <button data-role="transport" class="${r === 'transport' ? 'on' : ''}">${icon('truck', 'sm')}Transportør</button>
      <button data-role="shipper" class="${r === 'shipper' ? 'on' : ''}">${icon('package', 'sm')}Oppdragsgiver</button>
      ${me.user.is_admin ? `<button data-role="admin" class="${r === 'admin' ? 'on' : ''}">Admin</button>` : ''}</div>`
    : me.user.is_admin ? '' : '';
  let body = '';
  if (c) {
    body += `<div class="card">
      <div class="row"><div><div class="overline">Firma</div><h3>${esc(c.name)}</h3></div>${c.verified ? `<span class="badge green">${icon('shield-check')}Verifisert</span>` : `<span class="badge amber">Ikke verifisert</span>`}</div>
      <div class="meta"><span class="mono">${esc(c.orgnr)}</span>${c.municipality ? ` · ${esc(c.municipality)}` : ''}${c.naering ? ` · ${esc(c.naering)}` : ''}</div>
      <div class="meta">${c.jobs_completed} fullførte oppdrag${c.declines ? ` · ${c.declines} avslag etter tildeling` : ''}</div>
      ${c.verified_source === 'simulated' ? `<div class="micro" style="margin-top:4px">${icon('flask-conical', 'sm')} Verifisert mot simulert Brønnøysund</div>` : ''}
      <div class="btn-row"><button class="btn secondary sm" id="verify">${icon('refresh-cw', 'sm')}${c.verified ? 'Hent på nytt fra Brønnøysund' : 'Verifiser i Brønnøysund'}</button></div>
    </div>`;
  }
  if (c && r === 'transport') {
    const q = me.qualification;
    body += `<h2>Prekvalifisering</h2>
      <div class="prequal" style="cursor:default"><div class="overline">Status</div><div class="row"><h3>${q.approvedCount} av 3 godkjent</h3>${q.canBid ? `<span class="badge green">${icon('check')}Kan by</span>` : '<span class="badge red">Kan ikke by</span>'}</div>
      <div class="progress"><i style="width:${(q.approvedCount / 3) * 100}%"></i></div></div>
      ${q.docs.map(d => docRow(d)).join('')}
      <h2>Kjøretøy</h2><div id="vehicles"><div class="card tint">Laster …</div></div>
      <button class="btn secondary block" id="addv">${icon('circle-plus', 'sm')}Legg til kjøretøy</button>
      <h2>Innstillinger</h2>
      <div class="card"><label class="switch"><span><b>Vis laster jeg ikke kan kjøre</b><span class="meta" style="display:block">Låste laster vises med hva som mangler</span></span><input type="checkbox" id="showlocked" ${me.user.show_locked ? 'checked' : ''}></label></div>`;
  }
  body += `<h2>Konto</h2><div class="card"><div class="kv"><span>Navn</span><span>${esc(me.user.name)}</span></div><div class="kv"><span>E-post</span><span>${esc(me.user.email)}</span></div>${me.user.phone ? `<div class="kv"><span>Mobil</span><span>${esc(me.user.phone)}</span></div>` : ''}</div>
    <button class="btn ghost block" id="logout">${icon('log-out', 'sm')}Logg ut</button>`;
  $app.innerHTML = `<main>${head('Profil')}${roleSeg}${body}</main>`;

  $app.querySelectorAll('[data-role]').forEach(b => b.addEventListener('click', busy(b, async () => {
    if (b.dataset.role === r) return;
    S.me = await api('/me/role', { method: 'POST', body: { role: b.dataset.role } });
    toast(`Du bruker nå appen som ${{ transport: 'transportør', shipper: 'oppdragsgiver', admin: 'admin' }[b.dataset.role]}`);
    go('#/');
  })));
  const vb = document.getElementById('verify');
  vb?.addEventListener('click', busy(vb, async () => { S.me = await api('/me/company/verify', { method: 'POST' }); toast('Firmaopplysningene er oppdatert fra Brønnøysund'); render(); }));
  $app.querySelectorAll('[data-upload]').forEach(b => b.addEventListener('click', () => uploadSheet(b.dataset.upload, render)));
  document.getElementById('showlocked')?.addEventListener('change', e => guard(async () => { S.me = await api('/me/settings', { method: 'POST', body: { show_locked: e.target.checked } }); toast(e.target.checked ? 'Låste laster vises' : 'Låste laster skjules'); }));
  document.getElementById('logout').addEventListener('click', () => guard(async () => { await api('/auth/logout', { method: 'POST' }); S.me = null; go('#/'); }));
  if (c && r === 'transport') {
    const renderVehicles = async () => {
      const vs = await api('/me/vehicles');
      document.getElementById('vehicles').innerHTML = vs.length ? vs.map(v => `<div class="card row"><div class="row-start"><span class="icon-btn" style="border:0;background:var(--surface-tint-blue);color:var(--mg-blue-600)">${icon('truck')}</span>
        <div><b>${esc(v.name)}</b><div class="meta"><span class="mono">${esc(v.reg_no)}</span>${v.payload_t ? ` · ${String(v.payload_t).replace('.', ',')} t nyttelast` : ''}</div>${v.equipment.length ? `<div class="chips">${v.equipment.map(e => `<span class="badge">${esc(e)}</span>`).join('')}</div>` : ''}</div></div>
        <button class="icon-btn" data-delv="${v.id}" aria-label="Slett ${esc(v.name)}">${icon('trash-2')}</button></div>`).join('') : `<p class="meta">Ingen kjøretøy registrert. Kjøretøy velges når du gir bud eller tar last.</p>`;
      document.querySelectorAll('[data-delv]').forEach(b => b.addEventListener('click', busy(b, async () => { await api(`/me/vehicles/${b.dataset.delv}`, { method: 'DELETE' }); toast('Kjøretøyet er slettet'); renderVehicles(); })));
    };
    renderVehicles();
    document.getElementById('addv').addEventListener('click', () => openSheet(`<h2>Legg til kjøretøy</h2>
      <form id="vf">
        <label class="field"><span>Navn/modell</span><input name="name" required placeholder="Scania R500 krokbil"></label>
        <div class="grid2"><label class="field"><span>Reg.nr</span><input name="reg_no" required placeholder="AB 12345" autocapitalize="characters"></label>
          <label class="field"><span>Nyttelast (tonn)</span><input name="payload_t" inputmode="decimal"></label></div>
        <div class="field"><span style="display:block;font-size:14px;font-weight:600;color:var(--text-heading);margin-top:14px">Utstyr</span>
          ${S.meta.equipment.map(e => `<label class="check"><input type="checkbox" name="eq" value="${esc(e)}"><span class="grow">${esc(e)}</span></label>`).join('')}</div>
        <div class="btn-row"><button class="btn block">Lagre kjøretøy</button></div>
      </form>`, sheet => {
      const vf = sheet.querySelector('#vf');
      vf.addEventListener('submit', e => {
        e.preventDefault();
        busy(vf.querySelector('button'), async () => {
          await api('/me/vehicles', { method: 'POST', body: { name: vf.name.value, reg_no: vf.reg_no.value, payload_t: vf.payload_t.value || null,
            equipment: [...vf.querySelectorAll('[name=eq]:checked')].map(x => x.value) } });
          closeSheet(); toast('Kjøretøyet er lagt til'); renderVehicles();
        })();
      });
    }));
  }
}

// ======================================================================== varsler

async function viewVarsler() {
  setChrome();
  const list = await api('/notifications');
  $app.innerHTML = `<main>${back('#/')}${head('Varsler', '', list.some(n => !n.read) ? '<button class="btn ghost sm" id="readall">Merk alle som lest</button>' : '')}
    ${list.length ? list.map(n => {
      const href = n.load_id ? (role() === 'shipper' ? `#/mine/${n.load_id}` : role() === 'transport' ? `#/last/${n.load_id}` : null) : n.link;
      return `<${href ? `a href="${esc(href)}"` : 'div'} class="card notif ${n.read ? 'read' : ''}"><span class="ndot"></span><div><div>${esc(n.message)}</div><div class="micro">${dFull(n.created_at)}</div></div></${href ? 'a' : 'div'}>`;
    }).join('') : empty('bell', 'Ingen varsler.')}</main>`;
  const rb = document.getElementById('readall');
  rb?.addEventListener('click', busy(rb, async () => { await api('/notifications/read', { method: 'POST' }); await refreshMe(); render(); }));
}

// ======================================================================== admin

async function viewAdminDocs() {
  setChrome();
  const docs = await api('/admin/docs?status=pending');
  $app.innerHTML = `<main>${head('Dokumentkontroll', `${docs.length} til kontroll`)}
    ${docs.length ? docs.map(d => {
      const rc = d.register_check;
      const check = rc ? rc.error ? `<div class="banner warn" style="margin:10px 0 0">${icon('plug')}<div><b>Registerkontroll feilet</b>${esc(rc.error)}</div></div>`
        : `<div class="banner ${rc.valid ? 'ok' : 'err'}" style="margin:10px 0 0">${icon(rc.valid ? 'circle-check' : 'circle-x')}<div><b>${esc(rc.note)}</b>${rc.licenceNo || rc.registrationNo ? esc(rc.licenceNo || rc.registrationNo) : ''}${rc.source === 'simulated' ? ' · simulert svar' : ''}</div></div>`
        : d.type === 'adr' ? '<p class="micro" style="margin-top:8px">ADR-kompetansebevis har ikke et åpent register. Kontroller dokumentet manuelt.</p>' : '';
      return `<div class="card"><div class="row"><div><div class="overline">${esc(d.name)}</div><h3>${esc(d.company_name)}</h3><div class="meta"><span class="mono">${esc(d.orgnr)}</span> · sendt ${dFull(d.submitted_at)}</div></div>${reqBadge('pending')}</div>
        <div class="meta" style="margin-top:6px">Dokumentnr: ${esc(d.reference || '–')} · Gyldig til: ${dDay(d.valid_until)}</div>
        ${d.file_name ? `<a href="/api/qualifications/${d.id}/file" target="_blank" rel="noopener" class="row-start" style="margin-top:6px">${icon('file-check', 'sm')}${esc(d.file_name)}</a>` : ''}
        ${check}
        <div class="btn-row">${d.type !== 'adr' ? `<button class="btn ghost sm" data-check="${d.id}">${icon('refresh-cw', 'sm')}Kjør registerkontroll</button>` : ''}
          <button class="btn danger sm" data-reject="${d.id}">Avvis</button><button class="btn accent sm" data-approve="${d.id}">Godkjenn</button></div></div>`;
    }).join('') : empty('file-check', 'Ingen dokumenter venter på kontroll.')}</main>`;
  $app.querySelectorAll('[data-check]').forEach(b => b.addEventListener('click', busy(b, async () => { await api(`/admin/docs/${b.dataset.check}/check`, { method: 'POST' }); render(); })));
  $app.querySelectorAll('[data-approve]').forEach(b => b.addEventListener('click', () => {
    const d = docs.find(x => x.id === Number(b.dataset.approve));
    openSheet(`<h2>Godkjenn ${esc(d.name.toLowerCase())}</h2><p class="meta">${esc(d.company_name)}</p>
      <label class="field"><span>Gyldig til</span><input type="date" id="vu" value="${esc(d.valid_until || '')}"><div class="hint">Står tom: ingen utløpsdato.</div></label>
      <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn accent" id="yes">Godkjenn</button></div>`, sheet => {
      const y = sheet.querySelector('#yes');
      y.addEventListener('click', busy(y, async () => {
        await api(`/admin/docs/${d.id}/review`, { method: 'POST', body: { status: 'approved', valid_until: sheet.querySelector('#vu').value || null } });
        closeSheet(); toast(`${d.name} er godkjent`); render();
      }));
    });
  }));
  $app.querySelectorAll('[data-reject]').forEach(b => b.addEventListener('click', () => {
    const d = docs.find(x => x.id === Number(b.dataset.reject));
    openSheet(`<h2>Avvis ${esc(d.name.toLowerCase())}</h2><p class="meta">${esc(d.company_name)} får begrunnelsen som varsel.</p>
      <label class="field"><span>Begrunnelse</span><textarea id="why" required placeholder="F.eks. dokumentet er utløpt eller uleselig"></textarea></label>
      <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn danger" id="yes">Avvis</button></div>`, sheet => {
      const y = sheet.querySelector('#yes');
      y.addEventListener('click', busy(y, async () => {
        await api(`/admin/docs/${d.id}/review`, { method: 'POST', body: { status: 'rejected', reason: sheet.querySelector('#why').value } });
        closeSheet(); toast('Dokumentet er avvist'); render();
      }));
    });
  }));
}

async function viewAdminFirma() {
  setChrome();
  const list = await api('/admin/companies');
  $app.innerHTML = `<main>${head('Firma', `${list.length} registrert`)}
    ${list.map(c => `<div class="card"><div class="row"><div><h3>${esc(c.name)}</h3><div class="meta"><span class="mono">${esc(c.orgnr)}</span> · ${[c.is_transport && 'Transportør', c.is_shipper && 'Oppdragsgiver'].filter(Boolean).join(' og ')}</div></div>
      ${c.blocked ? '<span class="badge red">Sperret</span>' : c.verified ? `<span class="badge green">${icon('shield-check')}Verifisert</span>` : '<span class="badge amber">Ikke verifisert</span>'}</div>
      ${c.is_transport ? `<div class="chips">${c.docs.map(d => `<span class="badge ${REQ_STATUS[d.status][0]}">${esc(S.meta.reqTypes.find(t => t.key === d.key).short)}: ${REQ_STATUS[d.status][2].toLowerCase()}</span>`).join('')}</div>` : ''}
      <div class="meta" style="margin-top:6px">${c.jobs_completed} fullførte oppdrag · ${c.declines} avslag${c.blocked_reason ? ` · Sperret: ${esc(c.blocked_reason)}` : ''}</div>
      <div class="btn-row"><button class="btn ${c.blocked ? 'secondary' : 'danger'} sm" data-block="${c.id}" data-blocked="${c.blocked ? 1 : 0}">${c.blocked ? 'Opphev sperring' : 'Sperr firma'}</button></div></div>`).join('')}
  </main>`;
  $app.querySelectorAll('[data-block]').forEach(b => b.addEventListener('click', () => {
    const c = list.find(x => x.id === Number(b.dataset.block));
    if (c.blocked) return guard(async () => { await api(`/admin/companies/${c.id}/block`, { method: 'POST', body: { blocked: false } }); toast('Sperringen er opphevet'); render(); });
    openSheet(`<h2>Sperr ${esc(c.name)}?</h2><p class="meta">Firmaet kan ikke by eller ta last mens det er sperret.</p>
      <label class="field"><span>Begrunnelse</span><textarea id="why"></textarea></label>
      <div class="btn-row"><button class="btn secondary" data-close>Avbryt</button><button class="btn danger" id="yes">Sperr</button></div>`, sheet => {
      const y = sheet.querySelector('#yes');
      y.addEventListener('click', busy(y, async () => {
        await api(`/admin/companies/${c.id}/block`, { method: 'POST', body: { blocked: true, reason: sheet.querySelector('#why').value } });
        closeSheet(); toast(`${c.name} er sperret`); render();
      }));
    });
  }));
}

async function viewAdminIntegrasjoner() {
  setChrome();
  const data = await api('/admin/integrations');
  const outcome = t => t.notFound ? 'Ikke funnet' : t.deleted ? 'Slettet' : t.konkurs ? 'Konkurs' : t.underAvvikling ? 'Under avvikling' : t.name;
  const card = i => `<div class="card" data-integ="${i.id}">
    <div class="row"><div><div class="overline">${i.mode === 'live' ? 'Ekte kobling' : 'Simulert'}</div><h3>${esc(i.name)}</h3></div>
      ${i.mode === 'live' ? `<span class="badge green">${icon('plug')}Live</span>` : `<span class="badge blue">${icon('flask-conical')}Test</span>`}</div>
    <p class="meta" style="margin-top:4px">${esc(i.description)}</p>
    <div class="seg" style="margin-top:10px"><button data-mode="simulated" class="${i.mode === 'simulated' ? 'on' : ''}">Simulert</button><button data-mode="live" class="${i.mode === 'live' ? 'on' : ''}" ${i.live ? '' : 'disabled title="Ikke tilgjengelig ennå"'}>Ekte API</button></div>
    ${!i.live ? '<p class="micro" style="margin-top:6px">Ekte kobling krever avtale med etaten. Kun simulering er tilgjengelig.</p>' : ''}
    <div class="grid2" ${i.mode === 'live' ? 'hidden' : ''}>
      <label class="field"><span>Scenario</span><select data-scenario>${Object.entries(data.scenarios).map(([k, t]) => `<option value="${k}" ${i.scenario === k ? 'selected' : ''}>${esc(t)}</option>`).join('')}</select></label>
      <label class="field"><span>Svartid (ms)</span><input data-delay type="number" min="0" max="10000" step="100" value="${i.delayMs}"></label>
    </div>
    <form class="test" style="margin-top:14px"><label class="field"><span>Test med org.nr</span>
      <div class="row-start"><input name="orgnr" inputmode="numeric" placeholder="9 siffer" value="${i.id === 'brreg' ? '912345688' : '920000002'}"><button class="btn sm">${icon('flask-conical', 'sm')}Kjør test</button></div></label>
      ${i.id === 'brreg' && i.mode === 'simulated' ? `<div class="chips">${data.testOrgnrs.map(t => `<button type="button" class="filter" style="min-height:34px;padding:4px 12px;font-size:13px" data-org="${t.orgnr}">${esc(outcome(t))}</button>`).join('')}</div>` : ''}
      <div class="result"></div></form>
  </div>`;
  const logRows = data.log.map(r => `<tr><td class="micro">${dTime(r.created_at)}</td><td>${esc(r.integration)}<div class="micro">${r.mode === 'live' ? 'live' : `sim · ${esc(r.scenario)}`}</div></td>
    <td class="mono">${esc(r.input)}</td><td>${r.ok ? `<span class="badge green">OK</span>` : `<span class="badge red">${esc(r.error_code)}</span>`}<div class="micro">${r.duration_ms} ms</div></td>
    <td class="micro">${esc(r.ok ? (r.response?.name || r.response?.note || '') : r.message)}</td></tr>`).join('');
  $app.innerHTML = `<main>${head('Integrasjoner', 'Testpanel')}
    <div class="banner info">${icon('flask-conical')}<div><b>Simuler koblinger mot offentlige registre</b>Velg scenario for å se hvordan appen oppfører seg når registeret svarer, ikke finner firmaet, er tregt eller nede. Innstillingene gjelder hele appen, også registrering og dokumentkontroll.</div></div>
    ${data.integrations.map(card).join('')}
    <div class="row"><h2>Logg</h2><div class="row-start"><button class="btn ghost sm" id="reload">${icon('refresh-cw', 'sm')}Oppdater</button><button class="btn ghost sm" id="clear">${icon('trash-2', 'sm')}Tøm</button></div></div>
    <div class="card" style="overflow-x:auto">${data.log.length ? `<table class="log"><thead><tr><th>Tid</th><th>Kobling</th><th>Inn</th><th>Svar</th><th>Detaljer</th></tr></thead><tbody>${logRows}</tbody></table>` : '<p class="meta">Ingen kall ennå.</p>'}</div>
  </main>`;

  $app.querySelectorAll('[data-integ]').forEach(el => {
    const id = el.dataset.integ;
    const save = async patch => { await api(`/admin/integrations/${id}`, { method: 'PUT', body: patch }); };
    el.querySelectorAll('[data-mode]').forEach(b => b.addEventListener('click', busy(b, async () => { await save({ mode: b.dataset.mode }); toast(b.dataset.mode === 'live' ? 'Bruker ekte API' : 'Bruker simulering'); render(); })));
    el.querySelector('[data-scenario]')?.addEventListener('change', e => guard(async () => { await save({ scenario: e.target.value }); toast(`Scenario: ${data.scenarios[e.target.value]}`); }));
    el.querySelector('[data-delay]')?.addEventListener('change', e => guard(async () => { await save({ delayMs: Number(e.target.value) }); toast('Svartid er lagret'); }));
    const form = el.querySelector('form.test'), out = el.querySelector('.result');
    const run = busy(form.querySelector('button.btn'), async () => {
      out.innerHTML = `<div class="card tint row-start" style="margin-top:10px">${icon('clock')}Venter på svar …</div>`;
      const r = await api(`/admin/integrations/${id}/test`, { method: 'POST', body: { orgnr: form.orgnr.value } });
      out.innerHTML = r.ok
        ? `<div class="banner ok" style="margin-top:10px">${icon('circle-check')}<div><b>Svar på ${r.result.durationMs} ms (${r.result.source === 'live' ? 'ekte API' : 'simulert'})</b>${esc(r.result.name || r.result.note || '')}</div></div><pre class="json">${esc(JSON.stringify(r.result, null, 2))}</pre>`
        : `<div class="banner err" style="margin-top:10px">${icon('circle-x')}<div><b>Feil: ${esc(r.code)} (HTTP ${r.status || '–'})</b>${esc(r.error)}<div class="micro">${r.durationMs} ms</div></div></div>`;
      refreshLog();
    });
    form.addEventListener('submit', e => { e.preventDefault(); run(); });
    el.querySelectorAll('[data-org]').forEach(b => b.addEventListener('click', () => { form.orgnr.value = b.dataset.org; run(); }));
  });
  async function refreshLog() {
    const d = await api('/admin/integrations');
    const tbody = $app.querySelector('.log tbody');
    if (!tbody) return render();
    tbody.innerHTML = d.log.map(r => `<tr><td class="micro">${dTime(r.created_at)}</td><td>${esc(r.integration)}<div class="micro">${r.mode === 'live' ? 'live' : `sim · ${esc(r.scenario)}`}</div></td>
      <td class="mono">${esc(r.input)}</td><td>${r.ok ? `<span class="badge green">OK</span>` : `<span class="badge red">${esc(r.error_code)}</span>`}<div class="micro">${r.duration_ms} ms</div></td>
      <td class="micro">${esc(r.ok ? (r.response?.name || r.response?.note || '') : r.message)}</td></tr>`).join('');
  }
  document.getElementById('reload').addEventListener('click', () => render());
  const cl = document.getElementById('clear');
  cl.addEventListener('click', busy(cl, async () => { await api('/admin/integrations/log', { method: 'DELETE' }); toast('Loggen er tømt'); render(); }));
}

// ======================================================================== ruting

async function render() {
  closeSheet(true);
  if (map && !document.getElementById('map')) { map.remove(); map = null; }
  const path = (location.hash || '#/').slice(1).split('?')[0] || '/';
  try {
    if (!S.meta) S.meta = await api('/meta');
    if (!S.me) await refreshMe();
    if (!S.me) {
      if (!S.integ) S.integ = await api('/integrations/status').catch(() => null);
      if (path === '/login') return viewLogin();
      if (path === '/start') return viewWizard();
      return viewWelcome();
    }
    if (path === '/start' && S.wizard) return viewWizard();
    if (path === '/login' || path === '/start') return go('#/');
    if (path === '/varsler') return await viewVarsler();
    if (path === '/profil') return await viewProfil();
    const r = role();
    let m;
    if (r === 'transport') {
      if (path === '/') return await viewLastbors();
      if (path === '/oppdrag') return await viewOppdrag();
      if ((m = path.match(/^\/last\/(\d+)$/))) return await viewLoad(m[1]);
      if ((m = path.match(/^\/oppdrag\/(\d+)$/))) return await viewAssignment(m[1]);
    } else if (r === 'shipper') {
      if (path === '/') return await viewMineLaster();
      if (path === '/legg-ut') return viewLeggUt();
      if ((m = path.match(/^\/mine\/(\d+)$/))) return await viewShipperLoad(m[1]);
    } else if (r === 'admin') {
      if (path === '/') return await viewAdminDocs();
      if (path === '/admin/firma') return await viewAdminFirma();
      if (path === '/admin/integrasjoner') return await viewAdminIntegrasjoner();
    }
    go('#/');
  } catch (err) {
    setChrome();
    $app.innerHTML = `<main>${back('#/')}<div class="banner err">${icon('circle-x')}<div><b>Noe gikk galt</b>${esc(err.message)}</div></div></main>`;
  } finally {
    window.scrollTo(0, 0);
  }
}

window.addEventListener('hashchange', render);
document.addEventListener('DOMContentLoaded', render);

// Oppdaterer varsler og bekreftelser jevnlig mens appen er åpen.
setInterval(async () => {
  if (!S.me || document.hidden) return;
  const before = S.me.unread;
  await refreshMe();
  if (S.me && S.me.unread > before) {
    toast('Nytt varsel');
    const btn = document.querySelector('a.icon-btn[href="#/varsler"]');
    if (btn && !btn.querySelector('.dot')) btn.insertAdjacentHTML('beforeend', '<span class="dot"></span>');
    setChrome({ tabs: !$tabs.hidden, bar: document.body.classList.contains('has-bar') });
  }
}, 30000);

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
