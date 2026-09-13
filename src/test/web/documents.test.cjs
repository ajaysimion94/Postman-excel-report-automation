const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function documentsClient() {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const html = fs.readFileSync(path.join(directory, 'documents.html'), 'utf8');
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], {
    value:'', textContent:'', innerHTML:'', hidden:false, disabled:false, classList:{toggle(){},add(){},remove(){}},
    setAttribute(){}, addEventListener(){}, focus(){}, setSelectionRange(){}
  }]));
  const documentHandlers = {};
  const context = vm.createContext({
    console, URL, URLSearchParams, encodeURIComponent, decodeURIComponent, innerWidth:1400,
    location:{href:'http://127.0.0.1:8080/documents',search:''}, history:{replaceState(){}},
    setTimeout(){return 1;}, clearTimeout(){},
    document:{
      getElementById(id){return elements.get(id) || null;},
      querySelectorAll(){return [];}, querySelector(){return null;},
      addEventListener(name,handler){documentHandlers[name]=handler;}
    },
    window:{addEventListener(){}}, fetch(){throw new Error('Unexpected request');}
  });
  const source = fs.readFileSync(path.join(directory, 'documents.js'), 'utf8').replace(/\ninitialize\(\);\s*$/, '\n');
  vm.runInContext(source, context);
  return {context, run:code=>vm.runInContext(code,context)};
}

test('Markdown preview escapes HTML and supports procedures, tables, code, and note links', () => {
  const app = documentsClient();
  app.run(`state.summary.notes=[{title:'Safety',path:'documents/opds/Safety.md'}]`);
  app.context.source = `---\ntitle: Example\n---\n# Deploy\n\n- [x] Review **plan**\n\n| Step | Owner |\n| --- | --- |\n| Test | QA |\n\nUse [[Safety]] and <img src=x onerror=alert(1)>.\n\n\`\`\`json\n{"ok":true}\n\`\`\``;
  const html = app.run('renderMarkdown(source)');
  assert.match(html, /<h1>Deploy<\/h1>/);
  assert.match(html, /task-item/);
  assert.match(html, /<table>/);
  assert.match(html, /data-note-path="documents\/opds\/Safety.md"/);
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /<pre><code>\{&quot;ok&quot;:true\}<\/code><\/pre>/);
});

test('OPD sidebar opens notes by Markdown path instead of internal ID', () => {
  const app = documentsClient();
  app.run("state.summary.notes=[{id:'note-uuid',title:'Untitled OPD',path:'documents/opds/Untitled OPD.md',category:'Procedure',tags:[]}]; renderSidebar()");
  assert.match(app.run("$('documents-list').innerHTML"), /data-open-id="documents\/opds\/Untitled OPD.md"/);
  assert.doesNotMatch(app.run("$('documents-list').innerHTML"), /data-open-id="note-uuid"/);
});

test('nested record values render as editable tables without flattening objects', () => {
  const app = documentsClient();
  app.context.value = {name:'Ada',address:{city:'London',geo:{lat:51.5}},roles:['admin','author'],active:false};
  const html = app.run("nestedEditor(value, ['profile'], 'object')");
  assert.match(html, /nested-edit-table/);
  assert.match(html, /city/);
  assert.match(html, /geo/);
  assert.match(html, /roles/);
  assert.match(html, /Add item/);
  assert.match(html, /value="false" selected/);
  assert.doesNotMatch(html, /\[object Object\]/);
});

test('catalog JSON serialization omits transport metadata', () => {
  const app = documentsClient();
  app.context.entity = {id:'1',name:'Person',path:'documents/catalogs/types/1.json',revision:'abc',resolvedFields:[],fields:[]};
  assert.deepEqual(JSON.parse(JSON.stringify(app.run('entityData(entity)'))), {id:'1',name:'Person',fields:[]});
});
