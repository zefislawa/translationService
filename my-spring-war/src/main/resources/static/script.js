let rows = [];
let searchQuery = "";
let selectedFile = "";
let targetLanguage = "";
let rowsPerPage = 10;
let currentPage = 1;
let editingRow = null;

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
  selectAllRows: document.getElementById('selectAllRows'),
  targetLanguageSelect: document.getElementById('targetLanguage'),
  translateBtn: document.getElementById('translateBtn'),
  translationForm: document.getElementById('translationForm'),
  newLabelBtn: document.getElementById('newLabelBtn'),
  valueDialog: document.getElementById('valueDialog'),
  valueDialogTitle: document.getElementById('valueDialogTitle'),
  valueDialogTextarea: document.getElementById('valueDialogTextarea'),
  closeValueDialog: document.getElementById('closeValueDialog'),
  cancelValueDialog: document.getElementById('cancelValueDialog'),
  saveValueDialog: document.getElementById('saveValueDialog')
};

function getPathValue() {
  return (elements.pathInput.value || "").trim();
}

async function fetchFiles() {
  const path = getPathValue();
  const query = path ? `?path=${encodeURIComponent(path)}` : "";
  const res = await fetch(`/api/translations/files${query}`);
  if (!res.ok) throw new Error(`Unable to list files (HTTP ${res.status})`);
  return res.json();
}

async function fetchSupportedLanguages() {
  const res = await fetch('/api/translations/supported-languages');
  if (!res.ok) throw new Error(`Unable to load supported languages (HTTP ${res.status})`);
  return res.json();
}

function renderSupportedLanguages(languages) {
  elements.targetLanguageSelect.innerHTML = '';

  (languages || []).forEach((language) => {
    const option = document.createElement('option');
    option.value = language.languageCode;
    option.textContent = `${language.displayName} (${language.languageCode})`;
    elements.targetLanguageSelect.appendChild(option);
  });

  const preferredLanguage = 'fr';
  const hasPreferredLanguage = (languages || []).some((language) => language.languageCode === preferredLanguage);
  targetLanguage = hasPreferredLanguage ? preferredLanguage : (languages[0]?.languageCode || '');
  if (targetLanguage) {
    elements.targetLanguageSelect.value = targetLanguage;
  }
}

async function loadRows() {
  const path = getPathValue();
  selectedFile = elements.fileSelect.value;

  if (!selectedFile) {
    throw new Error("Please load and select a file first.");
  }

  const res = await fetch('/api/translations/load', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, fileName: selectedFile })
  });

  if (!res.ok) throw new Error(`Unable to load file (HTTP ${res.status})`);

  const apiRows = await res.json();
  rows = (apiRows || []).map((r) => ({
    id: `${r.section}.${r.key}`,
    section: r.section || "",
    column1: r.key || "",
    column2: r.text || "",
    englishReference: r.englishReference || "",
    selected: true
  }));

  currentPage = 1;
  renderTable();
  showSuccessMessage(`Loaded ${rows.length} rows from ${selectedFile}.`);
}

async function translateAndStore(targetLanguage) {
  const path = getPathValue();

  const payloadRows = rows
    .filter((r) => r.selected !== false)
    .map((r) => ({
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

  if (!res.ok) throw new Error(`Failed to translate text via Google API (HTTP ${res.status})`);
  return res.json();
}

function getFilteredRows() {
  if (!searchQuery) return rows;
  const q = searchQuery.toLowerCase();
  return rows.filter((row) =>
    row.column1.toLowerCase().includes(q) ||
    row.column2.toLowerCase().includes(q) ||
    row.section.toLowerCase().includes(q) ||
    row.englishReference.toLowerCase().includes(q)
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
  const areAllRowsSelected = rows.length > 0 && rows.every((row) => row.selected !== false);
  elements.selectAllRows.checked = areAllRowsSelected;

  paginatedRows.forEach((row) => {
    const tr = document.createElement('tr');

    const checkboxTd = document.createElement('td');
    const rowCheckbox = document.createElement('input');
    rowCheckbox.type = 'checkbox';
    rowCheckbox.className = 'checkbox';
    rowCheckbox.checked = row.selected !== false;
    rowCheckbox.addEventListener('change', (e) => {
      row.selected = e.target.checked;
      const allRowsSelected = rows.length > 0 && rows.every((item) => item.selected !== false);
      elements.selectAllRows.checked = allRowsSelected;
    });
    checkboxTd.appendChild(rowCheckbox);
    tr.appendChild(checkboxTd);

    const keyTd = document.createElement('td');
    const keyInput = document.createElement('input');
    keyInput.type = 'text';
    keyInput.className = 'cell-input';
    keyInput.value = row.column1;
    keyInput.addEventListener('input', (e) => {
      row.column1 = e.target.value;
    });
    keyTd.appendChild(keyInput);
    tr.appendChild(keyTd);

    const valueTd = document.createElement('td');
    const valueInputContainer = document.createElement('div');
    valueInputContainer.className = 'cell-input-container';

    const input = document.createElement('textarea');
    input.className = 'cell-input cell-textarea';
    input.rows = 1;
    input.value = row.column2;
    input.addEventListener('input', (e) => {
      row.column2 = e.target.value;
    });

    const expandBtn = document.createElement('button');
    expandBtn.type = 'button';
    expandBtn.className = 'btn-icon expand-icon-btn';
    expandBtn.setAttribute('aria-label', `Expand value for ${row.column1}`);
    expandBtn.innerHTML = `
      <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="15 3 21 3 21 9"></polyline>
        <polyline points="9 21 3 21 3 15"></polyline>
        <line x1="21" y1="3" x2="14" y2="10"></line>
        <line x1="3" y1="21" x2="10" y2="14"></line>
      </svg>`;
    expandBtn.addEventListener('click', () => openValueDialog(row));

    valueInputContainer.appendChild(input);
    valueInputContainer.appendChild(expandBtn);
    valueTd.appendChild(valueInputContainer);
    tr.appendChild(valueTd);

    const sectionTd = document.createElement('td');
    sectionTd.textContent = row.englishReference;
    tr.appendChild(sectionTd);

    const actionsTd = document.createElement('td');
    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-outline btn-sm';
    removeBtn.textContent = 'Delete';
    removeBtn.addEventListener('click', () => {
      rows = rows.filter((item) => item.id !== row.id);
      const totalPagesAfterDelete = getTotalPages();
      if (currentPage > totalPagesAfterDelete) {
        currentPage = totalPagesAfterDelete;
      }
      renderTable();
    });
    actionsTd.appendChild(removeBtn);
    tr.appendChild(actionsTd);

    elements.tableBody.appendChild(tr);
  });

  const totalPages = getTotalPages();
  elements.pageInfo.textContent = `Page ${currentPage} of ${totalPages}`;
  elements.prevBtn.disabled = currentPage <= 1;
  elements.nextBtn.disabled = currentPage >= totalPages;
}

function openValueDialog(row) {
  editingRow = row;
  elements.valueDialogTitle.textContent = `Edit value for ${row.column1 || 'new label'}`;
  elements.valueDialogTextarea.value = row.column2 || '';
  elements.valueDialog.classList.add('show');
  elements.valueDialog.setAttribute('aria-hidden', 'false');
  elements.valueDialogTextarea.focus();
}

function closeValueDialog() {
  editingRow = null;
  elements.valueDialog.classList.remove('show');
  elements.valueDialog.setAttribute('aria-hidden', 'true');
}

function saveValueDialog() {
  if (editingRow) {
    editingRow.column2 = elements.valueDialogTextarea.value;
    renderTable();
  }
  closeValueDialog();
}

function handleSelectAllRows(e) {
  const isChecked = e.target.checked;
  rows.forEach((row) => {
    row.selected = isChecked;
  });
  renderTable();
}

function showSuccessMessage(message) {
  if (!elements.successMessage) return;
  elements.successMessageText.textContent = message;
  elements.successMessage.classList.remove('hidden');
  setTimeout(() => elements.successMessage.classList.add('hidden'), 5000);
}

async function handleLoadFiles() {
  const supportedLanguages = await fetchSupportedLanguages();
  renderSupportedLanguages(supportedLanguages);

  const data = await fetchFiles();
  const files = data.files || [];

  elements.fileSelect.innerHTML = '';
  files.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    elements.fileSelect.appendChild(option);
  });

  selectedFile = files[0] || "";
  showSuccessMessage(`Loaded ${files.length} files and ${supportedLanguages.length} supported languages.`);
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

  targetLanguage = elements.targetLanguageSelect.value;
  if (!targetLanguage) {
    alert('Please select a target language.');
    return;
  }

  const selectedRows = rows.filter((row) => row.selected !== false);
  if (selectedRows.length === 0) {
    alert('Please select at least one label row to translate.');
    return;
  }

  const result = await translateAndStore(targetLanguage);

  await handleLoadFiles();
  showSuccessMessage(
    `Translation saved for ${targetLanguage}. ` +
    `Successfully translated ${Number(result.textCount || 0)} labels/rows. File: ${result.outputFile}`
  );
}

function handleAddNewLabel() {
  const newRow = {
    id: `new-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    section: 'custom',
    column1: '',
    column2: '',
    englishReference: '',
    selected: true
  };
  rows.unshift(newRow);
  currentPage = 1;
  renderTable();
}


function handleSubmit(e) {
  e.preventDefault();
  showSuccessMessage('Edits are in memory. Click Translate to call Google and save translated JSON.');
}

elements.loadFilesBtn.addEventListener('click', () => handleLoadFiles().catch((e) => alert(e.message)));
elements.selectFileBtn.addEventListener('click', () => loadRows().catch((e) => alert(e.message)));
elements.searchInput.addEventListener('input', handleSearch);
elements.rowsPerPageSelect.addEventListener('change', handleRowsPerPageChange);
elements.newLabelBtn.addEventListener('click', handleAddNewLabel);
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
elements.selectAllRows.addEventListener('change', handleSelectAllRows);
elements.targetLanguageSelect.addEventListener('change', () => {
  targetLanguage = elements.targetLanguageSelect.value;
});
elements.translateBtn.addEventListener('click', () => handleTranslate().catch((e) => alert(e.message)));
elements.translationForm.addEventListener('submit', handleSubmit);
elements.closeValueDialog.addEventListener('click', closeValueDialog);
elements.cancelValueDialog.addEventListener('click', closeValueDialog);
elements.saveValueDialog.addEventListener('click', saveValueDialog);
elements.valueDialog.addEventListener('click', (e) => {
  if (e.target === elements.valueDialog) {
    closeValueDialog();
  }
});

document.addEventListener('DOMContentLoaded', async () => {
  try {
    await handleLoadFiles();
    if (elements.fileSelect.value) {
      await loadRows();
    }
  } catch (e) {
    console.error(e);
  }
});
