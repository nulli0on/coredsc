'use strict';

const state = {
  token: '',
  session: null,
  files: [],
  active: null,
  revision: '',
  original: '',
  busy: false,
};

const elements = {
  sessionCard: document.querySelector('.session-card'),
  sessionState: document.querySelector('#session-state'),
  sessionExpiry: document.querySelector('#session-expiry'),
  fileCount: document.querySelector('#file-count'),
  fileList: document.querySelector('#file-list'),
  activeFile: document.querySelector('#active-file'),
  editor: document.querySelector('#editor'),
  documentState: document.querySelector('#document-state'),
  reloadButton: document.querySelector('#reload-button'),
  validateButton: document.querySelector('#validate-button'),
  saveButton: document.querySelector('#save-button'),
  resultPanel: document.querySelector('#result-panel'),
  resultTitle: document.querySelector('#result-title'),
  resultMessage: document.querySelector('#result-message'),
  warningList: document.querySelector('#warning-list'),
};

function acquireToken() {
  const fragment = new URLSearchParams(window.location.hash.slice(1));
  const supplied = fragment.get('token');
  if (supplied) sessionStorage.setItem('coredsc-webeditor-token', supplied);
  history.replaceState(null, '', window.location.pathname);
  return supplied || sessionStorage.getItem('coredsc-webeditor-token') || '';
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('Authorization', `Bearer ${state.token}`);
  const response = await fetch(path, {...options, headers, cache: 'no-store'});
  let payload = {};
  try { payload = await response.json(); } catch { /* non-JSON server failure */ }
  if (!response.ok) {
    if (response.status === 401) sessionStorage.removeItem('coredsc-webeditor-token');
    const error = new Error(payload.error || `Request failed with HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return payload;
}

function setBusy(busy) {
  state.busy = busy;
  const loaded = Boolean(state.active);
  elements.reloadButton.disabled = busy || !loaded;
  elements.validateButton.disabled = busy || !loaded;
  elements.saveButton.disabled = busy || !loaded || elements.editor.value === state.original;
  elements.editor.disabled = busy || !loaded;
}

function renderFiles() {
  elements.fileList.replaceChildren();
  for (const file of state.files) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `file-button${state.active?.id === file.id ? ' active' : ''}`;
    button.textContent = file.path;
    button.addEventListener('click', () => selectFile(file));
    elements.fileList.append(button);
  }
  elements.fileCount.textContent = String(state.files.length);
}

async function selectFile(file, force = false) {
  if (!force && state.active && elements.editor.value !== state.original) {
    const discard = window.confirm(`Discard unsaved changes to ${state.active.path}?`);
    if (!discard) return;
  }
  setBusy(true);
  showResult('Loading', `Reading ${file.path}…`, '');
  try {
    const document = await api(`/api/file/${file.id}`);
    state.active = file;
    state.revision = document.revision;
    state.original = document.content;
    elements.editor.value = document.content;
    elements.activeFile.textContent = file.path;
    elements.documentState.textContent = `${document.sizeBytes.toLocaleString()} bytes · revision ${document.revision.slice(0, 10)}`;
    renderFiles();
    hideResult();
  } catch (error) {
    showResult('Could not load file', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

async function validateDocument() {
  if (!state.active || state.busy) return;
  setBusy(true);
  showResult('Validating', 'Checking YAML, schema and the complete runtime configuration…', '');
  try {
    const result = await api(`/api/validate/${state.active.id}`, {
      method: 'POST',
      headers: {'Content-Type': 'text/plain; charset=utf-8'},
      body: elements.editor.value,
    });
    showValidationResult('Draft is valid', 'No files were changed.', result.warnings);
  } catch (error) {
    showResult('Validation failed', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

async function saveDocument() {
  if (!state.active || state.busy || elements.editor.value === state.original) return;
  setBusy(true);
  showResult('Saving', 'Validating, creating a backup and safely replacing the file…', '');
  try {
    const result = await api(`/api/file/${state.active.id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        'If-Match': state.revision,
      },
      body: elements.editor.value,
    });
    state.revision = result.revision;
    state.original = elements.editor.value;
    elements.documentState.textContent = `Saved · revision ${result.revision.slice(0, 10)}`;
    const backup = result.backup ? ` Backup: ${result.backup}.` : ' Content was unchanged.';
    showValidationResult('Saved safely', `${backup} Run /coredsc reload to apply the change.`, result.warnings);
  } catch (error) {
    const title = error.status === 409 ? 'Edit conflict' : 'Save failed';
    showResult(title, error.message, 'error');
  } finally {
    setBusy(false);
  }
}

function showValidationResult(title, message, warnings = []) {
  showResult(title, message, warnings.length ? 'warning' : 'success');
  for (const warning of warnings) {
    const item = document.createElement('li');
    item.textContent = warning.message;
    elements.warningList.append(item);
  }
}

function showResult(title, message, tone) {
  elements.resultPanel.hidden = false;
  elements.resultPanel.className = `result-panel${tone ? ` ${tone}` : ''}`;
  elements.resultTitle.textContent = title;
  elements.resultMessage.textContent = message;
  elements.warningList.replaceChildren();
}

function hideResult() {
  elements.resultPanel.hidden = true;
  elements.warningList.replaceChildren();
}

function updateDocumentState() {
  if (!state.active) return;
  const dirty = elements.editor.value !== state.original;
  elements.documentState.textContent = dirty
    ? `Unsaved changes · ${new Blob([elements.editor.value]).size.toLocaleString()} bytes`
    : `Saved · revision ${state.revision.slice(0, 10)}`;
  elements.saveButton.disabled = state.busy || !dirty;
}

function updateExpiry() {
  if (!state.session) return;
  const remaining = new Date(state.session.expiresAt).getTime() - Date.now();
  if (remaining <= 0) {
    elements.sessionCard.classList.remove('ready');
    elements.sessionCard.classList.add('failed');
    elements.sessionState.textContent = 'Session expired';
    elements.sessionExpiry.textContent = 'Start a new session from the server console.';
    setBusy(true);
    return;
  }
  const seconds = Math.ceil(remaining / 1000);
  const minutes = Math.floor(seconds / 60);
  elements.sessionExpiry.textContent = `Expires in ${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}

async function initialize() {
  state.token = acquireToken();
  if (!state.token) {
    elements.sessionCard.classList.add('failed');
    elements.sessionState.textContent = 'Capability token missing';
    elements.sessionExpiry.textContent = 'Open the exact URL printed by the server console.';
    showResult('Cannot authenticate', 'Start a new WebEditor session from the server console and open its full one-time URL.', 'error');
    return;
  }
  try {
    state.session = await api('/api/session');
    state.files = state.session.files;
    elements.sessionCard.classList.add('ready');
    elements.sessionState.textContent = `${state.session.product} · ${state.session.stage}`;
    renderFiles();
    updateExpiry();
    window.setInterval(updateExpiry, 1000);
    if (state.files.length) await selectFile(state.files[0], true);
  } catch (error) {
    elements.sessionCard.classList.add('failed');
    elements.sessionState.textContent = 'Session unavailable';
    elements.sessionExpiry.textContent = error.message;
    showResult('Cannot open WebEditor', error.message, 'error');
  }
}

elements.editor.addEventListener('input', updateDocumentState);
elements.reloadButton.addEventListener('click', () => state.active && selectFile(state.active));
elements.validateButton.addEventListener('click', validateDocument);
elements.saveButton.addEventListener('click', saveDocument);

document.addEventListener('keydown', (event) => {
  if (!(event.ctrlKey || event.metaKey)) return;
  if (event.key.toLowerCase() === 's') {
    event.preventDefault();
    saveDocument();
  } else if (event.key === 'Enter') {
    event.preventDefault();
    validateDocument();
  }
});

window.addEventListener('beforeunload', (event) => {
  if (state.active && elements.editor.value !== state.original) {
    event.preventDefault();
    event.returnValue = '';
  }
});

initialize();
