// =========================
// Backend loading (Option A)
// =========================

function getBaseName(fileName) {
  // "en.json" -> "en", "testSource.json" -> "testSource"
  if (!fileName) return "";
  return fileName.replace(/\.json$/i, "");
}

async function loadTranslationRowsFromApi(fileName) {
  const base = getBaseName(fileName);
  if (!base) return [];

  const res = await fetch(`/api/translations/${encodeURIComponent(base)}`, {
    method: "GET",
    headers: { "Accept": "application/json" }
  });

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Failed to load translations for "${base}" (HTTP ${res.status}). ${text}`);
  }

  // expected: [{section, key, text}, ...]
  return await res.json();
}

function mapApiRowsToUiRows(apiRows) {
  // Map backend rows into your UI's table model
  // Key -> column1
  // Text (Editable) -> column2
  // Source text -> column3 (not available in your file; keep blank)
  return (apiRows || []).map((r, idx) => ({
    id: `${Date.now()}-${idx}`,           // stable enough for session UI; can use `${r.section}-${r.key}` too
    section: r.section ?? "",             // keep for later (optional)
    column1: r.key ?? "",
    column2: r.text ?? "",
    column3: ""                           // no source text in this JSON format (for now)
  }));
}

async function loadFileAndRender(fileName) {
  // UI feedback (optional)
  elements.selectFileBtn.disabled = true;
  elements.selectFileBtn.textContent = "Loading...";

  try {
    const apiRows = await loadTranslationRowsFromApi(fileName);
    rows = mapApiRowsToUiRows(apiRows);

    // reset selection + pagination
    selectedRows.clear();
    searchQuery = "";
    currentPage = 1;

    // clear search box UI if you want
    if (elements.searchInput) elements.searchInput.value = "";

    renderTable();
    showSuccessMessage();
  } finally {
    elements.selectFileBtn.disabled = false;
    elements.selectFileBtn.textContent = "Select File";
  }
}

// =========================
// State Management
// =========================

let rows = []; // start empty; will load from backend
let searchQuery = "";
let selectedFile = "en.json"; // default; will be overwritten by dropdown value if present
let targetLanguage = "fr";
let selectedRows = new Set();
let rowsPerPage = 10;
let currentPage = 1;
let currentEditingRow = null;

// Mock translations (kept as-is; you can remove later)
const mockTranslations = {
  es: {
    "Vous avez plus d'un service. Veuillez vous inscrire avec les détails de votre compte.":
      "Tiene más de un servicio. Por favor regístrese con los detalles de su cuenta.",
    "Factures et Paiements": "Facturas y Pagos"
  },
  fr: {
    "You have more than one service. Please register with your account details.":
      "Vous avez plus d'un service. Veuillez vous inscrire avec les détails de votre compte.",
    "Bills and Payments": "Factures et Paiements"
  },
  de: {
    "Vous avez plus d'un service. Veuillez vous inscrire avec les détails de votre compte.":
      "Sie haben mehr als einen Dienst. Bitte registrieren Sie sich mit Ihren Kontodaten.",
    "Factures et Paiements": "Rechnungen und Zahlungen"
  }
};

// =========================
// DOM Elements
// =========================

const elements = {
  successMessage: document.getElementById('successMessage'),
  fileSelect: document.getElementById('fileSelect'),
  selectFileBtn: document.getElementById('selectFileBtn'),
  searchInput: document.getElementById('searchInput'),
  rowsPerPageSelect: document.getElementById('rowsPerPage'),
  tableBody: document.getElementById('tableBody'),
  selectAllCheckbox: document.getElementById('selectAll'),
  addRowBtn: document.getElementById('addRowBtn'),
  prevBtn: document.getElementById('prevBtn'),
  nextBtn: document.getElementById('nextBtn'),
  pageInfo: document.getElementById('pageInfo'),
  targetLanguageSelect: document.getElementById('targetLanguage'),
  translateBtn: document.getElementById('translateBtn'),
  translationForm: document.getElementById('translationForm'),
  editDialog: document.getElementById('editDialog'),
  dialogTitle: document.getElementById('dialogTitle'),
  editTextarea: document.getElementById('editTextarea'),
  closeDialog: document.getElementById('closeDialog'),
  cancelEdit: document.getElementById('cancelEdit'),
  saveEdit: document.getElementById('saveEdit')
};

// =========================
// Filtering + pagination
// =========================

function getFilteredRows() {
  if (!searchQuery) return rows;

  const query = searchQuery.toLowerCase();
  return rows.filter(row =>
    (row.column1 || "").toLowerCase().includes(query) ||
    (row.column2 || "").toLowerCase().includes(query)
  );
}

function getPaginatedRows() {
  const filteredRows = getFilteredRows();
  const startIndex = (currentPage - 1) * rowsPerPage;
  const endIndex = startIndex + rowsPerPage;
  return filteredRows.slice(startIndex, endIndex);
}

function getTotalPages() {
  const filteredRows = getFilteredRows();
  return Math.ceil(filteredRows.length / rowsPerPage) || 1;
}

// =========================
// Render table
// =========================

function renderTable() {
  const paginatedRows = getPaginatedRows();
  const filteredRows = getFilteredRows();

  elements.tableBody.innerHTML = '';

  paginatedRows.forEach(row => {
    const tr = document.createElement('tr');

    // Checkbox cell
    const checkboxTd = document.createElement('td');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'checkbox';
    checkbox.checked = selectedRows.has(row.id);
    checkbox.setAttribute('aria-label', `Select row ${row.column1}`);
    checkbox.addEventListener('change', () => toggleRowSelection(row.id));
    checkboxTd.appendChild(checkbox);
    tr.appendChild(checkboxTd);

    // Key cell
    const keyTd = document.createElement('td');
    const keyDiv = document.createElement('div');
    keyDiv.className = 'cell-content';
    keyDiv.textContent = row.column1;
    keyTd.appendChild(keyDiv);
    tr.appendChild(keyTd);

    // Text (Editable) cell
    const textTd = document.createElement('td');
    textTd.className = 'col-text';
    const textContainer = document.createElement('div');
    textContainer.className = 'cell-input-container';

    const textInput = document.createElement('input');
    textInput.type = 'text';
    textInput.className = 'cell-input';
    textInput.value = row.column2 || "";
    textInput.placeholder = 'Enter value';
    textInput.addEventListener('input', (e) => updateRow(row.id, 'column2', e.target.value));
    textContainer.appendChild(textInput);

    // Expand button
    if (row.column2) {
      const expandBtn = document.createElement('button');
      expandBtn.type = 'button';
      expandBtn.className = 'btn-icon';
      expandBtn.innerHTML = `
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M15 3h6v6"></path>
          <path d="M9 21H3v-6"></path>
          <path d="M21 3l-7 7"></path>
          <path d="M3 21l7-7"></path>
        </svg>
      `;
      expandBtn.addEventListener('click', () => openEditDialog(row));
      textContainer.appendChild(expandBtn);
    }

    textTd.appendChild(textContainer);
    tr.appendChild(textTd);

    // Source Text cell (left blank in this JSON format)
    const sourceTd = document.createElement('td');
    const sourceDiv = document.createElement('div');
    sourceDiv.className = 'cell-content';
    sourceDiv.textContent = row.column3 || "";
    sourceTd.appendChild(sourceDiv);
    tr.appendChild(sourceTd);

    elements.tableBody.appendChild(tr);
  });

  // Update select all checkbox
  elements.selectAllCheckbox.checked =
    selectedRows.size === filteredRows.length && filteredRows.length > 0;

  updatePagination();
}

function updatePagination() {
  const totalPages = getTotalPages();
  elements.pageInfo.textContent = `Page ${currentPage} of ${totalPages}`;
  elements.prevBtn.disabled = currentPage === 1;
  elements.nextBtn.disabled = currentPage === totalPages;
}

// =========================
// Selection
// =========================

function toggleRowSelection(rowId) {
  if (selectedRows.has(rowId)) {
    selectedRows.delete(rowId);
  } else {
    selectedRows.add(rowId);
  }
  renderTable();
}

function toggleAllRows() {
  const filteredRows = getFilteredRows();

  if (selectedRows.size === filteredRows.length) {
    selectedRows.clear();
  } else {
    selectedRows = new Set(filteredRows.map(row => row.id));
  }
  renderTable();
}

// =========================
// Row operations
// =========================

function updateRow(id, column, value) {
  const rowIndex = rows.findIndex(row => row.id === id);
  if (rowIndex !== -1) {
    rows[rowIndex][column] = value;
    renderTable();
  }
}

function addRow() {
  const newRow = {
    id: Date.now().toString(),
    section: "",
    column1: "",
    column2: "",
    column3: ""
  };
  rows.push(newRow);
  renderTable();
}

// =========================
// Edit dialog
// =========================

function openEditDialog(row) {
  currentEditingRow = row;
  elements.dialogTitle.textContent = `Edit Text - ${row.column1}`;
  elements.editTextarea.value = row.column2 || "";
  elements.editDialog.classList.add('show');
}

function closeEditDialog() {
  currentEditingRow = null;
  elements.editDialog.classList.remove('show');
}

function saveEdit() {
  if (currentEditingRow) {
    updateRow(currentEditingRow.id, 'column2', elements.editTextarea.value);
  }
  closeEditDialog();
}

// =========================
// UI event handlers
// =========================

async function handleFileSelect() {
  selectedFile = elements.fileSelect.value;
  await loadFileAndRender(selectedFile);
}

function handleSearch() {
  searchQuery = elements.searchInput.value || "";
  currentPage = 1;
  renderTable();
}

function handleRowsPerPageChange() {
  rowsPerPage = parseInt(elements.rowsPerPageSelect.value, 10);
  currentPage = 1;
  renderTable();
}

function goToPreviousPage() {
  if (currentPage > 1) {
    currentPage--;
    renderTable();
  }
}

function goToNextPage() {
  const totalPages = getTotalPages();
  if (currentPage < totalPages) {
    currentPage++;
    renderTable();
  }
}

// Mock translate (kept)
function handleTranslate() {
  if (selectedRows.size === 0) {
    alert("Please select at least one row to translate");
    return;
  }

  targetLanguage = elements.targetLanguageSelect.value;

  rows = rows.map(row => {
    if (selectedRows.has(row.id) && row.column2 && mockTranslations[targetLanguage]?.[row.column2]) {
      return { ...row, column2: mockTranslations[targetLanguage][row.column2] };
    }
    return row;
  });

  showSuccessMessage();
  renderTable();
}

function showSuccessMessage() {
  if (!elements.successMessage) return;
  elements.successMessage.classList.remove('hidden');
  setTimeout(() => {
    elements.successMessage.classList.add('hidden');
  }, 5000);
}

function handleSubmit(e) {
  e.preventDefault();
  console.log("Form data:", rows);
  alert("Form submitted! Check console for data.");
}

// =========================
// Event listeners
// =========================

elements.fileSelect.addEventListener('change', () => { handleFileSelect().catch(err => alert(err.message)); });
elements.selectFileBtn.addEventListener('click', () => { handleFileSelect().catch(err => alert(err.message)); });
elements.searchInput.addEventListener('input', handleSearch);
elements.rowsPerPageSelect.addEventListener('change', handleRowsPerPageChange);
elements.selectAllCheckbox.addEventListener('change', toggleAllRows);
elements.addRowBtn.addEventListener('click', addRow);
elements.prevBtn.addEventListener('click', goToPreviousPage);
elements.nextBtn.addEventListener('click', goToNextPage);
elements.translateBtn.addEventListener('click', handleTranslate);
elements.translationForm.addEventListener('submit', handleSubmit);

// Dialog event listeners
elements.closeDialog.addEventListener('click', closeEditDialog);
elements.cancelEdit.addEventListener('click', closeEditDialog);
elements.saveEdit.addEventListener('click', saveEdit);

// Close dialog when clicking outside
elements.editDialog.addEventListener('click', (e) => {
  if (e.target === elements.editDialog) {
    closeEditDialog();
  }
});

// Close dialog with Escape key
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && elements.editDialog.classList.contains('show')) {
    closeEditDialog();
  }
});

// =========================
// Initial load
// =========================

document.addEventListener("DOMContentLoaded", async () => {
  // If dropdown has a value, use it; otherwise keep default "en.json"
  if (elements.fileSelect && elements.fileSelect.value) {
    selectedFile = elements.fileSelect.value;
  }

  try {
    await loadFileAndRender(selectedFile);
  } catch (e) {
    console.error(e);
    alert(e.message);
    // Still render empty table so UI doesn't look broken
    renderTable();
  }
});
