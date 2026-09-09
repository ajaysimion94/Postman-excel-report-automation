/* Guided Report Studio workflow. It compiles structured choices into the existing
   .filter language so guided and editor-created reports share one execution path. */
const guide = {
  action:'home', step:'home', collectionPath:'', collection:null, current:null, items:[],
  busy:false, error:'', fieldSearch:'', filename:'', savedPath:'', savedSource:'', run:null, reportSearch:'',
  summary:{enabled:true,title:'API report',description:'Generated from selected API data.',query:true,
    mode:'table',valueField:'',metric:false,status:true}
};

function guideCollections() {
  return state.files.filter(file => !file.directory && file.path.startsWith('collections/') && file.path.endsWith('.json'));
}

function guideFilters() {
  return state.files.filter(file => !file.directory && file.path.startsWith('filters/') && file.path.endsWith('.filter'));
}

function guideEscape(value) { return escapeHtml(String(value ?? '')); }
function guideQuote(value) { return JSON.stringify(String(value ?? '')); }
function guideBasename(path) { return basename(path).replace(/\.(json|filter|xlsx)$/i, ''); }

function guideNewCurrent() {
  return {requestIndex:null,request:null,response:null,datasets:[],datasetIndex:0,fields:[],selected:[],labels:{},conditions:[]};
}

function guideReset(action = 'home') {
  guide.action = action;
  guide.step = action === 'home' ? 'home' : ['reports','run','collection'].includes(action) ? action : 'collection';
  guide.collectionPath = '';
  guide.collection = null;
  guide.current = guideNewCurrent();
  guide.items = [];
  guide.busy = false;
  guide.error = '';
  guide.fieldSearch = '';
  guide.filename = '';
  guide.savedPath = '';
  guide.savedSource = '';
  guide.run = null;
  guide.summary = {enabled:true,title:'API report',description:'Generated from selected API data.',query:true,
    mode:'table',valueField:'',metric:false,status:true};
  guideRender();
}

async function guideOpen(action = 'home') {
  $('guided-workspace').hidden = false;
  document.body.classList.add('guided-open');
  $('guided-toggle').innerHTML = '<span aria-hidden="true">⌘</span> IDE workspace';
  $('guided-toggle').setAttribute('aria-pressed','true');
  $('guided-toggle').title = 'Return to the IDE workspace';
  document.body.classList.remove('guided-preview-open');
  $('guided-preview-toggle').textContent = 'Preview';
  $('guided-preview-toggle').setAttribute('aria-pressed','false');
  if (!state.files.length && state.token) await refreshFiles();
  guideReset(action);
  $('guided-title').focus();
}

function guideClose() {
  $('guided-workspace').hidden = true;
  document.body.classList.remove('guided-open');
  document.body.classList.remove('guided-preview-open');
  $('guided-toggle').innerHTML = '<span aria-hidden="true">✦</span> Guided workspace';
  $('guided-toggle').setAttribute('aria-pressed','false');
  $('guided-toggle').title = 'Open the guided workspace';
  $('guided-toggle').focus();
}

function guideHeader(kicker, title) {
  $('guided-kicker').textContent = kicker;
  $('guided-title').textContent = title;
}

function guideError() {
  return guide.error ? `<div class="guided-error" role="alert"><strong>That step did not finish.</strong><span>${guideEscape(guide.error)}</span></div>` : '';
}

function guideHome() {
  guideHeader('GUIDED WORKSPACE', 'What are you working on?');
  const saved = guideStoredDraft();
  return `<div class="guided-welcome"><div class="guided-assistant-mark" aria-hidden="true">r.</div><div><strong>I’ll build this with you.</strong><p>Choose an outcome. Each step produces a real filter definition you can inspect, save, and run.</p></div></div>
    ${guide.error ? guideError() : ''}
    ${saved ? `<button type="button" class="guided-resume" data-guide-resume><span>RESUME DRAFT</span><strong>${guideEscape(saved.filename || guideBasename(saved.collectionPath || 'Report'))}</strong><small>${saved.items?.length || 0} configured request${saved.items?.length === 1 ? '' : 's'} · ${guideEscape(guideBasename(saved.collectionPath || 'collection'))}</small><b>Continue →</b></button>` : ''}
    <div class="guided-action-grid">
      <button type="button" data-guide-action="create"><span>CREATE</span><strong>Build a filter</strong><small>Test a request, choose fields, and generate a reusable definition.</small><b>→</b></button>
      <button type="button" data-guide-action="reports"><span>FIND</span><strong>Search reports</strong><small>Open a generated workbook or return to its run details.</small><b>→</b></button>
      <button type="button" data-guide-action="run"><span>RUN</span><strong>Generate a report</strong><small>Choose a saved filter and run it with its collection.</small><b>→</b></button>
      <button type="button" data-guide-action="test"><span>TEST</span><strong>Test an API</strong><small>Open a collection request in the complete API workbench.</small><b>→</b></button>
      <button type="button" data-guide-action="collection"><span>ADD</span><strong>Add a collection</strong><small>Import Postman JSON or create an empty collection.</small><b>→</b></button>
    </div>`;
}

function guideCollectionStep() {
  const testing = guide.action === 'test';
  guideHeader(testing ? 'TEST API · 1 OF 2' : 'CREATE FILTER · 1 OF 5', 'Choose a collection');
  const collections = guideCollections();
  if (!collections.length) return `<div class="guided-empty"><span>{ }</span><strong>No collections yet</strong><p>Import a Postman collection to begin.</p><button type="button" class="primary-button" data-guide-import>Import collection</button></div>`;
  return `<div class="guided-message"><span>01</span><div><strong>Which collection contains the request?</strong><p>Choose one collection for this ${testing ? 'API test' : 'report definition'}.</p></div></div>
    <label class="guided-search"><span>⌕</span><input type="search" data-guide-list-search placeholder="Search ${collections.length} collection${collections.length === 1 ? '' : 's'}" aria-label="Search collections"></label>
    <div class="guided-choice-list" data-guide-filterable>${collections.map(file => `<button type="button" data-guide-collection="${guideEscape(file.path)}" data-search="${guideEscape(file.path.toLowerCase())}"><span class="guided-file-icon">{ }</span><span><strong>${guideEscape(guideBasename(file.path))}</strong><small>${guideEscape(file.path)}</small></span><b>→</b></button>`).join('')}</div>`;
}

function guideRequestStep() {
  guideHeader(guide.action === 'test' ? 'TEST API · 2 OF 2' : 'CREATE FILTER · 2 OF 5', 'Choose a request');
  const requests = guide.collection?.requests || [];
  const used = new Set(guide.items.map(item => item.request.index));
  return `<button type="button" class="guided-back" data-guide-back="collection">← Change collection</button>
    <div class="guided-message"><span>02</span><div><strong>${guideEscape(guide.collection?.name)}</strong><p>${requests.length} request${requests.length === 1 ? '' : 's'} available. Choose the response you want to work with.</p></div></div>
    ${requests.length ? `<label class="guided-search"><span>⌕</span><input type="search" data-guide-list-search placeholder="Search requests" aria-label="Search requests"></label><div class="guided-choice-list guided-request-list" data-guide-filterable>${requests.map(request => `<button type="button" data-guide-request="${request.index}" data-search="${guideEscape(`${request.name} ${request.method} ${request.folder || ''}`.toLowerCase())}" ${request.disabled || used.has(request.index) ? 'disabled' : ''}><span class="method ${guideEscape(request.method.toLowerCase())}">${guideEscape(request.method)}</span><span><strong>${guideEscape(request.name)}</strong><small>${guideEscape(request.folder || request.url)}</small></span><b>${request.disabled ? 'Disabled' : used.has(request.index) ? 'Added' : '→'}</b></button>`).join('')}</div>` : `<div class="guided-empty"><strong>This collection has no requests</strong><p>Add a request in the collection JSON, then return here.</p></div>`}`;
}

function guideInspectStep() {
  const request = guide.current.request;
  guideHeader('CREATE FILTER · 3 OF 5', 'Inspect the response');
  return `<button type="button" class="guided-back" data-guide-back="request">← Change request</button>
    <div class="guided-message"><span>03</span><div><strong>${guideEscape(request.method)} ${guideEscape(request.name)}</strong><p>Run this request once to discover its datasets, fields, and sample values.</p></div></div>
    <div class="guided-request-review"><div><span>METHOD</span><strong>${guideEscape(request.method)}</strong></div><div><span>URL</span><code>${guideEscape(request.url)}</code></div></div>
    ${guide.error ? guideError() : ''}
    <div class="guided-footer-actions"><button type="button" class="primary-button" data-guide-inspect ${guide.busy ? 'disabled' : ''}>${guide.busy ? '<span class="guided-spinner"></span> Sending request…' : 'Run request and inspect'}</button><small>This sends the request with its saved collection settings.</small></div>`;
}

function guideDatasetStep() {
  guideHeader('CREATE FILTER · 4 OF 5', 'Choose the rows to use');
  return `<button type="button" class="guided-back" data-guide-back="inspect">← Test again</button>
    <div class="guided-message"><span>04</span><div><strong>The response contains ${guide.current.datasets.length} datasets.</strong><p>Choose what one report row should represent.</p></div></div>
    <div class="guided-dataset-list">${guide.current.datasets.map((dataset,index) => `<button type="button" data-guide-dataset="${index}" ${dataset.supported ? '' : 'disabled'}><span><strong>${guideEscape(dataset.path)}</strong><small>${dataset.rows.length} sampled row${dataset.rows.length === 1 ? '' : 's'} · ${dataset.fields.length} fields${dataset.supported ? '' : ' · unavailable in the current filter engine'}</small></span><b>${index === guide.current.datasetIndex ? 'Selected' : 'Choose'}</b></button>`).join('')}</div>`;
}

function guideConditionRows(item) {
  if (!item.conditions.length) return `<p class="guided-condition-empty">No row conditions. Every response row will be included.</p>`;
  return item.conditions.map((condition,index) => `<div class="guided-condition-row">
    <select data-guide-condition-field="${index}" aria-label="Condition field">${item.fields.map(field => `<option value="${guideEscape(field.path)}" ${field.path === condition.field ? 'selected' : ''}>${guideEscape(field.path)}</option>`).join('')}</select>
    <select data-guide-condition-op="${index}" aria-label="Condition operator">${['=','!=','>','>=','<','<=','CONTAINS','STARTS_WITH','ENDS_WITH','IS NULL','IS NOT NULL'].map(op => `<option ${op === condition.op ? 'selected' : ''}>${op}</option>`).join('')}</select>
    <input data-guide-condition-value="${index}" aria-label="Condition value" value="${guideEscape(condition.value)}" placeholder="Value" ${condition.op.startsWith('IS ') ? 'disabled' : ''}>
    <button type="button" data-guide-remove-condition="${index}" aria-label="Remove condition">×</button>
  </div>`).join('');
}

function guideColumnsStep() {
  const item = guide.current;
  const dataset = item.datasets[item.datasetIndex];
  guideHeader('CREATE FILTER · 5 OF 5', 'Choose columns and conditions');
  const query = guide.fieldSearch.toLowerCase();
  const fields = item.fields.filter(field => !query || field.path.toLowerCase().includes(query) || field.sample.toLowerCase().includes(query));
  return `<button type="button" class="guided-back" data-guide-back="${item.datasets.length > 1 ? 'dataset' : 'inspect'}">← Change data source</button>
    <div class="guided-message"><span>05</span><div><strong>Select the fields to include.</strong><p>Conditions may use fields that are not displayed in the report.</p></div></div>
    <div class="guided-section-heading"><div><strong>Output columns</strong><span>${item.selected.length} selected · ${guideEscape(dataset.path)}</span></div><div><button type="button" data-guide-select-all>Select visible</button><button type="button" data-guide-clear>Clear</button></div></div>
    <label class="guided-search"><span>⌕</span><input type="search" data-guide-field-search value="${guideEscape(guide.fieldSearch)}" placeholder="Search fields or sample values" aria-label="Search fields"></label>
    <div class="guided-field-table"><div class="guided-field-head"><span></span><span>Field</span><span>Type</span><span>Sample</span><span>Output label</span></div>${fields.map(field => `<label class="guided-field-row"><input type="checkbox" data-guide-field="${guideEscape(field.path)}" ${item.selected.includes(field.path) ? 'checked' : ''}><span><code>${guideEscape(field.path)}</code></span><small>${guideEscape(field.type)}</small><span title="${guideEscape(field.sample)}">${guideEscape(field.sample || '—')}</span><input type="text" data-guide-label="${guideEscape(field.path)}" value="${guideEscape(item.labels[field.path] || '')}" placeholder="${guideEscape(guideHumanize(field.path))}" aria-label="Output label for ${guideEscape(field.path)}"></label>`).join('')}</div>
    <div class="guided-section-heading guided-conditions-heading"><div><strong>Row conditions</strong><span>All conditions use AND</span></div><button type="button" data-guide-add-condition>＋ Add condition</button></div>
    <div class="guided-conditions">${guideConditionRows(item)}</div>
    ${guide.error ? guideError() : ''}
    <div class="guided-footer-actions"><button type="button" class="primary-button" data-guide-finish-request ${item.selected.length ? '' : 'disabled'}>${guide.items.length ? 'Add request to draft' : 'Create filter draft'}</button><small>${item.selected.length ? 'You can edit the generated definition before saving.' : 'Select at least one output column.'}</small></div>`;
}

function guideDraftStep() {
  guideHeader('FILTER DRAFT READY', 'Review and finish your report');
  const allFields = guide.items.flatMap((item,itemIndex) => item.selected.map(field => ({value:`${itemIndex}:${field}`,label:`${item.request.name} · ${field}`})));
  if (!guide.summary.valueField && allFields.length) guide.summary.valueField = allFields[0].value;
  return `<div class="guided-success"><span>✓</span><div><strong>Your filter draft is ready.</strong><p>${guide.items.length} request${guide.items.length === 1 ? '' : 's'} · ${guide.items.reduce((total,item) => total + item.selected.length,0)} output columns</p></div></div>
    <div class="guided-draft-requests">${guide.items.map((item,index) => `<div><span class="method ${guideEscape(item.request.method.toLowerCase())}">${guideEscape(item.request.method)}</span><span><strong>${guideEscape(item.request.name)}</strong><small>${item.selected.length} columns · ${guideEscape(item.dataset.path)}${item.conditions.length ? ` · ${item.conditions.length} condition${item.conditions.length === 1 ? '' : 's'}` : ''}</small></span><button type="button" data-guide-remove-item="${index}" aria-label="Remove ${guideEscape(item.request.name)}">×</button></div>`).join('')}</div>
    <button type="button" class="guided-add-request" data-guide-add-request>＋ Add another request</button>
    <details class="guided-summary-builder" ${guide.summary.enabled ? 'open' : ''}><summary>Create summary <span>Title, query, metric, and request status</span></summary><div class="guided-summary-fields">
      <label><span>Report title</span><input data-guide-summary="title" value="${guideEscape(guide.summary.title)}"></label>
      <label><span>Description</span><textarea data-guide-summary="description" rows="2">${guideEscape(guide.summary.description)}</textarea></label>
      <label class="guided-toggle-row"><input type="checkbox" data-guide-summary-check="query" ${guide.summary.query ? 'checked' : ''}><span><strong>Add query</strong><small>Show matching data in the Summary worksheet</small></span></label>
      <div class="guided-query-settings" ${guide.summary.query ? '' : 'hidden'}><label><span>Query output</span><select data-guide-summary="mode"><option value="table" ${guide.summary.mode === 'table' ? 'selected' : ''}>Select table</option><option value="condition" ${guide.summary.mode === 'condition' ? 'selected' : ''}>Select table with conditions</option><option value="values" ${guide.summary.mode === 'values' ? 'selected' : ''}>Select values from table</option></select></label>${guide.summary.mode === 'values' ? `<label><span>Value field</span><select data-guide-summary="valueField">${allFields.map(field => `<option value="${guideEscape(field.value)}" ${field.value === guide.summary.valueField ? 'selected' : ''}>${guideEscape(field.label)}</option>`).join('')}</select></label>` : ''}</div>
      <label class="guided-toggle-row ${guide.items.some(item => item.selected.length === 1) ? 'is-limited' : ''}"><input type="checkbox" data-guide-summary-check="metric" ${guide.summary.metric ? 'checked' : ''} ${guide.items.some(item => item.selected.length === 1) ? 'disabled' : ''}><span><strong>Add matching-row metric</strong><small>${guide.items.some(item => item.selected.length === 1) ? 'Select at least two columns per request for a reliable row count.' : 'Display the number of matching rows.'}</small></span></label>
      <label class="guided-toggle-row"><input type="checkbox" data-guide-summary-check="status" ${guide.summary.status ? 'checked' : ''}><span><strong>Add request status</strong><small>Method, HTTP status, success, and duration</small></span></label>
    </div></details>
    <label class="guided-filename"><span>Filter filename</span><div><span>filters/</span><input data-guide-filename value="${guideEscape(guide.filename || (guide.savedPath ? guideBasename(guide.savedPath) : guideDefaultName()))}" aria-label="Filter filename"><span>.filter</span></div></label>
    ${guide.error ? guideError() : ''}
    <div class="guided-footer-actions guided-final-actions"><button type="button" class="outline-button" data-guide-open-editor>Open draft in editor</button><button type="button" class="primary-button" data-guide-save ${guide.busy ? 'disabled' : ''}>${guide.busy ? 'Saving…' : guide.savedSource === guideCompile() ? 'Saved' : 'Save filter'}</button><button type="button" class="primary-button" data-guide-generate ${guide.busy ? 'disabled' : ''}>${guide.busy ? 'Working…' : 'Generate report'}</button></div>`;
}

function guideReportsStep() {
  guideHeader('SEARCH REPORTS', 'Find a generated report');
  const query = guide.reportSearch.trim().toLowerCase();
  const reports = state.files.filter(file => !file.directory && file.path.startsWith('reports/') && file.path.endsWith('.xlsx') && (!query || file.path.toLowerCase().includes(query)));
  return `<div class="guided-message"><span>⌕</span><div><strong>Search generated workbooks.</strong><p>Run details are shown when Report Studio can match the workbook to its history.</p></div></div>
    <label class="guided-search"><span>⌕</span><input type="search" data-guide-report-search value="${guideEscape(guide.reportSearch)}" placeholder="Search report names" aria-label="Search reports"></label>
    <div class="guided-report-list">${reports.map(file => { const run = state.history.find(item => item.files?.includes(file.path)); return `<button type="button" data-guide-report="${guideEscape(file.path)}"><span class="guided-report-glyph">▦</span><span><strong>${guideEscape(basename(file.path))}</strong><small>${run ? `${guideEscape(run.collection)} · ${guideEscape(formatDate(run.finishedAt || run.startedAt))}` : 'Workbook file'}</small></span><b>Open →</b></button>`; }).join('') || `<div class="guided-empty"><strong>No matching reports</strong><p>Generate a report or try another search.</p></div>`}</div>`;
}

function guideRunStep() {
  guideHeader('RUN A FILTER', 'Choose a saved filter');
  const filters = guideFilters();
  return `<div class="guided-message"><span>▶</span><div><strong>Generate a fresh workbook.</strong><p>The saved definition chooses its requests and collection.</p></div></div>
    ${guide.error ? guideError() : ''}<div class="guided-choice-list">${filters.map(file => `<button type="button" data-guide-run-filter="${guideEscape(file.path)}" ${guide.busy ? 'disabled' : ''}><span class="guided-file-icon">ƒ</span><span><strong>${guideEscape(guideBasename(file.path))}</strong><small>${guideEscape(file.path)}</small></span><b>${guide.busy ? 'Starting…' : 'Run →'}</b></button>`).join('') || `<div class="guided-empty"><strong>No saved filters</strong><p>Create a filter to generate your first report.</p><button type="button" class="primary-button" data-guide-action="create">Create filter</button></div>`}</div>`;
}

function guideCollectionAction() {
  guideHeader('ADD COLLECTION', 'Bring an API collection into the workspace');
  return `<div class="guided-message"><span>＋</span><div><strong>Use a Postman collection or start fresh.</strong><p>Imported JSON is validated when requests are opened.</p></div></div>
    <div class="guided-action-grid guided-two-actions"><button type="button" data-guide-import><span>IMPORT</span><strong>Import Postman JSON</strong><small>Choose a .json file from your computer.</small><b>↑</b></button><button type="button" data-guide-empty-collection><span>CREATE</span><strong>Empty collection</strong><small>Create a valid collection file, then add requests in the JSON editor.</small><b>→</b></button></div>`;
}

function guideRunView() {
  const run = guide.run;
  const running = run && ['queued','running'].includes(run.status);
  guideHeader(running ? 'GENERATING REPORT' : run?.status === 'completed' ? 'REPORT READY' : 'RUN NEEDS ATTENTION', running ? 'Running your filter' : run?.status === 'completed' ? 'Your workbook is ready' : 'The report could not finish');
  const progress = run?.total ? Math.round(((run.completed || 0) / run.total) * 100) : 0;
  return `<div class="guided-run-state ${run?.status || ''}"><span>${running ? '<i class="guided-spinner"></i>' : run?.status === 'completed' ? '✓' : '!'}</span><div><strong>${guideEscape(run?.name || 'Report')}</strong><p>${guideEscape(run?.summary || run?.error || 'Preparing requests…')}</p></div></div>
    <div class="guided-progress"><div><span>${run?.completed || 0} of ${run?.total || 0} requests</span><strong>${progress}%</strong></div><span><i style="width:${progress}%"></i></span></div>
    ${!running && run?.status === 'completed' ? `<div class="guided-final-actions"><button type="button" class="primary-button" data-guide-open-run>Open report results</button>${run.files?.[0] ? `<a class="outline-button" href="/api/download?path=${encodeURIComponent(run.files[0])}">Download Excel</a>` : ''}</div>` : ''}
    ${!running && run?.status !== 'completed' ? `<button type="button" class="outline-button" data-guide-action="run">Choose another filter</button>` : ''}`;
}

function guideRenderPreview() {
  const preview = $('guided-preview-content');
  if (guide.run) {
    $('guided-preview-title').textContent = 'Execution';
    $('guided-preview-state').textContent = guide.run.status || 'Starting';
    preview.innerHTML = `<div class="guided-preview-empty"><span>▶</span><strong>${guideEscape(guide.run.collection || 'Collection')}</strong><p>${guide.run.total || 0} selected request${guide.run.total === 1 ? '' : 's'}</p></div>`;
    return;
  }
  const source = guide.items.length ? guideCompile() : '';
  if (source) {
    $('guided-preview-title').textContent = 'Definition';
    $('guided-preview-state').textContent = guide.savedSource === source ? 'Saved' : 'Unsaved draft';
    preview.innerHTML = `<pre><code>${guideEscape(source)}</code></pre>`;
    return;
  }
  const dataset = guide.current?.datasets?.[guide.current.datasetIndex];
  if (dataset) {
    $('guided-preview-title').textContent = dataset.path;
    $('guided-preview-state').textContent = `${dataset.rows.length} sampled rows`;
    const columns = dataset.fields.slice(0,6).map(field => field.path);
    preview.innerHTML = `<div class="guided-preview-table-wrap"><table><thead><tr>${columns.map(column => `<th>${guideEscape(column)}</th>`).join('')}</tr></thead><tbody>${dataset.rows.slice(0,8).map(row => `<tr>${columns.map(column => `<td>${guideEscape(guideValue(row[column]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div><p class="guided-preview-note">Preview from the latest request test. Saving the filter does not store this response.</p>`;
    return;
  }
  $('guided-preview-title').textContent = 'Definition';
  $('guided-preview-state').textContent = 'Waiting for input';
  preview.innerHTML = `<div class="guided-preview-empty"><span>⌁</span><strong>Your work appears here</strong><p>Response data and generated filter source stay visible as you build.</p></div>`;
}

function guideRender() {
  const content = $('guided-content');
  if (guide.step === 'home') content.innerHTML = guideHome();
  else if (guide.step === 'collection') content.innerHTML = guideCollectionStep();
  else if (guide.step === 'request') content.innerHTML = guideRequestStep();
  else if (guide.step === 'inspect') content.innerHTML = guideInspectStep();
  else if (guide.step === 'dataset') content.innerHTML = guideDatasetStep();
  else if (guide.step === 'columns') content.innerHTML = guideColumnsStep();
  else if (guide.step === 'draft') content.innerHTML = guideDraftStep();
  else if (guide.step === 'reports') content.innerHTML = guideReportsStep();
  else if (guide.step === 'run') content.innerHTML = guideRunStep();
  else if (guide.step === 'collection-action') content.innerHTML = guideCollectionAction();
  else if (guide.step === 'running') content.innerHTML = guideRunView();
  guideRenderPreview();
  const active = guide.action === 'home' ? 'home' : guide.action;
  document.querySelectorAll('.guided-nav button').forEach(button => button.classList.toggle('active', button.dataset.guideAction === active));
}

function guideHumanize(path) {
  return path.split('.').at(-1).replace(/[_-]+/g,' ').replace(/([a-z])([A-Z])/g,'$1 $2').replace(/^./, char => char.toUpperCase());
}

function guideValue(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') { try { return JSON.stringify(value); } catch {} }
  return String(value);
}

function guideFlatten(value, prefix = '', target = {}) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    Object.entries(value).forEach(([key,item]) => guideFlatten(item, prefix ? `${prefix}.${key}` : key, target));
  } else target[prefix || 'Value'] = value;
  return target;
}

function guideDatasets(body) {
  let root;
  try { root = JSON.parse(body); } catch { return []; }
  let autoPath = '';
  if (root && typeof root === 'object' && !Array.isArray(root)) {
    for (const [key,value] of Object.entries(root)) {
      if (Array.isArray(value) && value.some(item => item && typeof item === 'object' && !Array.isArray(item))) { autoPath = key; break; }
    }
  }
  const datasets = [];
  const add = (value,path) => {
    const rows = value.slice(0,500).map(item => item && typeof item === 'object' && !Array.isArray(item) ? guideFlatten(item) : {Value:item});
    const paths = [];
    rows.forEach(row => Object.keys(row).forEach(key => { if (!paths.includes(key)) paths.push(key); }));
    const fields = paths.slice(0,120).map(key => { const values = rows.map(row => row[key]).filter(item => item !== null && item !== undefined); const sample = values[0]; return {path:key,type:Array.isArray(sample) ? 'array' : sample === null || sample === undefined ? 'unknown' : typeof sample,sample:guideValue(sample)}; });
    datasets.push({path,rows,fields,supported:!autoPath || path === autoPath,expand:Boolean(path !== 'Response' && path !== autoPath && !autoPath),prefix:path !== 'Response' && path !== autoPath && !autoPath ? path : ''});
  };
  const visit = (value,path='Response',depth=0) => {
    if (depth > 7) return;
    if (Array.isArray(value)) { add(value,path); return; }
    if (!value || typeof value !== 'object') return;
    Object.entries(value).forEach(([key,item]) => visit(item,path === 'Response' ? key : `${path}.${key}`,depth+1));
  };
  visit(root);
  if (!datasets.length && root && typeof root === 'object') {
    const row = Array.isArray(root) ? {Value:root} : guideFlatten(root);
    add([row],'Response');
  }
  return datasets.filter(dataset => dataset.fields.length);
}

function guideFieldPath(item, field) { return item.dataset.prefix ? `${item.dataset.prefix}.${field}` : field; }
function guideLiteral(value) {
  const trimmed = String(value ?? '').trim();
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed) || /^(true|false|null)$/i.test(trimmed)) return trimmed;
  return guideQuote(trimmed);
}
function guidePredicate(item) {
  return item.conditions.map(condition => {
    const left = guideFieldPath(item, condition.field);
    return condition.op.startsWith('IS ') ? `${left} ${condition.op}` : `${left} ${condition.op} ${guideLiteral(condition.value)}`;
  }).join(' AND ');
}

function guideCompile() {
  if (!guide.items.length) return '';
  const collection = guideBasename(guide.collectionPath);
  const requestNames = [...new Set(guide.items.map(item => item.request.name))];
  const lines = [`COLLECTION ${guideQuote(collection)};`,`REQUESTS ${requestNames.map(guideQuote).join(', ')};`,''];
  guide.items.forEach(item => {
    const name = guideQuote(item.request.name);
    if (item.dataset.expand) lines.push(`EXPAND ${name} ON ${item.dataset.path};`);
    const predicate = guidePredicate(item);
    if (predicate) lines.push(`FILTER ${name} WHERE ${predicate};`);
    lines.push(`COLUMNS ${name}: ${item.selected.map(field => { const path = guideFieldPath(item,field); const label = item.labels[field]?.trim(); return label && label !== guideHumanize(field) ? `${path} AS ${guideQuote(label)}` : path; }).join(', ')};`,'');
  });
  if (guide.summary.enabled) {
    guide.items.forEach((item,index) => {
      const predicate = guidePredicate(item);
      lines.push(`$QUERY_${index + 1} = FILTER ${guideQuote(item.request.name)}${predicate ? ` WHERE ${predicate}` : ''};`);
    });
    lines.push('','SUMMARY {',`  TITLE ${guideQuote(guide.summary.title || 'API report')} COLOR "#315D4D";`, `  DESCRIPTION ${guideQuote(guide.summary.description || 'Generated from selected API data.')};`);
    if (guide.summary.query) {
      if (guide.summary.mode === 'values') {
        const [rawIndex,field] = String(guide.summary.valueField).split(':');
        const itemIndex = Math.max(0,Math.min(Number(rawIndex) || 0,guide.items.length - 1));
        const item = guide.items[itemIndex];
        const selectedField = item.selected.includes(field) ? field : item.selected[0];
        lines.push(`  TABLE $QUERY_${itemIndex + 1} TITLE ${guideQuote(item.labels[selectedField]?.trim() || guideHumanize(selectedField))} COLUMNS ${guideFieldPath(item,selectedField)};`);
      } else guide.items.forEach((item,index) => lines.push(`  TABLE $QUERY_${index + 1} TITLE ${guideQuote(item.request.name)} COLUMNS ${item.selected.map(field => guideFieldPath(item,field)).join(', ')};`));
    }
    if (guide.summary.metric) guide.items.forEach((item,index) => lines.push(`  METRIC ${guideQuote(`${item.request.name} rows`)} = $QUERY_${index + 1};`));
    if (guide.summary.status) lines.push('  STATUS;');
    lines.push('  METRICS;','}');
  }
  return lines.join('\n').replace(/\n{3,}/g,'\n\n') + '\n';
}

function guideDefaultName() {
  const first = guide.items[0]?.request?.name || 'new-report';
  return first.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,60) || 'new-report';
}

async function guideSelectCollection(path) {
  guide.error = ''; guide.busy = true;
  try {
    guide.collectionPath = path;
    guide.collection = prepareApiCollection(await api(`/api/collection?path=${encodeURIComponent(path)}`));
    guide.step = 'request';
  } catch (error) { guide.error = error.message; }
  finally { guide.busy = false; guideRender(); }
}

function guideRequestPayload(request) {
  return {collection:guide.collectionPath,index:request.index,method:request.method,url:request.url,
    headers:(request.headers || []).filter(row => row.enabled !== false && row.key).map(({key,value}) => ({key,value})),
    body:request.body || '',bodyMode:request.bodyMode || 'none',bodyFields:request.bodyFields || [],auth:request.auth || {type:'noauth',values:{}},
    variables:Object.fromEntries((guide.collection.variables || []).filter(row => row.enabled !== false && row.key).map(row => [String(row.key).replace(/^{{|}}$/g,''),String(row.value ?? '')]))};
}

async function guideInspect() {
  guide.busy = true; guide.error = ''; guideRender();
  try {
    const response = await api('/api/request',{method:'POST',body:guideRequestPayload(guide.current.request)});
    guide.current.response = response;
    guide.current.datasets = guideDatasets(response.body);
    if (!response.success) throw new Error(response.error || `The API returned HTTP ${response.statusCode}. Review the request in the API workbench.`);
    if (!guide.current.datasets.length) throw new Error('The response has no JSON rows to turn into report columns. Choose another request or inspect the raw response.');
    guide.current.datasetIndex = Math.max(0,guide.current.datasets.findIndex(dataset => dataset.supported));
    const dataset = guide.current.datasets[guide.current.datasetIndex];
    guide.current.fields = dataset.fields;
    guide.current.selected = dataset.fields.slice(0,Math.min(12,dataset.fields.length)).map(field => field.path);
    guide.step = guide.current.datasets.filter(item => item.supported).length > 1 ? 'dataset' : 'columns';
  } catch (error) { guide.error = error.message; }
  finally { guide.busy = false; guideRender(); }
}

function guideCommitRequest() {
  if (!guide.current.selected.length) { guide.error = 'Select at least one output column.'; guideRender(); return; }
  const dataset = guide.current.datasets[guide.current.datasetIndex];
  guide.items.push({...guide.current,dataset,fields:[...guide.current.fields],selected:[...guide.current.selected],labels:{...guide.current.labels},conditions:guide.current.conditions.map(condition => ({...condition}))});
  guide.current = guideNewCurrent();
  guide.step = 'draft'; guide.error = ''; guidePersist(); guideRender();
}

function guideStoredDraft() {
  try { return JSON.parse(localStorage.getItem('report-studio.guided-draft') || 'null'); }
  catch { return null; }
}

function guidePersist() {
  if (!guide.items.length) { try { localStorage.removeItem('report-studio.guided-draft'); } catch {} return; }
  const snapshot = {
    version:1,collectionPath:guide.collectionPath,filename:guide.filename,summary:{...guide.summary},
    items:guide.items.map(item => ({requestIndex:item.request.index,dataset:{path:item.dataset.path,prefix:item.dataset.prefix,expand:item.dataset.expand},
      fields:item.fields.map(field => ({path:field.path,type:field.type,sample:field.sample})),selected:[...item.selected],labels:{...item.labels},conditions:item.conditions.map(condition => ({...condition}))}))
  };
  try { localStorage.setItem('report-studio.guided-draft',JSON.stringify(snapshot)); } catch {}
}

async function guideResume() {
  const saved = guideStoredDraft();
  if (!saved?.collectionPath || !saved.items?.length) return;
  guide.busy = true; guide.error = '';
  try {
    guide.action = 'create';
    guide.collectionPath = saved.collectionPath;
    guide.collection = prepareApiCollection(await api(`/api/collection?path=${encodeURIComponent(saved.collectionPath)}`));
    guide.items = saved.items.map(item => ({...item,request:guide.collection.requests.find(request => request.index === item.requestIndex) || {index:item.requestIndex,name:`Request ${item.requestIndex + 1}`,method:'GET'}}));
    guide.summary = {...guide.summary,...saved.summary};
    guide.filename = saved.filename || '';
    guide.current = guideNewCurrent();
    guide.step = 'draft';
  } catch (error) { guide.error = `The saved draft could not be resumed. ${error.message}`; guide.step = 'home'; }
  finally { guide.busy = false; guideRender(); }
}

async function guideSave(runAfter = false) {
  if (!guide.items.length || guide.busy) return;
  const name = (document.querySelector('[data-guide-filename]')?.value || guide.filename || guideDefaultName()).trim().replace(/\.filter$/i,'');
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$/.test(name)) { guide.error = 'Use a filename containing letters, numbers, periods, dashes, or underscores.'; guideRender(); return; }
  const path = `filters/${name}.filter`;
  const source = guideCompile();
  guide.busy = true; guide.error = ''; guideRender();
  try {
    await api('/api/validate',{method:'POST',body:{collection:guide.collectionPath,source,filename:path}});
    if (!guide.savedPath || guide.savedPath !== path) await api('/api/file',{method:'PUT',body:{path,content:source,revision:null}});
    else {
      const existing = await api(`/api/file?path=${encodeURIComponent(path)}`);
      await api('/api/file',{method:'PUT',body:{path,content:source,revision:existing.revision}});
    }
    guide.filename = name;
    guide.savedPath = path;
    guide.savedSource = source;
    guidePersist();
    await refreshFiles();
    if (runAfter) await guideStartSource(source,path);
    else notify(`Saved ${path}.`);
  } catch (error) { guide.error = error.message; guide.busy = false; guideRender(); return; }
  guide.busy = false; guideRender();
}

async function guideStartSource(source,filename) {
  const result = await api('/api/runs',{method:'POST',body:{collection:guide.collectionPath,source,filename}});
  guide.run = result; guide.step = 'running'; guide.busy = false; guideRender(); guidePoll(result.id);
}

async function guideRunSaved(path) {
  if (guide.busy) return;
  guide.busy = true; guide.error = ''; guideRender();
  try {
    const result = await api('/api/runs/saved-filter',{method:'POST',body:{filter:path}});
    guide.run = result; guide.step = 'running'; guide.busy = false; guideRender(); guidePoll(result.id);
  } catch (error) { guide.busy = false; guide.error = error.message; guideRender(); }
}

async function guidePoll(id) {
  try {
    const result = await api(`/api/run?id=${encodeURIComponent(id)}&poll=${Date.now()}`);
    if (guide.run?.id !== id) return;
    guide.run = result; guideRender();
    if (['queued','running'].includes(result.status)) { setTimeout(() => guidePoll(id),1000); return; }
    const updates = await Promise.allSettled([refreshFiles(),api('/api/runs')]);
    if (updates[1].status === 'fulfilled') state.history = updates[1].value;
  } catch (error) {
    if (guide.run?.id === id) { guide.error = error.message; guideRender(); setTimeout(() => guidePoll(id),3000); }
  }
}

async function guideOpenEditor() {
  const source = guideCompile();
  let path = guide.savedPath || `filters/${guideDefaultName()}.filter`;
  let doc = state.documents.find(item => item.path === path);
  if (!doc && guide.savedPath) {
    const file = await api(`/api/file?path=${encodeURIComponent(path)}`);
    doc = {...file,content:source,saved:file.content};
    state.documents.push(doc);
  } else if (!doc) { doc = {path,content:source,saved:'',revision:null}; state.documents.push(doc); }
  else doc.content = source;
  state.collection = guide.collectionPath;
  guideClose(); activate(doc); refreshOutline();
}

function guideNavigate(action) {
  if (action === 'home') guideReset('home');
  else if (action === 'collection') { guideReset('collection'); guide.step = 'collection-action'; guideRender(); }
  else guideReset(action);
}

function guideFilterVisible(input) {
  const query = input.value.trim().toLowerCase();
  input.closest('.guided-content').querySelectorAll('[data-guide-filterable] > button').forEach(button => { button.hidden = query && !button.dataset.search.includes(query); });
}

function initializeGuided() {
  bind('guided-toggle','click',() => $('guided-workspace').hidden ? guideOpen() : guideClose());
  bind('guided-close','click',guideClose);
  bind('guided-restart','click',() => { try { localStorage.removeItem('report-studio.guided-draft'); } catch {} guideReset('home'); });
  bind('guided-preview-toggle','click',() => {
    const open = !document.body.classList.contains('guided-preview-open');
    document.body.classList.toggle('guided-preview-open',open);
    $('guided-preview-toggle').textContent = open ? 'Build' : 'Preview';
    $('guided-preview-toggle').setAttribute('aria-pressed',String(open));
  });
  $('guided-workspace').addEventListener('click',event => {
    const target = event.target.closest('button,a');
    if (!target) return;
    if ('guideNewTask' in target.dataset) { try { localStorage.removeItem('report-studio.guided-draft'); } catch {} guideReset('home'); }
    else if ('guideResume' in target.dataset) guideResume();
    else if (target.dataset.guideAction) guideNavigate(target.dataset.guideAction);
    else if (target.dataset.guideCollection) guideSelectCollection(target.dataset.guideCollection);
    else if ('guideRequest' in target.dataset) {
      const request = guide.collection.requests.find(item => item.index === Number(target.dataset.guideRequest));
      if (guide.action === 'test') { guideClose(); openApiCollection(guide.collectionPath).then(() => { state.apiRequestIndex = request.index; renderResult(); }).catch(handleError); }
      else { guide.current = guideNewCurrent(); guide.current.requestIndex = request.index; guide.current.request = request; guide.step = 'inspect'; guideRender(); }
    } else if ('guideInspect' in target.dataset) guideInspect();
    else if ('guideDataset' in target.dataset) { guide.current.datasetIndex = Number(target.dataset.guideDataset); guide.current.fields = guide.current.datasets[guide.current.datasetIndex].fields; guide.current.selected = guide.current.fields.slice(0,Math.min(12,guide.current.fields.length)).map(field => field.path); guide.step = 'columns'; guideRender(); }
    else if ('guideSelectAll' in target.dataset) { const visible = [...$('guided-content').querySelectorAll('[data-guide-field]')].map(input => input.dataset.guideField); guide.current.selected = [...new Set([...guide.current.selected,...visible])]; guideRender(); }
    else if ('guideClear' in target.dataset) { guide.current.selected = []; guideRender(); }
    else if ('guideAddCondition' in target.dataset) { guide.current.conditions.push({field:guide.current.fields[0]?.path || '',op:'=',value:''}); guideRender(); }
    else if ('guideRemoveCondition' in target.dataset) { guide.current.conditions.splice(Number(target.dataset.guideRemoveCondition),1); guideRender(); }
    else if ('guideFinishRequest' in target.dataset) guideCommitRequest();
    else if ('guideAddRequest' in target.dataset) { guide.current = guideNewCurrent(); guide.step = 'request'; guideRender(); }
    else if ('guideRemoveItem' in target.dataset) { guide.items.splice(Number(target.dataset.guideRemoveItem),1); guide.step = guide.items.length ? 'draft' : 'request'; guidePersist(); guideRender(); }
    else if ('guideSave' in target.dataset) guideSave(false);
    else if ('guideGenerate' in target.dataset) guideSave(true);
    else if ('guideOpenEditor' in target.dataset) guideOpenEditor().catch(handleError);
    else if (target.dataset.guideRunFilter) guideRunSaved(target.dataset.guideRunFilter);
    else if (target.dataset.guideReport) { const run = state.history.find(item => item.files?.includes(target.dataset.guideReport)); guideClose(); if (run) state.run = run; openWorkbook(target.dataset.guideReport); }
    else if ('guideOpenRun' in target.dataset) { const run = guide.run; guideClose(); state.run = run; state.reportPath = run.files?.[0] || null; state.activeRun = null; setResultsOnly(true); setView('summary'); }
    else if ('guideImport' in target.dataset) { guideClose(); $('file-upload').click(); }
    else if ('guideEmptyCollection' in target.dataset) { guideClose(); $('new-file-type').value = 'collection'; openFileDialog('new','collections/new-collection.json'); }
    else if (target.dataset.guideBack) { guide.step = target.dataset.guideBack; guide.error = ''; guideRender(); }
  });
  $('guided-workspace').addEventListener('input',event => {
    const target = event.target;
    if ('guideFieldSearch' in target.dataset) { guide.fieldSearch = target.value; guideRender(); const input = document.querySelector('[data-guide-field-search]'); if (input) { input.focus(); input.setSelectionRange(input.value.length,input.value.length); } }
    else if ('guideField' in target.dataset) { guide.current.selected = target.checked ? [...new Set([...guide.current.selected,target.dataset.guideField])] : guide.current.selected.filter(field => field !== target.dataset.guideField); guideRender(); }
    else if ('guideLabel' in target.dataset) guide.current.labels[target.dataset.guideLabel] = target.value;
    else if ('guideConditionValue' in target.dataset) guide.current.conditions[Number(target.dataset.guideConditionValue)].value = target.value;
    else if ('guideSummary' in target.dataset) { guide.summary[target.dataset.guideSummary] = target.value; guidePersist(); guideRenderPreview(); }
    else if ('guideFilename' in target.dataset) { guide.filename = target.value; guidePersist(); }
    else if ('guideReportSearch' in target.dataset) { guide.reportSearch = target.value; guideRender(); const input = document.querySelector('[data-guide-report-search]'); if (input) { input.focus(); input.setSelectionRange(input.value.length,input.value.length); } }
    else if ('guideListSearch' in target.dataset) guideFilterVisible(target);
  });
  $('guided-workspace').addEventListener('change',event => {
    const target = event.target;
    if ('guideConditionField' in target.dataset) guide.current.conditions[Number(target.dataset.guideConditionField)].field = target.value;
    else if ('guideConditionOp' in target.dataset) { guide.current.conditions[Number(target.dataset.guideConditionOp)].op = target.value; guideRender(); }
    else if ('guideSummaryCheck' in target.dataset) { guide.summary[target.dataset.guideSummaryCheck] = target.checked; guidePersist(); guideRender(); }
    else if ('guideSummary' in target.dataset) { guide.summary[target.dataset.guideSummary] = target.value; guidePersist(); guideRender(); }
  });
  guideRender();
}

initializeGuided();
