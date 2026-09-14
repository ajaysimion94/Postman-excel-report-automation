'use strict';

const $ = id => document.getElementById(id);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));

let currentMode = 'files';
let currentResults = null;
let wsToken = '';

async function api(path, options = {}) {
    const headers = {...(options.headers || {}), 'X-Workspace-Token': wsToken};
    const response = await fetch(path, {...options, headers});
    if (!response.ok) {
        const contentType = response.headers.get("content-type");
        if (contentType && contentType.includes("application/json")) {
            const data = await response.json();
            throw new Error(data.error || `Request failed (${response.status})`);
        }
        throw new Error(`Request failed (${response.status})`);
    }
    const contentType = response.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
        return await response.json();
    }
    return await response.blob();
}

document.addEventListener('DOMContentLoaded', async () => {
    try {
        const session = await fetch('/api/session').then(r => r.json());
        wsToken = session.token;
    } catch { /* token stays empty for GET requests */ }

    $('global-search').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            performSearch();
        }
    });
    
    $('search-mode').addEventListener('change', (e) => {
        currentMode = e.target.value;
        updateSidebarFilters();
        performSearch();
    });

    $('export-btn').addEventListener('click', exportExcel);
    updateSidebarFilters();
});

function updateSidebarFilters() {
    const container = $('sidebar-filters');
    if (currentMode === 'files') {
        container.innerHTML = `
            <div class="filter-group">
                <label>File Type</label>
                <select id="file-type-filter">
                    <option value="all">All</option>
                    <option value="collection">Collections</option>
                    <option value="filter">Filters</option>
                    <option value="query">Queries</option>
                    <option value="report">Reports</option>
                </select>
            </div>
            <div class="filter-group">
                <label>Search In</label>
                <select id="file-scope-filter">
                    <option value="all">Name & Content</option>
                    <option value="name">Name only</option>
                    <option value="content">Content only</option>
                </select>
            </div>
        `;
    } else if (currentMode === 'queries') {
        container.innerHTML = `
            <div class="filter-group">
                <p class="sidebar-note">Execute ad-hoc queries against collection requests.</p>
            </div>
        `;
    } else if (currentMode === 'documents') {
        container.innerHTML = `
            <div class="filter-group">
                <p class="sidebar-note">Search titles, metadata, and Markdown content in operational procedure documents.</p>
            </div>
        `;
    } else if (currentMode === 'catalogs') {
        container.innerHTML = `
            <div class="filter-group">
                <p class="sidebar-note">Search reusable catalog types and the records created from them.</p>
            </div>
        `;
    }
}

async function performSearch() {
    const query = $('global-search').value.trim();
    const container = $('search-results-container');
    $('search-title').textContent = 
        currentMode === 'files' ? 'Workspace Files' :
        currentMode === 'queries' ? 'Queries' :
        currentMode === 'documents' ? 'Operational Procedure Documents' : 'Catalog types & records';
        
    container.innerHTML = '<p class="loading muted">Searching...</p>';
    $('export-btn').disabled = true;
    currentResults = null;

    try {
        if (currentMode === 'files') {
            const type = $('file-type-filter') ? $('file-type-filter').value : 'all';
            const scope = $('file-scope-filter') ? $('file-scope-filter').value : 'all';
            const res = await api(`/api/search?q=${encodeURIComponent(query)}&type=${type}&in=${scope}`);
            renderFiles(res);
        } else if (currentMode === 'queries') {
            const res = await api('/api/search/query', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({query})
            });
            renderQueries(res);
        } else if (currentMode === 'documents') {
            const res = await api(`/api/search/documents?q=${encodeURIComponent(query)}`);
            renderDocuments(res);
        } else if (currentMode === 'catalogs') {
            const res = await api(`/api/search/catalogs?q=${encodeURIComponent(query)}`);
            renderCatalogs(res);
        }
    } catch (e) {
        container.innerHTML = `<div class="error-state"><h2>Error</h2><p>${escapeHtml(e.message)}</p></div>`;
    }
}

function renderFiles(res) {
    const container = $('search-results-container');
    if (!res.results || res.results.length === 0) {
        container.innerHTML = '<div class="empty-state"><h2>No files found</h2></div>';
        return;
    }

    let html = `<div class="file-results-list" role="list" aria-label="Workspace files">
        <div class="file-results-header" aria-hidden="true">
            <span>Name</span><span>Location</span><span>Type</span>
        </div>`;
    for (const f of res.results) {
        const type = String(f.type || 'file');
        const icon = type === 'collection' ? '{ }' : type === 'report' ? '▤' : type === 'query' ? '⌕' : 'ƒ';
        html += `<article class="file-result-row" role="listitem">
            <div class="file-result-name"><span class="file-result-icon ${escapeHtml(type)}" aria-hidden="true">${icon}</span><strong title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</strong></div>
            <div class="file-result-path" title="${escapeHtml(f.path)}">${escapeHtml(f.path)}</div>
            <span class="file-type-badge">${escapeHtml(type)}</span>`;
        if (f.highlights && f.highlights.length > 0) {
            html += `<div class="search-highlights">`;
            for (const h of f.highlights) {
                html += `<div class="search-snippet"><span class="line-num">L${h.line}</span> <code>${escapeHtml(h.snippet)}</code></div>`;
            }
            html += `</div>`;
        }
        html += `</article>`;
    }
    html += '</div>';
    container.innerHTML = html;
}

function renderQueries(res) {
    currentResults = res;
    const container = $('search-results-container');
    if (!res.rows || res.rows.length === 0) {
        container.innerHTML = '<div class="empty-state"><h2>No results</h2></div>';
        return;
    }
    
    $('export-btn').disabled = false;
    
    let html = `<div class="table-container"><table class="search-table"><thead><tr>`;
    for (const col of res.columns) {
        html += `<th>${escapeHtml(col)}</th>`;
    }
    html += `</tr></thead><tbody>`;
    for (const row of res.rows) {
        html += `<tr>`;
        for (const col of res.columns) {
            html += `<td>${escapeHtml(row[col])}</td>`;
        }
        html += `</tr>`;
    }
    html += `</tbody></table></div>`;
    container.innerHTML = html;
}

function renderDocuments(res) {
    const container = $('search-results-container');
    if (!res.documents || res.documents.length === 0) {
        container.innerHTML = '<div class="empty-state"><h2>No documents found</h2></div>';
        return;
    }
    
    let html = '<div class="docs-grid">';
    for (const doc of res.documents) {
        html += `<a class="doc-card" href="/documents?open=${encodeURIComponent(doc.path)}">
            <div class="doc-header">
                <h3>${escapeHtml(doc.title)}</h3>
                <span class="doc-badge">${escapeHtml(doc.category)}</span>
            </div>
            <div class="doc-path">${escapeHtml(doc.id)}</div>
            <div class="doc-snippet">${escapeHtml(doc.snippet)}</div>
        </a>`;
    }
    html += '</div>';
    container.innerHTML = html;
}

function renderCatalogs(res) {
    const container = $('search-results-container');
    const types = res.types || [];
    const records = res.records || [];
    const typeName = id => types.find(type => type.id === id)?.name || id || 'Unassigned';
    if (!types.length && !records.length) {
        container.innerHTML = '<div class="empty-state"><h2>No catalogs found</h2></div>';
        return;
    }
    let html = '<div class="catalogs-tree">';
    if (types.length) html += `<div class="catalog-node"><div class="catalog-title">Types</div><div class="catalog-children">${types.map(type => `<a class="catalog-entry" href="/documents?open=${encodeURIComponent(type.id)}"><div class="entry-title">{ } ${escapeHtml(type.name)}</div><div class="entry-props"><span class="prop-badge">${escapeHtml(type.kind)}</span><span class="prop-badge">${(type.fields || []).length} fields</span></div></a>`).join('')}</div></div>`;
    if (records.length) html += `<div class="catalog-node"><div class="catalog-title">Records</div><div class="catalog-children">${records.map(record => `<a class="catalog-entry" href="/documents?open=${encodeURIComponent(record.id)}"><div class="entry-title">▦ ${escapeHtml(record.name)}</div><div class="entry-props"><span class="prop-badge">${escapeHtml(typeName(record.typeId))}</span></div></a>`).join('')}</div></div>`;
    html += '</div>';
    container.innerHTML = html;
}

async function exportExcel() {
    if (!currentResults || !currentResults.rows) return;
    try {
        const blob = await api('/api/search/export', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                columns: currentResults.columns,
                rows: currentResults.rows
            })
        });
        
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `search-export-${Date.now()}.xlsx`;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        a.remove();
    } catch (e) {
        alert("Export failed: " + e.message);
    }
}
