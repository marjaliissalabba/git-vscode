'use strict';
/* Lastebørs – klient (ren JavaScript, ingen byggesteg). */

const $app = document.getElementById('app');
const $nav = document.getElementById('nav');
const $top = document.getElementById('topbar-right');
const state = { me: null, types: [] };

// ------------------------------------------------------------ hjelpere

const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const kr = n => (n == null ? '–' : Number(n).toLocaleString('nb-NO') + ' kr');
const kg = n => (n == null ? '–' : Number(n).toLocaleString('nb-NO') + ' kg');
const dt = iso => (iso ? new Date(iso).toLocaleString('nb-NO', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '–');
const day = d => (d ? new Date(d + 'T12:00:00').toLocaleDateString('nb-NO', { day: 'numeric', month: 'short', year: 'numeric' }) : '–');
const typeName = code => state.types.find(t => t.code === code)?.name || code;

/** Verdi for <input type="datetime-local"> fra ISO-tid (lokal tid). */
function toLocalInput(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
const fromLocalInput = v => (v ? new Date(v).toISOString() : null);

function relative(iso) {
  const ms = new Date(iso) - Date.now();
  const abs = Math.abs(ms), h = Math.floor(abs / 3600000), m = Math.floor((abs % 3600000) / 60000);
  const txt = h >= 48 ? `${Math.floor(h / 24)} dager` : h > 0 ? `${h} t ${m} min` : `${m} min`;
  return ms >= 0 ? `om ${txt}` : `for ${txt} siden`;
}

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch('/api' + path, {
    method, credentials: 'same-origin',
    headers: method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    body: method === 'GET' ? undefined : JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && !['/auth/login', '/me'].includes(path)) { state.me = null; go('#/login'); }
  if (!res.ok) throw new Error(data.error || `Feil (${res.status})`);
  return data;
}

let toastTimer;
function toast(msg, isError = false) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.className = 'toast' + (isError ? ' err' : ''); t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }

const formData = form => Object.fromEntries(new FormData(form).entries());

/** Kobler et skjema til en asynkron handling med feilhåndtering og låsing av knapper. */
function onSubmit(form, fn) {
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const buttons = form.querySelectorAll('button');
    buttons.forEach(b => (b.disabled = true));
    try { await fn(formData(form), e.submitter); } catch (err) { toast(err.message, true); } finally { buttons.forEach(b => (b.disabled = false)); }
  });
}
async function action(fn) { try { await fn(); } catch (err) { toast(err.message, true); } }

const STATUS_PILL = {
  draft: 'grey', open: '', evaluation: 'warn', awarded: 'ok', picked_up: 'ok', delivered: 'ok', cancelled: 'err',
};
const statusPill = l => `<span class="pill ${STATUS_PILL[l.status]}">${esc(l.status_text)}</span>`;
const qualTags = codes => codes.length ? `<div class="tags">${codes.map(c => `<span class="pill grey">${esc(typeName(c))}</span>`).join('')}</div>` : '';
const QUAL_PILL = { approved: ['ok', 'Godkjent'], pending: ['warn', 'Venter godkjenning'], rejected: ['err', 'Avvist'] };
const COMPANY_PILL = { approved: ['ok', 'Prekvalifisert'], pending: ['warn', 'Venter vurdering'], rejected: ['err', 'Avslått'], suspended: ['err', 'Suspendert'] };
const BID_PILL = { active: ['', 'Aktivt bud'], won: ['ok', 'Vunnet'], lost: ['grey', 'Ikke valgt'], withdrawn: ['grey', 'Trukket'] };
const pill = ([cls, txt]) => `<span class="pill ${cls}">${esc(txt)}</span>`;

// ------------------------------------------------------------ ramme

function renderChrome() {
  const me = state.me;
  if (!me) { $nav.hidden = true; $top.innerHTML = ''; return; }
  $top.innerHTML = `<div class="who">${esc(me.user.name)}<br><span class="small">${esc(me.company ? me.company.name : 'Anlegget')}</span></div>`;
  const items = me.user.role === 'admin'
    ? [['#/', '📊', 'Oversikt'], ['#/loads', '📦', 'Laster'], ['#/loads/new', '➕', 'Ny last'], ['#/carriers', '🚚', 'Transportører'], ['#/notifications', '🔔', 'Varsler']]
    : [['#/', '📦', 'Laster'], ['#/bids', '💬', 'Mine bud'], ['#/jobs', '🚚', 'Oppdrag'], ['#/qualify', '✅', 'Prekval.'], ['#/notifications', '🔔', 'Varsler']];
  const current = location.hash || '#/';
  $nav.innerHTML = items.map(([href, ico, label]) => {
    const active = href === '#/' ? current === '#/' : current === href || (current.startsWith(href + '/') && href !== '#/loads') || (href === '#/loads' && /^#\/loads(\/\d+)?$/.test(current));
    const badge = href === '#/notifications' && me.unread ? `<span class="badge">${me.unread}</span>` : '';
    return `<a href="${href}" class="${active ? 'active' : ''}"><span class="ico" aria-hidden="true">${ico}</span>${label}${badge}</a>`;
  }).join('');
  $nav.hidden = false;
}

async function refreshMe() {
  try { state.me = await api('/me'); } catch { state.me = null; }
}

// ------------------------------------------------------------ innlogging

function viewLogin() {
  $app.innerHTML = `
    <div class="card">
      <h1>Logg inn</h1>
      <p class="muted">Anlegget legger ut transportlaster på anbud. Prekvalifiserte transportører gir bud eller tar oppdrag til fastpris.</p>
      <form id="f">
        <label for="email">E-post</label><input id="email" name="email" type="email" autocomplete="username" required>
        <label for="password">Passord</label><input id="password" name="password" type="password" autocomplete="current-password" required>
        <div class="actions stack"><button>Logg inn</button></div>
      </form>
      <p class="muted" style="margin-top:12px">Transportør uten konto? <a href="#/register">Registrer firmaet ditt</a></p>
    </div>
    <div class="card">
      <h2 style="margin-top:0">Demokontoer</h2>
      <p class="muted small">Passord for alle: <b>demo1234</b></p>
      <div class="demo">
        ${[['anlegg@demo.no', 'Anlegget (transportleder)'], ['nordtrans@demo.no', 'Nordtrans – prekvalifisert inkl. ADR'],
           ['fjordbil@demo.no', 'Fjordbil – prekvalifisert, uten ADR'], ['nykommer@demo.no', 'Nykommer – venter prekvalifisering']]
          .map(([e, t]) => `<button class="secondary" data-email="${e}"><span>${t}</span><span class="small">${e}</span></button>`).join('')}
      </div>
    </div>`;
  const form = document.getElementById('f');
  const login = async (email, password) => { await api('/auth/login', { method: 'POST', body: { email, password } }); await refreshMe(); go('#/'); };
  onSubmit(form, d => login(d.email, d.password));
  $app.querySelectorAll('[data-email]').forEach(b => b.addEventListener('click', () => action(() => login(b.dataset.email, 'demo1234'))));
}

function viewRegister() {
  $app.innerHTML = `
    <div class="card">
      <h1>Registrer transportør</h1>
      <p class="muted">Etter registrering laster du opp dokumentasjon for prekvalifisering. Anlegget godkjenner firmaet før dere kan gi bud.</p>
      <form id="f">
        <fieldset><legend>Firma</legend>
          <label for="cn">Firmanavn</label><input id="cn" name="company_name" required>
          <label for="org">Organisasjonsnummer</label><input id="org" name="org_nr" inputmode="numeric" pattern="\\d{3}\\s?\\d{3}\\s?\\d{3}" placeholder="9 siffer" required>
          <label for="cp">Telefon</label><input id="cp" name="company_phone" type="tel">
          <label for="ca">Adresse</label><input id="ca" name="address">
        </fieldset>
        <fieldset><legend>Kontaktperson / bruker</legend>
          <label for="un">Navn</label><input id="un" name="name" required autocomplete="name">
          <label for="ue">E-post</label><input id="ue" name="email" type="email" required autocomplete="email">
          <label for="up">Mobil</label><input id="up" name="phone" type="tel" autocomplete="tel">
          <label for="pw">Passord (minst 8 tegn)</label><input id="pw" name="password" type="password" minlength="8" required autocomplete="new-password">
        </fieldset>
        <div class="actions stack"><button>Registrer og gå til prekvalifisering</button></div>
      </form>
      <p class="muted"><a href="#/login">Tilbake til innlogging</a></p>
    </div>`;
  onSubmit(document.getElementById('f'), async d => {
    await api('/auth/register', { method: 'POST', body: {
      company: { name: d.company_name, org_nr: d.org_nr, phone: d.company_phone, address: d.address },
      user: { name: d.name, email: d.email, phone: d.phone, password: d.password },
    } });
    await refreshMe();
    toast('Firmaet er registrert');
    go('#/qualify');
  });
}

// ------------------------------------------------------------ felles: lastkort

function loadCard(l, { showEligibility = false } = {}) {
  const price = l.mode === 'direct'
    ? `<span class="price">${kr(l.price_nok)}</span>`
    : l.my_bid && l.my_bid.status !== 'withdrawn' ? `<span class="price">${kr(l.my_bid.price_nok)}</span>`
    : `<span class="pill">Anbud</span>`;
  let elig = '';
  if (showEligibility && l.eligibility && !l.eligibility.qualified) {
    elig = `<p class="small" style="color:var(--err);margin-top:6px">🔒 ${l.eligibility.companyApproved
      ? 'Mangler: ' + esc(l.eligibility.missing.map(m => m.name).join(', '))
      : 'Firmaet må prekvalifiseres før dere kan gi bud'}</p>`;
  }
  const deadline = l.status === 'open' && l.mode === 'tender' && l.bid_deadline ? ` · frist ${esc(relative(l.bid_deadline))}` : '';
  return `<a class="card" href="#/loads/${l.id}">
    <div class="row"><span class="muted small">${esc(l.ref)} · ${esc(l.cargo_type)}</span>${statusPill(l)}</div>
    <div class="row" style="margin-top:4px"><h3>${esc(l.title)}</h3>${price}</div>
    <div class="route">${esc(l.from_site)} → ${esc(l.to_address)}</div>
    <div class="muted small">Henting ${esc(dt(l.pickup_from))} · ${esc(kg(l.weight_kg))}${l.units ? ' · ' + esc(l.units) : ''}${deadline}${l.bid_count != null && l.mode === 'tender' && l.status !== 'draft' ? ` · ${l.bid_count} bud` : ''}</div>
    ${l.mode === 'direct' && l.status === 'open' ? '<div class="tags"><span class="pill ok">⚡ Fastpris – først til mølla</span></div>' : ''}
    ${qualTags(l.required_quals)}${elig}
  </a>`;
}

const listOrEmpty = (items, render, empty) => (items.length ? items.map(render).join('') : `<div class="empty">${empty}</div>`);

// ------------------------------------------------------------ anlegg (admin)

async function viewDashboard() {
  const [d, loads] = await Promise.all([api('/dashboard'), api('/loads')]);
  const c = d.counts;
  const attention = loads.filter(l => l.status === 'evaluation' || (l.status === 'open' && l.mode === 'tender' && l.bid_count > 0));
  $app.innerHTML = `
    <h1>Oversikt</h1>
    <div class="stats">
      <a class="card stat" href="#/loads?status=open"><b>${c.open || 0}</b><span class="muted small">Ute på anbud</span></a>
      <a class="card stat" href="#/loads?status=evaluation"><b>${c.evaluation || 0}</b><span class="muted small">Klar for tildeling</span></a>
      <a class="card stat" href="#/loads?status=awarded"><b>${(c.awarded || 0) + (c.picked_up || 0)}</b><span class="muted small">Tildelt / underveis</span></a>
      <a class="card stat" href="#/loads?status=draft"><b>${c.draft || 0}</b><span class="muted small">Utkast</span></a>
    </div>
    ${d.pending_companies || d.pending_quals ? `<div class="banner warn">📋 ${d.pending_companies} transportør(er) venter prekvalifisering og ${d.pending_quals} dokument(er) venter godkjenning. <a href="#/carriers">Gå til transportører</a></div>` : ''}
    <div class="banner info">🚚 ${d.approved_companies} prekvalifiserte transportører · ${c.delivered || 0} leverte laster · ${kr(d.delivered_value)} levert verdi</div>
    <div class="actions"><a class="btn" href="#/loads/new">➕ Legg ut ny last</a></div>
    <h2>Trenger oppfølging</h2>
    ${listOrEmpty(attention, l => loadCard(l), 'Ingen laster venter på deg akkurat nå.')}`;
}

async function viewAdminLoads() {
  const status = new URLSearchParams(location.hash.split('?')[1] || '').get('status') || '';
  const loads = await api('/loads' + (status ? `?status=${status}` : ''));
  const tabs = [['', 'Alle'], ['open', 'På anbud'], ['evaluation', 'Til tildeling'], ['draft', 'Utkast'], ['awarded', 'Tildelt'], ['picked_up', 'Hentet'], ['delivered', 'Levert'], ['cancelled', 'Avlyst']];
  $app.innerHTML = `
    <div class="row"><h1>Laster</h1><a class="btn" href="#/loads/new">➕ Ny last</a></div>
    <nav class="tabs">${tabs.map(([s, t]) => `<a href="#/loads${s ? '?status=' + s : ''}" class="${s === status ? 'active' : ''}">${t}</a>`).join('')}</nav>
    ${listOrEmpty(loads, l => loadCard(l), 'Ingen laster her.')}`;
}

function loadForm(l = {}) {
  const req = l.required_quals || ['AVFALL'];
  const mode = l.mode || 'tender';
  const extra = state.types.filter(t => !t.base);
  const defaultDeadline = toLocalInput(new Date(Date.now() + 24 * 3600000).toISOString());
  return `
    <form id="f">
      <fieldset><legend>Lasten</legend>
        <label for="title">Tittel</label><input id="title" name="title" value="${esc(l.title)}" placeholder="F.eks. Sortert metall til smelteverk" required>
        <label for="cargo">Lasttype / fraksjon</label>
        <input id="cargo" name="cargo_type" list="cargo-list" value="${esc(l.cargo_type)}" required>
        <datalist id="cargo-list">${['Metall', 'Farlig avfall', 'Trevirke', 'Papp/papir', 'Plast', 'Restavfall', 'Glass', 'EE-avfall', 'Masser', 'Containerbytte'].map(x => `<option value="${x}">`).join('')}</datalist>
        <div class="grid2">
          <div><label for="w">Vekt (kg)</label><input id="w" name="weight_kg" type="number" min="0" inputmode="numeric" value="${esc(l.weight_kg)}"></div>
          <div><label for="u">Antall / enheter</label><input id="u" name="units" value="${esc(l.units)}" placeholder="F.eks. 3 containere"></div>
        </div>
        <label for="desc">Beskrivelse</label><textarea id="desc" name="description" placeholder="Lasting, emballasje, tilkomst, kontaktperson …">${esc(l.description)}</textarea>
      </fieldset>
      <fieldset><legend>Rute og tid</legend>
        <label for="from">Hentested</label><input id="from" name="from_site" value="${esc(l.from_site || 'Anlegget')}" required>
        <label for="to">Leveres til</label><input id="to" name="to_address" value="${esc(l.to_address)}" required>
        <div class="grid2">
          <div><label for="pf">Henting fra</label><input id="pf" name="pickup_from" type="datetime-local" value="${toLocalInput(l.pickup_from)}" required></div>
          <div><label for="pt">Henting til</label><input id="pt" name="pickup_to" type="datetime-local" value="${toLocalInput(l.pickup_to)}"></div>
        </div>
        <label for="db">Levert innen</label><input id="db" name="deliver_by" type="datetime-local" value="${toLocalInput(l.deliver_by)}">
      </fieldset>
      <fieldset><legend>Krav til transportør</legend>
        <p class="muted small">Alle transportører må ha godkjent ${esc(state.types.filter(t => t.base).map(t => t.name.toLowerCase()).join(', '))}. Velg ekstra krav for denne lasten:</p>
        ${extra.map(t => `<label class="check"><input type="checkbox" name="q_${t.code}" ${req.includes(t.code) ? 'checked' : ''}><span><b>${esc(t.name)}</b><br><span class="muted small">${esc(t.description)}</span></span></label>`).join('')}
      </fieldset>
      <fieldset><legend>Prisform</legend>
        <div class="seg" role="radiogroup">
          <label><input type="radio" name="mode" value="tender" ${mode === 'tender' ? 'checked' : ''}>Anbud</label>
          <label><input type="radio" name="mode" value="direct" ${mode === 'direct' ? 'checked' : ''}>⚡ Fastpris</label>
        </div>
        <div id="mode-tender"><label for="bd">Anbudsfrist</label><input id="bd" name="bid_deadline" type="datetime-local" value="${toLocalInput(l.bid_deadline) || defaultDeadline}">
          <p class="muted small">Lukket anbud: transportørene ser ikke hverandres bud. Du velger vinner når fristen er ute (eller tidligere).</p></div>
        <div id="mode-direct"><label for="pr">Fastpris (kr eks. mva.)</label><input id="pr" name="price_nok" type="number" min="1" inputmode="numeric" value="${esc(l.price_nok)}">
          <p class="muted small">Første prekvalifiserte transportør som trykker «Ta oppdraget» får lasten – som en taxi-bestilling.</p></div>
      </fieldset>
      <div class="actions">
        <button name="publish" value="1">📣 Publiser nå</button>
        <button name="publish" value="0" class="secondary">Lagre som utkast</button>
      </div>
    </form>`;
}

function bindLoadForm(onSave) {
  const form = document.getElementById('f');
  const sync = () => {
    const direct = form.querySelector('[name=mode]:checked').value === 'direct';
    form.querySelector('#mode-tender').hidden = direct;
    form.querySelector('#mode-direct').hidden = !direct;
    form.querySelector('#pr').required = direct;
  };
  form.querySelectorAll('[name=mode]').forEach(r => r.addEventListener('change', sync));
  sync();
  onSubmit(form, async (d, submitter) => {
    const body = {
      title: d.title, cargo_type: d.cargo_type, description: d.description, from_site: d.from_site, to_address: d.to_address,
      weight_kg: d.weight_kg, units: d.units, pickup_from: fromLocalInput(d.pickup_from), pickup_to: fromLocalInput(d.pickup_to),
      deliver_by: fromLocalInput(d.deliver_by), mode: d.mode, price_nok: d.price_nok, bid_deadline: fromLocalInput(d.bid_deadline),
      required_quals: state.types.filter(t => d['q_' + t.code]).map(t => t.code),
    };
    await onSave(body, submitter && submitter.value === '1');
  });
}

function viewNewLoad() {
  $app.innerHTML = `<h1>Ny last</h1>${loadForm()}`;
  bindLoadForm(async (body, publish) => {
    const l = await api('/loads', { method: 'POST', body: { ...body, publish } });
    toast(publish ? 'Lasten er lagt ut – prekvalifiserte transportører er varslet' : 'Utkast lagret');
    go(`#/loads/${l.id}`);
  });
}

async function viewEditLoad(id) {
  const l = await api(`/loads/${id}`);
  $app.innerHTML = `<h1>Rediger ${esc(l.ref)}</h1>${loadForm(l)}`;
  bindLoadForm(async (body, publish) => {
    await api(`/loads/${id}`, { method: 'PUT', body });
    if (publish) await api(`/loads/${id}/publish`, { method: 'POST' });
    toast(publish ? 'Lasten er lagt ut' : 'Utkast lagret');
    go(`#/loads/${id}`);
  });
}

function loadFacts(l) {
  return `<dl class="grid2 kv">
    <div><dt>Hentested</dt><dd>${esc(l.from_site)}</dd></div>
    <div><dt>Leveres til</dt><dd>${esc(l.to_address)}</dd></div>
    <div><dt>Henting</dt><dd>${esc(dt(l.pickup_from))}${l.pickup_to ? ' – ' + esc(dt(l.pickup_to)) : ''}</dd></div>
    <div><dt>Levert innen</dt><dd>${esc(dt(l.deliver_by))}</dd></div>
    <div><dt>Vekt</dt><dd>${esc(kg(l.weight_kg))}</dd></div>
    <div><dt>Enheter</dt><dd>${esc(l.units || '–')}</dd></div>
    <div><dt>Prisform</dt><dd>${l.mode === 'direct' ? 'Fastpris ' + esc(kr(l.price_nok)) : 'Lukket anbud'}</dd></div>
    ${l.mode === 'tender' ? `<div><dt>Anbudsfrist</dt><dd>${esc(dt(l.bid_deadline))}${l.bid_deadline && l.status === 'open' ? `<br><span class="small muted">${esc(relative(l.bid_deadline))}</span>` : ''}</dd></div>` : ''}
  </dl>
  ${l.description ? `<p style="white-space:pre-wrap">${esc(l.description)}</p>` : ''}
  <p class="muted small" style="margin-top:8px">Krav: grunnkrav${l.required_quals.length ? ' + ' + esc(l.required_quals.map(typeName).join(', ')) : ''}</p>`;
}

const timeline = events => events && events.length
  ? `<h2>Historikk</h2><div class="card"><ul class="timeline">${events.map(e => `<li>${esc(e.message)}<div class="muted small">${esc(dt(e.created_at))}${e.user_name ? ' · ' + esc(e.user_name) : ''}</div></li>`).join('')}</ul></div>`
  : '';

async function viewAdminLoad(id) {
  const l = await api(`/loads/${id}`);
  const canAward = ['open', 'evaluation'].includes(l.status);
  const active = l.bids.filter(b => b.status === 'active');
  const best = active.filter(b => b.eligibility.qualified).sort((a, b) => a.price_nok - b.price_nok)[0];

  const bidsHtml = l.bids.length ? l.bids.map(b => `
    <div class="bid ${best && b.id === best.id ? 'best' : ''}">
      <div class="row"><b>${esc(b.company_name)}</b><span class="price">${kr(b.price_nok)}</span></div>
      <div class="muted small">${esc(b.user_name)} · ${esc(b.company_phone || '')} · levert ${esc(dt(b.updated_at))}${b.pickup_eta ? ' · henter ' + esc(dt(b.pickup_eta)) : ''}</div>
      ${b.comment ? `<p class="small">«${esc(b.comment)}»</p>` : ''}
      <div class="row" style="margin-top:6px">
        <span>${pill(BID_PILL[b.status])} ${b.eligibility.qualified ? pill(['ok', '✓ Kvalifisert']) : pill(['err', '✗ ' + (b.eligibility.missing.map(m => `${m.name} ${m.reason}`).join(', ') || 'Firma ikke godkjent')])}</span>
        ${canAward && b.status === 'active' ? `<button class="${best && b.id === best.id ? '' : 'secondary'}" data-award="${b.id}" ${b.eligibility.qualified ? '' : 'disabled'}>Tildel</button>` : ''}
      </div>
    </div>`).join('') : '<p class="muted">Ingen bud ennå.</p>';

  $app.innerHTML = `
    <p><a href="#/loads">← Laster</a></p>
    <div class="card">
      <div class="row"><span class="muted small">${esc(l.ref)} · ${esc(l.cargo_type)}</span>${statusPill(l)}</div>
      <h1 style="margin-top:6px">${esc(l.title)}</h1>
      ${loadFacts(l)}
      ${l.awarded ? `<div class="banner ok" style="margin-top:10px">Tildelt <b>${esc(l.awarded.company_name)}</b> for ${kr(l.awarded.price_nok)} · ☎ ${esc(l.awarded.company_phone || '–')}</div>` : ''}
      <div class="actions">
        ${l.status === 'draft' ? `<a class="btn secondary" href="#/loads/${l.id}/edit">Rediger</a><button data-act="publish">📣 Publiser</button>` : ''}
        ${l.mode === 'tender' && ['open', 'evaluation'].includes(l.status) ? '<button class="secondary" data-act="extend">Endre frist</button>' : ''}
        ${l.status === 'awarded' ? '<button class="secondary" data-act="picked_up">Merk som hentet</button>' : ''}
        ${l.status === 'picked_up' ? '<button class="secondary" data-act="delivered">Merk som levert</button>' : ''}
        ${!['delivered', 'cancelled'].includes(l.status) ? '<button class="ghost" data-act="cancel">Avlys last</button>' : ''}
      </div>
    </div>
    ${l.mode === 'tender' || l.bids.length ? `<h2>Bud (${l.bids.filter(b => b.status !== 'withdrawn').length})</h2><div class="card">${bidsHtml}</div>` : ''}
    ${timeline(l.events)}`;

  $app.querySelectorAll('[data-award]').forEach(btn => btn.addEventListener('click', () => action(async () => {
    const bid = l.bids.find(b => b.id === Number(btn.dataset.award));
    if (!confirm(`Tildele ${l.ref} til ${bid.company_name} for ${kr(bid.price_nok)}? De andre budgiverne får beskjed.`)) return;
    await api(`/loads/${l.id}/award`, { method: 'POST', body: { bid_id: bid.id } });
    toast('Oppdraget er tildelt'); render();
  })));
  $app.querySelectorAll('[data-act]').forEach(btn => btn.addEventListener('click', () => action(async () => {
    const act = btn.dataset.act;
    if (act === 'publish') { await api(`/loads/${l.id}/publish`, { method: 'POST' }); toast('Lagt ut – transportører er varslet'); }
    if (act === 'cancel') {
      const reason = prompt('Årsak til avlysning (sendes til budgivere):');
      if (reason === null) return;
      await api(`/loads/${l.id}/cancel`, { method: 'POST', body: { reason } }); toast('Lasten er avlyst');
    }
    if (act === 'extend') {
      const v = prompt('Ny anbudsfrist (ÅÅÅÅ-MM-DD TT:MM):', toLocalInput(new Date(Date.now() + 24 * 3600000).toISOString()).replace('T', ' '));
      if (!v) return;
      await api(`/loads/${l.id}/extend`, { method: 'POST', body: { bid_deadline: fromLocalInput(v.trim().replace(' ', 'T')) } }); toast('Fristen er endret');
    }
    if (act === 'picked_up' || act === 'delivered') { await api(`/loads/${l.id}/status`, { method: 'POST', body: { status: act } }); toast('Status oppdatert'); }
    render();
  })));
}

async function viewCarriers() {
  const list = await api('/companies');
  $app.innerHTML = `
    <h1>Transportører</h1>
    <p class="muted">Kun prekvalifiserte transportører med gyldige grunnkrav kan gi bud. Lastens ekstra krav (ADR, avfall, krokløft …) sjekkes per last.</p>
    ${listOrEmpty(list, c => `<a class="card" href="#/carriers/${c.id}">
      <div class="row"><h3>${esc(c.name)}</h3>${pill(COMPANY_PILL[c.status])}</div>
      <div class="muted small">Org.nr ${esc(c.org_nr)} · ${c.won_count} oppdrag vunnet</div>
      <div class="tags">
        ${c.pending_quals ? pill(['warn', `${c.pending_quals} dokument venter`]) : ''}
        ${c.base_status.missing.length ? pill(['err', 'Grunnkrav mangler: ' + c.base_status.missing.map(m => m.name).join(', ')]) : pill(['ok', 'Grunnkrav OK'])}
      </div></a>`, 'Ingen transportører registrert ennå.')}`;
}

async function viewCarrier(id) {
  const c = await api(`/companies/${id}`);
  const byCode = Object.fromEntries(c.qualifications.map(q => [q.type_code, q]));
  $app.innerHTML = `
    <p><a href="#/carriers">← Transportører</a></p>
    <div class="card">
      <div class="row"><h1 style="margin:0">${esc(c.name)}</h1>${pill(COMPANY_PILL[c.status])}</div>
      <p class="muted">Org.nr ${esc(c.org_nr)} · ${esc(c.phone || '')} · ${esc(c.address || '')}</p>
      ${c.status_comment ? `<p class="small">Kommentar: ${esc(c.status_comment)}</p>` : ''}
      ${c.base_status.missing.length ? `<div class="banner warn small">Grunnkrav som mangler: ${esc(c.base_status.missing.map(m => `${m.name} (${m.reason})`).join(', '))}</div>` : ''}
      <div class="actions">
        ${c.status !== 'approved' ? '<button data-status="approved">✓ Prekvalifiser firma</button>' : '<button class="secondary" data-status="suspended">Suspender</button>'}
        ${c.status === 'pending' ? '<button class="secondary" data-status="rejected">Avslå</button>' : ''}
      </div>
      <h2>Brukere</h2>
      ${c.users.map(u => `<div class="small">${esc(u.name)} · <a href="mailto:${esc(u.email)}">${esc(u.email)}</a> · ${esc(u.phone || '')}</div>`).join('')}
    </div>
    <h2>Dokumentasjon</h2>
    ${state.types.map(t => {
      const q = byCode[t.code];
      return `<div class="card">
        <div class="row"><h3>${esc(t.name)} ${t.base ? '<span class="pill grey">Grunnkrav</span>' : ''}</h3>${q ? pill(QUAL_PILL[q.status]) : pill(['grey', 'Ikke levert'])}</div>
        ${q ? `<div class="muted small">Ref: ${esc(q.reference || '–')} · Gyldig til ${esc(day(q.valid_until))} · sendt ${esc(dt(q.submitted_at))}</div>
          ${q.document_name ? `<div class="small"><a href="/api/qualifications/${q.id}/document" target="_blank" rel="noopener">📄 ${esc(q.document_name)}</a></div>` : ''}
          ${q.review_comment ? `<div class="small">Kommentar: ${esc(q.review_comment)}</div>` : ''}
          <div class="actions">
            ${q.status !== 'approved' ? `<button data-review="${q.id}" data-s="approved">Godkjenn</button>` : ''}
            ${q.status !== 'rejected' ? `<button class="secondary" data-review="${q.id}" data-s="rejected">Avvis</button>` : ''}
          </div>` : ''}
      </div>`;
    }).join('')}`;

  $app.querySelectorAll('[data-status]').forEach(b => b.addEventListener('click', () => action(async () => {
    const status = b.dataset.status;
    let comment = null;
    if (status !== 'approved') { comment = prompt('Begrunnelse (sendes til transportøren):'); if (!comment) return; }
    else if (c.base_status.missing.length && !confirm('Grunnkrav mangler fortsatt. Firmaet kan ikke gi bud før de er godkjent. Prekvalifisere likevel?')) return;
    await api(`/companies/${id}/status`, { method: 'POST', body: { status, comment } });
    toast('Status oppdatert'); render();
  })));
  $app.querySelectorAll('[data-review]').forEach(b => b.addEventListener('click', () => action(async () => {
    const status = b.dataset.s;
    const q = c.qualifications.find(x => x.id === Number(b.dataset.review));
    const body = { status };
    if (status === 'rejected') { body.comment = prompt('Hvorfor avvises dokumentet?'); if (!body.comment) return; }
    else {
      const v = prompt('Bekreft gyldig til (ÅÅÅÅ-MM-DD), tomt = ingen utløpsdato:', q.valid_until || '');
      if (v === null) return;
      body.valid_until = v.trim() || null;
    }
    await api(`/qualifications/${q.id}/review`, { method: 'POST', body });
    toast(status === 'approved' ? 'Godkjent' : 'Avvist'); render();
  })));
}

// ------------------------------------------------------------ transportør

function carrierBanner() {
  const me = state.me;
  const bs = me.base_status;
  let html = '';
  if (me.company.status === 'pending') html += `<div class="banner warn">⏳ Firmaet venter på prekvalifisering. ${bs.missing.length ? `Last opp: ${esc(bs.missing.map(m => m.name).join(', '))}. ` : ''}<a href="#/qualify">Gå til prekvalifisering</a></div>`;
  else if (me.company.status !== 'approved') html += `<div class="banner err">Firmaet er ${me.company.status === 'suspended' ? 'suspendert' : 'ikke godkjent'}${me.company.status_comment ? ': ' + esc(me.company.status_comment) : ''}. Ta kontakt med anlegget.</div>`;
  else if (bs.missing.length) html += `<div class="banner err">Dere kan ikke gi bud før dette er i orden: ${esc(bs.missing.map(m => `${m.name} (${m.reason})`).join(', '))}. <a href="#/qualify">Oppdater</a></div>`;
  for (const e of me.expiring || []) html += `<div class="banner warn">⚠️ «${esc(e.name)}» utløper ${esc(day(e.valid_until))}. <a href="#/qualify">Last opp ny</a></div>`;
  return html;
}

async function viewCarrierLoads() {
  const loads = await api('/loads');
  const ok = loads.filter(l => l.eligibility.qualified), locked = loads.filter(l => !l.eligibility.qualified);
  $app.innerHTML = `
    <h1>Ledige laster</h1>
    ${carrierBanner()}
    ${listOrEmpty(ok, l => loadCard(l, { showEligibility: true }), 'Ingen ledige laster du er kvalifisert for akkurat nå. Du får varsel når nye legges ut.')}
    ${locked.length ? `<h2>Krever annen kvalifikasjon</h2>${locked.map(l => loadCard(l, { showEligibility: true })).join('')}` : ''}`;
}

async function viewCarrierLoad(id) {
  const l = await api(`/loads/${id}`);
  const e = l.eligibility;
  const mine = l.my_bid && l.my_bid.status !== 'withdrawn' ? l.my_bid : null;
  let panel = '';

  if (l.is_mine) {
    const next = { awarded: ['picked_up', '📦 Bekreft henting'], picked_up: ['delivered', '✅ Bekreft levering'] }[l.status];
    panel = `<div class="banner ok">🎉 Oppdraget er ditt – ${kr(l.awarded.price_nok)}</div>
      ${next ? `<form id="sf" class="card"><label for="note">Merknad (valgfritt)</label><input id="note" name="note" placeholder="F.eks. vektseddel nr., avvik …">
        <div class="actions stack"><button>${next[1]}</button></div></form>` : ''}`;
  } else if (l.status !== 'open') {
    panel = `<div class="banner info">${l.status === 'evaluation' ? 'Anbudsfristen er ute. Anlegget vurderer budene.' : l.status === 'cancelled' ? 'Lasten er avlyst.' : 'Lasten er tildelt en annen transportør.'}
      ${mine ? `<br>Ditt bud: <b>${kr(mine.price_nok)}</b> ${pill(BID_PILL[mine.status])}` : ''}</div>`;
  } else if (!e.qualified) {
    panel = `<div class="banner err">🔒 Dere er ikke prekvalifisert for denne lasten.<br>${e.companyApproved
      ? esc(e.missing.map(m => `${m.name} – ${m.reason}`).join(', '))
      : 'Firmaet må godkjennes av anlegget først.'} <a href="#/qualify">Gå til prekvalifisering</a></div>`;
  } else if (l.mode === 'direct') {
    panel = `<form id="af" class="card">
      <div class="row"><b>Fastpris</b><span class="price">${kr(l.price_nok)}</span></div>
      <label for="eta">Når kan dere hente?</label><input id="eta" name="pickup_eta" type="datetime-local" value="${toLocalInput(l.pickup_from)}">
      <label for="c">Kommentar</label><input id="c" name="comment" placeholder="Bil/sjåfør, reg.nr …">
      <div class="actions stack"><button>⚡ Ta oppdraget</button></div>
      <p class="muted small">Første transportør som tar oppdraget får det.</p></form>`;
  } else {
    panel = `<form id="bf" class="card">
      <h2 style="margin-top:0">${mine ? 'Ditt bud' : 'Gi bud'}</h2>
      ${mine ? `<p>${pill(BID_PILL[mine.status])} <span class="muted small">sist endret ${esc(dt(mine.updated_at))}</span></p>` : ''}
      <label for="p">Pris (kr eks. mva.)</label><input id="p" name="price_nok" type="number" min="1" inputmode="numeric" required value="${esc(mine?.price_nok)}">
      <label for="eta">Planlagt henting</label><input id="eta" name="pickup_eta" type="datetime-local" value="${toLocalInput(mine?.pickup_eta || l.pickup_from)}">
      <label for="c">Kommentar</label><textarea id="c" name="comment" placeholder="Bil, kapasitet, forbehold …">${esc(mine?.comment)}</textarea>
      <div class="actions"><button>${mine ? 'Oppdater bud' : 'Send bud'}</button>${mine ? '<button type="button" class="ghost" id="withdraw">Trekk bud</button>' : ''}</div>
      <p class="muted small">Lukket anbud – andre transportører ser ikke budet ditt. Frist ${esc(dt(l.bid_deadline))} (${esc(relative(l.bid_deadline))}).</p></form>`;
  }

  $app.innerHTML = `
    <p><a href="javascript:history.back()">← Tilbake</a></p>
    <div class="card">
      <div class="row"><span class="muted small">${esc(l.ref)} · ${esc(l.cargo_type)}</span>${statusPill(l)}</div>
      <h1 style="margin-top:6px">${esc(l.title)}</h1>
      ${loadFacts(l)}
    </div>
    ${panel}
    ${timeline(l.events)}`;

  const bf = document.getElementById('bf');
  if (bf) onSubmit(bf, async d => {
    await api(`/loads/${id}/bids`, { method: 'POST', body: { price_nok: d.price_nok, pickup_eta: fromLocalInput(d.pickup_eta), comment: d.comment } });
    toast(mine ? 'Budet er oppdatert' : 'Budet er sendt'); render();
  });
  document.getElementById('withdraw')?.addEventListener('click', () => action(async () => {
    if (!confirm('Trekke budet?')) return;
    await api(`/loads/${id}/bids/mine`, { method: 'DELETE' }); toast('Budet er trukket'); render();
  }));
  const af = document.getElementById('af');
  if (af) onSubmit(af, async d => {
    if (!confirm(`Ta oppdraget ${l.ref} for ${kr(l.price_nok)}?`)) return;
    await api(`/loads/${id}/accept`, { method: 'POST', body: { pickup_eta: fromLocalInput(d.pickup_eta), comment: d.comment } });
    toast('Oppdraget er ditt!'); render();
  });
  const sf = document.getElementById('sf');
  if (sf) onSubmit(sf, async d => {
    const status = l.status === 'awarded' ? 'picked_up' : 'delivered';
    await api(`/loads/${id}/status`, { method: 'POST', body: { status, note: d.note } });
    toast('Status oppdatert – anlegget er varslet'); render();
  });
}

async function viewMyBids() {
  const loads = await api('/loads?scope=bids');
  $app.innerHTML = `<h1>Mine bud</h1>
    ${listOrEmpty(loads, l => loadCard(l).replace('</a>', `<div class="tags">${pill(BID_PILL[l.my_bid.status])}</div></a>`), 'Du har ikke gitt noen bud ennå.')}`;
}

async function viewJobs() {
  const loads = await api('/loads?scope=mine');
  const active = loads.filter(l => ['awarded', 'picked_up'].includes(l.status)), done = loads.filter(l => !['awarded', 'picked_up'].includes(l.status));
  $app.innerHTML = `<h1>Mine oppdrag</h1>
    ${listOrEmpty(active, l => loadCard(l), 'Ingen aktive oppdrag.')}
    ${done.length ? `<h2>Fullførte</h2>${done.map(l => loadCard(l)).join('')}` : ''}`;
}

const fileToBase64 = file => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result).split(',')[1]);
  r.onerror = () => reject(new Error('Kunne ikke lese filen'));
  r.readAsDataURL(file);
});

async function viewQualify() {
  await refreshMe();
  const quals = await api('/my/qualifications');
  const byCode = Object.fromEntries(quals.map(q => [q.type_code, q]));
  const me = state.me;
  $app.innerHTML = `
    <h1>Prekvalifisering</h1>
    <div class="card"><div class="row"><b>${esc(me.company.name)}</b>${pill(COMPANY_PILL[me.company.status])}</div>
      <p class="muted small">Org.nr ${esc(me.company.org_nr)}</p>
      <p class="small">For å gi bud må firmaet være godkjent og ha gyldige <b>grunnkrav</b>. Enkelte laster krever i tillegg f.eks. ADR eller registrering som avfallstransportør. Last opp dokumentasjon – anlegget godkjenner.</p>
    </div>
    ${carrierBanner()}
    ${state.types.map(t => {
      const q = byCode[t.code];
      return `<details class="card" ${!q && t.base ? 'open' : ''}>
        <summary class="row" style="cursor:pointer"><b>${esc(t.name)} ${t.base ? '<span class="pill grey">Grunnkrav</span>' : ''}</b>${q ? pill(QUAL_PILL[q.status]) : pill(['grey', 'Ikke levert'])}</summary>
        <p class="muted small" style="margin-top:8px">${esc(t.description)}</p>
        ${q ? `<p class="small">Ref: ${esc(q.reference || '–')} · Gyldig til ${esc(day(q.valid_until))}${q.document_name ? ` · <a href="/api/qualifications/${q.id}/document" target="_blank" rel="noopener">📄 ${esc(q.document_name)}</a>` : ''}</p>` : ''}
        ${q?.review_comment ? `<div class="banner ${q.status === 'rejected' ? 'err' : 'info'} small">Anlegget: ${esc(q.review_comment)}</div>` : ''}
        <form data-code="${t.code}">
          <label>Dokumentnummer / referanse</label><input name="reference" value="${esc(q?.reference)}">
          <label>Gyldig til</label><input name="valid_until" type="date" value="${esc(q?.valid_until)}">
          <label>Dokument (PDF/bilde, maks 5 MB)</label><input name="document" type="file" accept="application/pdf,image/png,image/jpeg,image/webp">
          <div class="actions"><button>${q ? 'Send inn på nytt' : 'Send inn'}</button></div>
        </form>
      </details>`;
    }).join('')}`;

  $app.querySelectorAll('form[data-code]').forEach(form => {
    form.addEventListener('submit', e => {
      e.preventDefault();
      action(async () => {
        const file = form.document.files[0];
        const body = { reference: form.reference.value, valid_until: form.valid_until.value || null };
        if (file) {
          if (file.size > 5 * 1024 * 1024) throw new Error('Filen er større enn 5 MB');
          body.document = { name: file.name, type: file.type, data: await fileToBase64(file) };
        }
        await api(`/my/qualifications/${form.dataset.code}`, { method: 'PUT', body });
        toast('Sendt til godkjenning'); render();
      });
    });
  });
}

// ------------------------------------------------------------ varsler

async function viewNotifications() {
  const list = await api('/notifications');
  $app.innerHTML = `<div class="row"><h1>Varsler</h1>${list.some(n => !n.read) ? '<button class="secondary" id="read">Merk alle som lest</button>' : ''}</div>
    ${listOrEmpty(list, n => `<${n.load_id ? `a href="#/loads/${n.load_id}"` : 'div'} class="card notif ${n.read ? '' : 'unread'}">
      <span class="dot"></span><div>${esc(n.message)}<div class="muted small">${esc(dt(n.created_at))}</div></div></${n.load_id ? 'a' : 'div'}>`, 'Ingen varsler.')}
    <p class="actions"><button class="ghost" id="logout">Logg ut</button></p>`;
  document.getElementById('read')?.addEventListener('click', () => action(async () => {
    await api('/notifications/read', { method: 'POST' }); await refreshMe(); render();
  }));
  document.getElementById('logout').addEventListener('click', () => action(async () => {
    await api('/auth/logout', { method: 'POST' }); state.me = null; go('#/login');
  }));
}

// ------------------------------------------------------------ ruting

async function render() {
  const hash = location.hash || '#/';
  const path = hash.slice(1).split('?')[0];
  if (!state.me && !['/login', '/register'].includes(path)) await refreshMe();
  if (!state.me && !['/login', '/register'].includes(path)) { location.hash = '#/login'; return; }
  if (state.me && ['/login', '/register'].includes(path)) { location.hash = '#/'; return; }
  if (state.me && !state.types.length) state.types = await api('/qualification-types');
  renderChrome();

  const admin = state.me?.user.role === 'admin';
  const m = (re) => path.match(re);
  let id;
  try {
    if (path === '/login') viewLogin();
    else if (path === '/register') viewRegister();
    else if (path === '/notifications') await viewNotifications();
    else if (admin) {
      if (path === '/') await viewDashboard();
      else if (path === '/loads') await viewAdminLoads();
      else if (path === '/loads/new') viewNewLoad();
      else if ((id = m(/^\/loads\/(\d+)\/edit$/))) await viewEditLoad(id[1]);
      else if ((id = m(/^\/loads\/(\d+)$/))) await viewAdminLoad(id[1]);
      else if (path === '/carriers') await viewCarriers();
      else if ((id = m(/^\/carriers\/(\d+)$/))) await viewCarrier(id[1]);
      else $app.innerHTML = '<div class="empty">Siden finnes ikke.</div>';
    } else {
      if (path === '/') { await refreshMe(); renderChrome(); await viewCarrierLoads(); }
      else if (path === '/bids') await viewMyBids();
      else if (path === '/jobs') await viewJobs();
      else if (path === '/qualify') await viewQualify();
      else if ((id = m(/^\/loads\/(\d+)$/))) await viewCarrierLoad(id[1]);
      else $app.innerHTML = '<div class="empty">Siden finnes ikke.</div>';
    }
  } catch (err) {
    $app.innerHTML = `<div class="banner err">${esc(err.message)}</div>`;
  }
  window.scrollTo(0, 0);
}

window.addEventListener('hashchange', render);
render();

// Oppdater varseltelleren jevnlig.
setInterval(async () => {
  if (!state.me || document.hidden) return;
  const before = state.me.unread;
  await refreshMe();
  if (state.me && state.me.unread !== before) { renderChrome(); if (state.me.unread > before) toast('🔔 Nytt varsel'); }
}, 30000);

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
