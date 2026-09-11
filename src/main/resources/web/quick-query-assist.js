/* Cursor-aware suggestions for the shared quick-query language. Response schemas
   live only in memory; editing a query never sends the same inspection twice. */
const quickAssist = {version:0, context:null, collections:new Map(), schemas:new Map(),
  options:[], selected:new Set(), selectionKey:'', search:'', schema:null, request:null, path:'', open:false,panel:''};

function quickTokens(source) {
  const tokens = [];
  for (let i = 0; i < source.length;) {
    if (/\s/.test(source[i])) { i++; continue; }
    const start = i;
    const char = source[i++];
    if (char === '"' || char === "'") {
      let value = '', closed = false;
      while (i < source.length) {
        if (source[i] === char) { i++; closed = true; break; }
        if (source[i] === '\\' && i + 1 < source.length) i++;
        value += source[i++];
      }
      tokens.push({value,start,end:i,quoted:true,closed});
    } else if (/[\w.*-]/.test(char)) {
      while (i < source.length && /[\w.*-]/.test(source[i])) i++;
      tokens.push({value:source.slice(start,i),start,end:i});
    } else tokens.push({value:char,start,end:i});
  }
  return tokens;
}

function quickContext(source, caret = source.length) {
  const tokens = quickTokens(source);
  const before = tokens.filter(token => token.end <= caret && !token.quoted && token.value === ';');
  const start = before.at(-1)?.end || 0;
  const end = tokens.find(token => token.start >= caret && !token.quoted && token.value === ';')?.start ?? source.length;
  let ts = tokens.filter(token => token.start >= start && token.start < end);
  if (ts[0]?.value === '$') ts = ts.slice(3);
  if (ts[0]?.value !== '@') return null;
  const collection = ts[1];
  const base = {source,caret,start,end,collection:collection?.value || ''};
  if (!collection || caret <= collection.end) return {...base,stage:'collection',from:collection?.start ?? ts[0].end,to:collection?.end ?? caret,fragment:source.slice(collection?.start ?? caret,caret).replace(/^["']/, '')};
  if (ts[2]?.value !== '#') return null;
  const request = ts[3];
  if (!request || caret <= request.end) return {...base,stage:'request',from:request?.start ?? ts[2].end,to:request?.end ?? caret,fragment:source.slice(request?.start ?? caret,caret).replace(/^["']/, '').replace(/["']$/, '')};
  if (ts[4]?.value !== '>' || caret < ts[4].end) return null;
  const where = ts.slice(5).find(token => !token.quoted && token.value.toUpperCase() === 'WHERE');
  const columnsEnd = where?.start ?? end;
  const entries = [];
  let entryStart = ts[4].end;
  for (const boundary of [...ts.slice(5).filter(t => t.start < columnsEnd && !t.quoted && t.value === ','), {start:columnsEnd,end:columnsEnd}]) {
    const raw = source.slice(entryStart,boundary.start).trim();
    if (raw) entries.push({field:quickTokens(raw)[0]?.value,raw});
    entryStart = boundary.end;
  }
  const context = {...base,request:request.value,columnsStart:ts[4].end,columnsEnd,entries,where:Boolean(where),stage:'columns',fragment:''};
  if (!where || caret <= where.start) {
    const lastComma = ts.slice(5).filter(t => !t.quoted && t.value === ',' && t.end <= caret).at(-1);
    const from = lastComma?.end ?? ts[4].end;
    context.fragment = source.slice(from,caret).trim().replace(/^["']/, '').replace(/["']$/, '');
    return context;
  }
  const current = ts.find(t => t.start < caret && t.end >= caret) || ts.filter(t => t.end <= caret).at(-1);
  const index = ts.indexOf(current);
  const previous = ts[index - 1];
  const expectsField = token => token && !token.quoted && ['WHERE','AND','OR','NOT','('].includes(token.value.toUpperCase());
  if (expectsField(current)) return {...context,stage:'path',from:caret,to:caret,fragment:''};
  if (current && expectsField(previous) && caret <= current.end) return {...context,stage:'path',from:current.start,to:current.end,fragment:source.slice(current.start,caret).replace(/^["']/, '')};
  return null; // Do not suggest fields in values or confuse a WHERE comparison with the projection '>'.
}

// A variable assignment is still a quick query once it reaches @. Everything
// else in the shared box is a workspace search rather than a malformed query.
function quickIsQuerySource(source) {
  return /^\s*(?:\$\s*[\w.-]+\s*=\s*)?@/.test(source);
}

function quickWorkspaceOptions(source) {
  const needle = source.trim().toLocaleLowerCase();
  if (!needle) return [];
  const kinds = [
    {prefix:'reports/', extension:'.xlsx', kind:'report', action:'Open report'},
    {prefix:'filters/', extension:'.filter', kind:'filter', action:'Open filter in IDE'},
    {prefix:'queries/', extension:'.filter', kind:'query', action:'Load into Quick run'}
  ];
  return state.files
    .filter(file => !file.directory)
    .map(file => {
      const type = kinds.find(item => file.path.startsWith(item.prefix) && file.path.endsWith(item.extension));
      return type && {...type,path:file.path,label:guideBasename(file.path)};
    })
    .filter(Boolean)
    .filter(item => `${item.label} ${item.path}`.toLocaleLowerCase().includes(needle))
    .sort((a,b) => {
      const aExact = a.label.toLocaleLowerCase().startsWith(needle) ? 0 : 1;
      const bExact = b.label.toLocaleLowerCase().startsWith(needle) ? 0 : 1;
      return aExact - bExact || a.kind.localeCompare(b.kind) || a.label.localeCompare(b.label);
    })
    .slice(0,30)
    .map(item => ({...item,detail:`${item.kind[0].toUpperCase() + item.kind.slice(1)} · ${item.action}`}));
}

function quickSchema(body) {
  const root = JSON.parse(body);
  const fields = new Map();
  let visited = 0, limited = false;
  const walk = (value, path = '', depth = 0) => {
    if (++visited > 20000 || depth > 24 || fields.size >= 1000) { limited = true; return; }
    const type = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value;
    if (path) {
      const old = fields.get(path);
      const sample = typeof value === 'object' ? '' : String(value).slice(0,100);
      fields.set(path,{path,type:old && old.type !== type ? (old.type === 'null' ? type : type === 'null' ? old.type : 'mixed') : type,
        branch:Boolean(old?.branch || type === 'array' || type === 'object'),sample:old?.sample || sample});
    }
    if (Array.isArray(value)) {
      if (value.length > 100) limited = true;
      value.slice(0,100).forEach(item => {
        // Array elements share a path. Do not replace an array node's type with 'object'.
        if (item && typeof item === 'object' && !Array.isArray(item)) Object.entries(item).forEach(([key,child]) => walk(child,path ? `${path}.${key}` : key,depth+1));
        else if (Array.isArray(item)) walk(item,path,depth+1);
      });
    } else if (value && typeof value === 'object') Object.entries(value).forEach(([key,child]) => walk(child,path ? `${path}.${key}` : key,depth+1));
  };
  walk(root);
  return {fields:[...fields.values()],limited};
}

function quickAssistClose() {
  quickAssist.version++;
  quickAssist.open = false;
  $('guided-quick-suggestions').hidden = true;
  $('guided-quick-query').setAttribute('aria-expanded','false');
}

function quickAssistShow(html) {
  quickAssist.open = true;
  $('guided-quick-suggestions').hidden = false;
  $('guided-quick-suggestions').innerHTML = html;
  $('guided-quick-query').setAttribute('aria-expanded','true');
}

function quickAssistMessage(message, retry = false) {
  quickAssist.panel = 'message';
  quickAssist.options = [];
  quickAssistShow(`<div class="quick-suggest-message" role="status">${guideEscape(message)}</div>${retry ? '<button type="button" data-quick-retry>Test again</button>' : ''}`);
}

function quickAssistList(title, options) {
  quickAssist.panel = 'list';
  quickAssist.options = options;
  quickAssistShow(`<div class="quick-suggest-heading"><strong>${guideEscape(title)}</strong><small>↓ to browse · Enter to select · Esc to close</small></div><div class="quick-suggest-list">${options.map((option,index) => `<button type="button" data-quick-pick="${index}" ${option.disabled ? 'disabled' : ''}><span><strong>${guideEscape(option.label)}</strong><small>${guideEscape(option.detail || '')}</small></span><span aria-hidden="true">${option.branch ? '→' : '↵'}</span></button>`).join('') || '<p class="quick-suggest-message">No matching suggestions.</p>'}</div>`);
}

async function quickLoadCollection(path) {
  if (!quickAssist.collections.has(path)) quickAssist.collections.set(path,
    api(`/api/collection?path=${encodeURIComponent(path)}`).then(prepareApiCollection).catch(error => { quickAssist.collections.delete(path); throw error; }));
  return quickAssist.collections.get(path);
}

async function quickDiscover(collection, path, request, force = false) {
  const key = `${path}:${request.index}`;
  if (force) quickAssist.schemas.delete(key);
  if (!quickAssist.schemas.has(key)) {
    quickAssist.schemas.set(key, api('/api/request',{method:'POST',body:guideRequestPayload(request,collection,path)})
      .then(response => {
        if (!response.success) throw new Error(response.error || `HTTP ${response.statusCode}: the request did not succeed.`);
        try { return {...quickSchema(response.body),status:response.statusCode}; }
        catch { throw new Error('The API response is not valid JSON. Inspect it in the API client.'); }
      }).catch(error => ({error:error.message})));
  }
  return quickAssist.schemas.get(key);
}

async function quickAssistUpdate(force = false) {
  const input = $('guided-quick-query');
  const source = input.value;
  const queryContext = quickContext(source,input.selectionStart ?? input.value.length);
  const context = queryContext || (!quickIsQuerySource(source) && source.trim()
    ? {stage:'workspace',source,from:0,to:source.length,fragment:source.trim()} : null);
  const version = ++quickAssist.version;
  quickAssist.context = context;
  quickAssist.options = [];
  if (!context) { quickAssistClose(); return; }
  const current = () => version === quickAssist.version && input.value === context.source;
  try {
    if (!state.files.length && state.token) { await refreshFiles(); if (!current()) return; }
    if (context.stage === 'workspace') {
      quickAssistList('Search reports, filters, and queries',quickWorkspaceOptions(context.fragment));
      return;
    }
    const collections = guideCollections();
    if (context.stage === 'collection') {
      quickAssistList('Choose a collection',collections.filter(file => guideBasename(file.path).toLowerCase().includes(context.fragment.toLowerCase().replace(/["']$/, '')))
        .map(file => ({label:guideBasename(file.path),value:guideBasename(file.path),detail:file.path})));
      return;
    }
    const matches = collections.filter(file => guideBasename(file.path).toLowerCase() === context.collection.toLowerCase());
    if (matches.length !== 1) { quickAssistMessage(matches.length ? 'This collection name matches several files. Rename one to make it unique.' : 'Choose a collection using @ first.'); return; }
    const path = matches[0].path;
    quickAssistMessage('Loading requests…');
    if (force) quickAssist.collections.delete(path);
    const collection = await quickLoadCollection(path);
    if (!current()) return;
    if (context.stage === 'request') {
      quickAssistList('Choose a request',collection.requests.filter(request => request.name.toLowerCase().includes(context.fragment.toLowerCase()))
        .map(request => ({label:request.name,value:request.name,detail:`${request.method} · ${request.folder || request.url}${request.disabled ? ' · Disabled' : ''}`,disabled:request.disabled})));
      return;
    }
    const requests = collection.requests.filter(request => request.name === context.request);
    if (requests.length !== 1 || requests[0].disabled) { quickAssistMessage('Choose an enabled request with a unique name using #.'); return; }
    const request = requests[0];
    quickAssistMessage(`Testing ${request.method} ${request.name} to discover columns…`);
    const schema = await quickDiscover(collection,path,request,force);
    if (!current()) return;
    if (schema.error) { quickAssistMessage(schema.error,true); return; }
    quickAssist.schema = schema; quickAssist.path = path; quickAssist.request = request;
    if (context.stage === 'path') {
      const fragment = context.fragment.toLowerCase();
      quickAssistList('Response paths',schema.fields.filter(field => field.path.toLowerCase().startsWith(fragment) && (!fragment.endsWith('.') || !field.path.slice(fragment.length).includes('.')))
        .map(field => ({label:field.path,value:field.path,branch:field.branch,detail:`${field.type}${field.branch ? ' · Browse fields' : field.sample ? ` · ${field.sample}` : ''}`})));
      return;
    }
    const selectionKey = `${path}:${request.index}:${context.start}:${context.source.slice(context.columnsStart,context.columnsEnd)}`;
    if (quickAssist.selectionKey !== selectionKey) {
      quickAssist.selectionKey = selectionKey;
      const leaves = schema.fields.filter(field => !field.branch);
      quickAssist.selected = new Set(context.entries.map(entry => entry.field).filter(field => field === '*'
        || leaves.some(leaf => leaf.path === field)
        || leaves.filter(leaf => leaf.path.endsWith('.' + field)).length === 1));
      quickAssist.search = schema.fields.some(field => field.path === context.fragment) ? '' : context.fragment;
    }
    quickAssistRenderColumns();
  } catch (error) { if (current()) quickAssistMessage(error.message); }
}

function quickAssistRenderColumns() {
  quickAssist.panel = 'columns';
  const {schema,selected,search} = quickAssist;
  const leaves = schema.fields.filter(field => !field.branch);
  const visible = leaves.filter(field => field.path.toLowerCase().includes(search.toLowerCase()));
  const branches = schema.fields.filter(field => field.branch && field.path.toLowerCase().startsWith(search.toLowerCase()) && !field.path.slice(search.length).replace(/^\./,'').includes('.'));
  quickAssist.options = [];
  quickAssistShow(`<div class="quick-suggest-heading"><strong>Select response columns</strong><small>HTTP ${schema.status} · ${leaves.length} fields${schema.limited ? ' · Sampled response; some fields may be absent' : ''}</small></div>
    <label class="quick-column-search">Find a field or object path<input id="quick-column-search" type="search" value="${guideEscape(search)}" placeholder="School.class…" autocomplete="off"></label>
    ${search ? '<button type="button" data-quick-root>← All fields</button>' : ''}
    <div class="quick-path-branches">${branches.map(field => `<button type="button" data-quick-branch="${guideEscape(field.path)}">${guideEscape(field.path)} <small>${field.type}</small> →</button>`).join('')}</div>
    <div class="quick-suggest-list quick-column-list">${visible.map(field => `<label><input type="checkbox" data-quick-column="${guideEscape(field.path)}" ${selected.has(field.path) ? 'checked' : ''}><span><strong>${guideEscape(field.path)}</strong><small>${guideEscape(field.type)}${field.sample ? ` · ${guideEscape(field.sample)}` : ''}</small></span></label>`).join('') || '<p class="quick-suggest-message">No scalar fields found here. Try another object path or inspect the API response.</p>'}</div>
    <div class="quick-suggest-footer"><span id="quick-selected-count">${selected.size} selected</span><button type="button" data-quick-retry>Test again</button><button type="button" data-quick-apply class="primary-button" ${selected.size ? '' : 'disabled'}>Use columns</button></div>`);
}

function quickName(value) { return /^[\w.-]+$/.test(value) ? value : guideQuote(value); }

function quickAssistReplace(from, to, text, caretOffset = text.length) {
  const input = $('guided-quick-query');
  input.setRangeText(text,from,to);
  input.focus(); input.setSelectionRange(from+caretOffset,from+caretOffset);
}

function quickAssistPick(index) {
  const option = quickAssist.options[index];
  const context = quickAssist.context;
  if (!option || option.disabled || !context || $('guided-quick-query').value !== context.source) return;
  if (context.stage === 'workspace') {
    quickAssistClose();
    if (option.kind === 'report') guideOpenSearchReport(option.path).catch(error => guideQuickError(error.message));
    else if (option.kind === 'query') guideLoadSavedQuery(option.path);
    else { guideClose(); openFile(option.path).catch(handleError); }
    return;
  }
  const tail = context.source.slice(context.to);
  if (context.stage === 'collection') {
    const marker = tail.match(/^\s*#/);
    quickAssistReplace(context.from,context.to,quickName(option.value) + (marker ? '' : ' #'),quickName(option.value).length + (marker ? marker[0].length : 2));
  } else if (context.stage === 'request') {
    const marker = tail.match(/^\s*>/);
    quickAssistReplace(context.from,context.to,quickName(option.value) + (marker ? '' : ' > '),quickName(option.value).length + (marker ? marker[0].length : 3));
  } else {
    const branchPath = option.value + '.';
    quickAssistReplace(context.from,context.to,option.branch
      ? (/^[\w.-]+$/.test(branchPath) ? branchPath : guideQuote(branchPath).slice(0,-1))
      : quickName(option.value) + ' ');
    if (!option.branch) { quickAssistClose(); return; }
  }
  quickAssistUpdate();
}

function quickAssistApply() {
  const context = quickAssist.context;
  if (!context || $('guided-quick-query').value !== context.source || !quickAssist.selected.size) return;
  const entries = new Map(context.entries.map(entry => [entry.field,entry.raw]));
  const columns = [...quickAssist.selected].map(field => entries.get(field) || quickName(field)).join(', ');
  const hasSemicolon = context.source[context.end] === ';';
  quickAssistReplace(context.columnsStart,context.columnsEnd,` ${columns}${context.where ? ' ' : hasSemicolon ? '' : ';'}`,columns.length + 1);
  quickAssistClose();
}

function quickAssistKey(event) {
  if (event.isComposing || !quickAssist.open) return false;
  const popup = $('guided-quick-suggestions');
  if (event.key === 'Escape') { event.preventDefault(); quickAssistClose(); return true; }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const choices = [...popup.querySelectorAll('button:not(:disabled),input')];
    (event.key === 'ArrowDown' ? choices[0] : choices.at(-1))?.focus();
    return true;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    const index = quickAssist.options.findIndex(option => !option.disabled);
    if (index >= 0) quickAssistPick(index);
    else if (quickAssist.panel === 'columns') quickAssistApply();
    return true;
  }
  return false;
}

function initializeQuickAssist() {
  quickAssistClose();
  bind('guided-quick-query','input',() => quickAssistUpdate());
  bind('guided-quick-query','click',() => quickAssistUpdate());
  bind('guided-quick-query','keyup',event => {
    if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) quickAssistUpdate();
  });
  const popup = $('guided-quick-suggestions');
  popup.addEventListener('click',event => {
    const button = event.target.closest('button');
    if (!button) return;
    if ('quickPick' in button.dataset) quickAssistPick(Number(button.dataset.quickPick));
    else if ('quickApply' in button.dataset) quickAssistApply();
    else if ('quickRetry' in button.dataset) quickAssistUpdate(true);
    else if ('quickBranch' in button.dataset || 'quickRoot' in button.dataset) {
      quickAssist.search = button.dataset.quickBranch ? button.dataset.quickBranch + '.' : '';
      quickAssistRenderColumns(); $('quick-column-search').focus();
    }
  });
  popup.addEventListener('input',event => {
    if (event.target.id === 'quick-column-search') {
      const position = event.target.selectionStart;
      quickAssist.search = event.target.value;
      quickAssistRenderColumns(); $('quick-column-search').focus(); $('quick-column-search').setSelectionRange(position,position);
    } else if ('quickColumn' in event.target.dataset) {
      event.target.checked ? quickAssist.selected.add(event.target.dataset.quickColumn) : quickAssist.selected.delete(event.target.dataset.quickColumn);
      $('quick-selected-count').textContent = `${quickAssist.selected.size} selected`;
      popup.querySelector('[data-quick-apply]').disabled = !quickAssist.selected.size;
    }
  });
  popup.addEventListener('keydown',event => {
    if (event.key === 'Escape') { event.preventDefault(); quickAssistClose(); $('guided-quick-query').focus(); }
    else if (event.key === 'Enter' && event.target.tagName === 'INPUT') { event.preventDefault(); quickAssistApply(); }
    else if (['ArrowDown','ArrowUp'].includes(event.key)) {
      const choices = [...popup.querySelectorAll('button:not(:disabled),input')];
      const index = choices.indexOf(event.target);
      event.preventDefault(); choices[(index + (event.key === 'ArrowDown' ? 1 : choices.length-1)) % choices.length]?.focus();
    }
  });
  document.addEventListener('pointerdown',event => {
    if (!event.target.closest('#guided-quick-form')) quickAssistClose();
  });
}
