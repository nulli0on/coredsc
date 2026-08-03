'use strict';

const state = {
  token: '',
  session: null,
  dashboard: null,
  revisions: {},
  channels: [],
  changes: new Map(),
  baseline: new Map(),
  activeView: 'modules',
  activeEmbed: 'startup',
  applyReady: false,
  busy: false,
  rendering: false,
  raw: {files: [], active: null, revision: '', original: ''},
};

const elements = {
  sessionCard: document.querySelector('.session-card'),
  sessionState: document.querySelector('#session-state'),
  sessionExpiry: document.querySelector('#session-expiry'),
  tabs: [...document.querySelectorAll('.tab')],
  views: [...document.querySelectorAll('.view')],
  summaryGrid: document.querySelector('#summary-grid'),
  moduleGroups: document.querySelector('#module-groups'),
  moduleSearch: document.querySelector('#module-search'),
  guildSelect: document.querySelector('#guild-select'),
  discordOffline: document.querySelector('#discord-offline'),
  mappingGrid: document.querySelector('#mapping-grid'),
  embedEvent: document.querySelector('#embed-event'),
  embedEnabled: document.querySelector('#embed-enabled'),
  embedTitle: document.querySelector('#embed-title'),
  embedDescription: document.querySelector('#embed-description'),
  embedColorPicker: document.querySelector('#embed-color-picker'),
  embedColor: document.querySelector('#embed-color'),
  embedFooter: document.querySelector('#embed-footer'),
  embedThumbnail: document.querySelector('#embed-thumbnail'),
  embedImage: document.querySelector('#embed-image'),
  embedPreview: document.querySelector('#embed-preview'),
  previewTitle: document.querySelector('#preview-title'),
  previewDescription: document.querySelector('#preview-description'),
  previewFooter: document.querySelector('#preview-footer'),
  previewThumbnail: document.querySelector('#preview-thumbnail'),
  previewImage: document.querySelector('#preview-image'),
  fileCount: document.querySelector('#file-count'),
  fileList: document.querySelector('#file-list'),
  activeFile: document.querySelector('#active-file'),
  editor: document.querySelector('#editor'),
  documentState: document.querySelector('#document-state'),
  reloadButton: document.querySelector('#reload-button'),
  validateButton: document.querySelector('#validate-button'),
  rawSaveButton: document.querySelector('#raw-save-button'),
  changeCount: document.querySelector('#change-count'),
  saveHint: document.querySelector('#save-hint'),
  discardButton: document.querySelector('#discard-button'),
  saveButton: document.querySelector('#save-button'),
  applyButton: document.querySelector('#apply-button'),
  resultPanel: document.querySelector('#result-panel'),
  resultTitle: document.querySelector('#result-title'),
  resultMessage: document.querySelector('#result-message'),
  warningList: document.querySelector('#warning-list'),
  dismissResult: document.querySelector('#dismiss-result'),
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
  try { payload = await response.json(); } catch { /* response was not JSON */ }
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
  document.querySelector('main').setAttribute('aria-busy', String(busy));
  updateSaveBar();
  updateRawButtons();
}

function switchView(view) {
  state.activeView = view;
  for (const tab of elements.tabs) tab.classList.toggle('active', tab.dataset.view === view);
  for (const panel of elements.views) {
    const active = panel.dataset.panel === view;
    panel.classList.toggle('active', active);
    panel.hidden = !active;
  }
  if (view === 'advanced' && !state.raw.active && state.raw.files.length) {
    selectFile(state.raw.files[0], true);
  }
}

function initializeBaseline() {
  state.baseline.clear();
  for (const module of state.dashboard.modules) baseline(module.file, module.path, module.enabled);
  for (const mapping of state.dashboard.mappings) baseline(mapping.file, mapping.path, mapping.value);
  for (const embed of state.dashboard.embeds) {
    const root = `events.${embed.event}`;
    baseline('modules/server-events.yml', `${root}.enabled`, embed.enabled);
    baseline('modules/server-events.yml', `${root}.embed.title`, embed.title);
    baseline('modules/server-events.yml', `${root}.embed.description`, embed.description);
    baseline('modules/server-events.yml', `${root}.embed.color`, embed.color);
    baseline('modules/server-events.yml', `${root}.embed.thumbnail-url`, embed.thumbnailUrl);
    baseline('modules/server-events.yml', `${root}.embed.image-url`, embed.imageUrl);
    baseline('modules/server-events.yml', `${root}.embed.footer`, embed.footer);
  }
}

function baseline(file, path, value) {
  state.baseline.set(changeKey(file, path), cloneValue(value));
}

function stageChange(file, path, value) {
  const key = changeKey(file, path);
  const original = state.baseline.get(key);
  if (equalValue(original, value)) state.changes.delete(key);
  else state.changes.set(key, {file, path, value: cloneValue(value)});
  state.applyReady = false;
  updateSaveBar();
}

function changeKey(file, path) { return `${file}\u0000${path}`; }
function cloneValue(value) { return value && typeof value === 'object' ? structuredClone(value) : value; }
function equalValue(left, right) { return JSON.stringify(left) === JSON.stringify(right); }

function renderSummary() {
  const modules = state.dashboard.modules;
  const enabled = modules.filter(module => module.enabled).length;
  const failed = modules.filter(module => module.state === 'FAILED').length;
  const cards = [
    ['Enabled modules', `${enabled}/${modules.length}`, '◉'],
    ['Runtime health', failed ? `${failed} failed` : 'Healthy', failed ? '!' : '✓'],
    ['Visible guilds', String(state.dashboard.guilds.length), '#'],
    ['Scheduler', state.dashboard.scheduler, '⚙'],
  ];
  elements.summaryGrid.replaceChildren(...cards.map(([label, value, icon]) => {
    const card = document.createElement('article');
    card.className = 'summary-card';
    const copy = document.createElement('div');
    const small = document.createElement('span');
    small.textContent = label;
    const strong = document.createElement('strong');
    strong.textContent = value;
    copy.append(small, strong);
    const badge = document.createElement('div');
    badge.className = 'summary-icon';
    badge.textContent = icon;
    card.append(copy, badge);
    return card;
  }));
}

function renderModules() {
  const filter = elements.moduleSearch.value.trim().toLowerCase();
  const grouped = new Map();
  for (const module of state.dashboard.modules) {
    const haystack = `${module.label} ${module.description} ${module.category} ${module.id}`.toLowerCase();
    if (filter && !haystack.includes(filter)) continue;
    if (!grouped.has(module.category)) grouped.set(module.category, []);
    grouped.get(module.category).push(module);
  }
  elements.moduleGroups.replaceChildren();
  for (const [category, modules] of [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const section = document.createElement('section');
    section.className = 'module-group';
    const heading = document.createElement('h3');
    heading.textContent = category;
    const grid = document.createElement('div');
    grid.className = 'module-grid';
    for (const module of modules.sort((a, b) => a.label.localeCompare(b.label))) {
      const card = document.createElement('label');
      card.className = 'module-card';
      const toggle = document.createElement('span');
      toggle.className = 'toggle';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = module.enabled;
      input.setAttribute('aria-label', `Enable ${module.label}`);
      input.addEventListener('change', () => {
        module.enabled = input.checked;
        stageChange(module.file, module.path, module.enabled);
        renderSummary();
      });
      const slider = document.createElement('span');
      toggle.append(input, slider);
      const copy = document.createElement('div');
      const title = document.createElement('strong');
      title.textContent = module.label;
      const description = document.createElement('p');
      description.textContent = module.description;
      const health = document.createElement('span');
      health.className = `health ${module.state.toLowerCase()}`;
      health.textContent = `${module.state} · ${module.detail}`;
      copy.append(title, description, health);
      card.append(toggle, copy);
      grid.append(card);
    }
    section.append(heading, grid);
    elements.moduleGroups.append(section);
  }
}

function renderGuilds() {
  const guildMapping = state.dashboard.mappings.find(mapping => mapping.id === 'guild');
  const current = guildMapping?.value || '';
  elements.guildSelect.replaceChildren();
  const placeholder = new Option(state.dashboard.discordReady ? 'Choose a Discord server' : 'Discord unavailable', '');
  elements.guildSelect.add(placeholder);
  for (const guild of state.dashboard.guilds) {
    elements.guildSelect.add(new Option(`${guild.name} · ${guild.textChannels} text channels`, guild.id));
  }
  if (current && !state.dashboard.guilds.some(guild => guild.id === current)) {
    elements.guildSelect.add(new Option(`Configured but not visible · ${current}`, current));
  }
  elements.guildSelect.value = current;
  elements.guildSelect.disabled = !state.dashboard.discordReady;
  elements.discordOffline.hidden = state.dashboard.discordReady;
}

async function loadChannels() {
  const guildId = elements.guildSelect.value;
  state.channels = [];
  if (!guildId || !state.dashboard.discordReady) {
    renderMappings();
    return;
  }
  try {
    const payload = await api(`/api/discord/channels?guildId=${encodeURIComponent(guildId)}`);
    state.channels = payload.channels || [];
    renderMappings();
  } catch (error) {
    renderMappings();
    showResult('Could not load channels', error.message, 'error');
  }
}

function renderMappings() {
  elements.mappingGrid.replaceChildren();
  for (const mapping of state.dashboard.mappings.filter(item => item.id !== 'guild')) {
    const card = document.createElement('article');
    card.className = 'mapping-card';
    const header = document.createElement('header');
    const title = document.createElement('strong');
    title.textContent = mapping.label;
    const path = document.createElement('span');
    path.textContent = mapping.runtimePath;
    header.append(title, path);
    const select = document.createElement('select');
    select.setAttribute('aria-label', mapping.label);
    select.add(new Option('Not mapped', ''));
    const compatible = state.channels.filter(channel => channel.type === mapping.channelType);
    for (const channel of compatible) {
      const prefix = channel.type === 'text' ? '#' : channel.type === 'voice' ? '🔊 ' : '▣ ';
      const parent = channel.parent ? `${channel.parent} / ` : '';
      const option = new Option(`${parent}${prefix}${channel.name}${channel.usable ? '' : ' · no send access'}`, channel.id);
      option.disabled = !channel.usable;
      select.add(option);
    }
    if (mapping.value && !compatible.some(channel => channel.id === mapping.value)) {
      select.add(new Option(`Configured ID · ${mapping.value}`, mapping.value));
    }
    select.value = mapping.value || '';
    select.disabled = !elements.guildSelect.value || !state.dashboard.discordReady;
    select.addEventListener('change', () => {
      mapping.value = select.value;
      stageChange(mapping.file, mapping.path, mapping.value);
    });
    const note = document.createElement('p');
    note.className = 'channel-option-note';
    note.textContent = `${compatible.length} compatible channel${compatible.length === 1 ? '' : 's'} visible to the bot.`;
    card.append(header, select, note);
    elements.mappingGrid.append(card);
  }
}

function renderEmbedSelector() {
  elements.embedEvent.replaceChildren();
  for (const embed of state.dashboard.embeds) elements.embedEvent.add(new Option(embed.label, embed.event));
  if (!state.dashboard.embeds.some(embed => embed.event === state.activeEmbed)) {
    state.activeEmbed = state.dashboard.embeds[0]?.event || '';
  }
  elements.embedEvent.value = state.activeEmbed;
  loadEmbedForm();
}

function currentEmbed() {
  return state.dashboard.embeds.find(embed => embed.event === state.activeEmbed);
}

function loadEmbedForm() {
  const embed = currentEmbed();
  if (!embed) return;
  state.rendering = true;
  elements.embedEnabled.checked = embed.enabled;
  elements.embedTitle.value = embed.title;
  elements.embedDescription.value = embed.description;
  elements.embedColor.value = normalizeColor(embed.color) || '#5865F2';
  elements.embedColorPicker.value = (normalizeColor(embed.color) || '#5865F2').toLowerCase();
  elements.embedFooter.value = embed.footer;
  elements.embedThumbnail.value = embed.thumbnailUrl;
  elements.embedImage.value = embed.imageUrl;
  state.rendering = false;
  renderEmbedPreview();
}

function updateEmbed(field, value, yamlField = field) {
  if (state.rendering) return;
  const embed = currentEmbed();
  if (!embed) return;
  embed[field] = value;
  const path = field === 'enabled'
    ? `events.${embed.event}.enabled`
    : `events.${embed.event}.embed.${yamlField}`;
  stageChange('modules/server-events.yml', path, value);
  renderEmbedPreview();
}

function renderEmbedPreview() {
  const embed = currentEmbed();
  if (!embed) return;
  const color = normalizeColor(embed.color) || '#5865F2';
  elements.embedPreview.style.setProperty('--embed-color', color);
  elements.previewTitle.textContent = previewText(embed.title) || 'Untitled embed';
  elements.previewDescription.textContent = previewText(embed.description) || 'Add a description to preview the message.';
  elements.previewFooter.textContent = previewText(embed.footer);
  elements.previewFooter.hidden = !embed.footer;
  setPreviewImage(elements.previewThumbnail, embed.thumbnailUrl);
  setPreviewImage(elements.previewImage, embed.imageUrl);
  elements.embedPreview.style.opacity = embed.enabled ? '1' : '.48';
}

function previewText(value) {
  const replacements = {
    server_name: 'CrimsonTide', player: 'Steve', death_message: 'Steve was slain by Alex',
    online_players: '42', max_players: '100', uptime: '3h 27m',
  };
  return String(value || '').replace(/%([a-z0-9_]+)%/gi, (match, key) => replacements[key] ?? match);
}

function setPreviewImage(image, value) {
  const safe = safeHttps(value);
  if (!safe) {
    image.hidden = true;
    image.removeAttribute('src');
    return;
  }
  image.src = previewText(safe);
  image.hidden = false;
  image.onerror = () => { image.hidden = true; };
}

function safeHttps(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' ? url.href : '';
  } catch { return ''; }
}

function normalizeColor(value) {
  const color = String(value || '').trim().toUpperCase();
  return /^#[0-9A-F]{6}$/.test(color) ? color : '';
}

async function saveVisualChanges() {
  if (!state.changes.size || state.busy) return;
  setBusy(true);
  showResult('Saving visual changes', 'Validating the complete configuration and creating a transactional backup…', '');
  const pending = [...state.changes.values()];
  try {
    const changes = pending.map(change => ({
      ...change,
      revision: state.revisions[change.file],
    }));
    const result = await api('/api/structured', {
      method: 'PUT',
      headers: {'Content-Type': 'application/json; charset=utf-8'},
      body: JSON.stringify({changes}),
    });
    Object.assign(state.revisions, result.revisions || {});
    for (const change of pending) state.baseline.set(changeKey(change.file, change.path), cloneValue(change.value));
    state.changes.clear();
    state.applyReady = Boolean(result.reloadRequired);
    const backup = result.backup ? ` Backup: ${result.backup}.` : ' No bytes changed.';
    showValidationResult('Visual configuration saved', `${result.changedFiles.length} file(s) changed.${backup}`, result.warnings);
  } catch (error) {
    showResult(error.status === 409 ? 'Edit conflict' : 'Save failed', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

function discardVisualChanges() {
  if (!state.changes.size) return;
  for (const change of state.changes.values()) {
    const original = state.baseline.get(changeKey(change.file, change.path));
    restoreModelValue(change.file, change.path, cloneValue(original));
  }
  state.changes.clear();
  renderSummary();
  renderModules();
  renderGuilds();
  loadChannels();
  loadEmbedForm();
  updateSaveBar();
}

function restoreModelValue(file, path, value) {
  const module = state.dashboard.modules.find(item => item.file === file && item.path === path);
  if (module) { module.enabled = value; return; }
  const mapping = state.dashboard.mappings.find(item => item.file === file && item.path === path);
  if (mapping) { mapping.value = value; return; }
  const match = /^events\.([a-z-]+)\.(enabled|embed\.(title|description|color|thumbnail-url|image-url|footer))$/.exec(path);
  if (!match) return;
  const embed = state.dashboard.embeds.find(item => item.event === match[1]);
  if (!embed) return;
  if (match[2] === 'enabled') embed.enabled = value;
  else {
    const field = {'thumbnail-url': 'thumbnailUrl', 'image-url': 'imageUrl'}[match[3]] || match[3];
    embed[field] = value;
  }
}

async function applyConfiguration() {
  if (!state.applyReady || state.changes.size || state.busy) return;
  setBusy(true);
  try {
    const result = await api('/api/apply', {method: 'POST'});
    state.applyReady = false;
    updateSaveBar();
    showResult('Applying configuration', result.message, 'success');
    elements.sessionState.textContent = 'Applying saved configuration';
    elements.sessionExpiry.textContent = 'This guarded session will close during module reload.';
  } catch (error) {
    showResult('Could not apply configuration', error.message, 'error');
    setBusy(false);
  }
}

function updateSaveBar() {
  const count = state.changes.size;
  elements.changeCount.textContent = count ? `${count} unsaved visual change${count === 1 ? '' : 's'}` : 'No visual changes';
  elements.saveHint.textContent = count
    ? 'All changes are validated and written as one transaction.'
    : state.applyReady ? 'Saved files are ready to apply.' : 'Choose a module, mapping or embed to begin.';
  elements.discardButton.disabled = state.busy || !count;
  elements.saveButton.disabled = state.busy || !count;
  elements.applyButton.disabled = state.busy || count > 0 || !state.applyReady;
}

function renderFiles() {
  elements.fileList.replaceChildren();
  for (const file of state.raw.files) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `file-button${state.raw.active?.id === file.id ? ' active' : ''}`;
    button.textContent = file.path;
    button.addEventListener('click', () => selectFile(file));
    elements.fileList.append(button);
  }
  elements.fileCount.textContent = String(state.raw.files.length);
}

async function selectFile(file, force = false) {
  if (!force && state.raw.active && elements.editor.value !== state.raw.original
      && !window.confirm(`Discard unsaved changes to ${state.raw.active.path}?`)) return;
  setBusy(true);
  try {
    const document = await api(`/api/file/${file.id}`);
    state.raw.active = file;
    state.raw.revision = document.revision;
    state.raw.original = document.content;
    elements.editor.value = document.content;
    elements.activeFile.textContent = file.path;
    elements.documentState.textContent = `${document.sizeBytes.toLocaleString()} bytes · revision ${document.revision.slice(0, 10)}`;
    renderFiles();
  } catch (error) {
    showResult('Could not load file', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

async function validateRawDocument() {
  if (!state.raw.active || state.busy) return;
  setBusy(true);
  showResult('Validating YAML', 'Checking syntax, schema and the complete runtime configuration…', '');
  try {
    const result = await api(`/api/validate/${state.raw.active.id}`, {
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

async function saveRawDocument() {
  if (!state.raw.active || state.busy || elements.editor.value === state.raw.original) return;
  setBusy(true);
  showResult('Saving YAML', 'Validating, backing up and atomically replacing the file…', '');
  try {
    const result = await api(`/api/file/${state.raw.active.id}`, {
      method: 'PUT',
      headers: {'Content-Type': 'text/plain; charset=utf-8', 'If-Match': state.raw.revision},
      body: elements.editor.value,
    });
    state.raw.revision = result.revision;
    state.raw.original = elements.editor.value;
    state.revisions[state.raw.active.path] = result.revision;
    state.applyReady = true;
    elements.documentState.textContent = `Saved · revision ${result.revision.slice(0, 10)}`;
    showValidationResult('YAML saved', `${result.backup ? `Backup: ${result.backup}. ` : ''}Apply when ready.`, result.warnings);
  } catch (error) {
    showResult(error.status === 409 ? 'Edit conflict' : 'Save failed', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

function updateRawButtons() {
  const loaded = Boolean(state.raw.active);
  const dirty = loaded && elements.editor.value !== state.raw.original;
  elements.reloadButton.disabled = state.busy || !loaded;
  elements.validateButton.disabled = state.busy || !loaded;
  elements.rawSaveButton.disabled = state.busy || !dirty;
  elements.editor.disabled = state.busy || !loaded;
  if (dirty) elements.documentState.textContent = `Unsaved changes · ${new Blob([elements.editor.value]).size.toLocaleString()} bytes`;
}

function showValidationResult(title, message, warnings = []) {
  showResult(title, message, warnings?.length ? 'warning' : 'success');
  for (const warning of warnings || []) {
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
  elements.sessionExpiry.textContent = `Expires in ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

async function initialize() {
  state.token = acquireToken();
  if (!state.token) {
    elements.sessionCard.classList.add('failed');
    elements.sessionState.textContent = 'Capability token missing';
    elements.sessionExpiry.textContent = 'Open the exact URL printed by the server console.';
    showResult('Cannot authenticate', 'Start a WebEditor session and open its complete one-time URL.', 'error');
    return;
  }
  setBusy(true);
  try {
    state.session = await api('/api/session');
    state.dashboard = await api('/api/dashboard');
    state.revisions = {...state.dashboard.revisions};
    state.raw.files = state.session.files;
    initializeBaseline();
    elements.sessionCard.classList.add('ready');
    elements.sessionState.textContent = `${state.session.product} · ${state.session.stage}`;
    renderSummary();
    renderModules();
    renderGuilds();
    renderMappings();
    renderEmbedSelector();
    renderFiles();
    updateExpiry();
    window.setInterval(updateExpiry, 1000);
    await loadChannels();
  } catch (error) {
    elements.sessionCard.classList.add('failed');
    elements.sessionState.textContent = 'Session unavailable';
    elements.sessionExpiry.textContent = error.message;
    showResult('Cannot open Control Center', error.message, 'error');
  } finally {
    setBusy(false);
  }
}

for (const tab of elements.tabs) tab.addEventListener('click', () => switchView(tab.dataset.view));
elements.moduleSearch.addEventListener('input', renderModules);
elements.guildSelect.addEventListener('change', async () => {
  const mapping = state.dashboard.mappings.find(item => item.id === 'guild');
  if (mapping) {
    mapping.value = elements.guildSelect.value;
    stageChange(mapping.file, mapping.path, mapping.value);
  }
  await loadChannels();
});
elements.embedEvent.addEventListener('change', () => { state.activeEmbed = elements.embedEvent.value; loadEmbedForm(); });
elements.embedEnabled.addEventListener('change', () => updateEmbed('enabled', elements.embedEnabled.checked));
elements.embedTitle.addEventListener('input', () => updateEmbed('title', elements.embedTitle.value));
elements.embedDescription.addEventListener('input', () => updateEmbed('description', elements.embedDescription.value));
elements.embedFooter.addEventListener('input', () => updateEmbed('footer', elements.embedFooter.value));
elements.embedThumbnail.addEventListener('input', () => updateEmbed('thumbnailUrl', elements.embedThumbnail.value, 'thumbnail-url'));
elements.embedImage.addEventListener('input', () => updateEmbed('imageUrl', elements.embedImage.value, 'image-url'));
elements.embedColorPicker.addEventListener('input', () => {
  const color = elements.embedColorPicker.value.toUpperCase();
  elements.embedColor.value = color;
  updateEmbed('color', color);
});
elements.embedColor.addEventListener('input', () => {
  const color = normalizeColor(elements.embedColor.value);
  if (!color) return;
  elements.embedColorPicker.value = color.toLowerCase();
  updateEmbed('color', color);
});
for (const swatch of document.querySelectorAll('.palette button')) {
  swatch.addEventListener('dragstart', event => event.dataTransfer.setData('text/x-coredsc-color', swatch.dataset.color));
  swatch.addEventListener('click', () => {
    elements.embedColor.value = swatch.dataset.color;
    elements.embedColorPicker.value = swatch.dataset.color.toLowerCase();
    updateEmbed('color', swatch.dataset.color);
  });
}
elements.embedPreview.addEventListener('dragover', event => { event.preventDefault(); elements.embedPreview.classList.add('drag-over'); });
elements.embedPreview.addEventListener('dragleave', () => elements.embedPreview.classList.remove('drag-over'));
elements.embedPreview.addEventListener('drop', event => {
  event.preventDefault();
  elements.embedPreview.classList.remove('drag-over');
  const color = normalizeColor(event.dataTransfer.getData('text/x-coredsc-color'));
  if (!color) return;
  elements.embedColor.value = color;
  elements.embedColorPicker.value = color.toLowerCase();
  updateEmbed('color', color);
});
elements.discardButton.addEventListener('click', discardVisualChanges);
elements.saveButton.addEventListener('click', saveVisualChanges);
elements.applyButton.addEventListener('click', applyConfiguration);
elements.editor.addEventListener('input', updateRawButtons);
elements.reloadButton.addEventListener('click', () => state.raw.active && selectFile(state.raw.active));
elements.validateButton.addEventListener('click', validateRawDocument);
elements.rawSaveButton.addEventListener('click', saveRawDocument);
elements.dismissResult.addEventListener('click', hideResult);

document.addEventListener('keydown', event => {
  if (!(event.ctrlKey || event.metaKey)) return;
  if (event.key.toLowerCase() === 's') {
    event.preventDefault();
    if (state.activeView === 'advanced') saveRawDocument(); else saveVisualChanges();
  } else if (event.key === 'Enter' && state.activeView === 'advanced') {
    event.preventDefault();
    validateRawDocument();
  }
});

window.addEventListener('beforeunload', event => {
  if (state.changes.size || (state.raw.active && elements.editor.value !== state.raw.original)) {
    event.preventDefault();
    event.returnValue = '';
  }
});

initialize();
