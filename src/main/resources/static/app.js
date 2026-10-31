'use strict';
/* Sourcerer web UI — dependency-free SPA over the JSON API. Hash routing: #/r/{owner}/{repo}/{tab}?ref=&path= */

// ---------- safe templating: every interpolation is HTML-escaped unless wrapped in raw()/h`` ----------
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
class Raw { constructor(s) { this.s = s; } }
const raw = s => new Raw(s);
const val = v => v instanceof Raw ? v.s : Array.isArray(v) ? v.map(val).join('') : esc(v);
const h = (strs, ...vals) => new Raw(strs.reduce((o, s, i) => o + s + (i < vals.length ? val(vals[i]) : ''), ''));

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const enc = encodeURIComponent;
const app = $('#app');

// ---------- state & API ----------
let token = null;
try { token = localStorage.getItem('sourcerer.token'); } catch { /* storage unavailable */ }
let me = null;

class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }

function setToken(t) {
  token = t;
  try { t ? localStorage.setItem('sourcerer.token', t) : localStorage.removeItem('sourcerer.token'); } catch { /* ignore */ }
}

async function api(method, path, body, retried = false) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = 'Bearer ' + token;
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 204) return null;
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  if (!res.ok) {
    if (res.status === 401 && token && !retried) { // stale/expired token: drop it and retry anonymously
      setToken(null); me = null; renderNav();
      return api(method, path, body, true);
    }
    throw new ApiError(res.status, (data && data.error) || res.statusText || 'request failed');
  }
  return data;
}

async function loadMe() {
  me = null;
  if (!token) return;
  try { me = await api('GET', '/api/user'); } catch { me = null; }
}

// ---------- helpers ----------
let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3200);
}

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
function ago(iso) {
  if (!iso) return '';
  const s = (new Date(iso).getTime() - Date.now()) / 1000;
  for (const [unit, sec] of [['year', 31536000], ['month', 2592000], ['day', 86400], ['hour', 3600], ['minute', 60]]) {
    if (Math.abs(s) >= sec) return rtf.format(Math.round(s / sec), unit);
  }
  return 'just now';
}
const fmtSize = n => n < 0 ? '' : n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
const qs = o => { const p = Object.entries(o).filter(([, v]) => v !== '' && v != null).map(([k, v]) => enc(k) + '=' + enc(v)); return p.length ? '?' + p.join('&') : ''; };
const show = view => { app.innerHTML = view.s; };
const errorBox = e => h`<div class="alert err">${e.message || e}</div>`;

function nav(to) { location.hash = to; }
function copyText(text) {
  (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(() => toast('Copied'), () => toast('Copy failed — select and copy manually'));
}

/** Wires a <form> to an async handler; shows errors inline and disables the submit button while busy. */
function onSubmit(form, fn) {
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const btn = $('button[type=submit]', form);
    const box = $('.form-error', form);
    if (box) box.innerHTML = '';
    if (btn) btn.disabled = true;
    try { await fn(Object.fromEntries(new FormData(form))); }
    catch (e) { if (box) box.innerHTML = errorBox(e).s; else toast(e.message); }
    finally { if (btn) btn.disabled = false; }
  });
}

// ---------- nav ----------
function renderNav() {
  $('#nav').innerHTML = (me
    ? h`<a class="btn sm primary" href="#/new">+ New repository</a>
        <a href="#/u/${me.username}">${me.username}</a>
        <a href="#/settings">Settings</a>
        <a href="#/logout">Sign out</a>`
    : h`<a href="#/login">Sign in</a><a class="btn sm primary" href="#/register">Register</a>`).s;
}

// ---------- router ----------
function parseHash() {
  const raw_ = location.hash.replace(/^#/, '') || '/';
  const [p, query = ''] = raw_.split('?');
  const q = Object.fromEntries(new URLSearchParams(query));
  return { parts: p.split('/').filter(Boolean).map(decodeURIComponent), q };
}

async function route() {
  const { parts, q } = parseHash();
  window.scrollTo(0, 0);
  try {
    const [a, b] = parts;
    if (!a) return await homePage();
    if (a === 'login') return loginPage();
    if (a === 'register') return registerPage();
    if (a === 'logout') { setToken(null); me = null; renderNav(); return nav('#/'); }
    if (a === 'new') return newRepoPage();
    if (a === 'settings') return await settingsPage();
    if (a === 'u' && b) return await homePage(b);
    if (a === 'r' && parts.length >= 3) return await repoPage(parts[1], parts[2], parts.slice(3), q);
    show(h`<div class="empty">Page not found. <a href="#/">Go home</a></div>`);
  } catch (e) {
    show(h`${errorBox(e)}<p><a href="#/">← Back to repositories</a></p>`);
  }
}
window.addEventListener('hashchange', route);

// ---------- pages: auth ----------
function authForm(kind) {
  const reg = kind === 'register';
  show(h`<div class="page-head"><h1>${reg ? 'Create your account' : 'Sign in'}</h1></div>
    <form class="stack card pad" id="f">
      <div class="form-error"></div>
      <label class="field">Username<input type="text" name="username" required autocomplete="username" autofocus></label>
      ${reg ? h`<label class="field">Email<input type="email" name="email" required autocomplete="email"></label>` : ''}
      <label class="field">Password<input type="password" name="password" required minlength="${reg ? 8 : 1}" autocomplete="${reg ? 'new-password' : 'current-password'}"></label>
      <button class="btn primary" type="submit">${reg ? 'Register' : 'Sign in'}</button>
      <span class="muted">${reg ? h`Already registered? <a href="#/login">Sign in</a>` : h`New here? <a href="#/register">Create an account</a>`}</span>
    </form>`);
  onSubmit($('#f'), async d => {
    if (reg) await api('POST', '/api/auth/register', { username: d.username, email: d.email, password: d.password });
    const r = await api('POST', '/api/auth/login', { username: d.username, password: d.password });
    setToken(r.token); await loadMe(); renderNav(); nav('#/');
  });
}
const loginPage = () => authForm('login');
const registerPage = () => authForm('register');

async function settingsPage() {
  if (!me) return nav('#/login');
  show(h`<div class="page-head"><h1>Settings</h1></div>
    <div class="card pad"><b>${me.username}</b> <span class="muted">${me.email}</span></div>
    <h2>Personal access token</h2>
    <p class="muted">Use as the password for <code>git clone</code> / <code>git push</code> over HTTP (username = your username).</p>
    <form class="stack card pad" id="f">
      <div class="form-error"></div>
      <label class="field">Valid for (days, max 365)<input type="number" name="days" value="30" min="1" max="365"></label>
      <button class="btn primary" type="submit">Generate token</button>
      <div id="out"></div>
    </form>`);
  onSubmit($('#f'), async d => {
    const r = await api('POST', '/api/auth/token', { days: Number(d.days) });
    $('#out').innerHTML = h`<div class="alert ok">Copy it now — it will not be shown again.</div>
      <div class="tokenbox mono" id="tok">${r.token}</div>
      <button class="btn sm" type="button" id="cp">Copy</button>`.s;
    $('#cp').onclick = () => copyText(r.token);
  });
}

// ---------- pages: repositories ----------
async function homePage(owner) {
  const repos = await api('GET', '/api/repos' + qs({ owner, limit: 100 }));
  show(h`<div class="page-head">
      <div><h1>${owner ? owner + "'s repositories" : 'Repositories'}</h1>
        <span class="muted">${repos.length} ${repos.length === 1 ? 'repository' : 'repositories'}</span></div>
      ${me ? h`<a class="btn primary" href="#/new">+ New repository</a>` : ''}
    </div>
    <div class="card list">${repos.length ? repos.map(r => h`
      <div class="row">
        <div class="grow">
          <a class="title" href="#/r/${enc(r.owner)}/${enc(r.name)}">${r.owner} / ${r.name}</a>
          ${r.isPrivate || r.private ? h` <span class="badge">Private</span>` : ''}
          <div class="muted truncate">${r.description || 'No description'}</div>
        </div>
        <span class="muted">${ago(r.createdAt)}</span>
      </div>`) : h`<div class="empty">No repositories yet.${me ? h` <a href="#/new">Create the first one.</a>` : h` <a href="#/register">Register</a> to create one.`}</div>`}
    </div>`);
}

function newRepoPage() {
  if (!me) return nav('#/login');
  show(h`<div class="page-head"><h1>New repository</h1></div>
    <form class="stack card pad" id="f">
      <div class="form-error"></div>
      <label class="field">Name<input type="text" name="name" required pattern="[a-z0-9._-]{1,64}" title="lowercase letters, digits, '.', '_' or '-'" autofocus></label>
      <label class="field">Description<input type="text" name="description" maxlength="500"></label>
      <label class="field">Default branch<input type="text" name="defaultBranch" value="main"></label>
      <label class="check"><input type="checkbox" name="private"> Private</label>
      <label class="check"><input type="checkbox" name="initReadme" checked> Initialize with a README</label>
      <button class="btn primary" type="submit">Create repository</button>
    </form>`);
  onSubmit($('#f'), async d => {
    const r = await api('POST', '/api/repos', {
      name: d.name, description: d.description, defaultBranch: d.defaultBranch || 'main',
      private: d.private === 'on', initReadme: d.initReadme === 'on'
    });
    nav(`#/r/${enc(r.owner)}/${enc(r.name)}`);
  });
}

const TABS = [['', 'Files'], ['commits', 'Commits'], ['branches', 'Branches'], ['issues', 'Issues'], ['mrs', 'Merge requests']];

async function repoPage(owner, name, sub, q) {
  const d = await api('GET', `/api/repos/${enc(owner)}/${enc(name)}`);
  const { repo, stats } = d;
  const base = `#/r/${enc(owner)}/${enc(name)}`;
  const tab = sub[0] === 'tree' || sub[0] === 'blob' ? '' : (sub[0] || '');
  const cloneUrl = location.origin + d.cloneUrlPath;
  const ctx = { owner, name, repo, stats, base, cloneUrl, q, sub, api: `/api/repos/${enc(owner)}/${enc(name)}` };
  const isOwner = me && me.username === repo.owner;
  show(h`<div class="page-head">
      <div>
        <h1><a href="#/u/${enc(repo.owner)}">${repo.owner}</a> / ${repo.name} ${repo.isPrivate || repo.private ? h`<span class="badge">Private</span>` : ''}</h1>
        <div class="muted">${repo.description || ''}</div>
      </div>
      ${isOwner ? h`<button class="btn sm danger" id="del">Delete</button>` : ''}
    </div>
    <div class="tabs">${TABS.map(([k, label]) => h`<a href="${base}${k ? '/' + k : ''}" class="${k === tab ? 'active' : ''}">${label}</a>`)}</div>
    <div id="tab"><p class="muted">Loading…</p></div>`);
  const del = $('#del');
  if (del) del.onclick = async () => {
    if (!confirm(`Delete ${repo.owner}/${repo.name}? This cannot be undone.`)) return;
    try { await api('DELETE', ctx.api); toast('Repository deleted'); nav('#/'); } catch (e) { toast(e.message); }
  };
  const el = $('#tab');
  try {
    if (tab === '') await filesTab(ctx, el);
    else if (tab === 'commits') await commitsTab(ctx, el);
    else if (tab === 'branches') await branchesTab(ctx, el);
    else if (tab === 'issues') await issuesTab(ctx, el);
    else if (tab === 'mrs') await mrsTab(ctx, el);
    else el.innerHTML = h`<div class="empty">Unknown tab.</div>`.s;
  } catch (e) { el.innerHTML = errorBox(e).s; }
}

function refSelect(branches, ref) {
  return h`<select id="ref" aria-label="Branch">${branches.map(b => h`<option value="${b.name}" ${b.name === ref ? raw('selected') : ''}>${b.name}</option>`)}</select>`;
}

// ---------- files ----------
function emptyRepo(ctx) {
  return h`<div class="card pad"><h2 style="margin-top:0">This repository is empty</h2>
    <p class="muted">Push your first commit:</p>
    <pre class="mono" style="overflow:auto">git clone ${ctx.cloneUrl}
cd ${ctx.name}
git checkout -b ${ctx.repo.defaultBranch}
git add . &amp;&amp; git commit -m "Initial commit"
git push -u origin ${ctx.repo.defaultBranch}</pre>
    <p class="muted">Pushing requires a <a href="#/settings">personal access token</a> as the password.</p></div>`;
}

async function filesTab(ctx, el) {
  if (ctx.stats.empty) { el.innerHTML = emptyRepo(ctx).s; return; }
  const ref = ctx.q.ref || ctx.repo.defaultBranch;
  const path = (ctx.q.path || '').replace(/^\/+|\/+$/g, '');
  const isBlob = ctx.sub[0] === 'blob';
  const branches = await api('GET', ctx.api + '/branches');
  const link = (kind, p) => `${ctx.base}/${kind}${qs({ ref, path: p })}`;

  const segs = path ? path.split('/') : [];
  const crumbs = h`<span class="crumbs"><a href="${link('tree', '')}">${ctx.name}</a>${segs.map((s, i) =>
    h`<span> / </span><a href="${link(i === segs.length - 1 && isBlob ? 'blob' : 'tree', segs.slice(0, i + 1).join('/'))}">${s}</a>`)}</span>`;
  const toolbar = h`<div class="toolbar">${refSelect(branches, ref)}${crumbs}
    <span class="grow" style="flex:1"></span>
    <div class="clone"><input type="text" readonly value="${ctx.cloneUrl}" size="38" id="clone" aria-label="Clone URL"><button class="btn sm" id="cp" type="button">Copy</button></div></div>`;

  let body;
  if (isBlob) {
    const r = await api('GET', ctx.api + '/blob' + qs({ ref, path }));
    const b = r.blob;
    body = h`<div class="card"><div class="card-head"><span>${segs[segs.length - 1] || ''} <span class="muted">· ${fmtSize(b.size)}</span></span>
        <button class="btn sm" id="dl" type="button">Download</button></div>
      ${b.binary ? h`<div class="empty">Binary file — preview not available.</div>`
        : h`${b.truncated ? h`<div class="alert warn" style="margin:12px">File is large; showing the first part only.</div>` : ''}<pre class="code mono">${b.content}</pre>`}</div>`;
  } else {
    const r = await api('GET', ctx.api + '/tree' + qs({ ref, path }));
    const entries = r.entries;
    const parent = segs.slice(0, -1).join('/');
    body = h`<div class="card list">
      ${segs.length ? h`<div class="file-row"><span class="icon">↰</span><a href="${link('tree', parent)}">..</a></div>` : ''}
      ${entries.length ? entries.map(e => {
        const p = (path ? path + '/' : '') + e.name;
        return h`<div class="file-row"><span class="icon">${e.type === 'tree' ? '📁' : e.type === 'commit' ? '🔗' : '📄'}</span>
          ${e.type === 'commit' ? h`<span>${e.name}</span>` : h`<a href="${link(e.type === 'tree' ? 'tree' : 'blob', p)}">${e.name}</a>`}
          <span class="size">${e.type === 'blob' ? fmtSize(e.size) : ''}</span></div>`;
      }) : h`<div class="empty">Empty directory.</div>`}</div>`;
    const readme = entries.find(e => e.type === 'blob' && /^readme(\.(md|markdown|txt|rst))?$/i.test(e.name));
    var readmePromise = readme ? api('GET', ctx.api + '/blob' + qs({ ref, path: (path ? path + '/' : '') + readme.name })) : null;
    var readmeName = readme && readme.name;
  }
  el.innerHTML = h`${toolbar}${body}<div id="readme"></div>`.s;

  $('#ref').onchange = e => nav(`${ctx.base}/tree${qs({ ref: e.target.value })}`);
  $('#cp').onclick = () => copyText(ctx.cloneUrl);
  $('#clone').onfocus = e => e.target.select();
  const dl = $('#dl');
  if (dl) dl.onclick = () => downloadRaw(ctx, ref, path);
  if (readmePromise) {
    try {
      const rb = (await readmePromise).blob;
      if (!rb.binary) $('#readme').innerHTML = h`<h2>${readmeName}</h2><div class="card"><pre class="code mono" style="white-space:pre-wrap;border-radius:10px">${rb.content}</pre></div>`.s;
    } catch { /* README is optional */ }
  }
}

async function downloadRaw(ctx, ref, path) {
  try {
    const res = await fetch(ctx.api + '/raw' + qs({ ref, path }), { headers: token ? { Authorization: 'Bearer ' + token } : {} });
    if (!res.ok) throw new Error('download failed (' + res.status + ')');
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url; a.download = path.split('/').pop() || 'file';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) { toast(e.message); }
}

// ---------- commits & branches ----------
async function commitsTab(ctx, el) {
  if (ctx.stats.empty) { el.innerHTML = emptyRepo(ctx).s; return; }
  const ref = ctx.q.ref || ctx.repo.defaultBranch;
  const page = Math.max(1, parseInt(ctx.q.page || '1', 10) || 1);
  const perPage = 30;
  const [branches, r] = await Promise.all([
    api('GET', ctx.api + '/branches'),
    api('GET', ctx.api + '/commits' + qs({ ref, page, perPage }))
  ]);
  const pg = p => `${ctx.base}/commits${qs({ ref, page: p })}`;
  el.innerHTML = h`<div class="toolbar">${refSelect(branches, ref)}</div>
    <div class="card list">${r.commits.length ? r.commits.map(c => h`
      <div class="row"><div class="grow"><div class="title truncate" title="${c.message}">${c.shortMessage}</div>
        <div class="muted">${c.authorName} · ${ago(c.time)}</div></div>
        <code class="badge">${c.sha.slice(0, 8)}</code></div>`) : h`<div class="empty">No commits on this page.</div>`}</div>
    <div class="toolbar" style="margin-top:12px">
      ${page > 1 ? h`<a class="btn sm" href="${pg(page - 1)}">← Newer</a>` : ''}
      ${r.commits.length === perPage ? h`<a class="btn sm" href="${pg(page + 1)}">Older →</a>` : ''}</div>`.s;
  $('#ref').onchange = e => nav(`${ctx.base}/commits${qs({ ref: e.target.value })}`);
}

async function branchesTab(ctx, el) {
  if (ctx.stats.empty) { el.innerHTML = emptyRepo(ctx).s; return; }
  const branches = await api('GET', ctx.api + '/branches');
  el.innerHTML = h`<div class="stats" style="margin-bottom:12px"><span><b>${ctx.stats.branches}</b> branches</span><span><b>${ctx.stats.tags}</b> tags</span></div>
    <div class="card list">${branches.map(b => h`
      <div class="row"><div class="grow"><a class="title" href="${ctx.base}/tree${qs({ ref: b.name })}">${b.name}</a>
        ${b.isDefault || b.default ? h` <span class="badge">default</span>` : ''}</div>
        <code class="muted">${(b.sha || '').slice(0, 8)}</code></div>`)}</div>`.s;
}

// ---------- issues ----------
const stateFilter = (base, tab, cur) => h`<div class="toolbar">${['open', 'closed', 'all'].map(s =>
  h`<a class="btn sm ${s === cur ? 'primary' : ''}" href="${base}/${tab}${qs({ state: s })}">${s[0].toUpperCase() + s.slice(1)}</a>`)}</div>`;

async function issuesTab(ctx, el) {
  const [, num, extra] = ctx.sub;
  if (num === 'new') return newIssue(ctx, el);
  if (num) return issueDetail(ctx, el, parseInt(num, 10));
  const state = ctx.q.state || 'open';
  const list = await api('GET', ctx.api + '/issues' + qs({ state: state === 'all' ? '' : state, limit: 100 }));
  el.innerHTML = h`<div class="page-head" style="margin:0 0 4px">${stateFilter(ctx.base, 'issues', state)}
      ${me ? h`<a class="btn primary" href="${ctx.base}/issues/new">New issue</a>` : ''}</div>
    <div class="card list">${list.length ? list.map(i => h`
      <div class="row"><div class="grow"><a class="title" href="${ctx.base}/issues/${i.number}">${i.title}</a>
        <div class="muted">#${i.number} opened ${ago(i.createdAt)} by ${i.author}</div></div>
        <span class="badge ${i.state}">${i.state}</span></div>`) : h`<div class="empty">No ${state === 'all' ? '' : state} issues.</div>`}</div>`.s;
}

function newIssue(ctx, el) {
  if (!me) { el.innerHTML = h`<div class="alert warn">Please <a href="#/login">sign in</a> to open an issue.</div>`.s; return; }
  el.innerHTML = h`<form class="stack card pad" id="f"><div class="form-error"></div>
    <label class="field">Title<input type="text" name="title" required autofocus></label>
    <label class="field">Description<textarea name="body"></textarea></label>
    <div><button class="btn primary" type="submit">Submit issue</button> <a class="btn" href="${ctx.base}/issues">Cancel</a></div></form>`.s;
  onSubmit($('#f'), async d => {
    const i = await api('POST', ctx.api + '/issues', { title: d.title, body: d.body });
    nav(`${ctx.base}/issues/${i.number}`);
  });
}

async function issueDetail(ctx, el, number) {
  const [issue, comments] = await Promise.all([
    api('GET', `${ctx.api}/issues/${number}`),
    api('GET', `${ctx.api}/issues/${number}/comments`)
  ]);
  const closed = issue.state === 'closed';
  el.innerHTML = h`<p><a href="${ctx.base}/issues">← All issues</a></p>
    <div class="page-head"><div><h1>${issue.title} <span class="muted">#${issue.number}</span></h1>
      <span class="badge ${issue.state}">${issue.state}</span> <span class="muted">opened ${ago(issue.createdAt)} by ${issue.author}</span></div>
      ${me ? h`<button class="btn" id="toggle">${closed ? 'Reopen issue' : 'Close issue'}</button>` : ''}</div>
    <div class="card comment"><div class="who">${issue.author}</div><div class="body-text">${issue.body || 'No description provided.'}</div></div>
    ${comments.map(c => h`<div class="card comment" style="margin-top:10px"><div class="who">${c.author} <span class="muted">· ${ago(c.createdAt)}</span></div><div class="body-text">${c.body}</div></div>`)}
    ${me ? h`<form class="stack" id="f" style="margin-top:16px;max-width:none"><div class="form-error"></div>
      <textarea name="body" required placeholder="Leave a comment"></textarea>
      <div><button class="btn primary" type="submit">Comment</button></div></form>`
      : h`<p class="muted" style="margin-top:16px"><a href="#/login">Sign in</a> to comment.</p>`}`.s;
  const t = $('#toggle');
  if (t) t.onclick = async () => {
    try { await api('PATCH', `${ctx.api}/issues/${number}`, { state: closed ? 'open' : 'closed' }); await issueDetail(ctx, el, number); }
    catch (e) { toast(e.message); }
  };
  const f = $('#f');
  if (f) onSubmit(f, async d => { await api('POST', `${ctx.api}/issues/${number}/comments`, { body: d.body }); await issueDetail(ctx, el, number); });
}

// ---------- merge requests ----------
async function mrsTab(ctx, el) {
  const [, num] = ctx.sub;
  if (num === 'new') return newMr(ctx, el);
  if (num) return mrDetail(ctx, el, parseInt(num, 10));
  const state = ctx.q.state || 'open';
  const list = await api('GET', ctx.api + '/merge-requests' + qs({ state: state === 'all' ? '' : state, limit: 100 }));
  el.innerHTML = h`<div class="page-head" style="margin:0 0 4px">${stateFilter(ctx.base, 'mrs', state)}
      ${me && !ctx.stats.empty ? h`<a class="btn primary" href="${ctx.base}/mrs/new">New merge request</a>` : ''}</div>
    <div class="card list">${list.length ? list.map(m => h`
      <div class="row"><div class="grow"><a class="title" href="${ctx.base}/mrs/${m.number}">${m.title}</a>
        <div class="muted">!${m.number} · <code>${m.sourceBranch}</code> → <code>${m.targetBranch}</code> · ${ago(m.createdAt)} by ${m.author}</div></div>
        <span class="badge ${m.state}">${m.state}</span></div>`) : h`<div class="empty">No ${state === 'all' ? '' : state} merge requests.</div>`}</div>`.s;
}

async function newMr(ctx, el) {
  if (!me) { el.innerHTML = h`<div class="alert warn">Please <a href="#/login">sign in</a> to open a merge request.</div>`.s; return; }
  const branches = await api('GET', ctx.api + '/branches');
  if (branches.length < 2) {
    el.innerHTML = h`<div class="alert warn">A merge request needs at least two branches. Push a feature branch first.</div>`.s; return;
  }
  const def = branches.find(b => b.isDefault || b.default) || branches[0];
  const other = branches.find(b => b.name !== def.name);
  const opts = sel => branches.map(b => h`<option value="${b.name}" ${b.name === sel ? raw('selected') : ''}>${b.name}</option>`);
  el.innerHTML = h`<form class="stack card pad" id="f"><div class="form-error"></div>
    <label class="field">Source branch (changes)<select name="sourceBranch">${opts(other.name)}</select></label>
    <label class="field">Target branch (merge into)<select name="targetBranch">${opts(def.name)}</select></label>
    <label class="field">Title<input type="text" name="title" required></label>
    <label class="field">Description<textarea name="description"></textarea></label>
    <div><button class="btn primary" type="submit">Create merge request</button> <a class="btn" href="${ctx.base}/mrs">Cancel</a></div></form>`.s;
  onSubmit($('#f'), async d => {
    const m = await api('POST', ctx.api + '/merge-requests', d);
    nav(`${ctx.base}/mrs/${m.number}`);
  });
}

function renderDiff(text) {
  if (!text || !text.trim()) return h`<div class="empty">No changes.</div>`;
  const cls = l => l.startsWith('@@') ? 'hunk' : l.startsWith('+++') || l.startsWith('---') || l.startsWith('diff ') || l.startsWith('index ') ? 'meta'
    : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : '';
  return h`<div class="diff mono">${text.replace(/\n$/, '').split('\n').map(l => h`<div class="${cls(l)}">${l || ' '}</div>`)}</div>`;
}

async function mrDetail(ctx, el, number) {
  const r = await api('GET', `${ctx.api}/merge-requests/${number}?check=true`);
  const m = r.mergeRequest, mg = r.mergeability;
  const diff = await api('GET', `${ctx.api}/merge-requests/${number}/diff`).catch(e => ({ diff: null, error: e.message }));
  const open = m.state === 'open';
  const isOwner = me && me.username === ctx.repo.owner;
  el.innerHTML = h`<p><a href="${ctx.base}/mrs">← All merge requests</a></p>
    <div class="page-head"><div><h1>${m.title} <span class="muted">!${m.number}</span></h1>
      <span class="badge ${m.state}">${m.state}</span>
      <span class="muted"><code>${m.sourceBranch}</code> → <code>${m.targetBranch}</code> · by ${m.author} · ${ago(m.createdAt)}</span></div>
      <div class="toolbar" style="margin:0">
        ${open && me ? h`<button class="btn" id="close">Close</button>` : ''}
        ${!open && m.state === 'closed' && me ? h`<button class="btn" id="reopen">Reopen</button>` : ''}
        ${open && isOwner ? h`<button class="btn primary" id="merge" ${mg && !mg.success ? raw('disabled') : ''}>Merge</button>` : ''}</div></div>
    ${m.description ? h`<div class="card comment"><div class="body-text">${m.description}</div></div>` : ''}
    ${m.state === 'merged' ? h`<div class="alert ok" style="margin-top:12px">Merged ${ago(m.mergedAt)}${m.mergeCommit ? h` as <code>${m.mergeCommit.slice(0, 8)}</code>` : ''}.</div>` : ''}
    ${open && mg ? (mg.success
      ? h`<div class="alert ok" style="margin-top:12px">✓ This branch can be merged without conflicts.</div>`
      : h`<div class="alert err" style="margin-top:12px">Merge conflicts in: ${(mg.conflicts || []).map(c => h`<code>${c}</code> `)}</div>`) : ''}
    ${open && me && !isOwner ? h`<p class="muted">Only the repository owner can merge.</p>` : ''}
    <h2>Changes</h2><div class="card">${diff.diff == null ? h`<div class="empty">${diff.error || 'Diff unavailable.'}</div>` : renderDiff(diff.diff)}</div>`.s;

  const patch = async state => {
    try { await api('PATCH', `${ctx.api}/merge-requests/${number}`, { state }); await mrDetail(ctx, el, number); }
    catch (e) { toast(e.message); }
  };
  if ($('#close')) $('#close').onclick = () => patch('closed');
  if ($('#reopen')) $('#reopen').onclick = () => patch('open');
  if ($('#merge')) $('#merge').onclick = async () => {
    const b = $('#merge'); b.disabled = true;
    try { await api('POST', `${ctx.api}/merge-requests/${number}/merge`); toast('Merged'); await mrDetail(ctx, el, number); }
    catch (e) { toast(e.message); b.disabled = false; }
  };
}

// ---------- boot ----------
(async function boot() {
  await loadMe();
  renderNav();
  route();
})();
