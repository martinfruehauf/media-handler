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
  loadControlState();
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

      ${r.sourceDeleteAfter ? `
      <span class="detail-label">Original deletion</span>
      <span class="detail-value">${fmtDateFull(r.sourceDeleteAfter)}</span>` : ''}

      ${r.processingNotes ? `
      <span class="detail-label" style="grid-column:1/-1; margin-top:8px; font-size:11px; text-transform:uppercase; letter-spacing:.05em;">Processing Steps</span>
      ${JSON.parse(r.processingNotes).map(n => `
        <span class="detail-label">${esc(n.step)}</span>
        <span class="detail-value" style="font-family:monospace;font-size:12px;">${esc(n.detail)}</span>
      `).join('')}` : ''}
    </div>
  `;
}

// ── Pipeline control ─────────────────────────────────────────────────────────
let pipelineRunning = true;

async function loadControlState() {
  try {
    const res = await fetch('/api/control');
    const data = await res.json();
    applyControlState(data.running);
  } catch (e) {
    console.error('Failed to load control state', e);
  }
}

async function togglePipeline() {
  const desired = !pipelineRunning;
  try {
    const res = await fetch('/api/control', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ running: desired }),
    });
    const data = await res.json();
    applyControlState(data.running);
    if (data.running) {
      toast('Pipeline started — re-scanning source folder', 'success');
      loadRecords();
      loadSourceFiles();
    } else {
      toast('Pipeline stopped', 'error');
    }
  } catch (e) {
    toast('Failed to change pipeline state', 'error');
  }
}

function applyControlState(running) {
  pipelineRunning = running;
  const status = document.getElementById('pipeline-status');
  const btn    = document.getElementById('pipeline-btn');
  if (running) {
    status.textContent = 'Running';
    status.className = 'pipeline-status running';
    btn.textContent = '■';
    btn.className = 'pipeline-btn btn-stop';
    btn.title = 'Stop processing';
  } else {
    status.textContent = 'Stopped';
    status.className = 'pipeline-status stopped';
    btn.textContent = '▶';
    btn.className = 'pipeline-btn btn-play';
    btn.title = 'Start processing';
  }
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
  setVal('cfg-source-folder',        config['source.folder']);
  setVal('cfg-target-folder-movies', config['target.folder.movies']);
  setVal('cfg-target-folder-shows',  config['target.folder.shows']);
  setVal('cfg-tmdb-api-key',  config['tmdb.api-key']);
  setVal('cfg-llm-api-key',   config['llm.api-key']);
  setVal('cfg-llm-base-url',  config['llm.base-url']);
  setVal('cfg-llm-model',     config['llm.model']);
  document.getElementById('cfg-file-overwrite').checked = config['file.overwrite'] === 'true';
  document.getElementById('cfg-file-copy-mode').checked = config['file.copy.mode'] === 'true';
  setVal('cfg-file-delete-original-after-hours', config['file.delete.original.after.hours'] || '0');
  toggleDeleteAfter();
  document.getElementById('cfg-wiki-title-lookup').checked = config['wiki.title.lookup'] === 'true';

  const provider = config['llm.provider'] || 'openai';
  selectProvider(provider, false);
}

function toggleDeleteAfter() {
  const copyMode = document.getElementById('cfg-file-copy-mode').checked;
  document.getElementById('delete-after-field').style.display = copyMode ? '' : 'none';
}

function selectProvider(p, save) {
  document.querySelectorAll('.provider-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.provider === p);
  });
  const isOpenAi = p !== 'anthropic';
  document.getElementById('openai-fields').style.display = isOpenAi ? '' : 'none';
  if (save) config['llm.provider'] = p;
}

async function deleteLogs() {
  if (!confirm('Delete all processing logs? This cannot be undone.')) return;
  try {
    await fetch('/api/records', { method: 'DELETE' });
    records = [];
    renderStats();
    renderTable();
    toast('All logs deleted', 'success');
  } catch (e) {
    toast('Failed to delete logs', 'error');
  }
}

async function saveSettings() {
  const payload = {
    'source.folder':        getVal('cfg-source-folder'),
    'target.folder.movies': getVal('cfg-target-folder-movies'),
    'target.folder.shows':  getVal('cfg-target-folder-shows'),
    'tmdb.api-key':   getVal('cfg-tmdb-api-key'),
    'llm.provider':   config['llm.provider'] || 'openai',
    'llm.api-key':    getVal('cfg-llm-api-key'),
    'llm.base-url':   getVal('cfg-llm-base-url'),
    'llm.model':      getVal('cfg-llm-model'),
    'file.overwrite': document.getElementById('cfg-file-overwrite').checked.toString(),
    'file.copy.mode': document.getElementById('cfg-file-copy-mode').checked.toString(),
    'file.delete.original.after.hours': getVal('cfg-file-delete-original-after-hours') || '0',
    'wiki.title.lookup': document.getElementById('cfg-wiki-title-lookup').checked.toString(),
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
    SKIPPED:     ['skipped', 'Skipped'],
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
