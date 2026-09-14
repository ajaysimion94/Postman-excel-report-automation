'use strict';

const $ = id => document.getElementById(id);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
const encode = encodeURIComponent;
const clone = value => JSON.parse(JSON.stringify(value));
const state = {
  token:'', summary:{notes:[],bookmarks:[],types:[],records:[]}, mode:'opds', catalogMode:'records', filter:'', active:null,
  note:null, noteView:innerWidth < 880 ? 'edit' : 'split', noteDirty:false, noteSaving:false, noteSavePromise:null, noteTimer:null,
  catalog:null, catalogKind:null, catalogView:'table', catalogDirty:false, jsonDraft:'', typeView:null, bookmark:null, bookmarkDirty:false
};
let toastTimer;

async function api(path, options = {}) {
  const headers = {'X-Workspace-Token':state.token, ...(options.headers || {})};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {...options, cache:'no-store', headers,
    body:options.body === undefined ? undefined : JSON.stringify(options.body)});
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
  const toast = $('documents-toast');
  toast.textContent = message;
  toast.classList.toggle('error', error);
  toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toast.hidden = true; }, error ? 8000 : 3200);
}

function handleError(error) { notify(error.message, true); }

async function initialize() {
  try {
    const session = await api('/api/session');
    state.token = session.token;
    $('workspace-label').textContent = session.workspace;
    await refreshSummary();
    const params = new URLSearchParams(location.search);
    const open = params.get('open');
    if (open) {
      const note = state.summary.notes.find(item => item.path === open || item.id === open);
      const bookmark = state.summary.bookmarks.find(item => item.id === open);
      const type = state.summary.types.find(item => item.id === open);
      const record = state.summary.records.find(item => item.id === open);
      if (note) await openNote(note.path);
      else if (bookmark) await openBookmark(bookmark.id);
      else if (type) await openCatalog('type', type.id);
      else if (record) await openCatalog('record', record.id);
    } else renderAll();
  } catch (error) {
    $('documents-main').innerHTML = errorState(error.message);
    handleError(error);
  }
}

async function refreshSummary() {
  state.summary = await api('/api/documents');
  renderSidebar();
}

function renderAll() { renderSidebar(); renderMain(); }

function renderSidebar() {
  $('mode-opds').setAttribute('aria-selected', String(state.mode === 'opds'));
  $('mode-bookmarks').setAttribute('aria-selected', String(state.mode === 'bookmarks'));
  $('mode-catalog').setAttribute('aria-selected', String(state.mode === 'catalog'));
  $('opd-count').textContent = state.summary.notes.length;
  $('bookmark-count').textContent = state.summary.bookmarks.length;
  $('catalog-count').textContent = state.summary.types.length + state.summary.records.length;
  $('catalog-subtabs').hidden = state.mode !== 'catalog';
  $('sidebar-eyebrow').textContent = state.mode === 'opds' ? 'PROCEDURES' : state.mode === 'bookmarks' ? 'LINK LIBRARY' : state.catalogMode.toUpperCase();
  $('sidebar-title').textContent = state.mode === 'opds' ? 'Operational documents' : state.mode === 'bookmarks' ? 'Browser bookmarks' : state.catalogMode === 'records' ? 'Catalog records' : 'Catalog types';
  $('create-item').title = state.mode === 'opds' ? 'Create OPD' : state.mode === 'bookmarks' ? 'Create bookmark' : state.catalogMode === 'records' ? 'Create record' : 'Create type';
  $('create-item').setAttribute('aria-label', $('create-item').title);
  document.querySelectorAll('[data-catalog-mode]').forEach(button => button.setAttribute('aria-selected', String(button.dataset.catalogMode === state.catalogMode)));
  const items = state.mode === 'opds' ? state.summary.notes : state.mode === 'bookmarks' ? state.summary.bookmarks : state.catalogMode === 'records' ? state.summary.records : state.summary.types;
  const filtered = items.filter(item => !state.filter || JSON.stringify(item).toLowerCase().includes(state.filter));
  if (!filtered.length) {
    const noun = state.mode === 'opds' ? 'OPDs' : state.mode === 'bookmarks' ? 'bookmarks' : state.catalogMode;
    $('documents-list').innerHTML = `<div class="documents-list-empty"><strong>No ${escapeHtml(noun)} found</strong><span>${state.filter ? 'Try another search.' : `Create the first ${state.mode === 'opds' ? 'procedure' : state.mode === 'bookmarks' ? 'bookmark' : state.catalogMode.slice(0,-1)}.`}</span></div>`;
    return;
  }
  $('documents-list').innerHTML = filtered.map(item => {
    const kind = state.mode === 'opds' ? 'note' : state.mode === 'bookmarks' ? 'bookmark' : state.catalogMode.slice(0,-1);
    const active = state.active === `${kind}:${item.id || item.path}`;
    const meta = kind === 'note' ? `${item.category || 'Procedure'} · ${(item.tags || []).length} tags` : kind === 'bookmark' ? `${item.folder} · ${new URL(item.url).host}`
      : kind === 'type' ? `${item.kind || 'class'} · ${(item.fields || []).length} fields`
      : typeName(item.typeId);
    const openId = kind === 'note' ? item.path : item.id;
    return `<button class="documents-list-item ${active ? 'active' : ''}" data-open-kind="${kind}" data-open-id="${escapeHtml(openId)}"><span class="documents-item-symbol" aria-hidden="true">${kind === 'note' ? '¶' : kind === 'bookmark' ? '↗' : kind === 'type' ? '{ }' : '▦'}</span><span><strong>${escapeHtml(item.title || item.name)}</strong><small>${escapeHtml(meta)}</small></span></button>`;
  }).join('');
}

function renderMain() {
  if (state.note) renderNote();
  else if (state.bookmark) renderBookmark();
  else if (state.catalog) renderCatalog();
}

async function changeMode(mode) {
  if (!await settleCurrentDraft()) return;
  state.mode = mode;
  state.note = null; state.catalog = null; state.bookmark = null; state.bookmarkDirty = false; state.active = null;
  renderSidebar();
  renderEmpty();
}

function renderEmpty() {
  if (state.mode === 'bookmarks') { $('documents-main').innerHTML = `<section class="documents-empty"><span class="documents-empty-mark" aria-hidden="true">↗</span><div><span class="eyebrow">BROWSER BOOKMARKS</span><h1>Keep useful links with your working notes.</h1><p>Import your browser bookmark HTML file, then maintain those links in the local workspace.</p></div><div class="documents-empty-actions"><button class="primary-button" data-empty-create="bookmark">Add bookmark</button><button class="outline-button" data-import-bookmarks>Import browser bookmarks</button></div></section>`; return; }
  $('documents-main').innerHTML = `<section class="documents-empty"><span class="documents-empty-mark" aria-hidden="true">${state.mode === 'opds' ? '¶' : '{ }'}</span><div><span class="eyebrow">${state.mode === 'opds' ? 'OPERATIONAL PROCEDURES' : 'STRUCTURED KNOWLEDGE'}</span><h1>${state.mode === 'opds' ? 'Write procedures that connect.' : 'Model types, then work with their records.'}</h1><p>${state.mode === 'opds' ? 'Create a Markdown OPD and link related notes with [[double brackets]].' : 'Define reusable fields and relationships, then maintain data in nested tables.'}</p></div><div class="documents-empty-actions"><button class="primary-button" data-empty-create="${state.mode === 'opds' ? 'opd' : state.catalogMode.slice(0,-1)}">Create ${state.mode === 'opds' ? 'an OPD' : `a ${state.catalogMode.slice(0,-1)}`}</button></div></section>`;
}

async function settleCurrentDraft() {
  if (state.noteDirty) {
    try { await saveNote(); } catch { return false; }
  }
  if (state.catalogDirty) {
    notify('Save or discard the catalog draft before switching.', true);
    return false;
  }
  if (state.bookmarkDirty) { notify('Save or discard the bookmark draft before switching.', true); return false; }
  return true;
}

async function openNote(path) {
  if (!await settleCurrentDraft()) return;
  state.mode = 'opds'; state.catalog = null; state.noteDirty = false;
  state.note = await api(`/api/documents/note?path=${encode(path)}`);
  state.active = `note:${state.note.id || state.note.path}`;
  renderAll();
  updateLocation(state.note.path);
}

async function createNote() {
  if (!await settleCurrentDraft()) return;
  const note = await api('/api/documents/note', {method:'POST', body:{title:'Untitled OPD'}});
  await refreshSummary();
  await openNote(note.path);
  $('opd-editor')?.focus();
  notify('OPD created. Start writing; changes save automatically.');
}

async function openBookmark(id) {
  if (!await settleCurrentDraft()) return;
  const item = state.summary.bookmarks.find(bookmark => bookmark.id === id); if (!item) return;
  state.mode='bookmarks'; state.note=null; state.catalog=null; state.bookmark=clone(item); state.bookmarkDirty=false; state.active=`bookmark:${id}`;
  renderAll(); updateLocation(id);
}

async function createBookmark() {
  if (!await settleCurrentDraft()) return;
  const created = await api('/api/documents/bookmarks', {method:'POST', body:{title:'New bookmark',url:'https://example.com',folder:'Unsorted'}});
  await refreshSummary(); await openBookmark(created.id); notify('Bookmark created. Update the details, then save.');
}

function renderBookmark() {
  const item=state.bookmark;
  $('documents-main').innerHTML=`<article class="bookmark-workbench"><header class="documents-document-bar"><div class="documents-title"><span class="eyebrow">BOOKMARK · ${escapeHtml(item.folder)}</span><h1>${escapeHtml(item.title)}</h1><span class="documents-path">${escapeHtml(item.url)}</span></div><div class="documents-document-actions"><a class="quiet-button" href="${safeHref(item.url)}" target="_blank" rel="noreferrer">Open link ↗</a><span class="note-save-state ${state.bookmarkDirty?'dirty':''}">${state.bookmarkDirty?'Unsaved draft':'Saved'}</span><button class="primary-button" data-save-bookmark ${state.bookmarkDirty?'':'disabled'}>Save bookmark</button><button class="quiet-button documents-delete" data-delete-current>Move to trash</button></div></header><section class="bookmark-editor"><label><span>Title</span><input data-bookmark-field="title" value="${escapeHtml(item.title)}" maxlength="250"></label><label><span>Website address</span><input data-bookmark-field="url" type="url" value="${escapeHtml(item.url)}" maxlength="4096"></label><label><span>Folder</span><input data-bookmark-field="folder" value="${escapeHtml(item.folder)}" maxlength="300"></label><div class="bookmark-transfer"><div><strong>Browser bookmark file</strong><small>Import Chrome, Edge, Firefox, or Safari HTML bookmarks. Export uses the standard Netscape bookmark format.</small></div><div><button class="outline-button" data-import-bookmarks>Import HTML</button><a class="outline-button" href="/api/documents/bookmarks/export">Export HTML</a></div></div></section></article>`;
}

async function saveBookmark() { if (!state.bookmark || !state.bookmarkDirty) return; const saved=await api('/api/documents/bookmarks',{method:'PUT',body:state.bookmark}); state.bookmark=clone(saved); state.bookmarkDirty=false; await refreshSummary(); renderBookmark(); notify('Bookmark saved.'); }

function importBookmarks(file) { const reader=new FileReader(); reader.onload=async()=>{ try { const doc=new DOMParser().parseFromString(String(reader.result),'text/html'), entries=[]; const walk=(node,folders=[])=>{ for(const child of node.children){ if(child.tagName==='DT'){ const h=child.querySelector(':scope > H3'),a=child.querySelector(':scope > A'),dl=child.querySelector(':scope > DL'); if(a&&/^https?:\/\//i.test(a.getAttribute('href')||''))entries.push({title:a.textContent.trim()||a.href,url:a.getAttribute('href'),folder:folders.join(' / ')||'Imported'}); if(h&&dl)walk(dl,[...folders,h.textContent.trim()||'Imported']); } else if(child.tagName==='DL')walk(child,folders); }}; walk(doc.body); const result=await api('/api/documents/bookmarks/import',{method:'POST',body:{bookmarks:entries}}); await refreshSummary(); if(state.mode==='bookmarks'&&!state.bookmark)renderEmpty(); notify(`${result.added} bookmark${result.added===1?'':'s'} imported.`); }catch(error){handleError(error);}finally{$('bookmark-import').value='';} }; reader.readAsText(file); }

function renderNote() {
  const note = state.note;
  const status = state.noteSaving ? 'Saving…' : state.noteDirty ? 'Unsaved changes' : 'Saved';
  const outline = noteOutline(note.content);
  const backlinks = note.backlinks || [];
  $('documents-main').innerHTML = `<article class="opd-workbench">
    <header class="documents-document-bar"><div class="documents-title"><span class="eyebrow">OPD · ${escapeHtml(note.category || 'PROCEDURE')}</span><h1>${escapeHtml(note.title)}</h1><span class="documents-path">${escapeHtml(note.path)}</span></div><div class="documents-document-actions"><div class="documents-view-switch" role="group" aria-label="Markdown view"><button data-note-view="edit" class="${state.noteView === 'edit' ? 'active' : ''}">Edit</button><button data-note-view="split" class="${state.noteView === 'split' ? 'active' : ''}">Split</button><button data-note-view="preview" class="${state.noteView === 'preview' ? 'active' : ''}">Preview</button></div><span id="note-save-state" class="note-save-state ${state.noteDirty ? 'dirty' : ''}">${status}</span><button class="quiet-button documents-delete" data-delete-current>Move to trash</button></div></header>
    <div class="opd-layout view-${state.noteView}"><section class="opd-editor-pane"><div class="opd-pane-heading"><span>MARKDOWN</span><span>Auto-save on</span></div><textarea id="opd-editor" aria-label="OPD Markdown" spellcheck="true">${escapeHtml(note.content)}</textarea></section><section class="opd-preview-pane"><div class="opd-pane-heading"><span>PREVIEW</span><span>${wordCount(note.content)} words</span></div><div id="opd-preview" class="markdown-preview">${renderMarkdown(note.content)}</div></section><aside class="opd-context"><section><span class="eyebrow">ON THIS PAGE</span><nav>${outline.length ? outline.map(item => `<button data-heading-line="${item.line}">${escapeHtml(item.text)}</button>`).join('') : '<p>No headings yet.</p>'}</nav></section><section><span class="eyebrow">BACKLINKS</span><div>${backlinks.length ? backlinks.map(item => `<button data-open-kind="note" data-open-id="${escapeHtml(item.path)}"><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.path)}</small></button>`).join('') : '<p>No other OPDs link here yet.</p>'}</div></section></aside></div>
  </article>`;
}

function scheduleNoteSave() {
  state.noteDirty = true;
  clearTimeout(state.noteTimer);
  updateNotePreview();
  state.noteTimer = setTimeout(() => saveNote().catch(handleError), 700);
}

async function saveNote() {
  clearTimeout(state.noteTimer);
  if (state.noteSaving) return state.noteSavePromise;
  if (!state.note || !state.noteDirty) return;
  const path = state.note.path;
  const revision = state.note.revision;
  state.noteSaving = true;
  const content = state.note.content;
  updateNoteSaveState();
  state.noteSavePromise = (async () => {
    try {
      const saved = await api('/api/documents/note', {method:'PUT', body:{path, content, revision}});
      if (state.note?.path !== path) return;
      const changedWhileSaving = state.note.content !== content;
      state.note = {...saved, content:changedWhileSaving ? state.note.content : saved.content};
      state.noteDirty = changedWhileSaving;
      await refreshSummary();
      if (changedWhileSaving) state.noteTimer = setTimeout(() => saveNote().catch(handleError), 500);
    } catch (error) {
      if (state.note?.path === path) state.noteDirty = true;
      if (error.status === 409) error.message = 'This OPD changed on disk. Copy your draft, reload the page, and reconcile both versions.';
      throw error;
    } finally {
      state.noteSaving = false;
      state.noteSavePromise = null;
      updateNoteSaveState();
    }
  })();
  return state.noteSavePromise;
}

function updateNotePreview() {
  if ($('opd-preview')) $('opd-preview').innerHTML = renderMarkdown(state.note.content);
  updateNoteSaveState();
}

function updateNoteSaveState() {
  const element = $('note-save-state');
  if (!element) return;
  element.textContent = state.noteSaving ? 'Saving…' : state.noteDirty ? 'Unsaved changes' : 'Saved';
  element.classList.toggle('dirty', state.noteDirty);
}

function noteOutline(content) {
  return content.split('\n').map((line, index) => ({line:index + 1, text:line.replace(/^#{1,6}\s+/, '')})).filter((item, index) => /^#{1,6}\s+/.test(content.split('\n')[index]) && !item.text.startsWith('#'));
}

function wordCount(content) {
  const text = content.replace(/^---[\s\S]*?---\s*/, '').trim();
  return text ? text.split(/\s+/).length : 0;
}

function safeHref(value) {
  const decoded = value.replaceAll('&amp;', '&').trim();
  return /^(https?:|mailto:|#|\/)/i.test(decoded) || !/^[a-z][a-z0-9+.-]*:/i.test(decoded) ? value : '#';
}

function inlineMarkdown(value) {
  let text = escapeHtml(value);
  const code = [];
  text = text.replace(/`([^`]+)`/g, (_, source) => { code.push(`<code>${source}</code>`); return `\u0000CODE${code.length - 1}\u0000`; });
  text = text.replace(/\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|([^\]]+))?\]\]/g, (_, target, label) => {
    const clean = target.trim();
    const matches = state.summary.notes.filter(note => note.title.toLowerCase() === clean.toLowerCase() || note.path.replace(/^documents\/opds\//,'').replace(/\.md$/,'').toLowerCase() === clean.replace(/\.md$/,'').toLowerCase());
    const path = matches.length === 1 ? matches[0].path : '';
    return `<button class="wiki-link ${matches.length ? '' : 'missing'}" data-note-target="${escapeHtml(clean)}" data-note-path="${escapeHtml(path)}">${label || target}${matches.length > 1 ? '<sup>?</sup>' : ''}</button>`;
  });
  text = text.replace(/\[([^\]]+)]\(([^)]+)\)/g, (_, label, href) => `<a href="${safeHref(href)}" target="_blank" rel="noreferrer">${label}</a>`);
  text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/__([^_]+)__/g, '<strong>$1</strong>');
  text = text.replace(/(^|\s)\*([^*]+)\*(?=\s|$)/g, '$1<em>$2</em>').replace(/(^|\s)_([^_]+)_(?=\s|$)/g, '$1<em>$2</em>');
  return text.replace(/\u0000CODE(\d+)\u0000/g, (_, index) => code[Number(index)]);
}

function renderMarkdown(source) {
  const content = source.replace(/^---\n[\s\S]*?\n---\s*/, '');
  const lines = content.split('\n');
  let html = '', paragraph = [], list = null, quote = [], code = null;
  const flushParagraph = () => { if (paragraph.length) { html += `<p>${inlineMarkdown(paragraph.join(' '))}</p>`; paragraph = []; } };
  const flushList = () => { if (list) { html += `</${list}>`; list = null; } };
  const flushQuote = () => { if (quote.length) { html += `<blockquote>${inlineMarkdown(quote.join(' '))}</blockquote>`; quote = []; } };
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (code !== null) {
      if (/^```/.test(line)) { html += `<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`; code = null; }
      else code.push(line);
      continue;
    }
    if (/^```/.test(line)) { flushParagraph(); flushList(); flushQuote(); code = []; continue; }
    if (line.includes('|') && index + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[index + 1])) {
      flushParagraph(); flushList(); flushQuote();
      const headers = line.replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
      index++;
      const rows = [];
      while (index + 1 < lines.length && lines[index + 1].includes('|') && lines[index + 1].trim()) rows.push(lines[++index].replace(/^\||\|$/g, '').split('|').map(cell => cell.trim()));
      html += `<div class="markdown-table-shell"><table><thead><tr>${headers.map(cell => `<th>${inlineMarkdown(cell)}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr>${headers.map((_, cell) => `<td>${inlineMarkdown(row[cell] || '')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
      continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) { flushParagraph(); flushList(); flushQuote(); const level = heading[1].length; html += `<h${level}>${inlineMarkdown(heading[2])}</h${level}>`; continue; }
    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) { flushParagraph(); flushList(); flushQuote(); html += '<hr>'; continue; }
    const listItem = line.match(/^\s*([-*+] |\d+\. )(.*)$/);
    if (listItem) {
      flushParagraph(); flushQuote(); const ordered = /^\d/.test(listItem[1]); const tag = ordered ? 'ol' : 'ul';
      if (list !== tag) { flushList(); html += `<${tag}>`; list = tag; }
      const task = listItem[2].match(/^\[([ xX])]\s+(.*)$/);
      html += task ? `<li class="task-item"><input type="checkbox" disabled ${task[1].toLowerCase() === 'x' ? 'checked' : ''}>${inlineMarkdown(task[2])}</li>` : `<li>${inlineMarkdown(listItem[2])}</li>`;
      continue;
    }
    if (line.startsWith('>')) { flushParagraph(); flushList(); quote.push(line.replace(/^>\s?/, '')); continue; }
    if (!line.trim()) { flushParagraph(); flushList(); flushQuote(); continue; }
    flushList(); flushQuote(); paragraph.push(line.trim());
  }
  flushParagraph(); flushList(); flushQuote();
  if (code !== null) html += `<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`;
  return html || '<div class="markdown-empty"><strong>This OPD is ready to write.</strong><span>Add a heading and the first procedure step.</span></div>';
}

async function openCatalog(kind, id) {
  if (!await settleCurrentDraft()) return;
  state.mode = 'catalog'; state.catalogMode = kind === 'type' ? 'types' : 'records'; state.note = null;
  let item = (kind === 'type' ? state.summary.types : state.summary.records).find(entry => entry.id === id);
  if (!item) return;
  if (kind === 'type') item = await api(`/api/documents/type?id=${encode(id)}`);
  state.catalog = clone(item); state.catalogKind = kind; state.catalogDirty = false; state.catalogView = 'table';
  state.jsonDraft = JSON.stringify(entityData(state.catalog), null, 2);
  state.active = `${kind}:${id}`;
  state.typeView = kind === 'record' && item.typeId ? await api(`/api/documents/type?id=${encode(item.typeId)}`).catch(() => null) : null;
  renderAll(); updateLocation(id);
}

async function createCatalog(kind) {
  if (!await settleCurrentDraft()) return;
  if (kind === 'record' && !state.summary.types.length) {
    state.mode = 'catalog'; state.catalogMode = 'types'; renderSidebar(); renderEmpty();
    notify('Define a catalog type before creating a record.', true); return;
  }
  const path = kind === 'type' ? '/api/documents/types' : '/api/documents/records';
  const body = kind === 'type' ? {name:'New Type',kind:'class',category:'General',fields:[],methods:[]}
    : {name:'New Record',typeId:state.summary.types[0].id,values:{}};
  const created = await api(path, {method:'POST', body});
  await refreshSummary(); await openCatalog(kind, created.id);
  notify(`${kind === 'type' ? 'Type' : 'Record'} created. Add details, then save.`);
}

function renderCatalog() {
  const item = state.catalog;
  const noun = state.catalogKind === 'type' ? 'TYPE' : 'RECORD';
  const body = state.catalogView === 'json' ? `<section class="catalog-json-editor"><div class="opd-pane-heading"><span>JSON</span><span id="json-validation">Valid draft</span></div><textarea id="catalog-json" aria-label="Catalog JSON" spellcheck="false">${escapeHtml(state.jsonDraft)}</textarea></section>`
    : state.catalogKind === 'type' ? typeTable(item) : recordTable(item);
  $('documents-main').innerHTML = `<article class="catalog-workbench"><header class="documents-document-bar"><div class="documents-title"><span class="eyebrow">CATALOG · ${noun}</span><h1>${escapeHtml(item.name)}</h1><span class="documents-path">${escapeHtml(item.path)}</span></div><div class="documents-document-actions"><div class="documents-view-switch" role="group" aria-label="Catalog view"><button data-catalog-view="table" class="${state.catalogView === 'table' ? 'active' : ''}">Table</button><button data-catalog-view="json" class="${state.catalogView === 'json' ? 'active' : ''}">JSON</button></div><span class="note-save-state ${state.catalogDirty ? 'dirty' : ''}">${state.catalogDirty ? 'Unsaved draft' : 'Saved'}</span><button class="primary-button" data-save-catalog ${state.catalogDirty ? '' : 'disabled'}>Save ${state.catalogKind}</button><button class="quiet-button documents-delete" data-delete-current>Move to trash</button></div></header>${body}</article>`;
}

function typeTable(type) {
  const parentOptions = `<option value="">No parent</option>` + state.summary.types.filter(item => item.id !== type.id).map(item => `<option value="${escapeHtml(item.id)}" ${item.id === type.extendsId ? 'selected' : ''}>${escapeHtml(item.name)}</option>`).join('');
  const fields = type.fields || [];
  const methods = type.methods || [];
  return `<div class="catalog-table-workspace"><section class="catalog-metadata"><label><span>Name</span><input data-root-field="name" value="${escapeHtml(type.name)}"></label><label><span>Kind</span><select data-root-field="kind"><option value="class" ${type.kind !== 'interface' ? 'selected' : ''}>Class</option><option value="interface" ${type.kind === 'interface' ? 'selected' : ''}>Interface</option></select></label><label><span>Category</span><input data-root-field="category" value="${escapeHtml(type.category || '')}"></label><label><span>Extends</span><select data-root-field="extendsId">${parentOptions}</select></label><label class="catalog-description"><span>Description</span><input data-root-field="description" value="${escapeHtml(type.description || '')}"></label></section>
    <section class="catalog-section"><div class="catalog-section-heading"><div><span class="eyebrow">STRUCTURE</span><h2>Fields</h2></div><button class="outline-button" data-add-row="fields">＋ Add field</button></div><div class="catalog-table-scroll"><table class="catalog-edit-table"><thead><tr><th>Name</th><th>Type</th><th>Required</th><th>Related type</th><th>Array items</th><th>Default</th><th>Visibility</th><th></th></tr></thead><tbody>${fields.map((field,index) => fieldRow(field,index)).join('') || '<tr class="catalog-empty-row"><td colspan="8">No fields yet. Add the first field this type owns.</td></tr>'}</tbody></table></div></section>
    ${inheritedFieldTable(type.resolvedFields || [])}
    <section class="catalog-section"><div class="catalog-section-heading"><div><span class="eyebrow">BEHAVIOR</span><h2>Methods</h2></div><button class="outline-button" data-add-row="methods">＋ Add method</button></div><div class="catalog-table-scroll"><table class="catalog-edit-table"><thead><tr><th>Name</th><th>Description</th><th>Return type</th><th>Linked request</th><th></th></tr></thead><tbody>${methods.map((method,index) => methodRow(method,index)).join('') || '<tr class="catalog-empty-row"><td colspan="5">No methods defined for this type.</td></tr>'}</tbody></table></div></section></div>`;
}

function fieldRow(field, index) {
  const related = `<option value="">None</option>` + state.summary.types.map(type => `<option value="${escapeHtml(type.id)}" ${type.id === field.referenceTypeId ? 'selected' : ''}>${escapeHtml(type.name)}</option>`).join('');
  const arrayItems = `<option value="">Any value</option>` + ['string','number','boolean','date','object','array'].map(type => `<option value="${type}" ${field.arrayItemType === type ? 'selected' : ''}>${type}</option>`).join('') + state.summary.types.map(type => `<option value="${escapeHtml(type.id)}" ${field.arrayItemType === type.id ? 'selected' : ''}>${escapeHtml(type.name)}</option>`).join('');
  return `<tr><td><input data-array-field="name" data-section="fields" data-index="${index}" value="${escapeHtml(field.name)}" aria-label="Field name"></td><td><select data-array-field="type" data-section="fields" data-index="${index}">${['string','number','boolean','date','object','array'].map(type => `<option ${type === field.type ? 'selected' : ''}>${type}</option>`).join('')}</select></td><td class="catalog-check"><input type="checkbox" data-array-field="required" data-section="fields" data-index="${index}" ${field.required ? 'checked' : ''} aria-label="Required"></td><td><select data-array-field="referenceTypeId" data-section="fields" data-index="${index}">${related}</select></td><td><select data-array-field="arrayItemType" data-section="fields" data-index="${index}">${arrayItems}</select></td><td><input data-array-field="defaultValue" data-section="fields" data-index="${index}" value="${escapeHtml(field.defaultValue || '')}" aria-label="Default value"></td><td><select data-array-field="visibility" data-section="fields" data-index="${index}"><option ${field.visibility !== 'private' ? 'selected' : ''}>public</option><option ${field.visibility === 'private' ? 'selected' : ''}>private</option></select></td><td><button class="catalog-row-delete" data-remove-row="fields" data-index="${index}" aria-label="Delete field">×</button></td></tr>`;
}

function methodRow(method, index) {
  return `<tr><td><input data-array-field="name" data-section="methods" data-index="${index}" value="${escapeHtml(method.name)}" aria-label="Method name"></td><td><input data-array-field="description" data-section="methods" data-index="${index}" value="${escapeHtml(method.description || '')}" aria-label="Method description"></td><td><input data-array-field="returnType" data-section="methods" data-index="${index}" value="${escapeHtml(method.returnType || '')}" aria-label="Return type"></td><td><input data-array-field="linkedRequest" data-section="methods" data-index="${index}" value="${escapeHtml(method.linkedRequest || '')}" placeholder="Collection / request" aria-label="Linked request"></td><td><button class="catalog-row-delete" data-remove-row="methods" data-index="${index}" aria-label="Delete method">×</button></td></tr>`;
}

function inheritedFieldTable(resolved) {
  const inherited = resolved.filter(item => item.inherited);
  if (!inherited.length) return '';
  return `<section class="catalog-section inherited-fields"><div class="catalog-section-heading"><div><span class="eyebrow">INHERITED</span><h2>Available fields</h2></div><span>${inherited.length} from parent types</span></div><div class="catalog-table-scroll"><table class="catalog-read-table"><thead><tr><th>Name</th><th>Type</th><th>Required</th><th>Source</th></tr></thead><tbody>${inherited.map(item => `<tr><td>${escapeHtml(item.field.name)}</td><td><code>${escapeHtml(item.field.type)}</code></td><td>${item.field.required ? 'Yes' : 'No'}</td><td>${escapeHtml(typeName(item.originTypeId))}</td></tr>`).join('')}</tbody></table></div></section>`;
}

function recordTable(record) {
  const fields = state.typeView?.resolvedFields || [];
  const typeOptions = state.summary.types.map(type => `<option value="${escapeHtml(type.id)}" ${type.id === record.typeId ? 'selected' : ''}>${escapeHtml(type.name)}</option>`).join('');
  return `<div class="catalog-table-workspace"><section class="catalog-metadata record-metadata"><label><span>Name</span><input data-root-field="name" value="${escapeHtml(record.name)}"></label><label><span>Type</span><select data-root-field="typeId">${typeOptions}</select></label><div class="catalog-type-summary"><span>${fields.length}</span><small>available fields</small></div></section><section class="catalog-section"><div class="catalog-section-heading"><div><span class="eyebrow">TABLE VIEW</span><h2>Record values</h2></div><span>Nested objects expand in place</span></div><div class="catalog-table-scroll"><table class="catalog-edit-table catalog-record-table"><thead><tr><th>Field</th><th>Type</th><th>Value</th><th>Source</th></tr></thead><tbody>${fields.map(item => recordFieldRow(item, record.values || {})).join('') || '<tr class="catalog-empty-row"><td colspan="4">This type has no fields. Add fields to its type first.</td></tr>'}</tbody></table></div></section></div>`;
}

function recordFieldRow(item, values) {
  const field = item.field;
  const value = Object.hasOwn(values, field.name) ? values[field.name] : defaultFor(field);
  return `<tr><th scope="row"><strong>${escapeHtml(field.name)}</strong>${field.required ? '<small>required</small>' : ''}</th><td><code>${escapeHtml(field.type)}</code>${field.referenceTypeId ? `<small>${escapeHtml(typeName(field.referenceTypeId))}</small>` : ''}</td><td class="catalog-nested-cell">${nestedEditor(value, [field.name], field.type)}</td><td>${escapeHtml(typeName(item.originTypeId))}${item.inherited ? '<small>inherited</small>' : ''}</td></tr>`;
}

function nestedEditor(value, path, hint = '') {
  const encoded = encode(JSON.stringify(path));
  if (Array.isArray(value)) return `<div class="nested-edit-shell"><table class="nested-edit-table"><thead><tr><th>#</th><th>Value</th><th></th></tr></thead><tbody>${value.map((item,index) => `<tr><th>${index + 1}</th><td>${nestedEditor(item, [...path,index])}</td><td><button data-remove-value="${encode(JSON.stringify([...path,index]))}" aria-label="Delete array item">×</button></td></tr>`).join('') || '<tr><td colspan="3" class="nested-empty">Empty array</td></tr>'}</tbody></table><button data-add-value="array" data-value-path="${encoded}">＋ Add item</button></div>`;
  if (value && typeof value === 'object') return `<div class="nested-edit-shell"><table class="nested-edit-table"><thead><tr><th>Field</th><th>Value</th><th></th></tr></thead><tbody>${Object.entries(value).map(([key,item]) => `<tr><th><input data-rename-value="${encode(JSON.stringify([...path,key]))}" value="${escapeHtml(key)}" aria-label="Nested field name"></th><td>${nestedEditor(item, [...path,key])}</td><td><button data-remove-value="${encode(JSON.stringify([...path,key]))}" aria-label="Delete nested field">×</button></td></tr>`).join('') || '<tr><td colspan="3" class="nested-empty">Empty object</td></tr>'}</tbody></table><button data-add-value="object" data-value-path="${encoded}">＋ Add field</button></div>`;
  const type = hint || (typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'string');
  if (type === 'boolean') return `<select data-value-path="${encoded}" data-value-type="boolean"><option value="true" ${value === true ? 'selected' : ''}>true</option><option value="false" ${value === false ? 'selected' : ''}>false</option><option value="null" ${value == null ? 'selected' : ''}>null</option></select>`;
  return `<input data-value-path="${encoded}" data-value-type="${escapeHtml(type)}" value="${escapeHtml(value ?? '')}" ${type === 'number' ? 'inputmode="decimal"' : ''}>`;
}

function defaultFor(field) {
  if (field.defaultValue !== undefined && field.defaultValue !== '') {
    if (field.type === 'number') return Number(field.defaultValue);
    if (field.type === 'boolean') return field.defaultValue === 'true';
    return field.defaultValue;
  }
  if (field.type === 'object') return {};
  if (field.type === 'array') return [];
  return null;
}

function typeName(id) { return state.summary.types.find(type => type.id === id)?.name || (id ? 'Unknown type' : 'Unassigned'); }

function entityData(entity) {
  const value = clone(entity);
  delete value.path; delete value.revision; delete value.resolvedFields; delete value.affectedRecords;
  return value;
}

function markCatalogDirty() {
  state.catalogDirty = true;
  state.jsonDraft = JSON.stringify(entityData(state.catalog), null, 2);
  const status = document.querySelector('.note-save-state');
  if (status) { status.textContent = 'Unsaved draft'; status.classList.add('dirty'); }
  const save = document.querySelector('[data-save-catalog]'); if (save) save.disabled = false;
}

function catalogJsonValue() {
  try {
    const value = JSON.parse(state.jsonDraft);
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('JSON must contain one object.');
    return value;
  } catch (error) {
    const validation = $('json-validation');
    if (validation) { validation.textContent = error.message; validation.classList.add('error'); }
    throw new Error(`Catalog JSON is not valid: ${error.message}`);
  }
}

async function setCatalogView(view) {
  if (view === state.catalogView) return;
  if (state.catalogView === 'json') state.catalog = {...state.catalog, ...catalogJsonValue()};
  else state.jsonDraft = JSON.stringify(entityData(state.catalog), null, 2);
  state.catalogView = view; renderCatalog();
}

async function saveCatalog() {
  if (!state.catalog || !state.catalogDirty) return;
  if (state.catalogView === 'json') state.catalog = {...state.catalog, ...catalogJsonValue()};
  const path = state.catalogKind === 'type' ? '/api/documents/types' : '/api/documents/records';
  const saved = await api(path, {method:'PUT', body:{...entityData(state.catalog), revision:state.catalog.revision}});
  const affected = saved.affectedRecords || [];
  state.catalog = clone(saved); state.catalogDirty = false;
  await refreshSummary();
  await openCatalog(state.catalogKind, saved.id);
  notify(affected.length ? `Type saved. ${affected.length} record${affected.length === 1 ? '' : 's'} now need attention.` : `${state.catalogKind === 'type' ? 'Type' : 'Record'} saved.`, affected.length > 0);
}

function valueAt(root, path) { return path.reduce((value, key) => value?.[key], root); }
function setValue(root, path, value) {
  let target = root;
  for (let index = 0; index < path.length - 1; index++) target = target[path[index]];
  target[path[path.length - 1]] = value;
}
function deleteValue(root, path) {
  const parent = valueAt(root, path.slice(0,-1));
  if (Array.isArray(parent)) parent.splice(Number(path.at(-1)), 1); else delete parent[path.at(-1)];
}

async function deleteCurrent() {
  if (state.note) {
    clearTimeout(state.noteTimer);
    state.noteDirty = false;
    if (state.noteSavePromise) await state.noteSavePromise;
    await api(`/api/documents/note?path=${encode(state.note.path)}`, {method:'DELETE'});
    state.note = null; state.noteDirty = false;
  } else if (state.catalog) {
    const plural = state.catalogKind === 'type' ? 'types' : 'records';
    await api(`/api/documents/${plural}?id=${encode(state.catalog.id)}`, {method:'DELETE'});
    state.catalog = null; state.catalogDirty = false;
  } else if (state.bookmark) {
    await api(`/api/documents/bookmarks?id=${encode(state.bookmark.id)}`, {method:'DELETE'});
    state.bookmark = null; state.bookmarkDirty = false;
  }
  state.active = null; await refreshSummary(); renderEmpty(); notify('Moved to the workspace trash.');
}

function updateLocation(open) {
  const url = new URL(location.href); url.pathname = '/documents'; url.search = open ? `?open=${encode(open)}` : '';
  history.replaceState(null, '', url);
}

function errorState(message) { return `<section class="documents-empty"><span class="documents-empty-mark error" aria-hidden="true">!</span><div><span class="eyebrow">DOCUMENTS UNAVAILABLE</span><h1>We could not open this workspace.</h1><p>${escapeHtml(message)}</p></div><div class="documents-empty-actions"><button class="outline-button" data-retry>Try again</button></div></section>`; }

document.addEventListener('click', event => {
  const mode = event.target.closest('[data-mode]'); if (mode) return changeMode(mode.dataset.mode).catch(handleError);
  const catalogMode = event.target.closest('[data-catalog-mode]'); if (catalogMode) {
    if (state.catalogDirty) return notify('Save or discard the catalog draft before switching.', true);
    state.catalogMode = catalogMode.dataset.catalogMode; state.catalog = null; state.active = null; renderSidebar(); renderEmpty(); return;
  }
  const open = event.target.closest('[data-open-kind]'); if (open) return (open.dataset.openKind === 'note' ? openNote(open.dataset.openId) : open.dataset.openKind === 'bookmark' ? openBookmark(open.dataset.openId) : openCatalog(open.dataset.openKind, open.dataset.openId)).catch(handleError);
  const emptyCreate = event.target.closest('[data-empty-create]'); if (emptyCreate) return (emptyCreate.dataset.emptyCreate === 'opd' ? createNote() : emptyCreate.dataset.emptyCreate === 'bookmark' ? createBookmark() : createCatalog(emptyCreate.dataset.emptyCreate)).catch(handleError);
  if (event.target.closest('#create-item')) return (state.mode === 'opds' ? createNote() : state.mode === 'bookmarks' ? createBookmark() : createCatalog(state.catalogMode.slice(0,-1))).catch(handleError);
  if (event.target.closest('[data-import-bookmarks]')) return $('bookmark-import').click();
  if (event.target.closest('[data-save-bookmark]')) return saveBookmark().catch(handleError);
  const noteView = event.target.closest('[data-note-view]'); if (noteView) { state.noteView = noteView.dataset.noteView; renderNote(); return; }
  const catalogView = event.target.closest('[data-catalog-view]'); if (catalogView) return setCatalogView(catalogView.dataset.catalogView).catch(handleError);
  if (event.target.closest('[data-save-catalog]')) return saveCatalog().catch(handleError);
  if (event.target.closest('[data-delete-current]')) return deleteCurrent().catch(handleError);
  const addRow = event.target.closest('[data-add-row]'); if (addRow) {
    if (addRow.dataset.addRow === 'fields') state.catalog.fields.push({name:'newField',type:'string',required:false,defaultValue:'',visibility:'public',validationRules:'',referenceTypeId:null,arrayItemType:null});
    else state.catalog.methods.push({name:'newMethod',description:'',linkedRequest:'',inputMapping:'',outputMapping:'',returnType:'object'});
    markCatalogDirty(); renderCatalog(); return;
  }
  const removeRow = event.target.closest('[data-remove-row]'); if (removeRow) { state.catalog[removeRow.dataset.removeRow].splice(Number(removeRow.dataset.index),1); markCatalogDirty(); renderCatalog(); return; }
  const addValue = event.target.closest('[data-add-value]'); if (addValue) {
    const path = JSON.parse(decodeURIComponent(addValue.dataset.valuePath)); const target = valueAt(state.catalog.values, path);
    if (addValue.dataset.addValue === 'array') target.push(''); else { let name='newField', index=2; while (Object.hasOwn(target,name)) name=`newField${index++}`; target[name]=''; }
    markCatalogDirty(); renderCatalog(); return;
  }
  const removeValue = event.target.closest('[data-remove-value]'); if (removeValue) { deleteValue(state.catalog.values, JSON.parse(decodeURIComponent(removeValue.dataset.removeValue))); markCatalogDirty(); renderCatalog(); return; }
  const heading = event.target.closest('[data-heading-line]'); if (heading && $('opd-editor')) { const lines=state.note.content.split('\n'); const line=Number(heading.dataset.headingLine); const offset=lines.slice(0,line-1).reduce((sum,value)=>sum+value.length+1,0); $('opd-editor').focus(); $('opd-editor').setSelectionRange(offset,offset); }
  const wiki = event.target.closest('[data-note-target]'); if (wiki) { if (wiki.dataset.notePath) openNote(wiki.dataset.notePath).catch(handleError); else notify(`No unique OPD matches “${wiki.dataset.noteTarget}”.`, true); }
  if (event.target.closest('[data-retry]')) initialize();
});

document.addEventListener('input', event => {
  if (event.target.id === 'documents-search') { state.filter = event.target.value.trim().toLowerCase(); renderSidebar(); return; }
  if (event.target.id === 'opd-editor') { state.note.content = event.target.value; scheduleNoteSave(); return; }
  if (event.target.dataset.bookmarkField) { state.bookmark[event.target.dataset.bookmarkField]=event.target.value; state.bookmarkDirty=true; const status=document.querySelector('.note-save-state'); if(status){status.textContent='Unsaved draft';status.classList.add('dirty');} const save=document.querySelector('[data-save-bookmark]'); if(save)save.disabled=false; return; }
  if (event.target.id === 'catalog-json') {
    state.jsonDraft = event.target.value; state.catalogDirty = true;
    const validation = $('json-validation');
    try { JSON.parse(state.jsonDraft); validation.textContent='Valid draft'; validation.classList.remove('error'); }
    catch (error) { validation.textContent=error.message; validation.classList.add('error'); }
    const save=document.querySelector('[data-save-catalog]'); if (save) save.disabled=false; return;
  }
  if (event.target.dataset.rootField) {
    state.catalog[event.target.dataset.rootField] = event.target.type === 'checkbox' ? event.target.checked : event.target.value || (event.target.dataset.rootField === 'extendsId' ? null : '');
    markCatalogDirty(); return;
  }
  if (event.target.dataset.arrayField) {
    state.catalog[event.target.dataset.section][Number(event.target.dataset.index)][event.target.dataset.arrayField] = event.target.type === 'checkbox' ? event.target.checked : event.target.value || null;
    markCatalogDirty(); return;
  }
  if (event.target.dataset.valuePath) {
    const path = JSON.parse(decodeURIComponent(event.target.dataset.valuePath)); let value = event.target.value;
    if (event.target.dataset.valueType === 'number') value = value === '' ? null : Number(value);
    if (event.target.dataset.valueType === 'boolean') value = value === 'null' ? null : value === 'true';
    setValue(state.catalog.values, path, value); markCatalogDirty(); return;
  }
  if (event.target.dataset.renameValue) {
    const path=JSON.parse(decodeURIComponent(event.target.dataset.renameValue)); const parent=valueAt(state.catalog.values,path.slice(0,-1)); const old=path.at(-1); const next=event.target.value.trim();
    if (next && next !== old && !Object.hasOwn(parent,next)) { const entries=Object.entries(parent).map(([key,value])=>[key===old?next:key,value]); Object.keys(parent).forEach(key=>delete parent[key]); entries.forEach(([key,value])=>parent[key]=value); event.target.dataset.renameValue=encode(JSON.stringify([...path.slice(0,-1),next])); markCatalogDirty(); }
  }
});

document.addEventListener('change', event => {
  if (event.target.id === 'bookmark-import' && event.target.files?.[0]) return importBookmarks(event.target.files[0]);
  if (event.target.dataset.rootField === 'typeId' && state.catalogKind === 'record') {
    state.typeView = null;
    api(`/api/documents/type?id=${encode(state.catalog.typeId)}`).then(type => { state.typeView=type; renderCatalog(); }).catch(handleError);
  }
});

document.addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    if (state.note) saveNote().catch(handleError); else if (state.catalog) saveCatalog().catch(handleError);
  }
});

window.addEventListener('beforeunload', event => { if (state.noteDirty || state.catalogDirty) { event.preventDefault(); event.returnValue=''; } });
window.addEventListener('resize', () => { if (innerWidth < 880 && state.noteView === 'split') { state.noteView='edit'; if (state.note) renderNote(); } });

initialize();
