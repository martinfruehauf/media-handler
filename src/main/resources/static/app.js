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
  checkSetupNeeded();
  switchTab('logs');
  loadControlState();
  const pipelineVisible = localStorage.getItem('pipelineControlVisible') === 'true';
  document.getElementById('dev-pipeline-visible').checked = pipelineVisible;
  document.getElementById('pipeline-control-bar').style.display = pipelineVisible ? 'flex' : 'none';
  loadRecords();
  loadSourceFiles();
  checkHealth();
  setInterval(() => { loadRecords(); loadSourceFiles(); }, 15_000);
  setInterval(checkHealth, 60_000);

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
  const grid    = document.getElementById('source-grid');
  const section = document.getElementById('source-section');
  const retryBtn = document.getElementById('retry-all-btn');

  const anyFailed = files.some(f => f.status && f.status.endsWith('_FAILED'));
  const hasFiles  = files.length > 0;

  if (section) {
    section.className = 'source-section ' + (
      !hasFiles ? 'source-section--clear' :
      anyFailed ? 'source-section--failed' :
                  'source-section--pending'
    );
  }
  if (retryBtn) retryBtn.style.display = anyFailed ? '' : 'none';

  if (!hasFiles) {
    grid.innerHTML = '<div class="empty">Source folder is clear — nothing to process.</div>';
    return;
  }

  grid.innerHTML = files.map(f => {
    const hasFailed = f.status && f.status.endsWith('_FAILED');
    const isPending = f.status === 'PENDING';
    const cls = hasFailed ? 'has-error' : isPending ? 'is-pending' : 'is-new';
    const safeId = (f.recordId ?? f.filename).toString().replace(/[^a-z0-9_-]/gi, '_');
    const rowId  = 'src-row-' + safeId;
    const rowOnclick = hasFailed ? `toggleSourceError('${f.recordId}')` : '';
    return `
      <div class="source-row ${cls}" onclick="${rowOnclick}" id="${rowId}">
        <span class="source-filename" title="${esc(f.path)}">${esc(f.filename)}</span>
        ${badge(f.status)}
        <span class="source-size">${fmtSize(f.sizeBytes)}</span>
        <span class="source-actions">
          <button class="source-action-btn"
            data-path="${esc(f.path)}"
            data-filename="${esc(f.filename)}"
            data-rowid="${rowId}"
            onclick="event.stopPropagation(); startRename(this.dataset.path, this.dataset.filename, this.dataset.rowid)"
            title="Rename file">&#9998; Rename</button>
          ${hasFailed && f.recordId ? `
          <button class="source-action-btn"
            onclick="event.stopPropagation(); retryFile(${f.recordId})"
            title="Reprocess this file">&#8635; Reprocess</button>
          <button class="source-action-btn danger"
            onclick="event.stopPropagation(); skipFile(${f.recordId})"
            title="Exclude this file">&#10005; Exclude</button>` : ''}
          ${f.status === 'SKIPPED' && f.recordId ? `
          <button class="source-action-btn"
            onclick="event.stopPropagation(); unskipFile(${f.recordId})"
            title="Re-include this file for processing">&#8635; Re-include</button>` : ''}
        </span>
      </div>
      ${hasFailed ? `<div class="source-error-detail" id="src-err-${f.recordId}">${esc(f.errorMessage || 'Unknown error')} &mdash; ${f.retryCount} attempt(s)</div>` : ''}
    `;
  }).join('');
}

function toggleSourceError(recordId) {
  const el = document.getElementById('src-err-' + recordId);
  if (el) el.classList.toggle('open');
}

// ── Source folder actions ─────────────────────────────────────────────────────
async function rescanSourceFolder() {
  const btn = document.getElementById('rescan-btn');
  btn.disabled = true;
  btn.textContent = 'Scanning…';
  try {
    const res  = await fetch('/api/source-files/rescan', { method: 'POST' });
    const data = await res.json();
    toast(`Rescan queued ${data.queued} file(s) for processing`, 'success');
    setTimeout(() => { loadSourceFiles(); loadRecords(); }, 800);
  } catch (e) {
    toast('Rescan failed', 'error');
  } finally {
    btn.disabled = false;
    btn.innerHTML = '&#8635; Reprocess';
  }
}

async function retryAllFailed() {
  const btn = document.getElementById('retry-all-btn');
  btn.disabled = true;
  btn.textContent = 'Retrying…';
  try {
    const res  = await fetch('/api/records/retry-failed', { method: 'POST' });
    const data = await res.json();
    toast(`Retrying ${data.queued} file(s)`, 'success');
    setTimeout(() => { loadSourceFiles(); loadRecords(); }, 1000);
  } catch (e) {
    toast('Failed to queue retry', 'error');
  } finally {
    btn.disabled = false;
    btn.innerHTML = '&#8634; Retry Failed';
  }
}

async function skipFile(recordId) {
  try {
    await fetch(`/api/records/${recordId}/skip`, { method: 'POST' });
    toast('File excluded', 'success');
    loadSourceFiles();
  } catch (e) {
    toast('Failed to exclude file', 'error');
  }
}

async function unskipFile(recordId) {
  try {
    await fetch(`/api/records/${recordId}/unskip`, { method: 'POST' });
    toast('File re-included for processing', 'success');
    loadSourceFiles();
  } catch (e) {
    toast('Failed to re-include file', 'error');
  }
}

async function retryFile(recordId) {
  try {
    await fetch(`/api/records/${recordId}/retry`, { method: 'POST' });
    toast('File queued for reprocessing', 'success');
    setTimeout(() => { loadSourceFiles(); loadRecords(); }, 800);
  } catch (e) {
    toast('Failed to reprocess file', 'error');
  }
}

function startRename(path, filename, rowId) {
  const existingRow = document.getElementById('rename-row-' + rowId);
  if (existingRow) { existingRow.remove(); return; }

  const sourceRow = document.getElementById(rowId);
  if (!sourceRow) return;

  const renameRow = document.createElement('div');
  renameRow.id = 'rename-row-' + rowId;
  renameRow.className = 'source-rename-row';
  renameRow.innerHTML = `
    <input class="source-rename-input" id="rename-input-${rowId}" type="text" value="${esc(filename)}" />
    <button class="source-action-btn" onclick="submitRename(${JSON.stringify(path)}, '${rowId}')">Save</button>
    <button class="source-action-btn" onclick="cancelRename('${rowId}')">Cancel</button>
  `;
  sourceRow.insertAdjacentElement('afterend', renameRow);

  const input = document.getElementById('rename-input-' + rowId);
  // Select the name part before the extension
  const dotIdx = filename.lastIndexOf('.');
  input.focus();
  input.setSelectionRange(0, dotIdx > 0 ? dotIdx : filename.length);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter')  submitRename(path, rowId);
    if (e.key === 'Escape') cancelRename(rowId);
  });
}

function cancelRename(rowId) {
  const el = document.getElementById('rename-row-' + rowId);
  if (el) el.remove();
}

async function submitRename(path, rowId) {
  const input = document.getElementById('rename-input-' + rowId);
  if (!input) return;
  const newName = input.value.trim();
  if (!newName) return;

  try {
    const res = await fetch('/api/source-files/rename', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: path, newName }),
    });
    if (!res.ok) {
      const err = await res.json();
      toast('Rename failed: ' + (err.error || 'Unknown error'), 'error');
      return;
    }
    toast('Renamed — processing will restart', 'success');
    cancelRename(rowId);
    setTimeout(() => { loadSourceFiles(); loadRecords(); }, 500);
  } catch (e) {
    toast('Rename failed', 'error');
  }
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
  const btn = document.getElementById('pipeline-btn');
  btn.disabled = true;
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
  } finally {
    btn.disabled = false;
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
  document.getElementById('cfg-folder-cleanup-enabled').checked = config['folder.cleanup.enabled'] !== 'false';
  document.getElementById('cfg-wiki-title-lookup').checked = config['wiki.title.lookup'] === 'true';

  const provider = config['llm.provider'] || 'openai';
  selectProvider(provider, false);
}

function toggleDevSettings() {
  const body    = document.getElementById('dev-settings-body');
  const chevron = document.getElementById('dev-settings-chevron');
  const open    = body.style.display === 'block';
  body.style.display = open ? 'none' : 'block';
  chevron.classList.toggle('open', !open);
}

function setPipelineControlVisible(visible) {
  document.getElementById('pipeline-control-bar').style.display = visible ? 'flex' : 'none';
  localStorage.setItem('pipelineControlVisible', visible);
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
    'folder.cleanup.enabled': document.getElementById('cfg-folder-cleanup-enabled').checked.toString(),
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

// ── Health indicators ─────────────────────────────────────────────────────────
async function checkHealth() {
  try {
    const res = await fetch('/api/health');
    if (!res.ok) return;
    const { tmdb, llm } = await res.json();
    updateHealthIndicator('health-tmdb', tmdb);
    updateHealthIndicator('health-llm',  llm);
  } catch (e) { /* silent — don't disrupt normal use on network hiccup */ }
}

function updateHealthIndicator(id, status) {
  const el = document.getElementById(id);
  if (!el) return;
  el.className = 'health-indicator ' + (status.ok ? 'ok' : 'err');
  el.title = status.message || '';
}

// ── Self-update ───────────────────────────────────────────────────────────────
async function updateApp() {
  if (!confirm(
    'This will download the latest release from GitHub and restart the service.\n\n' +
    'The page will reload automatically after ~30 seconds.\n\nProceed?'
  )) return;
  try {
    const res = await fetch('/api/admin/update', { method: 'POST' });
    if (res.status === 400) {
      const data = await res.json();
      toast(data.error || 'Update not available in this environment', 'error');
      return;
    }
    toast('Update in progress — page will reload in 30s…', 'success');
    setTimeout(() => location.reload(), 30_000);
  } catch (e) {
    toast('Update request failed', 'error');
  }
}

// ── First-run setup wizard ────────────────────────────────────────────────────
let suProvider = 'openai';

async function checkSetupNeeded() {
  try {
    const res = await fetch('/api/setup-status');
    const { needsSetup } = await res.json();
    if (needsSetup) {
      document.getElementById('setup-overlay').style.display = 'flex';
      document.querySelector('nav').style.visibility = 'hidden';
    }
  } catch (e) { /* silent — don't block normal load on error */ }
}

function suSelectProvider(p) {
  suProvider = p;
  document.querySelectorAll('#setup-overlay .provider-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.provider === p);
  });
  document.getElementById('su-llm-url-field').style.display = p !== 'anthropic' ? '' : 'none';
}

async function saveSetup() {
  const source = getVal('su-source'), movies = getVal('su-movies'),
        shows  = getVal('su-shows'),  tmdb   = getVal('su-tmdb');
  if (!source || !movies || !shows || !tmdb) {
    showSetupError('Source folder, both target folders, and TMDB key are required.');
    return;
  }
  const payload = {
    'source.folder':        source,
    'target.folder.movies': movies,
    'target.folder.shows':  shows,
    'tmdb.api-key':         tmdb,
    'llm.provider':         suProvider,
    'llm.api-key':          getVal('su-llm-key') || 'ollama',
    'llm.base-url':         getVal('su-llm-url') || 'http://localhost:11434',
    'llm.model':            getVal('su-llm-model') || 'qwen2.5:14b',
  };
  try {
    await fetch('/api/config', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    document.getElementById('setup-overlay').style.display = 'none';
    document.querySelector('nav').style.visibility = '';
    loadRecords();
    loadSourceFiles();
    toast('Setup complete — MediaHandler is running', 'success');
  } catch (e) {
    showSetupError('Failed to save settings. Please try again.');
  }
}

function showSetupError(msg) {
  const el = document.getElementById('setup-error');
  el.textContent = msg;
  el.style.display = 'block';
}
