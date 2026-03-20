'use strict';

// ── State ────────────────────────────────────────────────────────────────────
let records = [];
let activeFilter = 'ALL';
let openDetailId = null;
let config = {};
let currentPage = 0;
let pageSize = 10;
let dateFormat = localStorage.getItem('dateFormat') || 'YYYY-MM-DD';

// ── Init ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  switchTab('logs');
  loadRecords();
  loadSourceFiles();
  setInterval(() => { loadRecords(); loadSourceFiles(); }, 15_000);
});

// ── Tabs ─────────────────────────────────────────────────────────────────────
function switchTab(name) {
  document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.nav-btn').forEach(el => el.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  document.getElementById('nav-' + name).classList.add('active');

  if (name === 'settings') { loadConfig(); document.getElementById('cfg-date-format').value = dateFormat; }
}

// ── Source Folder ─────────────────────────────────────────────────────────────
async function loadSourceFiles(manual = false) {
  const grid    = document.getElementById('source-grid');
  const spinner = document.getElementById('source-spinner');
  spinner.classList.add('spinning');
  try {
    const res = await fetch('/api/source-files');
    if (!res.ok) {
      grid.innerHTML = `<div class="empty">Error loading source folder (${res.status})</div>`;
      return;
    }
    const files = await res.json();
    renderSourceFiles(files);
  } catch (e) {
    console.error('Failed to load source files', e);
    grid.innerHTML = '<div class="empty">Could not reach server.</div>';
  } finally {
    spinner.classList.remove('spinning');
  }
}

function renderSourceFiles(files) {
  const grid = document.getElementById('source-grid');
  if (files.length === 0) {
    grid.innerHTML = '<div class="empty">No media files in source folder.</div>';
    return;
  }

  grid.innerHTML = files.map(f => {
    const hasFailed = f.status && f.status.endsWith('_FAILED');
    const isPending = f.status === 'PENDING';
    const cls = hasFailed ? 'has-error' : isPending ? 'is-pending' : 'is-new';
    const onclick = hasFailed ? `toggleSourceError('${f.recordId}')` : '';
    return `
      <div class="source-row ${cls}" onclick="${onclick}" id="src-row-${f.recordId ?? f.filename}">
        <span class="source-filename" title="${esc(f.path)}">${esc(f.filename)}</span>
        ${badge(f.status)}
        <span class="source-size">${fmtSize(f.sizeBytes)}</span>
      </div>
      ${hasFailed ? `<div class="source-error-detail" id="src-err-${f.recordId}">${esc(f.errorMessage || 'Unknown error')} &mdash; ${f.retryCount} attempt(s)</div>` : ''}
    `;
  }).join('');
}

function toggleSourceError(recordId) {
  const el = document.getElementById('src-err-' + recordId);
  if (el) el.classList.toggle('open');
}

// ── Logs ─────────────────────────────────────────────────────────────────────
async function loadRecords() {
  try {
    const res = await fetch('/api/records');
    records = await res.json();
    renderStats();
    renderTable();
  } catch (e) {
    console.error('Failed to load records', e);
  }
}

function renderStats() {
  const total   = records.length;
  const moved   = records.filter(r => r.status === 'MOVED').length;
  const failed  = records.filter(r => r.status.endsWith('_FAILED')).length;
  const pending = records.filter(r => r.status === 'PENDING').length;

  document.getElementById('stat-total').textContent   = total;
  document.getElementById('stat-moved').textContent   = moved;
  document.getElementById('stat-failed').textContent  = failed;
  document.getElementById('stat-pending').textContent = pending;
}

function setFilter(f) {
  activeFilter = f;
  currentPage = 0;
  document.querySelectorAll('.filter-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.filter === f);
  });
  openDetailId = null;
  renderTable();
}

function setPageSize(val) {
  pageSize = val === 'all' ? Infinity : parseInt(val);
  currentPage = 0;
  openDetailId = null;
  renderTable();
}

function goPage(delta) {
  const filtered = filteredRecords();
  const totalPages = Math.ceil(filtered.length / pageSize);
  currentPage = Math.max(0, Math.min(currentPage + delta, totalPages - 1));
  openDetailId = null;
  renderTable();
}

function filteredRecords() {
  return records.filter(r => {
    if (activeFilter === 'ALL')     return true;
    if (activeFilter === 'SUCCESS') return r.status === 'MOVED';
    if (activeFilter === 'FAILED')  return r.status.endsWith('_FAILED');
    if (activeFilter === 'PENDING') return r.status === 'PENDING';
    return true;
  });
}

function renderTable() {
  const filtered = filteredRecords();
  const tbody = document.getElementById('records-tbody');

  if (filtered.length === 0) {
    tbody.innerHTML = `<tr><td colspan="4" class="empty">No records found.</td></tr>`;
    document.getElementById('detail-panel').classList.remove('open');
    document.getElementById('pagination').style.display = 'none';
    return;
  }

  const isAll = pageSize === Infinity;
  const totalPages = isAll ? 1 : Math.ceil(filtered.length / pageSize);
  currentPage = Math.min(currentPage, totalPages - 1);
  const page = isAll ? filtered : filtered.slice(currentPage * pageSize, (currentPage + 1) * pageSize);

  tbody.innerHTML = page.map(r => `
    <tr class="clickable" onclick="toggleDetail(${r.id})" data-id="${r.id}">
      <td><span class="filename" title="${esc(r.originalFilename)}">${esc(r.originalFilename)}</span></td>
      <td>${badge(r.status)}</td>
      <td>${fmtDate(r.createdAt)}</td>
      <td>${fmtDate(r.lastAttemptAt) || '—'}</td>
    </tr>
  `).join('');

  if (openDetailId !== null) renderDetail(openDetailId);

  // Pagination bar
  const pag = document.getElementById('pagination');
  pag.style.display = 'flex';
  document.getElementById('pag-info').textContent =
    isAll ? `${filtered.length} records` : `${currentPage + 1} / ${totalPages}  (${filtered.length} total)`;
  document.getElementById('pag-prev').disabled = currentPage === 0 || isAll;
  document.getElementById('pag-next').disabled = currentPage >= totalPages - 1 || isAll;
}

function toggleDetail(id) {
  if (openDetailId === id) {
    openDetailId = null;
    document.getElementById('detail-panel').classList.remove('open');
  } else {
    openDetailId = id;
    renderDetail(id);
  }
}

function renderDetail(id) {
  const r = records.find(x => x.id === id);
  if (!r) return;

  const panel = document.getElementById('detail-panel');
  panel.classList.add('open');

  panel.innerHTML = `
    <button class="close-btn" onclick="toggleDetail(${r.id})">✕</button>
    <h3>Record #${r.id}</h3>
    <div class="detail-grid">
      <span class="detail-label">Filename</span>
      <span class="detail-value">${esc(r.originalFilename)}</span>

      <span class="detail-label">Status</span>
      <span class="detail-value">${badge(r.status)}</span>

      <span class="detail-label">Attempts</span>
      <span class="detail-value">${r.retryCount}</span>

      <span class="detail-label">Source path</span>
      <span class="detail-value">${esc(r.sourcePath || '—')}</span>

      ${r.targetPath ? `
      <span class="detail-label">Target path</span>
      <span class="detail-value">${esc(r.targetPath)}</span>` : ''}

      ${r.errorMessage ? `
      <span class="detail-label">Error</span>
      <span class="detail-error">${esc(r.errorMessage)}</span>` : ''}

      <span class="detail-label">Created</span>
      <span class="detail-value">${fmtDateFull(r.createdAt)}</span>

      ${r.processedAt ? `
      <span class="detail-label">Processed</span>
      <span class="detail-value">${fmtDateFull(r.processedAt)}</span>` : ''}
    </div>
  `;
}

// ── Settings ─────────────────────────────────────────────────────────────────
async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    config = await res.json();
    applyConfig();
  } catch (e) {
    console.error('Failed to load config', e);
  }
}

function applyConfig() {
  setVal('cfg-source-folder', config['source.folder']);
  setVal('cfg-target-folder', config['target.folder']);
  setVal('cfg-tmdb-api-key',  config['tmdb.api-key']);
  setVal('cfg-llm-api-key',   config['llm.api-key']);
  setVal('cfg-llm-base-url',  config['llm.base-url']);
  setVal('cfg-llm-model',     config['llm.model']);

  const provider = config['llm.provider'] || 'openai';
  selectProvider(provider, false);
}

function selectProvider(p, save) {
  document.querySelectorAll('.provider-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.provider === p);
  });
  const isOpenAi = p !== 'anthropic';
  document.getElementById('openai-fields').style.display = isOpenAi ? '' : 'none';
  if (save) config['llm.provider'] = p;
}

async function saveSettings() {
  const payload = {
    'source.folder':  getVal('cfg-source-folder'),
    'target.folder':  getVal('cfg-target-folder'),
    'tmdb.api-key':   getVal('cfg-tmdb-api-key'),
    'llm.provider':   config['llm.provider'] || 'openai',
    'llm.api-key':    getVal('cfg-llm-api-key'),
    'llm.base-url':   getVal('cfg-llm-base-url'),
    'llm.model':      getVal('cfg-llm-model'),
  };

  // Strip masked values so we don't overwrite with masks
  Object.keys(payload).forEach(k => {
    if (payload[k] && /^\*+.{4}$/.test(payload[k])) delete payload[k];
  });

  try {
    await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    toast('Settings saved', 'success');
  } catch (e) {
    toast('Failed to save settings', 'error');
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function badge(status) {
  if (!status) return `<span class="badge badge-warning">New</span>`;
  const map = {
    MOVED:       ['moved',   'Moved'],
    PENDING:     ['pending', 'Pending'],
    LLM_FAILED:  ['failed',  'LLM Failed'],
    TMDB_FAILED: ['failed',  'TMDB Failed'],
    MOVE_FAILED: ['failed',  'Move Failed'],
  };
  const [cls, label] = map[status] || ['warning', status];
  return `<span class="badge badge-${cls}">${label}</span>`;
}

function fmtSize(bytes) {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}

function esc(s) {
  if (!s) return '';
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function applyDateFormat(d) {
  const pad = n => String(n).padStart(2, '0');
  return dateFormat
    .replace('YYYY', d.getFullYear())
    .replace('MM',   pad(d.getMonth() + 1))
    .replace('DD',   pad(d.getDate()));
}

function fmtDate(iso) {
  if (!iso) return null;
  return applyDateFormat(new Date(iso));
}

function fmtDateFull(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = n => String(n).padStart(2, '0');
  return `${applyDateFormat(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function setDateFormat(fmt) {
  dateFormat = fmt;
  localStorage.setItem('dateFormat', fmt);
  renderTable();
  renderStats();
}

function getVal(id) { return document.getElementById(id)?.value || ''; }
function setVal(id, v) { const el = document.getElementById(id); if (el) el.value = v || ''; }

let toastTimer;
function toast(msg, type) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = `toast show ${type}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
}
