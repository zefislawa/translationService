let rows = [];
let searchQuery = "";
let selectedFile = "";
let targetLanguage = "";
let rowsPerPage = 10;
let currentPage = 1;
let editingRow = null;
let preferredTargetLanguage = "";
let preferredDisplayLanguage = "";
let compareDifferences = [];
let compareStatusFilter = 'ALL';
let compareSelectedKeys = new Set();
let originalRowsSnapshot = new Map();
let availableFiles = [];
let mergedPayload = null;
let translationProgressLogCount = 0;
let translationProgressState = null;
let activeTranslationAbortController = null;

const elements = {
  successMessage: document.getElementById('successMessage'),
  successMessageText: document.getElementById('successMessageText'),
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
  glossaryFileSelect: document.getElementById('glossaryFileSelect'),
  syncGlossaryBtn: document.getElementById('syncGlossaryBtn'),
  targetLanguageSelect: document.getElementById('targetLanguage'),
  translateBtn: document.getElementById('translateBtn'),
  translationForm: document.getElementById('translationForm'),
  newLabelBtn: document.getElementById('newLabelBtn'),
  exportDeltaBtn: document.getElementById('exportDeltaBtn'),
  valueDialog: document.getElementById('valueDialog'),
  valueDialogTitle: document.getElementById('valueDialogTitle'),
  valueDialogTextarea: document.getElementById('valueDialogTextarea'),
  closeValueDialog: document.getElementById('closeValueDialog'),
  cancelValueDialog: document.getElementById('cancelValueDialog'),
  saveValueDialog: document.getElementById('saveValueDialog'),
  labelPrefixInfoBtn: document.getElementById('labelPrefixInfoBtn'),
  labelPrefixDialog: document.getElementById('labelPrefixDialog'),
  closeLabelPrefixDialog: document.getElementById('closeLabelPrefixDialog'),
  compareFile1: document.getElementById('compareFile1'),
  compareFile2: document.getElementById('compareFile2'),
  compareStatusFilter: document.getElementById('compareStatusFilter'),
  compareBtn: document.getElementById('compareBtn'),
  compareTranslateImportBtn: document.getElementById('compareTranslateImportBtn'),
  compareResultContainer: document.getElementById('compareResultContainer'),
  compareSummary: document.getElementById('compareSummary'),
  compareResultBody: document.getElementById('compareResultBody'),
  selectAllCompareRows: document.getElementById('selectAllCompareRows'),
  tabButtons: document.querySelectorAll('.tab-button'),
  tabPanels: document.querySelectorAll('.tab-panel'),
  mergeDefaultFile: document.getElementById('mergeDefaultFile'),
  mergeDeltaFile: document.getElementById('mergeDeltaFile'),
  runDeepMergeBtn: document.getElementById('runDeepMergeBtn'),
  downloadMergedBtn: document.getElementById('downloadMergedBtn'),
  deepMergeOutput: document.getElementById('deepMergeOutput'),
  translationProgressDialog: document.getElementById('translationProgressDialog'),
  translationProgressTitle: document.getElementById('translationProgressTitle'),
  translationProgressStatusText: document.getElementById('translationProgressStatusText'),
  translationProgressEtaText: document.getElementById('translationProgressEtaText'),
  translationProgressBar: document.getElementById('translationProgressBar'),
  translationProgressLogs: document.getElementById('translationProgressLogs'),
  stopTranslationBtn: document.getElementById('stopTranslationBtn'),
  closeTranslationProgressBtn: document.getElementById('closeTranslationProgressBtn')
};

async function fetchFiles() {
  const res = await fetch('/api/translations/files');
  if (!res.ok) throw new Error(`Unable to list files (HTTP ${res.status})`);
  return res.json();
}

async function fetchUiConfig() {
  const res = await fetch('/api/config');
  if (!res.ok) throw new Error(`Unable to load UI config (HTTP ${res.status})`);
  return res.json();
}

async function fetchSupportedLanguages() {
  const res = await fetch('/api/translations/supported-languages');
  if (!res.ok) throw new Error(`Unable to load supported languages (HTTP ${res.status})`);
  return res.json();
}

async function fetchGlossaryFiles() {
  const res = await fetch('/api/translations/admin/glossary/files');
  if (!res.ok) throw new Error(`Unable to load glossary files (HTTP ${res.status})`);
  return res.json();
}

async function synchronizeGlossary(glossaryFilePath, sourceLanguage, targetLanguage) {
  const res = await fetch('/api/translations/admin/glossary/sync', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      glossaryFilePath,
      sourceLanguage,
      targetLanguage
    })
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(errorText || `Unable to synchronize glossary (HTTP ${res.status})`);
  }
  return res.json();
}

async function fetchRowsForFile(fileName) {
  const res = await fetch('/api/translations/load', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName })
  });

  if (!res.ok) throw new Error(`Unable to load ${fileName} for deep merge (HTTP ${res.status})`);
  return res.json();
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function deepMerge(defaultObject, deltaObject) {
  if (!isPlainObject(defaultObject) || !isPlainObject(deltaObject)) {
    return deltaObject === undefined ? defaultObject : deltaObject;
  }

  const merged = { ...defaultObject };
  Object.keys(deltaObject).forEach((key) => {
    const defaultValue = defaultObject[key];
    const deltaValue = deltaObject[key];
    merged[key] = deepMerge(defaultValue, deltaValue);
  });

  return merged;
}

function rowsToNestedObject(rowsList) {
  return (rowsList || []).reduce((payload, row) => {
    const section = row.section || '';
    const key = row.key || '';
    if (!section || !key) {
      return payload;
    }

    payload[section] = payload[section] || {};
    payload[section][key] = row.text || '';
    return payload;
  }, {});
}

function buildMergedFileName(defaultFileName) {
  const baseName = (defaultFileName || 'translations').replace(/\.json$/i, '');
  return `${baseName}-merged.json`;
}

function renderDeepMergeOutput(content) {
  elements.deepMergeOutput.textContent = content;
}

function populateDeepMergeFileOptions(files) {
  const defaultSelection = elements.mergeDefaultFile.value;
  const deltaSelection = elements.mergeDeltaFile.value;
  const defaultLanguageFile = files.find((fileName) => fileName.toLowerCase() === `${(preferredDisplayLanguage || '').toLowerCase()}.json`);
  const fallbackDefaultFile = defaultLanguageFile || files.find((fileName) => !fileName.toLowerCase().includes('-delta')) || files[0] || '';
  const fallbackDeltaFile = files.find((fileName) => fileName.toLowerCase().includes('-delta')) || files[0] || '';

  [elements.mergeDefaultFile, elements.mergeDeltaFile].forEach((select) => {
    select.innerHTML = '';
    files.forEach((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      select.appendChild(option);
    });
  });

  elements.mergeDefaultFile.value = files.includes(defaultSelection) ? defaultSelection : fallbackDefaultFile;
  elements.mergeDeltaFile.value = files.includes(deltaSelection) ? deltaSelection : fallbackDeltaFile;
}

async function runDeepMerge() {
  const defaultFileName = elements.mergeDefaultFile.value;
  const deltaFileName = elements.mergeDeltaFile.value;
  if (!defaultFileName || !deltaFileName) {
    throw new Error('Please select both default and delta files.');
  }

  const [defaultRows, deltaRows] = await Promise.all([
    fetchRowsForFile(defaultFileName),
    fetchRowsForFile(deltaFileName)
  ]);

  const defaultPayload = rowsToNestedObject(defaultRows);
  const deltaPayload = rowsToNestedObject(deltaRows);
  mergedPayload = deepMerge(defaultPayload, deltaPayload);
  renderDeepMergeOutput(JSON.stringify(mergedPayload, null, 2));
  showSuccessMessage(`Deep merge completed using ${defaultFileName} and ${deltaFileName}.`);
}

function handleDownloadMergedFile() {
  if (!mergedPayload) {
    alert('Run deep merge first.');
    return;
  }

  downloadJsonFile(buildMergedFileName(elements.mergeDefaultFile.value), mergedPayload);
}

function activateTab(tabTargetId) {
  elements.tabButtons.forEach((button) => {
    const isActive = button.dataset.tabTarget === tabTargetId;
    button.classList.toggle('active', isActive);
    button.setAttribute('aria-selected', String(isActive));
  });

  elements.tabPanels.forEach((panel) => {
    panel.classList.toggle('active', panel.id === tabTargetId);
  });
}

function renderSupportedLanguagesUnavailable() {
  elements.targetLanguageSelect.innerHTML = '';
  const option = document.createElement('option');
  option.value = '';
  option.textContent = 'Google supported languages unavailable';
  elements.targetLanguageSelect.appendChild(option);
  targetLanguage = '';
}

function renderSupportedLanguages(languages) {
  elements.targetLanguageSelect.innerHTML = '';

  (languages || []).forEach((language) => {
    const option = document.createElement('option');
    option.value = language.languageCode;
    option.textContent = `${language.displayName} (${language.languageCode})`;
    elements.targetLanguageSelect.appendChild(option);
  });

  const preferredLanguage = preferredTargetLanguage;
  const hasPreferredLanguage = (languages || []).some((language) => language.languageCode === preferredLanguage);
  targetLanguage = hasPreferredLanguage ? preferredLanguage : (languages[0]?.languageCode || '');
  if (targetLanguage) {
    elements.targetLanguageSelect.value = targetLanguage;
  }
}

function renderGlossaryFiles(files) {
  elements.glossaryFileSelect.innerHTML = '';
  if (!files || files.length === 0) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'No glossary files found';
    elements.glossaryFileSelect.appendChild(option);
    return;
  }

  files.forEach((fileName) => {
    const option = document.createElement('option');
    option.value = fileName;
    option.textContent = fileName;
    elements.glossaryFileSelect.appendChild(option);
  });
}

function detectSourceLanguageFromSelectedFile(fileName) {
  if (!fileName) {
    return '';
  }
  const normalized = fileName.replace(/\.json$/i, '').trim();
  return normalized || '';
}


async function compareFiles(file1, file2) {
  const res = await fetch('/api/translations/compare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName1: file1, fileName2: file2 })
  });

  if (!res.ok) throw new Error(`Unable to compare files (HTTP ${res.status})`);
  return res.json();
}

async function translateAndImportCompareRows(sourceFileName, targetFileName, rows) {
  const res = await fetch('/api/translations/compare/translate-import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sourceFileName,
      targetFileName,
      rows
    })
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(errorText || `Unable to translate and import compare rows (HTTP ${res.status})`);
  }

  return res.json();
}


function getFilteredCompareDifferences() {
  if (compareStatusFilter === 'ALL') {
    return compareDifferences;
  }

  return compareDifferences.filter((item) => (item.status || '') === compareStatusFilter);
}

function renderCompareStatusFilterOptions() {
  const statuses = [...new Set(compareDifferences.map((item) => item.status).filter(Boolean))].sort();
  const options = ['ALL', ...statuses];

  elements.compareStatusFilter.innerHTML = '';
  options.forEach((status) => {
    const option = document.createElement('option');
    option.value = status;
    option.textContent = status === 'ALL' ? 'All statuses' : status;
    elements.compareStatusFilter.appendChild(option);
  });

  if (!options.includes(compareStatusFilter)) {
    compareStatusFilter = 'ALL';
  }

  elements.compareStatusFilter.value = compareStatusFilter;
}

function renderCompareResult() {
  const differences = getFilteredCompareDifferences();
  const totalDifferences = compareDifferences.length;
  const filteredSuffix = compareStatusFilter === 'ALL'
    ? `${totalDifferences} difference(s)`
    : `${differences.length} of ${totalDifferences} difference(s)`;

  elements.compareResultContainer.classList.remove('hidden');
  elements.compareTranslateImportBtn.disabled = !['Missing in file 1', 'Missing in file 2'].includes(compareStatusFilter) || differences.length === 0;
  elements.compareSummary.textContent = `${elements.compareFile1.value} vs ${elements.compareFile2.value} (${filteredSuffix})`;

  elements.compareResultBody.innerHTML = '';

  if (differences.length === 0) {
    elements.selectAllCompareRows.checked = false;
    elements.compareResultBody.innerHTML = '<tr><td colspan="5">No differences found.</td></tr>';
    return;
  }

  const filteredKeySet = new Set(differences.map((item) => item.keyPath));
  compareSelectedKeys = new Set([...compareSelectedKeys].filter((keyPath) => filteredKeySet.has(keyPath)));

  const areAllSelected = differences.length > 0 && differences.every((item) => compareSelectedKeys.has(item.keyPath));
  elements.selectAllCompareRows.checked = areAllSelected;

  differences.forEach((item) => {
    const tr = document.createElement('tr');

    const checkboxTd = document.createElement('td');
    const rowCheckbox = document.createElement('input');
    rowCheckbox.type = 'checkbox';
    rowCheckbox.className = 'checkbox';
    rowCheckbox.checked = compareSelectedKeys.has(item.keyPath);
    rowCheckbox.addEventListener('change', (e) => {
      if (e.target.checked) {
        compareSelectedKeys.add(item.keyPath);
      } else {
        compareSelectedKeys.delete(item.keyPath);
      }

      const allSelected = differences.length > 0 && differences.every((entry) => compareSelectedKeys.has(entry.keyPath));
      elements.selectAllCompareRows.checked = allSelected;
    });
    checkboxTd.appendChild(rowCheckbox);
    tr.appendChild(checkboxTd);

    const keyPathTd = document.createElement('td');
    keyPathTd.textContent = item.keyPath || '';
    tr.appendChild(keyPathTd);

    const file1Td = document.createElement('td');
    file1Td.textContent = item.valueInFile1 || '';
    tr.appendChild(file1Td);

    const file2Td = document.createElement('td');
    file2Td.textContent = item.valueInFile2 || '';
    tr.appendChild(file2Td);

    const statusTd = document.createElement('td');
    statusTd.textContent = item.status || '';
    tr.appendChild(statusTd);

    elements.compareResultBody.appendChild(tr);
  });
}

function showCompareResult(result) {
  compareDifferences = result.differences || [];
  compareStatusFilter = 'ALL';
  compareSelectedKeys = new Set(compareDifferences.map((item) => item.keyPath));
  renderCompareStatusFilterOptions();
  renderCompareResult();
}

function buildRowIdentity(section, key) {
  return `${section || ''}.${key || ''}`;
}

function createRowSnapshot(row) {
  return {
    id: buildRowIdentity(row.section, row.column1),
    section: row.section || '',
    key: row.column1 || '',
    text: row.column2 || '',
    reference: row.reference || ''
  };
}

function rebuildOriginalRowsSnapshot() {
  originalRowsSnapshot = new Map(
    rows
      .filter((row) => !(row.isCustom === true || row.section === 'custom'))
      .map((row) => {
        const snapshot = createRowSnapshot(row);
        return [snapshot.id, snapshot];
      })
  );
}

function normalizeRowForDelta(row) {
  const isCustomRow = row.isCustom === true || row.section === 'custom';
  const rawKey = (row.column1 || '').trim();

  if (isCustomRow) {
    if (!rawKey) {
      return null;
    }

    const keyMatch = /^([^.]+)\.(.+)$/.exec(rawKey);
    if (!keyMatch) {
      throw new Error('New label key must include a section prefix, for example: b.newKey');
    }

    return {
      section: keyMatch[1].trim(),
      key: keyMatch[2].trim(),
      text: row.column2 || '',
      reference: row.reference || ''
    };
  }

  return {
    section: row.section || '',
    key: rawKey,
    text: row.column2 || '',
    reference: row.reference || ''
  };
}

function getDeltaRows() {
  return rows.reduce((deltaRows, row) => {
    const normalizedRow = normalizeRowForDelta(row);
    if (!normalizedRow) {
      return deltaRows;
    }

    const rowId = buildRowIdentity(normalizedRow.section, normalizedRow.key);
    const originalRow = originalRowsSnapshot.get(rowId);
    const currentSnapshot = {
      id: rowId,
      ...normalizedRow,
      changeType: originalRow ? 'Changed' : 'Created'
    };

    if (!originalRow || originalRow.text !== currentSnapshot.text) {
      deltaRows.push(currentSnapshot);
    }

    return deltaRows;
  }, []);
}

function buildDeltaExportPayload(deltaRows) {
  return deltaRows.reduce((payload, row) => {
    payload[row.section] = payload[row.section] || {};
    payload[row.section][row.key] = row.text;
    return payload;
  }, {});
}

function buildDeltaFileName() {
  const baseName = (selectedFile || 'translations.json').replace(/\.json$/i, '');
  return `${baseName}-delta.json`;
}

function downloadJsonFile(fileName, payload) {
  const json = JSON.stringify(payload, null, 2);
  const blob = new Blob([json], { type: 'application/json' });
  const objectUrl = URL.createObjectURL(blob);
  const downloadLink = document.createElement('a');
  downloadLink.href = objectUrl;
  downloadLink.download = fileName;
  document.body.appendChild(downloadLink);
  downloadLink.click();
  document.body.removeChild(downloadLink);
  URL.revokeObjectURL(objectUrl);
}

function handleExportDelta() {
  if (!selectedFile) {
    alert('Please choose and load a file first.');
    return;
  }

  if (elements.fileSelect.value !== selectedFile) {
    alert('Selected file changed. Click Select to load the chosen file before exporting.');
    return;
  }

  const deltaRows = getDeltaRows();
  if (deltaRows.length === 0) {
    alert('There are no changed or created rows to export.');
    return;
  }

  const deltaPayload = buildDeltaExportPayload(deltaRows);
  downloadJsonFile(buildDeltaFileName(), deltaPayload);
  showSuccessMessage(`Exported ${deltaRows.length} changed/created row(s) to JSON.`);
}

function splitKeyPath(keyPath) {
  const separatorIndex = (keyPath || '').indexOf('.');
  if (separatorIndex <= 0 || separatorIndex === keyPath.length - 1) {
    throw new Error(`Invalid key path: ${keyPath}`);
  }

  return {
    section: keyPath.slice(0, separatorIndex),
    key: keyPath.slice(separatorIndex + 1)
  };
}

async function handleCompareTranslateImport() {
  const supportedStatuses = ['Missing in file 1', 'Missing in file 2'];
  if (!supportedStatuses.includes(compareStatusFilter)) {
    alert('Translate and Import is available when status filter is Missing in file 1 or Missing in file 2.');
    return;
  }

  const selectedDifferences = getFilteredCompareDifferences().filter((item) => compareSelectedKeys.has(item.keyPath));
  if (selectedDifferences.length === 0) {
    alert('Please select at least one compare result row.');
    return;
  }

  const sourceValueField = compareStatusFilter === 'Missing in file 1' ? 'valueInFile2' : 'valueInFile1';
  const sourceFileName = compareStatusFilter === 'Missing in file 1'
    ? elements.compareFile2.value
    : elements.compareFile1.value;
  const targetFileName = compareStatusFilter === 'Missing in file 1'
    ? elements.compareFile1.value
    : elements.compareFile2.value;

  const rowsToTranslate = selectedDifferences.map((item) => {
    const { section, key } = splitKeyPath(item.keyPath || '');
    return {
      section,
      key,
      text: item[sourceValueField] || ''
    };
  });

  const result = await translateAndImportCompareRows(sourceFileName, targetFileName, rowsToTranslate);
  showSuccessMessage(`Translated and imported ${Number(result.textCount || 0)} row(s) into ${result.outputFileName}.`);

  const compareResult = await compareFiles(elements.compareFile1.value, elements.compareFile2.value);
  showCompareResult(compareResult);
}

async function loadRows() {
  selectedFile = elements.fileSelect.value;

  if (!selectedFile) {
    throw new Error("Please load and select a file first.");
  }

  const res = await fetch('/api/translations/load', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName: selectedFile })
  });

  if (!res.ok) throw new Error(`Unable to load file (HTTP ${res.status})`);

  const apiRows = await res.json();
  rows = (apiRows || []).map((r) => ({
    id: `${r.section}.${r.key}`,
    section: r.section || "",
    column1: r.key || "",
    column2: r.text || "",
    reference: r.englishReference || "",
    selected: true
  }));

  rebuildOriginalRowsSnapshot();
  currentPage = 1;
  renderTable();
  showSuccessMessage(`Loaded ${rows.length} rows from ${selectedFile}.`);
}

async function translateAndStore(targetLanguage, signal) {
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
    signal,
    body: JSON.stringify({
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
    row.reference.toLowerCase().includes(q) ||
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
    const isNewCustomRow = row.section === 'custom';

    if (isNewCustomRow) {
      const keyInput = document.createElement('input');
      keyInput.type = 'text';
      keyInput.className = 'cell-input key-cell-input';
      keyInput.placeholder = 'Enter key (example: b.newKey)';
      keyInput.value = row.column1;
      keyInput.addEventListener('input', (e) => {
        row.column1 = e.target.value;
      });
      keyTd.appendChild(keyInput);
    } else {
      const keyText = document.createElement('div');
      keyText.className = 'cell-content';
      keyText.textContent = row.column1;
      keyTd.appendChild(keyText);
    }

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
    expandBtn.setAttribute('aria-label', 'Expand value editor');
    expandBtn.innerHTML = `
      <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <polyline points="15 3 21 3 21 9"></polyline>
        <polyline points="9 21 3 21 3 15"></polyline>
        <line x1="21" y1="3" x2="14" y2="10"></line>
        <line x1="3" y1="21" x2="10" y2="14"></line>
      </svg>
    `;
    expandBtn.addEventListener('click', () => openValueDialog(row));

    valueInputContainer.appendChild(input);
    valueInputContainer.appendChild(expandBtn);
    valueTd.appendChild(valueInputContainer);
    tr.appendChild(valueTd);

    const referenceTd = document.createElement('td');
    referenceTd.className = 'reference-cell';
    referenceTd.textContent = row.reference;
    tr.appendChild(referenceTd);

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

function openLabelPrefixDialog() {
  elements.labelPrefixDialog.classList.add('show');
  elements.labelPrefixDialog.setAttribute('aria-hidden', 'false');
}

function closeLabelPrefixDialog() {
  elements.labelPrefixDialog.classList.remove('show');
  elements.labelPrefixDialog.setAttribute('aria-hidden', 'true');
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

function formatDuration(seconds) {
  const normalizedSeconds = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(normalizedSeconds / 60);
  const remainingSeconds = normalizedSeconds % 60;
  if (minutes <= 0) {
    return `${remainingSeconds}s`;
  }
  if (minutes < 60) {
    return `${minutes}m ${String(remainingSeconds).padStart(2, '0')}s`;
  }
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return `${hours}h ${String(remainingMinutes).padStart(2, '0')}m`;
}

function refreshTranslationEtaLabel() {
  if (!translationProgressState || !elements.translationProgressEtaText) {
    return;
  }

  if (translationProgressState.currentPercent >= 100) {
    elements.translationProgressEtaText.textContent = 'Estimated time remaining: Completed';
    return;
  }

  const now = Date.now();
  const elapsedSeconds = (now - translationProgressState.startTimeMs) / 1000;
  const effectiveRate = translationProgressState.currentPercent / Math.max(elapsedSeconds, 0.001);
  if (effectiveRate <= 0) {
    elements.translationProgressEtaText.textContent = 'Estimated time remaining: Calculating...';
    return;
  }

  const estimatedSecondsRemaining = (100 - translationProgressState.currentPercent) / effectiveRate;
  elements.translationProgressEtaText.textContent = `Estimated time remaining: ${formatDuration(estimatedSecondsRemaining)}`;
}

function openTranslationProgressDialog(title) {
  const now = Date.now();
  translationProgressLogCount = 0;
  translationProgressState = {
    startTimeMs: now,
    currentPercent: 0,
    intervalId: null
  };
  elements.translationProgressTitle.textContent = title;
  elements.translationProgressStatusText.textContent = 'Preparing...';
  elements.translationProgressEtaText.textContent = 'Estimated time remaining: Calculating...';
  elements.translationProgressBar.style.width = '0%';
  elements.translationProgressBar.parentElement.setAttribute('aria-valuenow', '0');
  elements.translationProgressLogs.innerHTML = '';
  elements.stopTranslationBtn.classList.remove('hidden');
  elements.stopTranslationBtn.disabled = false;
  elements.closeTranslationProgressBtn.classList.add('hidden');
  elements.translationProgressDialog.classList.add('show');
  elements.translationProgressDialog.setAttribute('aria-hidden', 'false');

  translationProgressState.intervalId = setInterval(() => {
    refreshTranslationEtaLabel();
  }, 1000);
}

function appendTranslationProgressLog(logLine) {
  translationProgressLogCount += 1;
  const li = document.createElement('li');
  li.textContent = `[${translationProgressLogCount}] ${logLine}`;
  elements.translationProgressLogs.appendChild(li);
  elements.translationProgressLogs.scrollTop = elements.translationProgressLogs.scrollHeight;
}

function updateTranslationProgress(percent, statusText, logLine) {
  const normalizedPercent = Math.max(0, Math.min(100, Math.round(percent)));
  if (translationProgressState) {
    translationProgressState.currentPercent = normalizedPercent;
  }
  elements.translationProgressStatusText.textContent = statusText;
  elements.translationProgressBar.style.width = `${normalizedPercent}%`;
  elements.translationProgressBar.parentElement.setAttribute('aria-valuenow', String(normalizedPercent));
  refreshTranslationEtaLabel();
  if (logLine) {
    appendTranslationProgressLog(logLine);
  }
}

function completeTranslationProgress(successText, logLine) {
  updateTranslationProgress(100, successText, logLine);
  if (translationProgressState && translationProgressState.intervalId) {
    clearInterval(translationProgressState.intervalId);
    translationProgressState.intervalId = null;
  }
  elements.stopTranslationBtn.classList.add('hidden');
  elements.closeTranslationProgressBtn.classList.remove('hidden');
}

function closeTranslationProgressDialog() {
  if (translationProgressState && translationProgressState.intervalId) {
    clearInterval(translationProgressState.intervalId);
  }
  translationProgressState = null;
  activeTranslationAbortController = null;
  elements.translationProgressDialog.classList.remove('show');
  elements.translationProgressDialog.setAttribute('aria-hidden', 'true');
}

async function handleLoadFiles() {
  const previouslySelectedFile = elements.fileSelect.value || selectedFile;

  try {
    const uiConfig = await fetchUiConfig();
    preferredTargetLanguage = (uiConfig.preferredTargetLanguage || '').trim();
    preferredDisplayLanguage = (uiConfig.referenceLanguageFile || uiConfig.displayLanguageCode || '').trim();
  } catch (error) {
    preferredTargetLanguage = '';
    preferredDisplayLanguage = '';
    console.warn(error);
  }

  const data = await fetchFiles();
  const files = data.files || [];
  availableFiles = files;
  try {
    const glossaryFileData = await fetchGlossaryFiles();
    renderGlossaryFiles(glossaryFileData.files || []);
  } catch (error) {
    console.warn(error);
    renderGlossaryFiles([]);
  }

  elements.fileSelect.innerHTML = '';
  files.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    elements.fileSelect.appendChild(option);
  });

  const normalizedPreferredDisplayLanguage = preferredDisplayLanguage.toLowerCase();
  const directLanguageFileMatch = files.find((fileName) =>
    fileName.toLowerCase() === `${normalizedPreferredDisplayLanguage}.json`
  );
  const fallbackLanguageFileMatch = normalizedPreferredDisplayLanguage.includes('-')
    ? files.find((fileName) => fileName.toLowerCase() === `${normalizedPreferredDisplayLanguage.split('-')[0]}.json`)
    : '';
  const selectedFileStillExists = files.includes(previouslySelectedFile);
  elements.fileSelect.value = selectedFileStillExists
    ? previouslySelectedFile
    : (directLanguageFileMatch || fallbackLanguageFileMatch || files[0] || '');

  [elements.compareFile1, elements.compareFile2].forEach((select, index) => {
    select.innerHTML = '';
    files.forEach((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      select.appendChild(option);
    });

    if (files.length > 1 && index === 1) {
      select.value = files[1];
    }
  });

  try {
    const supportedLanguages = await fetchSupportedLanguages();
    renderSupportedLanguages(supportedLanguages);
    showSuccessMessage(`Loaded ${files.length} files and ${supportedLanguages.length} Google supported languages.`);
  } catch (error) {
    console.warn(error);
    renderSupportedLanguagesUnavailable();
    showSuccessMessage(`Loaded ${files.length} files. Unable to load Google supported languages.`);
  }

  populateDeepMergeFileOptions(availableFiles);
}


async function handleCompare() {
  const file1 = elements.compareFile1.value;
  const file2 = elements.compareFile2.value;

  if (!file1 || !file2) {
    alert('Please select both files to compare.');
    return;
  }

  if (file1 === file2) {
    alert('Please select two different files.');
    return;
  }

  const result = await compareFiles(file1, file2);
  showCompareResult(result);
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
  if (elements.fileSelect.value !== selectedFile) {
    alert('Selected file changed. Click Select to load the chosen file before translating.');
    return;
  }

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

  openTranslationProgressDialog(`Translation and validation (${targetLanguage})`);
  updateTranslationProgress(8, 'Preparing selected rows...', `Preparing ${selectedRows.length} selected rows.`);
  updateTranslationProgress(25, 'Sending translation request...', `Calling translation service for ${selectedFile}.`);

  try {
    activeTranslationAbortController = new AbortController();
    const result = await translateAndStore(targetLanguage, activeTranslationAbortController.signal);
    updateTranslationProgress(78, 'Validating translated file...', 'Translation completed. Validating placeholders and risky terms.');
    await handleLoadFiles();
    completeTranslationProgress(
      `Completed successfully. ${Number(result.textCount || 0)} rows translated and validated.`,
      `Saved file ${result.outputFile}. Validation report generated next to the translated file.`
    );
    showSuccessMessage(
      `Translation saved for ${targetLanguage}. ` +
      `Successfully translated ${Number(result.textCount || 0)} labels/rows. File: ${result.outputFile}`
    );
  } catch (error) {
    if (error.name === 'AbortError') {
      updateTranslationProgress(100, 'Translation stopped.', 'Translation process was stopped by user.');
      elements.stopTranslationBtn.classList.add('hidden');
      elements.closeTranslationProgressBtn.classList.remove('hidden');
      return;
    }
    updateTranslationProgress(100, 'Translation failed. Please review the error and try again.', `Error: ${error.message}`);
    elements.stopTranslationBtn.classList.add('hidden');
    elements.closeTranslationProgressBtn.classList.remove('hidden');
    throw error;
  } finally {
    activeTranslationAbortController = null;
  }
}

async function handleSyncGlossary() {
  const glossaryFilePath = elements.glossaryFileSelect.value;
  if (!glossaryFilePath) {
    alert('Please select a glossary file.');
    return;
  }

  const sourceLanguage = detectSourceLanguageFromSelectedFile(selectedFile || elements.fileSelect.value);
  if (!sourceLanguage) {
    alert('Please select a translation file so the source language can be inferred.');
    return;
  }

  const selectedTargetLanguage = elements.targetLanguageSelect.value;
  if (!selectedTargetLanguage) {
    alert('Please select a target language before syncing glossary.');
    return;
  }

  const result = await synchronizeGlossary(glossaryFilePath, sourceLanguage, selectedTargetLanguage);
  showSuccessMessage(
    `Glossary synchronized for ${result.sourceLanguage} → ${result.targetLanguage}. Active glossary: ${result.glossary}`
  );
}

function stopTranslation() {
  if (!activeTranslationAbortController) {
    return;
  }
  elements.stopTranslationBtn.disabled = true;
  updateTranslationProgress(
    translationProgressState ? translationProgressState.currentPercent : 0,
    'Stopping translation...',
    'Stop requested. Cancelling translation request...'
  );
  activeTranslationAbortController.abort();
}

function handleAddNewLabel() {
  const newRow = {
    id: `new-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    section: 'custom',
    column1: '',
    column2: '',
    reference: '',
    isCustom: true,
    selected: true
  };
  rows.unshift(newRow);
  currentPage = 1;
  renderTable();
}

function normalizeRowsForSave() {
  const preparedRows = rows.map((row) => {
    const isCustomRow = row.isCustom === true || row.section === 'custom';
    if (!isCustomRow) {
      return {
        section: row.section,
        key: row.column1,
        text: row.column2,
        isCustom: false
      };
    }

    const rawKey = (row.column1 || '').trim();
    const keyMatch = /^([^.]+)\.(.+)$/.exec(rawKey);
    if (!keyMatch) {
      throw new Error('New label key must include a section prefix, for example: b.newKey');
    }

    return {
      section: keyMatch[1].trim(),
      key: keyMatch[2].trim(),
      text: row.column2,
      isCustom: true
    };
  });

  const existingRows = preparedRows.filter((row) => !row.isCustom);
  const customRows = preparedRows.filter((row) => row.isCustom);
  return [...existingRows, ...customRows].map(({ section, key, text }) => ({ section, key, text }));
}


async function handleSubmit(e) {
  e.preventDefault();

  if (elements.fileSelect.value !== selectedFile) {
    alert('Selected file changed. Click Select to load the chosen file before saving.');
    return;
  }

  if (!selectedFile) {
    alert('Please choose and load a file first.');
    return;
  }

  const payloadRows = normalizeRowsForSave();

  const res = await fetch('/api/translations/save', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      fileName: selectedFile,
      rows: payloadRows
    })
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(errorText || `Unable to save file (HTTP ${res.status})`);
  }

  await loadRows();
  showSuccessMessage(`Saved ${payloadRows.length} rows to ${selectedFile}.`);
}

elements.loadFilesBtn.addEventListener('click', () => handleLoadFiles().catch((e) => alert(e.message)));
elements.selectFileBtn.addEventListener('click', () => loadRows().catch((e) => alert(e.message)));
elements.searchInput.addEventListener('input', handleSearch);
elements.rowsPerPageSelect.addEventListener('change', handleRowsPerPageChange);
elements.newLabelBtn.addEventListener('click', handleAddNewLabel);
elements.exportDeltaBtn.addEventListener('click', handleExportDelta);
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
elements.syncGlossaryBtn.addEventListener('click', () => handleSyncGlossary().catch((e) => alert(e.message)));
elements.targetLanguageSelect.addEventListener('change', () => {
  targetLanguage = elements.targetLanguageSelect.value;
});
elements.translateBtn.addEventListener('click', () => handleTranslate().catch((e) => alert(e.message)));
elements.compareBtn.addEventListener('click', () => handleCompare().catch((e) => alert(e.message)));
elements.compareStatusFilter.addEventListener('change', () => {
  compareStatusFilter = elements.compareStatusFilter.value;
  renderCompareResult();
});
elements.selectAllCompareRows.addEventListener('change', (e) => {
  const differences = getFilteredCompareDifferences();
  if (e.target.checked) {
    differences.forEach((item) => compareSelectedKeys.add(item.keyPath));
  } else {
    differences.forEach((item) => compareSelectedKeys.delete(item.keyPath));
  }
  renderCompareResult();
});
elements.compareTranslateImportBtn.addEventListener('click', () => handleCompareTranslateImport().catch((e) => alert(e.message)));
elements.translationForm.addEventListener('submit', (e) => handleSubmit(e).catch((error) => alert(error.message)));
elements.tabButtons.forEach((button) => {
  button.addEventListener('click', () => activateTab(button.dataset.tabTarget));
});
elements.runDeepMergeBtn.addEventListener('click', () => runDeepMerge().catch((e) => alert(e.message)));
elements.downloadMergedBtn.addEventListener('click', handleDownloadMergedFile);
elements.closeValueDialog.addEventListener('click', closeValueDialog);
elements.cancelValueDialog.addEventListener('click', closeValueDialog);
elements.saveValueDialog.addEventListener('click', saveValueDialog);
elements.labelPrefixInfoBtn.addEventListener('click', openLabelPrefixDialog);
elements.closeLabelPrefixDialog.addEventListener('click', closeLabelPrefixDialog);
elements.stopTranslationBtn.addEventListener('click', stopTranslation);
elements.closeTranslationProgressBtn.addEventListener('click', closeTranslationProgressDialog);
elements.labelPrefixDialog.addEventListener('click', (e) => {
  if (e.target === elements.labelPrefixDialog) {
    closeLabelPrefixDialog();
  }
});
elements.valueDialog.addEventListener('click', (e) => {
  if (e.target === elements.valueDialog) {
    closeValueDialog();
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (elements.labelPrefixDialog.classList.contains('show')) {
      closeLabelPrefixDialog();
    } else if (elements.translationProgressDialog.classList.contains('show') && !elements.closeTranslationProgressBtn.classList.contains('hidden')) {
      closeTranslationProgressDialog();
    } else if (elements.valueDialog.classList.contains('show')) {
      closeValueDialog();
    }
  }
});

document.addEventListener('DOMContentLoaded', async () => {
  try {
    await handleLoadFiles();
    if (elements.fileSelect.value) {
      await loadRows();
    }
    if (availableFiles.length > 0) {
      await runDeepMerge();
    }
  } catch (e) {
    console.error(e);
  }
});
