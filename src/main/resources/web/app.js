'use strict';

const $ = id => document.getElementById(id);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const basename = path => path.split('/').pop();
const encode = encodeURIComponent;
const state = {token:'', files:[], documents:[], active:null, selected:'filters', collapsed:new Set(), collection:'', resultHidden:false, editorHidden:false,
  run:null, activeRun:null, history:[], view:'summary', logs:[], reportPath:null, sheet:0, offset:0, renderVersion:0, previewCache:new Map(), busy:false,
  apiCollectionPath:null, apiCollection:null, apiRequestIndex:0, apiResponse:null, apiSending:false,
  apiRequestTab:'params', apiVariablesSaving:false, apiVariablesSaved:'', apiResponseView:'pretty', apiResponseDataset:0, apiTableFilter:''};
const summaryBlock = `SUMMARY {
  TITLE "API execution report" COLOR "#245C50";
  DESCRIPTION "Request outcomes and execution details.";
  PARAGRAPH "Review the request status table for any failures before sharing this report.";
  STATUS;
  METRICS;
}`;
const starter = `# Select a collection above, then validate and run this report.
# Add REQUESTS "Request name"; to execute specific requests.

${summaryBlock}
`;
let toastTimer;
let dialogMode = 'new';
let importQueue = [];
let confirmResolve;
let highlightFrame;
let allowTabNavigation = false;

async function api(path, options = {}) {
  const headers = {'X-Workspace-Token':state.token};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {...options, cache:'no-store', headers, body:options.body === undefined ? undefined : JSON.stringify(options.body)});
  let data;
  try { data = await response.json(); } catch { throw new Error('The application returned an unreadable response.'); }
  if (!response.ok) {
    const error = new Error(data.error || `The request failed (${response.status}).`);
    error.status = response.status;
    throw error;
  }
  return data;
}

function notify(message, error = false) {
  $('status-message').textContent = message;
  $('toast').textContent = message;
  $('toast').classList.toggle('error', error);
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, error ? 9000 : 3500);
}

function log(message, type = 'info') {
  state.logs.unshift({message, type, at:new Date().toLocaleTimeString([], {hour12:false})});
  state.logs = state.logs.slice(0, 100);
  $('output-count').textContent = state.logs.length;
  if (state.view === 'output') renderResult();
}

function handleError(error) {
  notify(error.message, true);
  log(error.message, 'error');
  const location = error.message.match(/:(\d+):(\d+)\s/);
  if (location && activeDocument()) {
    const lines = $('editor').value.split('\n');
    const line = Number(location[1]);
    const position = lines.slice(0, line - 1).reduce((length, text) => length + text.length + 1, 0) + Number(location[2]) - 1;
    $('editor').focus();
    $('editor').setSelectionRange(position, position);
    $('editor').scrollTop = Math.max(0, (line - 4) * parseFloat(getComputedStyle($('editor')).lineHeight));
    syncScroll();
    updateCursor();
  }
}

function bind(id, event, handler) {
  $(id).addEventListener(event, event => {
    try { Promise.resolve(handler(event)).catch(handleError); }
    catch (error) { handleError(error); }
  });
}

function activeDocument() { return state.documents.find(doc => doc.path === state.active); }
function isDirty(doc) { return doc && doc.content !== doc.saved; }
function confirmAction(title, message, label = 'Continue') {
  $('confirm-title').textContent = title;
  $('confirm-message').textContent = message;
  $('confirm-yes').textContent = label;
  $('confirm-dialog').showModal();
  $('confirm-no').focus();
  return new Promise(resolve => { confirmResolve = resolve; });
}

async function refreshFiles() {
  state.files = await api('/api/files');
  renderTree();
  const collections = state.files.filter(file => !file.directory && file.path.startsWith('collections/'));
  $('collection-select').innerHTML = '<option value="">Select a collection</option>' + collections.map(file =>
    `<option value="${escapeHtml(file.path)}">${escapeHtml(file.path.slice(12))}</option>`).join('');
  if (!collections.some(file => file.path === state.collection)) state.collection = collections[0]?.path || '';
  $('collection-select').value = state.collection;
  await refreshOutline();
}

function renderTree() {
  const query = $('file-search').value.trim().toLowerCase();
  const selected = state.selected;
  $('file-tree').innerHTML = state.files.filter(file => {
    if (query) return file.directory || file.path.toLowerCase().includes(query);
    const parents = file.path.split('/');
    parents.pop();
    return !parents.some((part, index) => state.collapsed.has(parents.slice(0, index + 1).join('/')));
  }).map(file => {
    const depth = file.path.split('/').length - 1;
    const count = file.directory ? state.files.filter(child => !child.directory && child.path.startsWith(file.path + '/')).length : '';
    const icon = file.directory ? '▱' : file.path.endsWith('.json') ? '{}' : file.path.endsWith('.filter') ? 'ƒ' : '▦';
    const iconClass = file.path.endsWith('.json') ? 'collection-icon' : file.path.endsWith('.filter') ? 'filter-icon' : '';
    const label = depth === 0 ? file.name.charAt(0).toUpperCase() + file.name.slice(1) : file.name;
    const run = !file.directory && file.path.endsWith('.filter')
      ? `<button class="quick-run" data-run-filter="${escapeHtml(file.path)}" title="Run ${escapeHtml(label)} without opening it" aria-label="Run ${escapeHtml(label)} without opening it">▶</button>` : '';
    const createFilter = file.directory && file.path === 'filters' && selected === file.path
      ? `<button class="create-filter" data-create-filter="${escapeHtml(file.path)}" title="Create a new filter" aria-label="Create a new filter">+ Filter</button>` : '';
    return `<div class="tree-entry"><button class="tree-row ${depth === 0 ? 'root-row' : ''} ${selected === file.path ? 'selected' : ''}" data-path="${escapeHtml(file.path)}" data-directory="${file.directory}" style="padding-left:${8 + depth * 13}px" title="${escapeHtml(file.path)}" ${file.directory ? `aria-expanded="${!state.collapsed.has(file.path)}"` : ''}><span class="tree-chevron" aria-hidden="true">${file.directory ? state.collapsed.has(file.path) && !query ? '▸' : '▾' : ''}</span><span class="tree-icon ${iconClass}" aria-hidden="true">${icon}</span><span class="file-name">${escapeHtml(label)}</span>${file.directory ? `<span class="tree-count">${count}</span>` : ''}</button>${createFilter}${run}</div>`;
  }).join('') || '<p class="muted loading">No matching files.</p>';
}

async function refreshOutline() {
  const collection = state.collection;
  if (!collection) { $('request-outline').innerHTML = '<p class="reference-intro">Import a collection to see its requests.</p>'; return; }
  try {
    const data = await api(`/api/collection?path=${encode(collection)}`);
    if (collection !== state.collection) return;
    $('request-outline').innerHTML = data.requests.map(request => `<button class="outline-request" data-request="${escapeHtml(request.name)}" title="Insert request name into editor"><span class="method ${escapeHtml(request.method.toLowerCase())}">${escapeHtml(request.method)}</span><span>${escapeHtml(request.name)}${request.disabled ? ' (disabled)' : ''}</span></button>`).join('');
  } catch (error) {
    if (collection === state.collection) $('request-outline').innerHTML = `<p class="reference-intro">${escapeHtml(error.message)}</p>`;
  }
}

async function openFile(path) {
  state.selected = path;
  if (path.endsWith('.xlsx')) {
    state.run = state.history.find(run => run.files?.includes(path)) || null;
    openWorkbook(path); renderTree();
    return;
  }
  if (path.endsWith('.json')) {
    await openApiCollection(path);
    return;
  }
  await openSourceFile(path);
}

async function openSourceFile(path) {
  let doc = state.documents.find(item => item.path === path);
  if (!doc) {
    const file = await api(`/api/file?path=${encode(path)}`);
    doc = {...file, saved:file.content};
    state.documents.push(doc);
  }
  activate(doc);
  if (path.endsWith('.json')) {
    state.collection = path; $('collection-select').value = path;
    await refreshOutline();
  } else {
    const match = doc.content.match(/^\s*COLLECTION\s+(?:"([^"]+)"|'([^']+)'|([^;\s]+))\s*;/mi);
    const name = match && (match[1] || match[2] || match[3]);
    const matches = name ? state.files.filter(file => file.path.startsWith('collections/') && !file.directory && basename(file.path).replace(/\.json$/, '') === name) : [];
    if (matches.length === 1) { state.collection = matches[0].path; $('collection-select').value = state.collection; await refreshOutline(); }
  }
}

async function openApiCollection(path) {
  state.selected = path;
  state.collection = path;
  $('collection-select').value = path;
  state.apiCollectionPath = path;
  state.apiCollection = null;
  state.apiRequestIndex = 0;
  state.apiResponse = null;
  state.apiRequestTab = 'params';
  state.apiResponseView = 'pretty';
  state.apiResponseDataset = 0;
  state.apiTableFilter = '';
  state.apiVariablesSaving = false;
  state.apiVariablesSaved = '';
  setResultsOnly(true);
  setView('api');
  renderTree();
  const results = await Promise.allSettled([api(`/api/collection?path=${encode(path)}`), refreshOutline()]);
  if (state.apiCollectionPath !== path) return;
  if (results[0].status === 'rejected') throw results[0].reason;
  state.apiCollection = prepareApiCollection(results[0].value);
  state.apiVariablesSaved = apiVariableSignature();
  renderResult();
}

function activate(doc) {
  const previous = activeDocument();
  if (previous) { previous.scrollTop = $('editor').scrollTop; previous.selection = $('editor').selectionStart; }
  state.active = doc.path;
  state.selected = doc.path;
  $('editor').value = doc.content;
  $('editor').scrollTop = doc.scrollTop || 0;
  $('editor').setSelectionRange(doc.selection || 0, doc.selection || 0);
  $('file-breadcrumb').textContent = doc.path.replaceAll('/', ' / ');
  $('editor-language').textContent = doc.path.endsWith('.json') ? 'POSTMAN COLLECTION · JSON' : 'REPORT DEFINITION';
  renderEditor(); renderDocumentTabs(); renderTree(); updateControls();
}

function renderDocumentTabs() {
  $('document-tabs').innerHTML = state.documents.map(doc => `<div class="doc-tab ${doc.path === state.active ? 'active' : ''}"><button role="tab" aria-selected="${doc.path === state.active}" data-open="${escapeHtml(doc.path)}" title="${escapeHtml(doc.path)}">${doc.path.endsWith('.json') ? '{ }' : 'ƒ'} &nbsp; ${escapeHtml(basename(doc.path))}</button><button class="close-tab ${isDirty(doc) ? 'dirty' : ''}" data-close="${escapeHtml(doc.path)}" aria-label="Close ${escapeHtml(basename(doc.path))}${isDirty(doc) ? ' (unsaved)' : ''}">${isDirty(doc) ? '•' : '×'}</button></div>`).join('');
}

function updateControls() {
  const doc = activeDocument();
  const filter = doc?.path.endsWith('.filter');
  $('run-report').disabled = state.busy || Boolean(state.activeRun) || !state.collection || !doc;
  $('run-report').innerHTML = state.activeRun ? '◌ Running…' : '<span aria-hidden="true">▶</span> Run report';
  $('validate-report').disabled = state.busy || !state.collection || !doc;
  $('save-file').disabled = !doc || !isDirty(doc) && doc.revision !== null;
  $('insert-summary').disabled = !filter;
  $('export-report').disabled = !state.reportPath;
  $('document-state').textContent = doc ? isDirty(doc) || doc.revision === null ? 'Unsaved changes' : 'Saved to workspace' : 'No file open';
  $('run-badge').textContent = state.activeRun ? 'Running' : state.run?.status === 'completed' ? 'Report ready' : 'Ready';
  $('run-badge').classList.toggle('working', Boolean(state.activeRun));
  const workspace = document.querySelector('.main-workspace');
  if (workspace) {
    workspace.classList.toggle('results-hidden', state.resultHidden);
    workspace.classList.toggle('results-only', state.editorHidden);
  }
  const toggle = $('toggle-results');
  if (toggle) {
    toggle.textContent = state.resultHidden ? 'Show results' : 'Hide results';
    toggle.setAttribute('aria-pressed', String(state.resultHidden));
    toggle.title = state.resultHidden ? 'Show report results' : 'Hide report results';
  }
  const editorToggle = $('toggle-editor');
  if (editorToggle) {
    editorToggle.textContent = state.editorHidden ? 'Show editor' : 'Focus results';
    editorToggle.setAttribute('aria-pressed', String(state.editorHidden));
    editorToggle.title = state.editorHidden ? 'Return to the report editor' : 'Show only report results';
  }
}

function renderEditor() {
  const source = $('editor').value;
  const large = source.length > 200000;
  $('editor').classList.toggle('plain', large);
  $('syntax-code').innerHTML = large ? '' : highlight(source) + '\n';
  const count = Math.min(source.split('\n').length, 20000);
  $('line-numbers').textContent = Array.from({length:count}, (_, index) => index + 1).join('\n');
  syncScroll(); updateCursor();
}

function highlight(source) {
  const pattern = /("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(#[^\n]*|--[^\n]*)|(\$[\w]+)|\b(COLLECTION|REQUESTS?|FILTER|WHERE|COLUMNS|AS|SUMMARY|TITLE|DESCRIPTION|PARAGRAPH|METRIC|FIELD|METRICS|STATUS|TABLE|LOOKUP_TABLE|LABEL_TABLE|QUICK_TABLE|QT|KV|LV|TEXT|COLOR|IF|THEN|ELSE|AND|OR|NOT|IS|TRUE|FALSE|NULL|SHAPE|ORDER|BY|LIMIT|OFFSET|GROUP|AGG|HAVING|DISTINCT|UNION|ALL|FROM|INTERSECT|EXCEPT|DIFF|COMPARE|ON|EXPAND|DATE_CONFIG|FORMAT|TIMEZONE|IN|LIKE|ILIKE|BETWEEN|SET|OUTPUT_PREFIX|HEADERS|ROW)\b|\b\d+(?:\.\d+)?\b/gim;
  let last = 0, html = '';
  for (const match of source.matchAll(pattern)) {
    html += escapeHtml(source.slice(last, match.index));
    const type = match[1] ? 'string' : match[2] ? 'comment' : match[3] ? 'variable' : match[4] ? 'keyword' : 'number';
    html += `<span class="syntax-${type}">${escapeHtml(match[0])}</span>`;
    last = match.index + match[0].length;
  }
  return html + escapeHtml(source.slice(last));
}

function syncScroll() {
  $('syntax-layer').scrollTop = $('editor').scrollTop;
  $('syntax-layer').scrollLeft = $('editor').scrollLeft;
  $('line-numbers').scrollTop = $('editor').scrollTop;
}

function updateCursor() {
  const before = $('editor').value.slice(0, $('editor').selectionStart);
  const lines = before.split('\n');
  $('cursor-position').textContent = `Ln ${lines.length}, Col ${lines.at(-1).length + 1}`;
}

function onEditorInput() {
  const doc = activeDocument();
  if (!doc) return;
  doc.content = $('editor').value;
  cancelAnimationFrame(highlightFrame);
  highlightFrame = requestAnimationFrame(renderEditor);
  renderDocumentTabs(); updateControls();
}

function insertText(text) {
  if (!activeDocument()) return;
  const editor = $('editor');
  editor.focus();
  editor.setRangeText(text, editor.selectionStart, editor.selectionEnd, 'end');
  onEditorInput();
}

async function saveDocument() {
  const doc = activeDocument();
  if (!doc) return;
  const source = doc.content;
  const result = await api('/api/file', {method:'PUT', body:{path:doc.path, content:source, revision:doc.revision}});
  doc.revision = result.revision; doc.saved = source;
  renderDocumentTabs(); updateControls();
  await refreshFiles();
  notify(`${basename(doc.path)} saved.`);
}

async function closeDocument(path) {
  const doc = state.documents.find(item => item.path === path);
  if (isDirty(doc) && !await confirmAction('Close unsaved file?', `Your edits to ${basename(path)} have not been saved. Close it and discard these edits?`, 'Discard edits')) return;
  state.documents = state.documents.filter(item => item !== doc);
  if (state.active === path) {
    state.active = null;
    if (state.documents.length) activate(state.documents.at(-1));
    else newUntitled();
  }
  renderDocumentTabs(); updateControls();
}

function newUntitled() {
  let count = 1;
  let path = 'filters/untitled.filter';
  while (state.files.some(file => file.path === path) || state.documents.some(doc => doc.path === path)) path = `filters/untitled-${++count}.filter`;
  const doc = {path, content:starter, saved:'', revision:null};
  state.documents.push(doc); activate(doc);
}

function selectedFolder(type = 'filter') {
  const base = type === 'collection' ? 'collections' : 'filters';
  const selected = state.files.find(file => file.path === state.selected);
  const folder = selected?.directory ? selected.path : state.selected.split('/').slice(0, -1).join('/');
  return folder?.startsWith(base) ? folder : base;
}

function openFileDialog(mode, suggested = '') {
  dialogMode = mode;
  $('file-dialog').returnValue = '';
  $('dialog-error').textContent = '';
  $('file-type-label').hidden = mode !== 'new';
  const names = {new:['New file','Create a report definition or Postman collection in your workspace.','Create file'], folder:['New folder','Organize collections, filters, and reports into folders.','Create folder'], move:['Rename or move','Enter the complete destination path within the same workspace folder.','Move'], import:['Import file','Choose where to save this file. Existing files will not be overwritten.','Import'], saveAs:['Save file as','Save the current editor contents to a new file. Existing files will not be overwritten.','Save copy']};
  const [title, description, action] = names[mode];
  $('dialog-title').textContent = title; $('dialog-description').textContent = description; $('dialog-submit').textContent = action;
  $('dialog-path').value = suggested || (mode === 'move' ? state.selected : mode === 'folder' ? selectedFolder() + '/new-folder' : selectedFolder($('new-file-type').value) + '/new-report.filter');
  $('file-dialog').showModal(); $('dialog-path').focus(); $('dialog-path').select();
}

async function submitFileDialog(event) {
  event.preventDefault();
  const path = $('dialog-path').value.trim();
  $('dialog-submit').disabled = true;
  try {
    if (dialogMode === 'folder') await api('/api/folder', {method:'POST', body:{path}});
    else if (dialogMode === 'move') {
      const from = state.selected;
      await api('/api/move', {method:'POST', body:{from, to:path}});
      for (const doc of state.documents) if (doc.path === from || doc.path.startsWith(from + '/')) {
        const old = doc.path;
        doc.path = path + old.slice(from.length);
        if (state.active === old) state.active = doc.path;
      }
      if (state.collection === from || state.collection.startsWith(from + '/')) state.collection = path + state.collection.slice(from.length);
      if (state.reportPath === from || state.reportPath?.startsWith(from + '/')) state.reportPath = path + state.reportPath.slice(from.length);
    } else {
      const imported = dialogMode === 'import' ? importQueue[0] : null;
      const content = dialogMode === 'saveAs' ? activeDocument().content : imported ? await imported.text() : $('new-file-type').value === 'collection'
        ? JSON.stringify({info:{name:basename(path).replace(/\.json$/, ''),schema:'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},item:[]},null,2) + '\n' : starter;
      await api('/api/file', {method:'PUT', body:{path, content, revision:null}});
      if (imported) importQueue.shift();
    }
    state.selected = path;
    state.collapsed.delete(path.split('/').slice(0, -1).join('/'));
    $('file-dialog').close('saved');
    await refreshFiles();
    if (!state.files.find(file => file.path === path)?.directory) await openFile(path);
    else if (activeDocument()) activate(activeDocument());
    notify(dialogMode === 'move' ? 'File or folder moved.' : dialogMode === 'folder' ? 'Folder created.' : 'File saved to workspace.');
    if (importQueue.length) nextImport();
  } catch (error) { $('dialog-error').textContent = error.message; }
  finally { $('dialog-submit').disabled = false; }
}

function nextImport() {
  const file = importQueue[0];
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) { importQueue = []; throw new Error('Import files up to 5 MB.'); }
  if (!/\.(json|filter)$/.test(file.name)) { importQueue = []; throw new Error('Choose a .json collection or a .filter report definition.'); }
  openFileDialog('import', `${file.name.endsWith('.json') ? 'collections' : 'filters'}/${file.name}`);
}

async function trashSelection() {
  $('manage-dialog').close();
  const path = state.selected;
  if (!await confirmAction('Move to trash?', `${path} will be moved to .web-trash. You can recover it from that folder. Any unsaved editor changes to this selection will be discarded.`, 'Move to trash')) return;
  const result = await api('/api/trash', {method:'POST', body:{path}});
  const affected = doc => doc.path === path || doc.path.startsWith(path + '/');
  const activeRemoved = affected({path:state.active || ''});
  state.documents = state.documents.filter(doc => !affected(doc));
  if (activeRemoved) { state.active = null; if (state.documents.length) activate(state.documents.at(-1)); else newUntitled(); }
  if (state.reportPath === path || state.reportPath?.startsWith(path + '/')) { state.reportPath = null; state.run = null; }
  await refreshFiles(); renderDocumentTabs(); updateControls(); renderResult();
  notify(`Moved to trash. Recovery location: ${result.recoveryPath}`);
}

async function reloadSelection() {
  $('manage-dialog').close();
  const doc = state.documents.find(item => item.path === state.selected);
  if (isDirty(doc) && !await confirmAction('Reload from disk?', 'Reloading will replace your unsaved edits with the current file on disk.', 'Reload')) return;
  const file = await api(`/api/file?path=${encode(state.selected)}`);
  if (doc) Object.assign(doc, file, {saved:file.content});
  else state.documents.push({...file,saved:file.content});
  activate(doc || state.documents.at(-1)); notify('Loaded the current file from disk.');
}

async function validateOrRun(execute) {
  const doc = activeDocument();
  if (!doc || !state.collection || state.busy || execute && state.activeRun) return;
  state.busy = true; updateControls();
  try {
    const body = {collection:state.collection, source:doc.path.endsWith('.filter') ? doc.content : '', filename:doc.path.endsWith('.filter') ? doc.path : ''};
    if (execute && $('output-file-pattern').value.trim()) body.outputFile = $('output-file-pattern').value.trim();
    const collectionDoc = state.documents.find(item => item.path === state.collection);
    if (collectionDoc) body.collectionSource = collectionDoc.content;
    const result = await api(execute ? '/api/runs' : '/api/validate', {method:'POST', body});
    if (!execute) {
      const message = `${result.message} ${result.requests} request${result.requests === 1 ? '' : 's'} selected; ${result.summaryBlocks} summary blocks.`;
      notify(message); log(message, 'success');
    } else {
      beginRun(result);
    }
  } finally { state.busy = false; updateControls(); }
}

function beginRun(result, resultsOnly = false) {
  state.run = result; state.activeRun = result.id; state.reportPath = null; state.sheet = 0; state.offset = 0;
  if (resultsOnly) setResultsOnly(true);
  log(`Started ${result.name}. Executing ${result.total} request${result.total === 1 ? '' : 's'} from ${result.collection}.`);
  setView('summary'); pollRun(result.id);
}

function setResultsOnly(hidden) {
  state.editorHidden = hidden;
  if (hidden) state.resultHidden = false;
  try { localStorage.setItem('report-studio.results-only', String(hidden)); } catch {}
  updateControls();
}

function openWorkbook(path) {
  state.reportPath = path; state.sheet = 0; state.offset = 0;
  setResultsOnly(true);
  setView('workbook');
}

async function runSavedFilter(path) {
  if (state.busy || state.activeRun) return;
  state.busy = true; state.selected = path; renderTree(); updateControls();
  try {
    const body = {filter:path, collection:state.collection || undefined};
    if ($('output-file-pattern').value.trim()) body.outputFile = $('output-file-pattern').value.trim();
    const result = await api('/api/runs/saved-filter', {method:'POST', body});
    beginRun(result, true);
  } finally { state.busy = false; updateControls(); }
}

async function pollRun(id) {
  try {
    // Prevent browsers and local proxies from replaying an earlier run state.
    const result = await api(`/api/run?id=${encode(id)}&poll=${Date.now()}`);
    const stillRunning = ['queued','running'].includes(result.status);
    state.activeRun = stillRunning ? id : null;
    if (state.run?.id === id) {
      state.run = result;
      state.reportPath = result.files?.[0] || null;
      if (['summary','requests'].includes(state.view)) renderResult();
    }
    updateControls();
    if (stillRunning) { setTimeout(() => pollRun(id), 1000); return; }
    if (result.status === 'completed') { log(result.summary, result.failed ? 'info' : 'success'); notify('Your report is ready.'); }
    else { log(result.error || 'The run failed.', 'error'); notify(result.error || 'The run failed.', true); }
    const updates = await Promise.allSettled([refreshFiles(), api('/api/runs')]);
    if (updates[1].status === 'fulfilled') state.history = updates[1].value;
    if (state.view === 'history') renderResult();
  } catch (error) {
    if (error.status === 404) {
      state.activeRun = null;
      updateControls();
      handleError(new Error('This run is no longer available. Refresh the workspace and run the report again.'));
      return;
    }
    $('status-message').textContent = 'Connection interrupted. Reconnecting to the active run…';
    setTimeout(() => pollRun(id), 3000);
  }
}

function setView(view) {
  state.view = view;
  document.querySelectorAll('#result-tabs button').forEach(button => { button.setAttribute('aria-selected', String(button.dataset.view === view)); });
  renderResult(); updateControls();
}

async function renderResult() {
  const version = ++state.renderVersion;
  const content = $('result-content');
  try {
    if (state.view === 'api') {
      content.innerHTML = state.apiCollection ? apiClientView(state.apiCollection)
        : empty('Loading API workspace…', 'Reading requests from the selected Postman collection.');
      return;
    }
    if (state.view === 'output') { content.innerHTML = state.logs.length ? `<ol class="log-list">${state.logs.map(entry => `<li class="${entry.type}"><time>${escapeHtml(entry.at)}</time><span>${escapeHtml(entry.message)}</span></li>`).join('')}</ol>` : empty('No output yet.', 'Validation messages and run details will appear here.'); return; }
    if (state.view === 'history') {
      state.history = await api('/api/runs');
      if (version !== state.renderVersion) return;
      content.innerHTML = state.history.length ? `<div class="history-wrap"><h2>Run history <span class="muted">· ${state.history.length} recent runs</span></h2><table class="history-table"><thead><tr><th>Report definition</th><th>Collection</th><th>Started</th><th>Status</th><th>Requests</th></tr></thead><tbody>${state.history.map(run => `<tr><td><button class="link-button" data-run="${escapeHtml(run.id)}">${escapeHtml(run.name)}</button></td><td>${escapeHtml(run.collection)}</td><td>${escapeHtml(formatDate(run.startedAt))}</td><td>${escapeHtml(run.status)}${run.failed ? ` · ${run.failed} failed` : ''}</td><td>${run.completed} / ${run.total}</td></tr>`).join('')}</tbody></table></div>` : empty('Your runs, all in one place.', 'Run history is saved with this workspace and remains available after you restart the application.');
      return;
    }
    if (state.view === 'workbook') {
      if (!state.reportPath) { content.innerHTML = empty('No workbook to preview yet.', 'Run a report or open an existing .xlsx file from the Reports folder.'); return; }
      content.innerHTML = '<p class="loading muted">Loading worksheet…</p>';
      const preview = await getPreview(state.reportPath, state.sheet, state.offset);
      if (version !== state.renderVersion) return;
      content.innerHTML = workbookView(preview); return;
    }
    const run = state.run;
    if (!run) { content.innerHTML = empty('Your next report starts here.', 'Open a filter, select its collection, and run your report.<br>Your summary and formatted worksheets will appear here.', true); return; }
    if (state.view === 'requests') { content.innerHTML = `<div class="requests-wrap"><h2>Request results</h2>${run.requests?.length ? requestTable(run.requests) : '<p class="muted">No requests have completed yet.</p>'}</div>`; return; }
    if (['failed','interrupted'].includes(run.status)) { content.innerHTML = `<div class="error-state"><span class="eyebrow">RUN ${escapeHtml(run.status.toUpperCase())}</span><h2>This report could not be completed.</h2><p>${escapeHtml(run.error)}</p><button class="outline-button" data-view-action="output">View output</button></div>`; return; }
    if (['queued','running'].includes(run.status)) {
      const percent = run.total ? Math.min(100, Math.round(run.completed / run.total * 100)) : 0;
      const starting = run.status === 'running' && run.completed === 0;
      content.innerHTML = `<div class="empty-state"><span class="eyebrow">${escapeHtml(run.collection)}</span><h2>${escapeHtml(run.phase)}</h2><div class="progress-track" role="progressbar" aria-label="Requests completed" aria-valuetext="${run.completed} of ${run.total} requests completed" aria-valuenow="${run.completed}" aria-valuemin="0" aria-valuemax="${run.total}"><div class="progress-fill ${starting ? 'starting' : 'working'}" style="width:${percent}%"></div></div><p>${run.completed} of ${run.total} requests completed.<br>${starting ? 'Waiting for the first API response…' : 'Building the report as responses arrive…'}</p></div>`; return;
    }
    content.innerHTML = summaryView(run);
    if (state.reportPath) {
      const path = state.reportPath;
      let preview = await getPreview(path, 0, 0);
      const summaryIndex = preview.sheets.findIndex(sheet => sheet.name === 'Summary');
      if (summaryIndex > 0) preview = await getPreview(path, summaryIndex, 0);
      if (version !== state.renderVersion || !$('custom-summary')) return;
      $('custom-summary').innerHTML = `<div class="section-label"><h2>Workbook summary</h2><span>Values and formatting from your Excel report</span></div><div class="table-overflow">${sheetTable(preview)}</div>${preview.totalRows > preview.rows.length ? '<p class="report-note">Showing the first 200 rows. Open Workbook to view the remaining rows.</p>' : ''}`;
    }
  } catch (error) {
    if (version !== state.renderVersion) return;
    content.innerHTML = `<div class="error-state"><h2>This view could not be loaded.</h2><p>${escapeHtml(error.message)}</p><button class="outline-button" data-retry="true">Try again</button></div>`;
  }
}

function empty(title, description, example = false) {
  return `<div class="empty-state"><span class="empty-symbol" aria-hidden="true">▤</span><h2>${escapeHtml(title)}</h2><p>${description}</p>${example ? '<button class="outline-button" data-example="true">Open the summary example ↗</button><p class="empty-note">Validate checks your definition. Run report executes its API requests.</p>' : ''}</div>`;
}

function currentApiRequest() {
  return state.apiCollection?.requests?.[state.apiRequestIndex] || null;
}

function prepareApiCollection(collection) {
  const normalizeRows = rows => (rows || []).map(row => ({...row, key:row.key || '', value:row.value || '', enabled:row.enabled ?? !row.disabled}));
  const variables = Array.isArray(collection.variables)
    ? normalizeRows(collection.variables)
    : Object.entries(collection.variables || {}).map(([key,value]) => ({key, value:String(value ?? ''), enabled:true}));
  return {...collection, variables, requests:(collection.requests || []).map(request => {
    const prepared = {
      ...request,
      headers:normalizeRows(request.headers),
      params:normalizeRows(request.params),
      auth:{type:request.auth?.type || 'noauth', values:{...(request.auth?.values || {})}},
      bodyMode:['none','raw','urlencoded','formdata'].includes(request.bodyMode) ? request.bodyMode : request.body ? 'raw' : 'none',
      bodyFields:normalizeRows(request.bodyFields).map(field => ({...field, type:field.type || 'text'}))
    };
    if (!prepared.params.length && prepared.url.includes('?')) syncParamsFromUrl(prepared);
    return prepared;
  })};
}

function decodeQueryPart(value) {
  try { return decodeURIComponent(String(value).replaceAll('+', ' ')); }
  catch { return String(value); }
}

function encodeQueryPart(value) {
  return String(value ?? '').split(/(\{\{[^}]+}})/g)
    .map(part => part.startsWith('{{') && part.endsWith('}}') ? part : encodeURIComponent(part)).join('');
}

function syncParamsFromUrl(request) {
  const beforeHash = request.url.split('#', 1)[0];
  const queryIndex = beforeHash.indexOf('?');
  if (queryIndex < 0) { request.params = []; return; }
  request.params = beforeHash.slice(queryIndex + 1).split('&').filter(Boolean).map(pair => {
    const [key,...rest] = pair.split('=');
    return {key:decodeQueryPart(key), value:decodeQueryPart(rest.join('=')), enabled:true};
  });
}

function syncUrlFromParams(request) {
  const hashIndex = request.url.indexOf('#');
  const fragment = hashIndex >= 0 ? request.url.slice(hashIndex) : '';
  const withoutHash = hashIndex >= 0 ? request.url.slice(0, hashIndex) : request.url;
  const base = withoutHash.split('?', 1)[0];
  const query = request.params.filter(param => param.enabled && param.key.trim()).map(param =>
    `${encodeQueryPart(param.key)}=${encodeQueryPart(param.value)}`).join('&');
  request.url = base + (query ? `?${query}` : '') + fragment;
  return request.url;
}

function apiRows(kind, request = currentApiRequest()) {
  if (kind === 'param') return request?.params;
  if (kind === 'header') return request?.headers;
  if (kind === 'variable') return state.apiCollection?.variables;
  if (kind === 'body') return request?.bodyFields;
  return null;
}

function apiVariablePayload() {
  return Object.fromEntries((state.apiCollection?.variables || []).filter(row => row.enabled && row.key.trim()).map(row => {
    const key = row.key.trim().replace(/^\{\{\s*/, '').replace(/\s*}}$/, '');
    return [key, row.value];
  }).filter(([key]) => key));
}

function apiCollectionVariableEntries() {
  const values = new Map();
  (state.apiCollection?.variables || []).forEach(row => {
    const key = row.key.trim().replace(/^\{\{\s*/, '').replace(/\s*}}$/, '');
    if (key) values.set(key, String(row.value ?? ''));
  });
  return [...values].map(([key,value]) => ({key,value}));
}

function apiVariableSignature() {
  return JSON.stringify(apiCollectionVariableEntries());
}

function apiVariablesDirty() {
  return apiVariableSignature() !== state.apiVariablesSaved;
}

async function saveApiVariables() {
  if (!state.apiCollectionPath || state.apiVariablesSaving || !apiVariablesDirty()) return;
  state.apiVariablesSaving = true;
  renderResult();
  try {
    const open = state.documents.find(document => document.path === state.apiCollectionPath);
    const sourceDocument = open || await api(`/api/file?path=${encode(state.apiCollectionPath)}`);
    let collection;
    try { collection = JSON.parse(sourceDocument.content); }
    catch { throw new Error('The collection JSON is invalid. Fix it before saving variables.'); }
    if (!collection || typeof collection !== 'object' || Array.isArray(collection)) throw new Error('The collection JSON must be an object.');
    const existing = new Map((Array.isArray(collection.variable) ? collection.variable : [])
      .filter(variable => variable && typeof variable === 'object' && variable.key !== undefined)
      .map(variable => [String(variable.key), variable]));
    collection.variable = apiCollectionVariableEntries().map(({key,value}) => ({...(existing.get(key) || {}), key, value,
      type:existing.get(key)?.type || 'string'}));
    const content = JSON.stringify(collection, null, 2) + '\n';
    const saved = await api('/api/file', {method:'PUT', body:{path:state.apiCollectionPath, content, revision:sourceDocument.revision}});
    if (open) {
      open.content = content; open.saved = content; open.revision = saved.revision;
      if (state.active === open.path) { $('editor').value = content; renderEditor(); }
      renderDocumentTabs(); updateControls();
    }
    state.apiVariablesSaved = apiVariableSignature();
    await refreshFiles();
    notify(`Variables saved to ${basename(state.apiCollectionPath)}.`);
  } finally {
    state.apiVariablesSaving = false;
    renderResult();
  }
}

function prettyResponseBody(body) {
  if (!body) return '';
  try { return JSON.stringify(JSON.parse(body), null, 2); }
  catch { return body; }
}

function responseTableRow(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return {...value};
  return {Value:value};
}

function apiValueText(value) {
  if (value === null || value === undefined) return '';
  if (typeof value !== 'object') return String(value);
  try { return JSON.stringify(value); }
  catch { return String(value); }
}

function apiPrimitiveValue(value) {
  if (value === null || value === undefined) return '<span class="api-nested-empty">—</span>';
  const text = String(value);
  const type = typeof value === 'boolean' ? 'is-boolean' : typeof value === 'number' ? 'is-number' : '';
  const display = escapeHtml(text);
  return `<span class="${type}">${display}</span>`;
}

function apiNestedTable(value) {
  if (Array.isArray(value)) {
    if (!value.length) return '<span class="api-nested-empty">[]</span>';
    const objectRows = value.every(item => item && typeof item === 'object' && !Array.isArray(item));
    if (objectRows) {
      const columns = [];
      value.forEach(item => Object.keys(item).forEach(key => { if (!columns.includes(key)) columns.push(key); }));
      if (!columns.length) return '<span class="api-nested-empty">[{}]</span>';
      return `<div class="api-nested-shell"><table class="api-nested-table api-nested-array"><thead><tr><th class="api-nested-index">#</th>${columns.map(column => `<th>${escapeHtml(column)}</th>`).join('')}</tr></thead><tbody>${value.map((item,index) => `<tr><th class="api-nested-index" scope="row">${index + 1}</th>${columns.map(column => `<td class="${item[column] && typeof item[column] === 'object' ? 'is-structured' : ''}">${apiNestedValue(item[column])}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
    }
    return `<div class="api-nested-shell"><table class="api-nested-table api-nested-list"><thead><tr><th class="api-nested-index">#</th><th>Value</th></tr></thead><tbody>${value.map((item,index) => `<tr><th class="api-nested-index" scope="row">${index + 1}</th><td class="${item && typeof item === 'object' ? 'is-structured' : ''}">${apiNestedValue(item)}</td></tr>`).join('')}</tbody></table></div>`;
  }
  const entries = Object.entries(value);
  if (!entries.length) return '<span class="api-nested-empty">{}</span>';
  return `<div class="api-nested-shell"><table class="api-nested-table api-nested-object"><thead><tr><th>Field</th><th>Value</th></tr></thead><tbody>${entries.map(([key,item]) => `<tr><th scope="row">${escapeHtml(key)}</th><td class="${item && typeof item === 'object' ? 'is-structured' : ''}">${apiNestedValue(item)}</td></tr>`).join('')}</tbody></table></div>`;
}

function apiNestedValue(value) {
  return value && typeof value === 'object' ? apiNestedTable(value) : apiPrimitiveValue(value);
}

function responseDatasets(body) {
  let root;
  try { root = JSON.parse(body); } catch { return []; }
  const datasets = [];
  const visit = (value, name, depth = 0) => {
    if (depth > 5) return false;
    if (Array.isArray(value)) {
      const rows = value.slice(0, 500).map(item => responseTableRow(item));
      const columns = [];
      rows.forEach(row => Object.keys(row).forEach(key => { if (!columns.includes(key) && columns.length < 60) columns.push(key); }));
      datasets.push({name, rows, columns, total:value.length});
      return true;
    }
    if (!value || typeof value !== 'object') return false;
    let found = false;
    Object.entries(value).forEach(([key,item]) => {
      if (Array.isArray(item) || item && typeof item === 'object') found = visit(item, name === 'Response' ? key : `${name}.${key}`, depth + 1) || found;
    });
    return found;
  };
  const found = visit(root, 'Response');
  if (!found) {
    const row = responseTableRow(root);
    datasets.push({name:'Response', rows:[row], columns:Object.keys(row).slice(0, 60), total:1});
  }
  return datasets;
}

function apiResponseTable(datasets) {
  const index = Math.max(0, Math.min(state.apiResponseDataset, datasets.length - 1));
  const dataset = datasets[index];
  if (!dataset || !dataset.rows.length) return '<div class="api-response-empty">The selected JSON array is empty.</div>';
  const filter = state.apiTableFilter.trim().toLowerCase();
  const rows = filter ? dataset.rows.filter(row => dataset.columns.some(column => apiValueText(row[column]).toLowerCase().includes(filter))) : dataset.rows;
  const selector = `<label class="api-dataset-picker"><span>Dataset</span><select data-api-response-dataset aria-label="Response dataset">${datasets.map((item,itemIndex) => `<option value="${itemIndex}" ${itemIndex === index ? 'selected' : ''}>${escapeHtml(item.name)}</option>`).join('')}</select></label>`;
  const columnHeading = column => {
    const parts = column.split('.');
    const name = parts.pop();
    return parts.length ? `<span>${escapeHtml(parts.join('.'))}</span><strong>${escapeHtml(name)}</strong>` : `<strong>${escapeHtml(name)}</strong>`;
  };
  const cell = value => {
    if (value === null || value === undefined) return '<td class="is-null">—</td>';
    if (typeof value === 'object') return `<td class="is-structured">${apiNestedTable(value)}</td>`;
    const text = typeof value === 'string' ? value : value === null || value === undefined ? '' : String(value);
    const type = typeof value === 'boolean' ? 'is-boolean' : typeof value === 'number' ? 'is-number' : '';
    const display = escapeHtml(text.length > 2_000 ? text.slice(0, 2_000) + '…' : text);
    return `<td class="${type}">${typeof value === 'boolean' ? `<span>${display}</span>` : display}</td>`;
  };
  const resultCount = `${rows.length.toLocaleString()} of ${dataset.total.toLocaleString()} row${dataset.total === 1 ? '' : 's'}`;
  const emptyRows = `<tr class="api-table-empty-row" data-api-filter-empty ${rows.length ? 'hidden' : ''}><td colspan="${dataset.columns.length + 1}">${state.apiTableFilter.trim() ? `No rows match “${escapeHtml(state.apiTableFilter.trim())}”.` : 'This dataset has no rows.'}</td></tr>`;
  return `<div class="api-table-toolbar">${selector}<label class="api-table-search"><span aria-hidden="true">⌕</span><input data-api-table-filter type="search" aria-label="Filter response rows" value="${escapeHtml(state.apiTableFilter)}" placeholder="Filter rows"></label><span class="api-table-count" data-api-table-count data-total="${dataset.total}">${resultCount}</span></div><div class="api-table-scroll"><table class="api-json-table"><thead><tr><th class="api-row-number">#</th>${dataset.columns.map(column => `<th>${columnHeading(column)}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr data-api-data-row><th>${dataset.rows.indexOf(row) + 1}</th>${dataset.columns.map(column => cell(row[column])).join('')}</tr>`).join('')}${emptyRows}</tbody></table></div>${dataset.total > dataset.rows.length ? `<p class="api-table-note">Large response: showing the first 500 of ${dataset.total.toLocaleString()} rows.</p>` : ''}`;
}

function apiResponseContent(response) {
  if (state.apiSending) return '<div class="api-response-loading"><span aria-hidden="true"></span><strong>Sending request</strong><small>Waiting for the API response…</small></div>';
  if (!response) return '<div class="api-response-empty">Response status, time, and body will appear here.</div>';
  if (response.error) return `<div class="api-response-error">${escapeHtml(response.error)}</div>`;
  const datasets = responseDatasets(response.body);
  if (state.apiResponseView === 'table' && datasets.length) return apiResponseTable(datasets);
  const body = state.apiResponseView === 'raw' ? response.body : prettyResponseBody(response.body);
  return `<pre>${escapeHtml(body)}</pre>`;
}

function apiResponseToolbar(response) {
  if (!response || response.error || state.apiSending) return '';
  const hasTable = responseDatasets(response.body).length > 0;
  return `<div class="api-response-views" role="group" aria-label="Response view"><button data-api-response-view="pretty" class="${state.apiResponseView === 'pretty' ? 'active' : ''}">Pretty</button><button data-api-response-view="table" class="${state.apiResponseView === 'table' ? 'active' : ''}" ${hasTable ? '' : 'disabled'}>Table</button><button data-api-response-view="raw" class="${state.apiResponseView === 'raw' ? 'active' : ''}">Raw</button></div><button class="api-copy-response" data-copy-response="true" title="Copy the current response view" aria-label="Copy the current response view">Copy</button>`;
}

function responseStatusText(code) {
  const labels = {200:'OK',201:'Created',202:'Accepted',204:'No content',400:'Bad request',401:'Unauthorized',403:'Forbidden',404:'Not found',409:'Conflict',422:'Unprocessable',429:'Too many requests',500:'Server error',502:'Bad gateway',503:'Unavailable',504:'Timeout'};
  return labels[code] || (code >= 200 && code < 300 ? 'Success' : code >= 400 ? 'Failed' : 'Response');
}

function formatResponseSize(body) {
  const bytes = String(body || '').length;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

async function copyApiResponse() {
  if (!state.apiResponse || state.apiResponse.error) return;
  let text = state.apiResponseView === 'raw' ? state.apiResponse.body : prettyResponseBody(state.apiResponse.body);
  if (state.apiResponseView === 'table') {
    const datasets = responseDatasets(state.apiResponse.body);
    const dataset = datasets[Math.max(0, Math.min(state.apiResponseDataset, datasets.length - 1))];
    if (dataset) {
      const rows = state.apiTableFilter.trim() ? dataset.rows.filter(row => dataset.columns.some(column => apiValueText(row[column]).toLowerCase().includes(state.apiTableFilter.trim().toLowerCase()))) : dataset.rows;
      const clean = value => apiValueText(value).replaceAll('\t', ' ').replaceAll('\r', ' ').replaceAll('\n', ' ');
      text = [dataset.columns.join('\t'), ...rows.map(row => dataset.columns.map(column => clean(row[column])).join('\t'))].join('\n');
    }
  }
  if (globalThis.navigator?.clipboard?.writeText) await globalThis.navigator.clipboard.writeText(text);
  else {
    const textarea = document.createElement('textarea'); textarea.value = text; textarea.setAttribute('readonly', '');
    textarea.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
    document.body.append(textarea); textarea.select(); document.execCommand('copy'); textarea.remove();
  }
  notify(state.apiResponseView === 'table' ? 'Table copied as tab-separated values.' : 'Response copied.');
}

function apiKeyValueEditor(kind, rows, labels = {}) {
  const values = rows.length ? rows : [{key:'', value:'', enabled:true, type:'text'}];
  const typeColumn = kind === 'body' && currentApiRequest()?.bodyMode === 'formdata';
  return `<div class="api-kv-table ${typeColumn ? 'has-type' : ''}" role="group" aria-label="${escapeHtml(labels.title || kind)}"><div class="api-kv-heading"><span>Use</span><span>${escapeHtml(labels.key || 'Key')}</span><span>${escapeHtml(labels.value || 'Value')}</span>${typeColumn ? '<span>Type</span>' : ''}<span></span></div>${values.map((row,index) => `<div class="api-kv-row"><label class="api-kv-toggle" title="Include this ${escapeHtml(kind)}"><input type="checkbox" data-api-row-kind="${kind}" data-api-row-index="${index}" data-api-row-enabled ${row.enabled ? 'checked' : ''}><span aria-hidden="true"></span><span class="sr-only">Include ${escapeHtml(kind)} ${index + 1}</span></label><input data-api-row-kind="${kind}" data-api-row-index="${index}" data-api-row-field="key" aria-label="${escapeHtml(labels.key || 'Key')} ${index + 1}" value="${escapeHtml(row.key)}" placeholder="${escapeHtml(labels.keyPlaceholder || 'Key')}"><input data-api-row-kind="${kind}" data-api-row-index="${index}" data-api-row-field="value" aria-label="${escapeHtml(labels.value || 'Value')} ${index + 1}" value="${escapeHtml(row.type === 'file' ? row.source || row.value : row.value)}" placeholder="${escapeHtml(labels.valuePlaceholder || 'Value')}">${typeColumn ? `<select data-api-row-kind="${kind}" data-api-row-index="${index}" data-api-row-field="type" aria-label="Field type ${index + 1}"><option value="text" ${row.type !== 'file' ? 'selected' : ''}>Text</option><option value="file" ${row.type === 'file' ? 'selected' : ''}>File</option></select>` : ''}<button data-remove-api-row="${kind}" data-api-row-index="${index}" title="Remove row" aria-label="Remove ${escapeHtml(kind)} ${index + 1}">×</button></div>`).join('')}</div>`;
}

function apiTemplateVariables(request) {
  const names = [...new Set([...request.url.matchAll(/\{\{\s*([^}]+?)\s*}}/g)].map(match => match[1].trim()))];
  if (!names.length) return '';
  const values = apiVariablePayload();
  return `<div class="api-template-vars"><span>URL variables</span>${names.map(name => `<button data-api-request-tab="variables" class="${Object.hasOwn(values,name) ? 'is-set' : 'is-missing'}"><code>{{${escapeHtml(name)}}}</code><small>${Object.hasOwn(values,name) ? 'set' : 'needs value'}</small></button>`).join('')}</div>`;
}

function apiAuthPanel(request) {
  const type = request.auth.type;
  const values = request.auth.values;
  const secret = (field, label, placeholder) => `<label><span>${label}</span><input type="password" data-api-auth-field="${field}" value="${escapeHtml(values[field] || '')}" placeholder="${placeholder}" autocomplete="off"></label>`;
  let fields = '<p class="api-panel-empty">This request will be sent without an Authorization header.</p>';
  if (type === 'basic') fields = `<div class="api-auth-fields"><label><span>Username</span><input data-api-auth-field="username" value="${escapeHtml(values.username || '')}" placeholder="{{API_USERNAME}}" autocomplete="off"></label>${secret('password','Password','{{API_PASSWORD}}')}</div>`;
  else if (type === 'bearer') fields = `<div class="api-auth-fields">${secret('token','Token','{{BEARER_TOKEN}}')}</div>`;
  else if (type === 'apikey') fields = `<div class="api-auth-fields"><label><span>Key</span><input data-api-auth-field="key" value="${escapeHtml(values.key || '')}" placeholder="X-API-Key" autocomplete="off"></label>${secret('value','Value','{{API_KEY}}')}<label><span>Add to</span><select data-api-auth-field="in"><option value="header" ${values.in !== 'query' ? 'selected' : ''}>Header</option><option value="query" ${values.in === 'query' ? 'selected' : ''}>Query params</option></select></label></div>`;
  return `<div class="api-auth-panel"><label class="api-auth-type"><span>Type</span><select data-api-auth-type><option value="noauth" ${type === 'noauth' ? 'selected' : ''}>No Auth</option><option value="basic" ${type === 'basic' ? 'selected' : ''}>Basic Auth</option><option value="bearer" ${type === 'bearer' ? 'selected' : ''}>Bearer Token</option><option value="apikey" ${type === 'apikey' ? 'selected' : ''}>API Key</option></select></label>${fields}<p class="api-config-note">Values may use collection or environment variables such as <code>{{TOKEN}}</code>.</p></div>`;
}

function apiBodyPanel(request) {
  const modes = [['none','none'],['raw','raw'],['urlencoded','x-www-form-urlencoded'],['formdata','form-data']];
  let editor = '<p class="api-panel-empty">This request has no body.</p>';
  if (request.bodyMode === 'raw') editor = `<textarea class="api-body" data-api-field="body" aria-label="Raw request body" spellcheck="false" placeholder="Request body">${escapeHtml(request.body)}</textarea>`;
  else if (request.bodyMode === 'urlencoded' || request.bodyMode === 'formdata') editor = `${apiKeyValueEditor('body', request.bodyFields, {title:'Body fields', key:'Field', keyPlaceholder:'Field name'})}<button class="api-add-row" data-add-api-row="body">+ Add field</button>${request.bodyMode === 'formdata' ? '<p class="api-config-note">Text multipart fields are supported. Local file upload fields are shown but cannot be sent yet.</p>' : ''}`;
  return `<div class="api-body-panel"><div class="api-body-modes" role="group" aria-label="Request body type">${modes.map(([value,label]) => `<button data-api-body-mode="${value}" class="${request.bodyMode === value ? 'active' : ''}">${label}</button>`).join('')}</div>${editor}</div>`;
}

function apiRequestPanel(request) {
  if (state.apiRequestTab === 'params') return `<div class="api-config-panel">${apiTemplateVariables(request)}${apiKeyValueEditor('param', request.params, {title:'Query parameters', keyPlaceholder:'Parameter', valuePlaceholder:'Value or {{variable}}'})}<button class="api-add-row" data-add-api-row="param">+ Add parameter</button></div>`;
  if (state.apiRequestTab === 'auth') return `<div class="api-config-panel">${apiAuthPanel(request)}</div>`;
  if (state.apiRequestTab === 'headers') return `<div class="api-config-panel">${apiKeyValueEditor('header', request.headers, {title:'Headers', key:'Header', keyPlaceholder:'Header name'})}<button class="api-add-row" data-add-api-row="header">+ Add header</button></div>`;
  if (state.apiRequestTab === 'body') return `<div class="api-config-panel">${apiBodyPanel(request)}</div>`;
  return `<div class="api-config-panel"><div class="api-variable-intro"><div><strong>Collection variables</strong><span>Use names anywhere in the request as <code>{{NAME}}</code>. Changes apply immediately; save them to keep them.</span></div><button class="api-save-variables" data-save-api-variables ${state.apiVariablesSaving || !apiVariablesDirty() ? 'disabled' : ''}>${state.apiVariablesSaving ? 'Saving…' : 'Save to collection'}</button></div>${apiKeyValueEditor('variable', state.apiCollection.variables, {title:'Variables', key:'Variable', keyPlaceholder:'baseUrl', valuePlaceholder:'https://api.example.com'})}<button class="api-add-row" data-add-api-row="variable">+ Add variable</button></div>`;
}

function apiClientView(collection) {
  if (!collection.requests.length) return empty('This collection has no requests.', 'Add a request to the Postman collection, then refresh the workspace.');
  const request = currentApiRequest() || collection.requests[0];
  const response = state.apiResponse;
  const counts = {params:request.params.filter(row => row.enabled && row.key.trim()).length, headers:request.headers.filter(row => row.enabled && row.key.trim()).length};
  const tabs = [['params','Params',counts.params],['auth','Authorization',''],['headers','Headers',counts.headers],['body','Body',request.bodyMode === 'none' ? '' : '•'],['variables','Variables',collection.variables.filter(row => row.enabled && row.key.trim()).length]];
  const responseMeta = state.apiSending ? '<span class="api-response-prompt">Waiting for response…</span>' : response ? `<div class="api-response-meta"><span class="api-status ${response.success ? 'ok' : 'error'}">${response.statusCode || 'Error'} ${response.statusCode ? responseStatusText(response.statusCode) : ''}</span><span>${response.durationMs} ms</span><span>${formatResponseSize(response.body)}</span></div>` : '<span class="api-response-prompt">Send the request to see its response.</span>';
  return `<div class="api-client"><aside class="api-request-list"><div class="api-collection-heading"><span class="eyebrow">COLLECTION</span><h2>${escapeHtml(collection.name)}</h2><span>${collection.requests.length} request${collection.requests.length === 1 ? '' : 's'}</span></div><div class="api-request-scroll">${collection.requests.map((item,index) => `<button class="api-request-item ${index === state.apiRequestIndex ? 'active' : ''}" data-api-request="${index}"><span class="method ${escapeHtml(item.method.toLowerCase())}">${escapeHtml(item.method)}</span><span><strong>${escapeHtml(item.name)}</strong>${item.folder ? `<small>${escapeHtml(item.folder)}</small>` : ''}</span>${item.disabled ? '<em>OFF</em>' : ''}</button>`).join('')}</div><button class="api-source-button" data-edit-collection="${escapeHtml(collection.path)}">{ } View collection JSON</button></aside><section class="api-request-workspace"><div class="api-request-title"><div><span class="eyebrow">${escapeHtml(request.folder || 'REQUEST')}</span><h2>${escapeHtml(request.name)}</h2></div>${request.disabled ? '<span class="api-disabled">Disabled in collection · manual Send is available</span>' : ''}</div><div class="api-url-bar"><select data-api-field="method" aria-label="HTTP method">${['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS'].map(method => `<option ${method === request.method.toUpperCase() ? 'selected' : ''}>${method}</option>`).join('')}</select><input data-api-field="url" aria-label="Request URL" value="${escapeHtml(request.url)}" spellcheck="false"><button class="primary-button api-send" data-send-request="true" ${state.apiSending ? 'disabled' : ''}>${state.apiSending ? 'Sending…' : 'Send'}</button></div>${request.description ? `<p class="api-description">${escapeHtml(request.description)}</p>` : ''}<div class="api-request-config"><div class="api-request-tabs" role="tablist" aria-label="Request configuration">${tabs.map(([value,label,count]) => `<button role="tab" aria-selected="${state.apiRequestTab === value}" data-api-request-tab="${value}" class="${state.apiRequestTab === value ? 'active' : ''}">${label}${count !== '' ? `<span>${count}</span>` : ''}</button>`).join('')}</div>${apiRequestPanel(request)}</div><section class="api-response"><div class="api-response-heading"><h3>Response</h3><div class="api-response-tools">${apiResponseToolbar(response)}${responseMeta}</div></div>${apiResponseContent(response)}</section></section></div>`;
}

async function sendApiRequest() {
  const request = currentApiRequest();
  if (!request || state.apiSending) return;
  state.apiSending = true;
  renderResult();
  try {
    state.apiResponse = await api('/api/request', {method:'POST', body:{collection:state.apiCollectionPath,
      index:request.index, method:request.method, url:request.url,
      headers:request.headers.filter(header => header.enabled && header.key.trim()).map(({key,value}) => ({key,value})),
      body:request.body, bodyMode:request.bodyMode,
      bodyFields:request.bodyFields.map(field => ({key:field.key,value:field.value,type:field.type || 'text',source:field.source || '',disabled:!field.enabled,contentType:field.contentType || ''})),
      auth:request.auth, variables:apiVariablePayload()}});
    const datasets = responseDatasets(state.apiResponse.body);
    state.apiResponseView = datasets.length ? 'table' : 'pretty';
    state.apiResponseDataset = 0;
    state.apiTableFilter = '';
  } catch (error) {
    state.apiResponse = {statusCode:0, durationMs:0, success:false, error:error.message, body:''};
  } finally {
    state.apiSending = false;
    renderResult();
  }
}

function formatDate(value) { return new Date(value).toLocaleString([], {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}); }

function summaryView(run) {
  const passRate = run.total ? Math.round(run.passed / run.total * 100) : 0;
  const workbooks = run.files?.filter(path => path.endsWith('.xlsx')) || [];
  const workbookActions = workbooks.length ? `<div class="report-open-actions">${workbooks.map(path => `<button class="outline-button" data-open-workbook="${escapeHtml(path)}">Open Excel preview <span aria-hidden="true">↗</span></button>`).join('')}</div>` : '';
  return `<article class="summary-page"><header class="summary-heading"><div><span class="eyebrow">EXECUTION OVERVIEW</span><h1>${escapeHtml(run.name.replace(/\.filter$/, '').replaceAll('-', ' '))}</h1><p>${escapeHtml(run.collection)}</p></div><div class="summary-stamp"><span class="outcome-badge ${run.failed ? 'failed' : ''}">${run.failed ? '◷ Needs attention' : '✓ All requests passed'}</span><br>Generated ${escapeHtml(formatDate(run.finishedAt))}</div></header><div class="metrics-strip"><div class="metric"><div class="metric-label">Total requests</div><div class="metric-value">${run.total}</div></div><div class="metric"><div class="metric-label">Successful</div><div class="metric-value">${run.passed}<span>${passRate}%</span></div></div><div class="metric"><div class="metric-label">Failed</div><div class="metric-value ${run.failed ? 'failure' : ''}">${run.failed}</div></div><div class="metric"><div class="metric-label">Avg. response time</div><div class="metric-value">${run.averageMs}<span>ms</span></div></div></div><div class="summary-narrative ${run.failed ? 'attention' : ''}"><span aria-hidden="true">${run.failed ? '!' : '✓'}</span><div>${escapeHtml(run.summary)}${run.failed ? ' Review the failed requests below before sharing this report.' : ' Your report is ready to review and export.'}</div></div>${workbookActions}<div class="section-label"><h2>Request overview</h2><span>${run.requests.length} requests · ${run.files.length} workbook${run.files.length === 1 ? '' : 's'}</span></div>${requestTable(run.requests)}<div id="custom-summary"><p class="loading muted">Loading your formatted summary…</p></div><p class="report-note">Generated from your selected collection and editor contents. HTTP outcomes use the existing report engine's success rules.</p></article>`;
}

function requestTable(requests) {
  return `<div class="table-overflow"><table class="request-table"><thead><tr><th>Request</th><th>HTTP status</th><th>Duration</th><th>Outcome</th></tr></thead><tbody>${requests.map(request => `<tr><td><span class="method ${escapeHtml(request.method.toLowerCase())}">${escapeHtml(request.method)}</span>${escapeHtml(request.name)}${request.error ? `<p class="request-error">${escapeHtml(request.error)}</p>` : ''}${request.assertions?.length ? `<p class="request-error">${escapeHtml(request.assertions.join(' · '))}</p>` : ''}</td><td>${request.statusCode || '—'}</td><td>${request.durationMs} ms</td><td class="${request.success ? 'result-ok' : 'result-fail'}">${request.success ? '✓ Passed' : '× Failed'}</td></tr>`).join('')}</tbody></table></div>`;
}

async function getPreview(path, sheet, offset) {
  const key = `${path}:${sheet}:${offset}`;
  if (!state.previewCache.has(key)) {
    const value = await api(`/api/workbook?path=${encode(path)}&sheet=${sheet}&offset=${offset}&limit=200`);
    if (state.previewCache.size > 12) state.previewCache.delete(state.previewCache.keys().next().value);
    state.previewCache.set(key, value);
  }
  return state.previewCache.get(key);
}

function workbookView(preview) {
  const paths = state.run?.files?.includes(state.reportPath) ? state.run.files : [state.reportPath];
  return `<div class="workbook-toolbar"><select id="workbook-file" aria-label="Workbook file">${paths.map(path => `<option value="${escapeHtml(path)}" ${path === state.reportPath ? 'selected' : ''}>${escapeHtml(basename(path))}</option>`).join('')}</select><span>${preview.sheets.length} worksheets</span><div class="page-controls"><button data-page="prev" ${state.offset === 0 ? 'disabled' : ''} aria-label="Previous worksheet page">←</button><span>Rows ${preview.rows.length ? preview.offset + 1 : 0}–${preview.offset + preview.rows.length} of ${preview.totalRows.toLocaleString()}</span><button data-page="next" ${preview.offset + preview.rows.length >= preview.totalRows ? 'disabled' : ''} aria-label="Next worksheet page">→</button></div></div><div class="sheet-tabs" role="tablist" aria-label="Worksheets">${preview.sheets.map((sheet,index) => `<button role="tab" aria-selected="${index === state.sheet}" class="${index === state.sheet ? 'active' : ''}" data-sheet="${index}">${escapeHtml(sheet.name)}</button>`).join('')}</div><div class="workbook-scroll">${sheetTable(preview)}</div>`;
}

function columnLabel(index) {
  let result = '';
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) result = String.fromCharCode(65 + (n - 1) % 26) + result;
  return result;
}

function sheetTable(preview) {
  const merges = preview.merges;
  const width = preview.widths.reduce((sum, item) => sum + Math.max(32, item), 38);
  const styleText = style => {
    if (!style) return '';
    const safeColor = color => /^#[0-9a-f]{6}$/i.test(color) ? color : '';
    return `font-weight:${style.bold ? 700 : 400};font-style:${style.italic ? 'italic' : 'normal'};font-size:${Math.max(8, Math.min(72, Number(style.fontSize) || 11))}pt;color:${safeColor(style.color) || '#26352b'};background:${safeColor(style.background) || '#fff'};text-align:${['left','center','right'].includes(style.align) ? style.align : 'left'};white-space:${style.wrap ? 'pre-wrap' : 'pre'};`;
  };
  return `<table class="workbook-table" aria-label="${escapeHtml(preview.sheets[preview.sheet].name)} worksheet" style="width:${width}px"><colgroup><col style="width:38px">${preview.widths.map(value => `<col style="width:${Math.max(32, value)}px">`).join('')}</colgroup><thead><tr><th aria-label="Row number"></th>${preview.widths.map((_,index) => `<th scope="col">${columnLabel(index)}</th>`).join('')}</tr></thead><tbody>${preview.rows.map(row => {
    const cells = new Map(row.cells.map(cell => [cell.column,cell]));
    let html = `<tr style="height:${Math.max(22,row.height)}px"><th scope="row">${row.index + 1}</th>`;
    for (let column = 0; column < preview.widths.length; column++) {
      const merge = merges.find(merge => row.index >= merge.firstRow && row.index <= merge.lastRow && column >= merge.firstColumn && column <= merge.lastColumn);
      if (merge && (row.index !== Math.max(merge.firstRow, preview.offset) || column !== merge.firstColumn)) continue;
      const cell = merge && merge.firstRow < preview.offset ? {text:merge.text,style:merge.style} : cells.get(column);
      const span = merge ? ` colspan="${merge.lastColumn - merge.firstColumn + 1}" rowspan="${Math.min(merge.lastRow + 1, preview.offset + preview.rows.length) - Math.max(merge.firstRow, preview.offset)}"` : '';
      html += `<td${span} style="${styleText(preview.styles[cell?.style])}">${escapeHtml(cell?.text || '')}</td>`;
    }
    return html + '</tr>';
  }).join('')}</tbody></table>`;
}

async function openExample() {
  const example = state.files.find(file => file.path === 'filters/reqres.filter') || state.files.find(file => file.path.endsWith('/pokeapi-open.filter'));
  if (example) await openFile(example.path);
  else { newUntitled(); notify('A new report definition is ready. Select a collection to begin.'); }
}

bind('file-tree','click', async event => {
  const createFilter = event.target.closest('[data-create-filter]');
  if (createFilter) {
    newUntitled();
    notify('New report script created. Save it when you are ready.');
    return;
  }
  const run = event.target.closest('[data-run-filter]');
  if (run) { await runSavedFilter(run.dataset.runFilter); return; }
  const row = event.target.closest('[data-path]');
  if (!row) return;
  state.selected = row.dataset.path;
  if (row.dataset.directory === 'true') {
    state.collapsed.has(state.selected) ? state.collapsed.delete(state.selected) : state.collapsed.add(state.selected);
    renderTree();
  } else { await openFile(state.selected); if (innerWidth <= 600) $('explorer').classList.remove('visible'); }
});
bind('file-search','input',renderTree);
bind('refresh-files','click',async () => { state.previewCache.clear(); await refreshFiles(); notify('Workspace refreshed.'); });
bind('new-file','click',() => openFileDialog('new'));
bind('new-folder','click',() => openFileDialog('folder'));
bind('import-file','click',() => $('file-upload').click());
bind('file-upload','change',() => { importQueue = [...$('file-upload').files]; $('file-upload').value = ''; nextImport(); });
bind('file-form','submit',submitFileDialog);
bind('new-file-type','change',() => { const collection = $('new-file-type').value === 'collection'; $('dialog-path').value = collection ? 'collections/new-collection.json' : 'filters/new-report.filter'; });
bind('dialog-close','click',() => { importQueue = []; $('file-dialog').close(); });
bind('dialog-cancel','click',() => { importQueue = []; $('file-dialog').close(); });
bind('file-dialog','cancel',() => { importQueue = []; });
bind('manage-file','click',() => {
  if (!state.selected.includes('/')) { notify('Select a file or a subfolder to manage it.'); return; }
  $('manage-path').textContent = state.selected;
  $('reopen-file').hidden = state.files.find(file => file.path === state.selected)?.directory || state.selected.endsWith('.xlsx');
  $('manage-dialog').showModal();
});
bind('manage-close','click',() => $('manage-dialog').close());
bind('rename-file','click',() => { $('manage-dialog').close(); openFileDialog('move'); });
bind('save-as-file','click',() => { $('manage-dialog').close(); if (activeDocument()) openFileDialog('saveAs', activeDocument().path.replace(/\.(filter|json)$/, '-copy.$1')); });
bind('delete-file','click',trashSelection);
bind('reopen-file','click',reloadSelection);
bind('confirm-yes','click',() => { confirmResolve?.(true); confirmResolve = null; $('confirm-dialog').close(); });
bind('confirm-no','click',() => { confirmResolve?.(false); confirmResolve = null; $('confirm-dialog').close(); });
bind('confirm-dialog','cancel',() => { confirmResolve?.(false); confirmResolve = null; });
bind('document-tabs','click',async event => {
  const close = event.target.closest('[data-close]');
  const open = event.target.closest('[data-open]');
  if (close) await closeDocument(close.dataset.close);
  else if (open) activate(state.documents.find(doc => doc.path === open.dataset.open));
});
bind('editor','input',onEditorInput);
bind('editor','scroll',syncScroll);
bind('editor','click',updateCursor);
bind('editor','keyup',updateCursor);
bind('editor','keydown',event => {
  if (event.key === 'Escape') { allowTabNavigation = true; notify('Press Tab to move focus out of the editor.'); }
  else if (event.key === 'Tab' && allowTabNavigation) allowTabNavigation = false;
  else if (event.key === 'Tab' && !event.shiftKey) { event.preventDefault(); insertText('  '); }
  else if (event.key === 'Enter' && !event.metaKey && !event.ctrlKey) {
    const before = $('editor').value.slice(0,$('editor').selectionStart).split('\n').at(-1);
    const indent = before.match(/^\s*/)[0] + (before.trimEnd().endsWith('{') ? '  ' : '');
    if (indent) { event.preventDefault(); insertText('\n' + indent); }
  }
});
bind('save-file','click',saveDocument);
bind('run-report','click',() => validateOrRun(true));
bind('validate-report','click',() => validateOrRun(false));
bind('collection-select','change',async () => { state.collection = $('collection-select').value; await refreshOutline(); updateControls(); });
bind('output-file-pattern','change',() => {
  try { localStorage.setItem('report-studio.output-file-pattern', $('output-file-pattern').value.trim()); } catch {}
});
bind('request-outline','click',event => { const button = event.target.closest('[data-request]'); if (button) insertText(JSON.stringify(button.dataset.request)); });
bind('insert-summary','click',() => insertText('\n' + summaryBlock + '\n'));
bind('toggle-reference','click',() => {
  const panel = $('reference-panel');
  if (innerWidth <= 1150) panel.classList.toggle('visible');
  else panel.hidden = !panel.hidden;
});
bind('toggle-results','click',() => {
  state.resultHidden = !state.resultHidden;
  try { localStorage.setItem('report-studio.results-hidden', String(state.resultHidden)); } catch {}
  updateControls();
  if (!state.resultHidden) renderResult();
});
bind('toggle-editor','click',() => {
  setResultsOnly(!state.editorHidden);
  if (!state.editorHidden) renderResult();
});
bind('toggle-explorer','click',() => $('explorer').classList.toggle('visible'));
bind('editor-size','input',() => document.documentElement.style.setProperty('--editor-height',$('editor-size').value + '%'));
bind('result-tabs','click',event => { const button = event.target.closest('[data-view]'); if (button) setView(button.dataset.view); });
bind('result-content','click',async event => {
  const button = event.target.closest('button');
  if (!button || button.disabled) return;
  if ('apiRequest' in button.dataset) {
    state.apiRequestIndex = Number(button.dataset.apiRequest); state.apiResponse = null;
    state.apiResponseView = 'pretty'; state.apiResponseDataset = 0; renderResult();
  }
  else if (button.dataset.apiRequestTab) { state.apiRequestTab = button.dataset.apiRequestTab; renderResult(); }
  else if (button.dataset.apiBodyMode) {
    const request = currentApiRequest();
    if (request) { request.bodyMode = button.dataset.apiBodyMode; renderResult(); }
  }
  else if (button.dataset.addApiRow) {
    const rows = apiRows(button.dataset.addApiRow);
    if (rows) { rows.push({key:'',value:'',enabled:true,type:'text'}); renderResult(); }
  }
  else if (button.dataset.removeApiRow) {
    const rows = apiRows(button.dataset.removeApiRow);
    if (rows) {
      rows.splice(Number(button.dataset.apiRowIndex), 1);
      if (button.dataset.removeApiRow === 'param' && currentApiRequest()) syncUrlFromParams(currentApiRequest());
      renderResult();
    }
  }
  else if ('saveApiVariables' in button.dataset) await saveApiVariables();
  else if (button.dataset.apiResponseView) { state.apiResponseView = button.dataset.apiResponseView; renderResult(); }
  else if (button.dataset.copyResponse) await copyApiResponse();
  else if (button.dataset.sendRequest) await sendApiRequest();
  else if (button.dataset.editCollection) {
    setResultsOnly(false); state.resultHidden = false;
    await openSourceFile(button.dataset.editCollection);
  }
  else if ('example' in button.dataset || button.id === 'open-example') await openExample();
  else if (button.dataset.viewAction) setView(button.dataset.viewAction);
  else if (button.dataset.openWorkbook) {
    openWorkbook(button.dataset.openWorkbook);
  }
  else if (button.dataset.run) {
    state.run = await api(`/api/run?id=${encode(button.dataset.run)}`);
    state.reportPath = state.run.files?.[0] || null; state.sheet = 0; state.offset = 0;
    setView('summary');
  } else if ('sheet' in button.dataset) { state.sheet = Number(button.dataset.sheet); state.offset = 0; renderResult(); }
  else if (button.dataset.page) { state.offset = Math.max(0,state.offset + (button.dataset.page === 'next' ? 200 : -200)); renderResult(); }
  else if (button.dataset.retry) renderResult();
});
bind('result-content','input',event => {
  if ('apiTableFilter' in event.target.dataset) {
    state.apiTableFilter = event.target.value;
    const panel = event.target.closest('.api-response');
    const query = state.apiTableFilter.trim().toLowerCase();
    const rows = [...panel.querySelectorAll('[data-api-data-row]')];
    let visible = 0;
    rows.forEach(row => { row.hidden = query && !row.textContent.toLowerCase().includes(query); if (!row.hidden) visible++; });
    const count = panel.querySelector('[data-api-table-count]');
    if (count) count.textContent = `${visible.toLocaleString()} of ${Number(count.dataset.total).toLocaleString()} rows`;
    const empty = panel.querySelector('[data-api-filter-empty]');
    if (empty) empty.hidden = visible !== 0;
    return;
  }
  const request = currentApiRequest();
  if (!request) return;
  if (event.target.dataset.apiField === 'url') { request.url = event.target.value; syncParamsFromUrl(request); }
  else if (event.target.dataset.apiField === 'body') request.body = event.target.value;
  else if (event.target.dataset.apiAuthField) request.auth.values[event.target.dataset.apiAuthField] = event.target.value;
  else if (event.target.dataset.apiRowKind && event.target.dataset.apiRowField) {
    const rows = apiRows(event.target.dataset.apiRowKind, request);
    const index = Number(event.target.dataset.apiRowIndex);
    while (rows.length <= index) rows.push({key:'',value:'',enabled:true,type:'text'});
    rows[index][event.target.dataset.apiRowField] = event.target.value;
    if (event.target.dataset.apiRowKind === 'param') {
      syncUrlFromParams(request);
      const workspace = event.target.closest?.('.api-request-workspace');
      const urlInput = workspace?.querySelector?.('[data-api-field="url"]');
      if (urlInput) urlInput.value = request.url;
    } else if (event.target.dataset.apiRowKind === 'variable') {
      const panel = event.target.closest?.('.api-config-panel');
      const save = panel?.querySelector?.('[data-save-api-variables]');
      if (save) save.disabled = !apiVariablesDirty();
    }
  }
});
bind('result-content','change',event => {
  if (event.target.id === 'workbook-file') { state.reportPath = event.target.value; state.sheet = 0; state.offset = 0; renderResult(); }
  else if (event.target.dataset.apiField === 'method' && currentApiRequest()) currentApiRequest().method = event.target.value;
  else if ('apiAuthType' in event.target.dataset && currentApiRequest()) {
    currentApiRequest().auth = {type:event.target.value, values:{}}; renderResult();
  }
  else if (event.target.dataset.apiAuthField && currentApiRequest()) {
    currentApiRequest().auth.values[event.target.dataset.apiAuthField] = event.target.value;
  }
  else if (event.target.dataset.apiRowKind && 'apiRowEnabled' in event.target.dataset) {
    const rows = apiRows(event.target.dataset.apiRowKind);
    const index = Number(event.target.dataset.apiRowIndex);
    while (rows.length <= index) rows.push({key:'',value:'',enabled:true,type:'text'});
    rows[index].enabled = event.target.checked;
    if (event.target.dataset.apiRowKind === 'param' && currentApiRequest()) syncUrlFromParams(currentApiRequest());
    renderResult();
  }
  else if (event.target.dataset.apiRowKind && event.target.dataset.apiRowField) {
    const rows = apiRows(event.target.dataset.apiRowKind);
    const index = Number(event.target.dataset.apiRowIndex);
    while (rows.length <= index) rows.push({key:'',value:'',enabled:true,type:'text'});
    rows[index][event.target.dataset.apiRowField] = event.target.value;
  }
  else if ('apiResponseDataset' in event.target.dataset) { state.apiResponseDataset = Number(event.target.value); state.apiTableFilter = ''; renderResult(); }
});
bind('export-report','click',() => {
  if (!state.reportPath) return;
  const link = document.createElement('a'); link.href = `/api/download?path=${encode(state.reportPath)}`; link.download = basename(state.reportPath); document.body.append(link); link.click(); link.remove();
});
function filterHelp(query) {
  const normalized = query.trim().toLowerCase();
  const sections = [...document.querySelectorAll('[data-help-section]')];
  let visible = 0;
  sections.forEach(section => {
    const searchable = `${section.dataset.helpTerms || ''} ${section.textContent}`.toLowerCase();
    section.hidden = Boolean(normalized) && !searchable.includes(normalized);
    if (!section.hidden) visible++;
  });
  $('help-empty').hidden = visible !== 0;
  $('help-search-count').textContent = normalized ? `${visible} of ${sections.length} topics` : 'All topics';
}

bind('help-button','click',() => {
  $('help-dialog').showModal();
  $('help-search').focus();
});
bind('help-close','click',() => $('help-dialog').close());
bind('help-search','input',event => filterHelp(event.target.value));
document.addEventListener('keydown',event => {
  if (document.querySelector('dialog[open]')) return;
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    if (event.shiftKey && activeDocument()) openFileDialog('saveAs', activeDocument().path.replace(/\.(filter|json)$/, '-copy.$1'));
    else saveDocument().catch(handleError);
  }
  else if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') { event.preventDefault(); validateOrRun(!event.shiftKey).catch(handleError); }
  else if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); if (innerWidth <= 600) $('explorer').classList.add('visible'); $('file-search').focus(); }
  else if (event.key === '?' && !['INPUT','TEXTAREA','SELECT'].includes(event.target.tagName)) $('help-dialog').showModal();
});
window.addEventListener('beforeunload',event => { if (state.documents.some(isDirty)) { event.preventDefault(); event.returnValue = ''; } });

async function initialize() {
  try {
    try {
      state.resultHidden = localStorage.getItem('report-studio.results-hidden') === 'true';
      state.editorHidden = localStorage.getItem('report-studio.results-only') === 'true';
      $('output-file-pattern').value = localStorage.getItem('report-studio.output-file-pattern') || '';
      if (state.editorHidden) state.resultHidden = false;
    } catch {}
    const session = await api('/api/session');
    state.token = session.token;
    const results = await Promise.allSettled([refreshFiles(),api('/api/runs')]);
    if (results[0].status === 'rejected') throw results[0].reason;
    if (results[1].status === 'fulfilled') state.history = results[1].value;
    await openExample();
    const running = state.history.find(run => ['queued','running'].includes(run.status));
    if (running) { state.run = running; state.activeRun = running.id; pollRun(running.id); }
    else if (state.history.length) {
      const latest = state.history.find(run => run.status === 'completed') || state.history[0];
      state.run = latest; state.reportPath = latest.files?.[0] || null;
    }
    updateControls(); renderResult();
    log('Workspace connected. Open a file to edit, or run the selected report definition.');
  } catch (error) {
    $('file-tree').innerHTML = `<p class="loading muted">${escapeHtml(error.message)}</p>`;
    handleError(error);
  }
}
initialize();
