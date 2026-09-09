const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the bundled client without adding runtime dependencies or starting a browser.
// This harness deliberately does not claim to test browser layout or native interactions.
function client() {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const html = fs.readFileSync(path.join(directory, 'index.html'), 'utf8');
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], {
    value: '', innerHTML: '', textContent: '', hidden: false, disabled: false,
    selectionStart: 0, selectionEnd: 0, scrollTop: 0, scrollLeft: 0,
    handlers: {}, classList: {toggle() {}, add() {}, remove() {}},
    attrs: {},
    addEventListener(name, handler) { this.handlers[name] = handler; },
    setAttribute(name, value) { this.attrs[name] = String(value); },
    getAttribute(name) { return this.attrs[name] ?? null; },
    focus() {}, select() {}, showModal() {}, close() {},
    setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
    setRangeText(text, start, end) {
      this.value = this.value.slice(0, start) + text + this.value.slice(end);
      this.selectionStart = this.selectionEnd = start + text.length;
    }
  }]));
  const context = vm.createContext({
    console, URLSearchParams, encodeURIComponent,
    document: {
      getElementById(id) { assert.ok(elements.has(id), `Unknown static DOM binding: ${id}`); return elements.get(id); },
      querySelectorAll() { return []; }, querySelector() { return null; }, addEventListener() {},
      body: {classList: {values:new Set(), add(value) { this.values.add(value); }, remove(value) { this.values.delete(value); },
        contains(value) { return this.values.has(value); }, toggle(value, force) { const enabled = force === undefined ? !this.values.has(value) : force; enabled ? this.values.add(value) : this.values.delete(value); return enabled; }}},
      documentElement: {style: {setProperty() {}}}
    },
    window: {addEventListener() {}}, innerWidth: 1400,
    requestAnimationFrame(callback) { callback(); return 1; }, cancelAnimationFrame() {},
    setTimeout() { return 1; }, clearTimeout() {},
    fetch() { throw new Error('Unexpected network request in client test'); },
    getComputedStyle() { return {lineHeight: '22px'}; }
  });
  const source = fs.readFileSync(path.join(directory, 'app.js'), 'utf8').replace(/\ninitialize\(\);\s*$/, '\n')
    + '\n' + fs.readFileSync(path.join(directory, 'guided-workflow.js'), 'utf8');
  vm.runInContext(source, context);
  return {context, elements, run: code => vm.runInContext(code, context)};
}

test('all static control bindings exist and editor keys prevent their default synchronously', () => {
  const app = client();
  app.run("state.documents = [{path:'filters/new.filter',content:'abc',saved:'abc',revision:'1'}]; state.active='filters/new.filter';");
  const editor = app.elements.get('editor');
  editor.value = 'abc'; editor.selectionStart = editor.selectionEnd = 3;
  let prevented = false;
  editor.handlers.keydown({key:'Tab', preventDefault() { prevented = true; }});
  assert.equal(prevented, true);
  assert.equal(editor.value, 'abc  ');
  assert.equal(app.run('activeDocument().content'), 'abc  ');
});

test('syntax highlighting escapes markup in literals and comments', () => {
  const app = client();
  app.context.source = 'TITLE "<img src=x onerror=alert(1)>"; # <script>bad()</script>\nMETRIC "Count" = $ROWS;';
  const html = app.run('highlight(source)');
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('&lt;img'));
  assert.ok(html.includes('syntax-variable'));
  assert.ok(html.includes('syntax-comment'));
});

test('workbook cells render as text and preserve merges, widths, and formatted styles', () => {
  const app = client();
  app.context.preview = {sheets:[{name:'Summary'}],sheet:0,offset:0,totalRows:1,widths:[110,230],
    rows:[{index:0,height:32,cells:[{column:0,text:'<script>alert(1)</script>',style:1}]}],
    styles:{1:{fontSize:18,bold:true,italic:false,align:'center',background:'#245c50',color:'#ffffff',wrap:true}},
    merges:[{firstRow:0,lastRow:0,firstColumn:0,lastColumn:1}]};
  const html = app.run('sheetTable(preview)');
  assert.ok(html.includes('colspan="2"'));
  assert.ok(html.includes('width:230px'));
  assert.ok(html.includes('background:#245c50'));
  assert.ok(html.includes('font-weight:700'));
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.equal(app.run('columnLabel(26)'), 'AA');
});

test('request names and API error messages cannot introduce HTML', () => {
  const app = client();
  app.context.requests = [{name:'<img src=x>',method:'GET',statusCode:500,durationMs:5,success:false,error:'<script>bad()</script>',assertions:[]}];
  const html = app.run('requestTable(requests)');
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('× Failed'));
});

test('validation uses unsaved collection and filter buffers without saving either file', async () => {
  const app = client();
  await app.run(`
    state.documents = [
      {path:'filters/report.filter', content:'METRICS;', saved:'TITLE "Old";', revision:'1'},
      {path:'collections/local.json', content:'{"item":[]}', saved:'{}', revision:'2'}
    ];
    state.active = 'filters/report.filter'; state.collection = 'collections/local.json';
    globalThis.calls = [];
    api = async (path, options) => { calls.push({path,body:options.body}); return {message:'Valid.',requests:1,summaryBlocks:1}; };
    validateOrRun(false);
  `);
  assert.equal(app.run('calls.length'), 1);
  assert.equal(app.run('calls[0].path'), '/api/validate');
  assert.equal(app.run('calls[0].body.collectionSource'), '{"item":[]}');
  assert.equal(app.run('calls[0].body.source'), 'METRICS;');
  assert.equal(app.run('activeDocument().saved'), 'TITLE "Old";');
});

test('running a report sends the chosen output filename pattern', async () => {
  const app = client();
  app.elements.get('output-file-pattern').value = 'daily-{collection}-{timestamp}.xlsx';
  app.run(`
    state.documents = [{path:'filters/report.filter', content:'METRICS;', saved:'METRICS;', revision:'1'}];
    state.active = 'filters/report.filter'; state.collection = 'collections/local.json';
    beginRun = () => {};
    api = async (path, options) => { globalThis.runRequest = {path, body:options.body}; return {id:'run-1'}; };
    globalThis.pendingRun = validateOrRun(true);
  `);
  await app.context.pendingRun;
  assert.equal(app.run('runRequest.path'), '/api/runs');
  assert.equal(app.run('runRequest.body.outputFile'), 'daily-{collection}-{timestamp}.xlsx');
});

test('edits made during an in-flight save remain marked unsaved', async () => {
  const app = client();
  app.run(`
    state.documents = [{path:'filters/report.filter',content:'TITLE "First";',saved:'',revision:'1'}];
    state.active = 'filters/report.filter';
    api = () => new Promise(resolve => { globalThis.completeSave = resolve; });
    refreshFiles = async () => {};
    globalThis.pendingSave = saveDocument();
    activeDocument().content = 'TITLE "Still editing";';
    completeSave({revision:'2'});
  `);
  await app.context.pendingSave;
  assert.equal(app.run('activeDocument().saved'), 'TITLE "First";');
  assert.equal(app.run('activeDocument().content'), 'TITLE "Still editing";');
  assert.equal(app.run('isDirty(activeDocument())'), true);
});

test('switching documents preserves independent buffers and unsaved markers', () => {
  const app = client();
  app.run(`
    state.documents = [
      {path:'filters/one.filter',content:'TITLE "One";',saved:'',revision:null},
      {path:'filters/two.filter',content:'TITLE "Two";',saved:'TITLE "Two";',revision:'2'}
    ];
    activate(state.documents[0]); activate(state.documents[1]); activate(state.documents[0]);
  `);
  assert.equal(app.elements.get('editor').value, 'TITLE "One";');
  assert.equal(app.run('isDirty(state.documents[0])'), true);
  assert.equal(app.run('isDirty(state.documents[1])'), false);
});

test('Escape then Tab releases editor focus instead of trapping keyboard users', () => {
  const app = client();
  const editor = app.elements.get('editor');
  let prevented = false;
  editor.handlers.keydown({key:'Escape'});
  editor.handlers.keydown({key:'Tab', preventDefault() { prevented = true; }});
  assert.equal(prevented, false);
});

test('results pane toggle updates its accessible state and remembers the preference', () => {
  const app = client();
  const toggle = app.elements.get('toggle-results');
  let saved;
  app.context.localStorage = {setItem(key, value) { saved = [key, value]; }, getItem() { return null; }};
  app.run('state.resultHidden = false; updateControls()');
  toggle.handlers.click({});
  assert.equal(app.run('state.resultHidden'), true);
  assert.equal(toggle.textContent, 'Show results');
  assert.equal(toggle.getAttribute('aria-pressed'), 'true');
  assert.deepEqual(saved, ['report-studio.results-hidden', 'true']);
});

test('API reads bypass browser caches', async () => {
  const app = client();
  let request;
  app.context.fetch = async (path, options) => {
    request = {path, options};
    return {ok:true, json:async () => ({status:'running'})};
  };
  await app.run("api('/api/run?id=run-1')");
  assert.equal(request.path, '/api/run?id=run-1');
  assert.equal(request.options.cache, 'no-store');
});

test('language guide includes the complete filter reference and search', () => {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const html = fs.readFileSync(path.join(directory, 'index.html'), 'utf8');
  for (const keyword of ['COLLECTION','REQUESTS','FILTER','COLUMNS','DATE_CONFIG','SHAPE','EXPAND',
    'LOOKUP_TABLE','UNION','INTERSECT','EXCEPT','DIFF','COMPARE','SUMMARY','METRIC','PARAGRAPH',
    'QUICK_TABLE','LABEL_TABLE','STATUS','METRICS','DATE_PRESET','HAVING']) {
    assert.ok(html.includes(keyword), `Missing ${keyword} from the in-app language guide`);
  }
  assert.ok(html.includes('id="help-search"'));
  assert.ok(html.includes('data-help-section'));
  assert.ok(html.includes('COLLECTION reqres;'));
});

test('run polling advances progress and publishes completion without a refresh', async () => {
  const app = client();
  const timers = [];
  app.context.setTimeout = (callback, delay) => { timers.push({callback, delay}); return timers.length; };
  app.run(`
    state.run = {id:'run-1', status:'queued', completed:0, total:2, files:[]};
    state.activeRun = 'run-1';
    state.view = 'summary';
    globalThis.pollCalls = [];
    globalThis.renderCalls = 0;
    globalThis.pollResponses = [
      {id:'run-1', name:'report.filter', collection:'API', status:'running', phase:'Executing collection requests', completed:1, total:2, requests:[{name:'One'}], files:[]},
      {id:'run-1', name:'report.filter', collection:'API', status:'completed', phase:'Report ready', completed:2, total:2, passed:2, failed:0, averageMs:10, summary:'Done', finishedAt:'now', requests:[{name:'One'},{name:'Two'}], files:['reports/done.xlsx']}
    ];
    api = async path => {
      pollCalls.push(path);
      if (path.startsWith('/api/run?')) return pollResponses.shift();
      if (path === '/api/runs') return [];
      throw new Error('Unexpected API call: ' + path);
    };
    renderResult = () => { renderCalls++; };
    updateControls = () => {};
    refreshFiles = async () => {};
    log = () => {};
    notify = () => {};
  `);
  await app.run("pollRun('run-1')");
  assert.equal(app.run('state.run.completed'), 1);
  assert.equal(timers.length, 1);
  assert.equal(timers[0].delay, 1000);
  await timers.shift().callback();
  assert.equal(app.run('state.run.status'), 'completed');
  assert.equal(app.run('state.activeRun'), null);
  assert.equal(app.run('state.reportPath'), 'reports/done.xlsx');
  assert.ok(app.run('renderCalls') >= 2);
  assert.ok(app.run("pollCalls.filter(path => path.startsWith('/api/run?')).every(path => path.includes('&poll='))"));
});

test('a saved filter can start from the explorer without opening an editor tab', async () => {
  const app = client();
  app.run(`
    state.files = [{path:'filters/daily.filter', name:'daily.filter', directory:false, size:1, modified:'now'}];
    state.collection = 'collections/local.json';
    renderTree();
    renderResult = () => {};
    pollRun = () => {};
    api = async (path, options) => {
      globalThis.quickRun = {path, body:options.body};
      return {id:'run-1', name:'daily.filter', collection:'Local API', total:1, status:'queued', requests:[], files:[]};
    };
    runSavedFilter('filters/daily.filter');
  `);
  await Promise.resolve();
  assert.ok(app.elements.get('file-tree').innerHTML.includes('data-run-filter'));
  assert.equal(app.run('quickRun.path'), '/api/runs/saved-filter');
  assert.equal(app.run('quickRun.body.filter'), 'filters/daily.filter');
  assert.equal(app.run('state.documents.length'), 0);
  assert.equal(app.run('state.editorHidden'), true);
  assert.equal(app.elements.get('toggle-editor').textContent, 'Show editor');
});

test('the selected Filters root creates a new report script directly', () => {
  const app = client();
  app.run(`
    state.files = [{path:'filters', name:'filters', directory:true, size:0, modified:'now'}];
    state.selected = 'filters';
    renderTree();
  `);
  assert.ok(app.elements.get('file-tree').innerHTML.includes('data-create-filter="filters"'));
  const treeClick = app.elements.get('file-tree').handlers.click;
  treeClick({target:{closest(selector) { return selector === '[data-create-filter]' ? {dataset:{createFilter:'filters'}} : null; }}});
  assert.equal(app.run('state.active'), 'filters/untitled.filter');
  assert.equal(app.run('activeDocument().content'), app.run('starter'));
  assert.equal(app.run('activeDocument().saved'), '');
});

test('the summary can open its generated Excel workbook in the browser', () => {
  const app = client();
  app.run(`
    globalThis.workbookPath = 'reports/report.xlsx';
    state.run = {name:'report.filter', collection:'Local API', total:1, passed:1, failed:0, averageMs:1, summary:'Ready', finishedAt:'2026-01-01T00:00:00Z', requests:[], files:[workbookPath]};
    renderResult = () => {};
  `);
  assert.ok(app.run('summaryView(state.run)').includes('data-open-workbook'));
  const resultClick = app.elements.get('result-content').handlers.click;
  resultClick({target:{closest(selector) { return selector === 'button' ? {dataset:{openWorkbook:'reports/report.xlsx'}} : null; }}});
  assert.equal(app.run('state.reportPath'), 'reports/report.xlsx');
  assert.equal(app.run('state.view'), 'workbook');
  assert.equal(app.run('state.editorHidden'), true);
});

test('a collection opens as a right-side API client with editable requests', async () => {
  const app = client();
  app.run(`
    state.files = [{path:'collections/local.json', name:'local.json', directory:false, size:1, modified:'now'}];
    refreshOutline = async () => {};
    renderResult = () => {};
    api = async path => ({name:'Local API', path:'collections/local.json', requests:[{index:0,name:'List items',method:'GET',url:'https://example.test/items',folder:'Items',description:'',disabled:false,headers:[],body:''}]});
    globalThis.openingCollection = openApiCollection('collections/local.json');
  `);
  await app.context.openingCollection;
  assert.equal(app.run('state.view'), 'api');
  assert.equal(app.run('state.editorHidden'), true);
  assert.equal(app.run('state.apiCollection.requests[0].name'), 'List items');
  const markup = app.run('apiClientView(state.apiCollection)');
  assert.ok(markup.includes('data-send-request'));
  assert.ok(markup.includes('https://example.test/items'));
  assert.ok(markup.includes('>Params'));
  assert.ok(markup.includes('>Authorization'));
  assert.ok(markup.includes('>Variables'));
});

test('API client syncs params, variables, auth, and structured bodies into a send', async () => {
  const app = client();
  app.context.collection = {name:'Users',path:'collections/users.json',variables:{baseUrl:'https://example.test'},requests:[{
    index:0,name:'Get user',method:'POST',url:'{{baseUrl}}/users/{{ID}}?page=1',folder:'',description:'',disabled:false,
    headers:[{key:'Accept',value:'application/json'}],params:[{key:'page',value:'1',disabled:false}],
    auth:{type:'bearer',values:{token:'{{TOKEN}}'}},body:'',bodyMode:'urlencoded',
    bodyFields:[{key:'user_id',value:'{{ID}}',type:'text',disabled:false}]
  }]};
  app.run(`
    state.apiCollectionPath = collection.path;
    state.apiCollection = prepareApiCollection(collection);
    state.apiRequestIndex = 0;
    state.apiCollection.variables.push({key:'{{ID}}',value:'42',enabled:true},{key:'TOKEN',value:'secret',enabled:true});
    currentApiRequest().params.push({key:'include',value:'profile details',enabled:true});
    syncUrlFromParams(currentApiRequest());
    globalThis.sent = null;
    api = async (path, options) => { sent = {path,body:options.body}; return {statusCode:200,durationMs:1,success:true,error:'',body:'{}'}; };
    renderResult = () => {};
  `);
  assert.equal(app.run('currentApiRequest().url'), '{{baseUrl}}/users/{{ID}}?page=1&include=profile%20details');
  assert.deepEqual({...app.run('apiVariablePayload()')}, {baseUrl:'https://example.test',ID:'42',TOKEN:'secret'});
  await app.run('sendApiRequest()');
  assert.equal(app.run('sent.path'), '/api/request');
  assert.equal(app.run('sent.body.auth.type'), 'bearer');
  assert.equal(app.run('sent.body.bodyMode'), 'urlencoded');
  assert.equal(app.run('sent.body.bodyFields[0].value'), '{{ID}}');
  assert.equal(app.run('sent.body.variables.ID'), '42');
});

test('API client saves edited baseUrl back to the collection JSON', async () => {
  const app = client();
  app.context.collection = {name:'Users',path:'collections/users.json',variables:{baseUrl:'https://old.example',ID:'7'},requests:[{
    index:0,name:'Get user',method:'GET',url:'{{baseUrl}}/users/{{ID}}',folder:'',description:'',disabled:false,
    headers:[],params:[],auth:{type:'noauth',values:{}},body:'',bodyMode:'none',bodyFields:[]
  }]};
  app.run(`
    state.apiCollectionPath = collection.path;
    state.apiCollection = prepareApiCollection(collection);
    state.apiVariablesSaved = apiVariableSignature();
    state.apiRequestTab = 'variables';
    state.apiCollection.variables[0].value = 'https://new.example';
    globalThis.savedRequest = null;
    api = async (path, options = {}) => {
      if (path.startsWith('/api/file?')) return {path:collection.path,revision:'r1',content:JSON.stringify({info:{name:'Users'},variable:[{key:'baseUrl',value:'https://old.example',description:'API host'},{key:'ID',value:'7'}],item:[]})};
      savedRequest = options.body;
      return {revision:'r2'};
    };
    refreshFiles = async () => {};
    renderResult = () => {};
  `);
  assert.equal(app.run('apiVariablesDirty()'), true);
  assert.ok(app.run('apiRequestPanel(currentApiRequest())').includes('Save to collection'));
  await app.run('saveApiVariables()');
  const saved = JSON.parse(app.run('savedRequest.content'));
  assert.equal(saved.variable[0].key, 'baseUrl');
  assert.equal(saved.variable[0].value, 'https://new.example');
  assert.equal(saved.variable[0].description, 'API host');
  assert.equal(saved.variable[1].value, '7');
  assert.equal(app.run('apiVariablesDirty()'), false);
});

test('JSON API responses render nested values as inline tables', () => {
  const app = client();
  app.context.body = JSON.stringify({data:{items:[
    {id:1, profile:{name:'Ada', active:true, preferences:{theme:'dark'}}, tags:['api','test']},
    {id:2, profile:{name:'<img src=x>', active:false}, history:[{year:2025, change:{role:'admin'}}], misc:[1,{label:'mixed'},[true]], empty:{}}
  ]}, errors:[{code:'E1', message:'Example'}]});
  assert.equal(app.run('responseDatasets(body).length'), 2);
  assert.equal(app.run('responseDatasets(body)[0].name'), 'data.items');
  assert.deepEqual([...app.run('responseDatasets(body)[0].columns')], ['id','profile','tags','history','misc','empty']);
  app.run("state.apiResponseDataset = 0");
  const table = app.run('apiResponseTable(responseDatasets(body))');
  assert.ok(table.includes('<strong>profile</strong>'));
  assert.ok(table.includes('api-nested-object'));
  assert.ok(table.includes('api-nested-array'));
  assert.ok(table.includes('api-nested-list'));
  assert.ok(table.includes('preferences'));
  assert.ok(table.includes('theme'));
  assert.ok(table.includes('role'));
  assert.ok(table.includes('api-nested-empty">{}'));
  assert.ok(table.includes('&lt;img src=x&gt;'));
  assert.ok(!table.includes('<img'));
  assert.ok(!table.includes('[object Object]'));
  assert.ok(table.includes('data-api-response-dataset'));
  assert.ok(table.includes('data-api-table-filter'));
  assert.ok(table.includes('class="is-number"'));
  assert.ok(table.includes('class="is-boolean"'));
  assert.ok(table.includes('data-api-filter-empty'));
});

test('nested response values participate in filtering and copy as JSON', async () => {
  const app = client();
  app.context.body = JSON.stringify([
    {id:1, profile:{name:'Ada', active:true}, tags:['api','test']},
    {id:2, profile:{name:'Grace', active:false}, tags:[]}
  ]);
  app.run(`
    state.apiResponse = {statusCode:200,durationMs:1,success:true,error:'',body};
    state.apiResponseView = 'table'; state.apiResponseDataset = 0; state.apiTableFilter = 'Ada';
    globalThis.copied = '';
    globalThis.navigator = {clipboard:{writeText(value) { copied = value; return Promise.resolve(); }}};
  `);
  const filtered = app.run('apiResponseTable(responseDatasets(body))');
  assert.ok(filtered.includes('1 of 2 rows'));
  assert.ok(filtered.includes('Ada'));
  assert.ok(!filtered.includes('Grace'));
  await app.run('copyApiResponse()');
  assert.equal(app.run('copied'), 'id\tprofile\ttags\n1\t{"name":"Ada","active":true}\t["api","test"]');
});

test('the API response workbench exposes clear modes, metadata, and loading feedback', () => {
  const app = client();
  app.run(`state.apiResponse = {statusCode:200,durationMs:42,success:true,error:'',body:'[{"id":1}]'}; state.apiResponseView = 'table';`);
  const toolbar = app.run('apiResponseToolbar(state.apiResponse)');
  assert.ok(toolbar.includes('Pretty'));
  assert.ok(toolbar.includes('Table'));
  assert.ok(toolbar.includes('Raw'));
  assert.ok(toolbar.includes('data-copy-response'));
  assert.equal(app.run('responseStatusText(200)'), 'OK');
  assert.equal(app.run("formatResponseSize('abc')"), '3 B');
  app.run('state.apiSending = true');
  assert.ok(app.run('apiResponseContent(state.apiResponse)').includes('Sending request'));
});

test('guided workflow discovers nested row datasets with flattened field paths', () => {
  const app = client();
  app.context.body = JSON.stringify({School:{class:{students:[
    {student:{name:'Asha',age:12}},
    {student:{name:'Ben',age:14}}
  ]}}});
  app.run('globalThis.discovered = guideDatasets(body)');
  assert.equal(app.run('discovered.length'), 1);
  assert.equal(app.run('discovered[0].path'), 'School.class.students');
  assert.equal(app.run('discovered[0].expand'), true);
  assert.deepEqual([...app.run('discovered[0].fields.map(field => field.path)')], ['student.name','student.age']);
});

test('guided workflow compiles the nested School query into the existing filter language', () => {
  const app = client();
  app.run(`
    guide.collectionPath = 'collections/school.json';
    guide.items = [{
      request:{name:'Get school',method:'GET'},
      dataset:{path:'School.class.students',prefix:'School.class.students',expand:true},
      fields:[{path:'student.name'},{path:'student.age'}],
      selected:['student.name'],labels:{'student.name':'Student name'},
      conditions:[{field:'student.age',op:'<',value:'13'}]
    }];
    guide.summary = {enabled:true,title:'Young students',description:'Students under 13',query:true,
      mode:'values',valueField:'0:student.name',metric:false,status:true};
    globalThis.definition = guideCompile();
  `);
  const definition = app.context.definition;
  assert.match(definition, /COLLECTION "school";/);
  assert.match(definition, /EXPAND "Get school" ON School\.class\.students;/);
  assert.match(definition, /FILTER "Get school" WHERE School\.class\.students\.student\.age < 13;/);
  assert.match(definition, /COLUMNS "Get school": School\.class\.students\.student\.name AS "Student name";/);
  assert.match(definition, /TABLE \$QUERY_1 TITLE "Student name" COLUMNS School\.class\.students\.student\.name;/);
  assert.match(definition, /STATUS;/);
});

test('guided workflow uses the report engine automatic row extraction for a top-level array', () => {
  const app = client();
  app.context.body = JSON.stringify({data:[{id:1,name:'Ada'}],errors:[{code:'E1'}]});
  app.run('globalThis.discovered = guideDatasets(body)');
  assert.equal(app.run('discovered[0].path'), 'data');
  assert.equal(app.run('discovered[0].expand'), false);
  assert.equal(app.run('discovered[0].prefix'), '');
  assert.equal(app.run('discovered[1].supported'), false);
});

test('guided draft recovery stores structure without response bodies or request credentials', () => {
  const app = client();
  const storage = new Map();
  app.context.localStorage = {setItem(key,value) { storage.set(key,value); }, getItem(key) { return storage.get(key) || null; }, removeItem(key) { storage.delete(key); }};
  app.run(`
    guide.collectionPath = 'collections/private.json';
    guide.items = [{request:{index:2,name:'List students',method:'GET',headers:[{key:'Authorization',value:'secret'}]},
      response:{body:'sensitive response'},dataset:{path:'students',prefix:'',expand:false},
      fields:[{path:'name',type:'string',sample:'Asha'}],selected:['name'],labels:{},conditions:[]}];
    guide.filename = 'students-under-13';
    guidePersist();
  `);
  const raw = storage.get('report-studio.guided-draft');
  assert.ok(raw.includes('students-under-13'));
  assert.ok(!raw.includes('sensitive response'));
  assert.ok(!raw.includes('Authorization'));
  assert.ok(!raw.includes('secret'));
});

test('guided header control switches in both directions between Guided and IDE modes', async () => {
  const app = client();
  app.run("state.files = []; state.token = '';");
  const toggle = app.elements.get('guided-toggle');
  app.elements.get('guided-workspace').hidden = true;
  toggle.handlers.click({});
  await Promise.resolve();
  assert.equal(app.elements.get('guided-workspace').hidden, false);
  assert.ok(toggle.innerHTML.includes('IDE workspace'));
  assert.equal(toggle.getAttribute('aria-pressed'), 'true');
  toggle.handlers.click({});
  assert.equal(app.elements.get('guided-workspace').hidden, true);
  assert.ok(toggle.innerHTML.includes('Guided workspace'));
  assert.equal(toggle.getAttribute('aria-pressed'), 'false');
});
