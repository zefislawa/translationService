let rows = [];
let searchQuery = "";
let selectedFile = "";
let targetLanguage = "fr";
let rowsPerPage = 10;
let currentPage = 1;

const elements = {
  successMessage: document.getElementById('successMessage'),
  successMessageText: document.getElementById('successMessageText'),
  pathInput: document.getElementById('pathInput'),
  loadFilesBtn: document.getElementById('loadFilesBtn'),
  fileSelect: document.getElementById('fileSelect'),
  selectFileBtn: document.getElementById('selectFileBtn'),
  searchInput: document.getElementById('searchInput'),
  rowsPerPageSelect: document.getElementById('rowsPerPage'),
  tableBody: document.getElementById('tableBody'),
  prevBtn: document.getElementById('prevBtn'),
  nextBtn: document.getElementById('nextBtn'),
  pageInfo: document.getElementById('pageInfo'),
  targetLanguageSelect: document.getElementById('targetLanguage'),
  translateBtn: document.getElementById('translateBtn'),
  translationForm: document.getElementById('translationForm')
};

function getPathValue() {
  return (elements.pathInput.value || "").trim();
}

function getBaseName(fileName) {
  if (!fileName) return "";
  return fileName.replace(/\.json$/i, "");
}

async function fetchFiles() {
  const path = getPathValue();

  // Preferred API: POST with JSON body (handles Windows paths safely).
  const res = await fetch('/api/translations/files', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path })
  });

  if (res.ok) {
    return res.json();
  }

  // Backward-compatible fallback for older backend versions.
  if (res.status === 404 || res.status === 405) {
    return { path, files: [] };
  }

  const text = await res.text().catch(() => '');
  throw new Error(`Unable to list files (HTTP ${res.status}). ${text}`);
}
async function loadRows() {
  const path = getPathValue();
  selectedFile = elements.fileSelect.value;

  if (!selectedFile) {
    throw new Error("Please load and select a file first.");
  }

  // Preferred API
  let res = await fetch('/api/translations/load', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, fileName: selectedFile })
  });

  // Backward-compatible fallback for older backend versions.
  if (res.status === 404 || res.status === 405) {
    const base = getBaseName(selectedFile);
    res = await fetch(`/api/translations/${encodeURIComponent(base)}`, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    });
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Unable to load file (HTTP ${res.status}). ${text}`);
  }

  const apiRows = await res.json();
  rows = (apiRows || []).map((r) => ({
    id: `${r.section}.${r.key}`,
    section: r.section || "",
    column1: r.key || "",
    column2: r.text || ""
  }));

  currentPage = 1;
  renderTable();
  showSuccessMessage(`Loaded ${rows.length} rows from ${selectedFile}.`);
}
async function exportTranslatePayload() {
  const path = getPathValue();
  targetLanguage = elements.targetLanguageSelect.value;

  const payloadRows = rows.map((r) => ({
    section: r.section,
    key: r.column1,
    text: r.column2
  }));

  const res = await fetch('/api/translations/translate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      path,
      fileName: selectedFile,
      targetLanguage,
      rows: payloadRows
    })
  });

  if (!res.ok) throw new Error(`Failed to generate translation payload (HTTP ${res.status})`);
  return res.json();
}

function getFilteredRows() {
  if (!searchQuery) return rows;
  const q = searchQuery.toLowerCase();
  return rows.filter((row) =>
    row.column1.toLowerCase().includes(q) ||
    row.column2.toLowerCase().includes(q) ||
    row.section.toLowerCase().includes(q)
  );
}

function getTotalPages() {
  return Math.max(1, Math.ceil(getFilteredRows().length / rowsPerPage));
}

function getPaginatedRows() {
  const filteredRows = getFilteredRows();
  const start = (currentPage - 1) * rowsPerPage;
  return filteredRows.slice(start, start + rowsPerPage);
}

function renderTable() {
  const paginatedRows = getPaginatedRows();
  elements.tableBody.innerHTML = '';

  paginatedRows.forEach((row) => {
    const tr = document.createElement('tr');

    const keyTd = document.createElement('td');
    keyTd.textContent = row.column1;
    tr.appendChild(keyTd);

    const valueTd = document.createElement('td');
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'cell-input';
    input.value = row.column2;
    input.addEventListener('input', (e) => {
      row.column2 = e.target.value;
    });
    valueTd.appendChild(input);
    tr.appendChild(valueTd);

    const sectionTd = document.createElement('td');
    sectionTd.textContent = row.section;
    tr.appendChild(sectionTd);

    elements.tableBody.appendChild(tr);
  });

  const totalPages = getTotalPages();
  elements.pageInfo.textContent = `Page ${currentPage} of ${totalPages}`;
  elements.prevBtn.disabled = currentPage <= 1;
  elements.nextBtn.disabled = currentPage >= totalPages;
}

function showSuccessMessage(message) {
  if (!elements.successMessage) return;
  elements.successMessageText.textContent = message;
  elements.successMessage.classList.remove('hidden');
  setTimeout(() => elements.successMessage.classList.add('hidden'), 5000);
}

async function handleLoadFiles() {
  const data = await fetchFiles();
  const files = data.files || [];

  elements.fileSelect.innerHTML = '';

  if (files.length === 0) {
    const empty = document.createElement('option');
    empty.value = '';
    empty.textContent = 'No .json files found';
    elements.fileSelect.appendChild(empty);
    selectedFile = '';
    showSuccessMessage('No JSON files returned by API for this path. You can still choose an existing filename and click Select.');
    return;
  }

  files.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    elements.fileSelect.appendChild(option);
  });

  selectedFile = files[0] || "";
  showSuccessMessage(`Loaded ${files.length} files from directory.`);
}

function handleRowsPerPageChange() {
  rowsPerPage = parseInt(elements.rowsPerPageSelect.value, 10);
  currentPage = 1;
  renderTable();
}

function handleSearch() {
  searchQuery = elements.searchInput.value || "";
  currentPage = 1;
  renderTable();
}

async function handleTranslate() {
  if (!selectedFile) {
    alert('Please choose and load a file first.');
    return;
  }

  const result = await exportTranslatePayload();
  showSuccessMessage(`Google V2 payload created: ${result.outputFile}`);
}

function handleSubmit(e) {
  e.preventDefault();
  showSuccessMessage('Edits are in memory. Click Translate to export JSON payload.');
}

elements.loadFilesBtn.addEventListener('click', () => handleLoadFiles().catch((e) => {
  console.error(e);
  alert(e.message);
  showSuccessMessage(`Error: ${e.message}`);
}));
elements.selectFileBtn.addEventListener('click', () => loadRows().catch((e) => alert(e.message)));
elements.searchInput.addEventListener('input', handleSearch);
elements.rowsPerPageSelect.addEventListener('change', handleRowsPerPageChange);
elements.prevBtn.addEventListener('click', () => {
  if (currentPage > 1) {
    currentPage -= 1;
    renderTable();
  }
});
elements.nextBtn.addEventListener('click', () => {
  if (currentPage < getTotalPages()) {
    currentPage += 1;
    renderTable();
  }
});
elements.targetLanguageSelect.addEventListener('change', () => {
  targetLanguage = elements.targetLanguageSelect.value;
});
elements.translateBtn.addEventListener('click', () => handleTranslate().catch((e) => alert(e.message)));
elements.translationForm.addEventListener('submit', handleSubmit);

document.addEventListener('DOMContentLoaded', async () => {
  try {
    await handleLoadFiles();

    if (!elements.fileSelect.value && elements.fileSelect.options.length > 0) {
      elements.fileSelect.selectedIndex = 0;
    }

    if (elements.fileSelect.value) {
      await loadRows();
    }
  } catch (e) {
    console.error(e);
  }
});
