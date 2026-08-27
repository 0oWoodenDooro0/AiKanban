/**
 * AiKanban - Modern Interactive Web Kanban Dashboard
 */

(() => {
  'use strict';

  // ==========================================================================
  // Application State
  // ==========================================================================
  const state = {
    columns: [],
    tasks: [],
    selectedTaskId: null,
    taskLogs: [],
    filters: {
      search: '',
      priority: '',
      assignee: '',
      tag: '',
    },
    operator: localStorage.getItem('aikanban_operator') || 'web-user',
    isEditingDesc: false,
    sseSource: null,
  };

  // ==========================================================================
  // API Service Helper
  // ==========================================================================
  const api = {
    async fetchJson(url, options = {}) {
      const defaultHeaders = {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      };
      const config = {
        ...options,
        headers: {
          ...defaultHeaders,
          ...options.headers,
        },
      };

      if (config.body && typeof config.body === 'object') {
        config.body = JSON.stringify(config.body);
      }

      const response = await fetch(url, config);
      if (!response.ok) {
        let errorMsg = `HTTP Error ${response.status}: ${response.statusText}`;
        try {
          const errData = await response.json();
          if (errData && errData.error) {
            errorMsg = errData.error;
          }
        } catch (_) {
          // ignore
        }
        throw new Error(errorMsg);
      }

      if (response.status === 204) {
        return null;
      }
      return await response.json();
    },

    // Column endpoints
    getColumns() {
      return this.fetchJson('/api/columns');
    },
    createColumn(data) {
      return this.fetchJson('/api/columns', { method: 'POST', body: data });
    },
    updateColumn(id, data) {
      return this.fetchJson(`/api/columns/${encodeURIComponent(id)}`, { method: 'PUT', body: data });
    },
    deleteColumn(id) {
      return this.fetchJson(`/api/columns/${encodeURIComponent(id)}`, { method: 'DELETE' });
    },

    // Task endpoints
    getTasks() {
      return this.fetchJson('/api/tasks');
    },
    getTask(id) {
      return this.fetchJson(`/api/tasks/${id}`);
    },
    createTask(data) {
      return this.fetchJson('/api/tasks', { method: 'POST', body: data });
    },
    updateTask(id, data) {
      return this.fetchJson(`/api/tasks/${id}`, { method: 'PUT', body: data });
    },
    deleteTask(id) {
      return this.fetchJson(`/api/tasks/${id}`, { method: 'DELETE' });
    },
    moveTask(id, data) {
      return this.fetchJson(`/api/tasks/${id}/move`, { method: 'POST', body: data });
    },
    claimTask(data) {
      return this.fetchJson('/api/tasks/claim', { method: 'POST', body: data });
    },
    releaseTask(id, data) {
      return this.fetchJson(`/api/tasks/${id}/release`, { method: 'POST', body: data });
    },
    getTaskLogs(id) {
      return this.fetchJson(`/api/tasks/${id}/logs`);
    },
    addComment(id, data) {
      return this.fetchJson(`/api/tasks/${id}/logs`, { method: 'POST', body: data });
    },
  };

  // ==========================================================================
  // Markdown & Utility Helpers
  // ==========================================================================

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function renderMarkdown(md) {
    if (!md || !md.trim()) {
      return '<p class="text-muted"><em>No description provided.</em></p>';
    }

    let html = escapeHtml(md);

    // Fenced code blocks
    html = html.replace(/```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
      return `<pre><code class="language-${lang}">${code.trim()}</code></pre>`;
    });

    // Inline code
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

    // Headers
    html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>');
    html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>');
    html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>');

    // Blockquotes
    html = html.replace(/^\> (.*$)/gim, '<blockquote>$1</blockquote>');

    // Bold & Italic
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');

    // Unordered lists
    html = html.replace(/^\s*[-*]\s+(.*$)/gim, '<li>$1</li>');
    html = html.replace(/(<li>.*<\/li>)/gims, '<ul>$1</ul>');

    // Links [text](url)
    html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

    // Line breaks into paragraphs
    const paragraphs = html.split(/\n{2,}/).map(p => {
      p = p.trim();
      if (!p) return '';
      if (p.startsWith('<h') || p.startsWith('<pre') || p.startsWith('<ul>') || p.startsWith('<blockquote>')) {
        return p;
      }
      return `<p>${p.replace(/\n/g, '<br>')}</p>`;
    });

    return paragraphs.join('\n');
  }

  function formatTimeAgo(isoString) {
    if (!isoString) return '';
    try {
      const date = new Date(isoString);
      const now = new Date();
      const seconds = Math.floor((now - date) / 1000);
      if (seconds < 60) return 'just now';
      const minutes = Math.floor(seconds / 60);
      if (minutes < 60) return `${minutes}m ago`;
      const hours = Math.floor(minutes / 60);
      if (hours < 24) return `${hours}h ago`;
      const days = Math.floor(hours / 24);
      if (days < 30) return `${days}d ago`;
      return date.toLocaleDateString();
    } catch (_) {
      return isoString;
    }
  }

  function formatDateTime(isoString) {
    if (!isoString) return '';
    try {
      const date = new Date(isoString);
      return date.toLocaleString();
    } catch (_) {
      return isoString;
    }
  }

  function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(10px)';
      toast.style.transition = 'all 0.3s ease';
      setTimeout(() => toast.remove(), 300);
    }, 3500);
  }

  // ==========================================================================
  // Real-time Server-Sent Events (SSE)
  // ==========================================================================

  function updateSseStatus(status, text) {
    const badge = document.getElementById('sse-status');
    const textEl = document.getElementById('sse-status-text');
    if (!badge || !textEl) return;

    badge.className = `status-badge status-${status}`;
    textEl.textContent = text;
  }

  function setupSseConnection() {
    if (state.sseSource) {
      state.sseSource.close();
    }

    try {
      const source = new EventSource('/api/events');
      state.sseSource = source;

      source.onopen = () => {
        updateSseStatus('connected', 'Live');
      };

      source.onerror = () => {
        updateSseStatus('reconnecting', 'Reconnecting');
      };

      // Listen for named event types emitted by KanbanApi
      const handleServerEvent = (e) => {
        try {
          if (!e.data) return;
          const data = JSON.parse(e.data);
          handleEventPayload(data);
        } catch (err) {
          console.error('Failed to parse SSE event data:', err, e.data);
        }
      };

      source.onmessage = handleServerEvent;
      source.addEventListener('TaskCreated', handleServerEvent);
      source.addEventListener('TaskMoved', handleServerEvent);
      source.addEventListener('TaskUpdated', handleServerEvent);
      source.addEventListener('TaskDeleted', handleServerEvent);
      source.addEventListener('TaskClaimed', handleServerEvent);
      source.addEventListener('TaskReleased', handleServerEvent);
      source.addEventListener('TaskCommentAdded', handleServerEvent);
      source.addEventListener('ColumnCreated', handleServerEvent);
      source.addEventListener('ColumnUpdated', handleServerEvent);
      source.addEventListener('ColumnDeleted', handleServerEvent);
    } catch (err) {
      console.error('SSE initialization error:', err);
      updateSseStatus('disconnected', 'Offline');
    }
  }

  function handleEventPayload(event) {
    const type = event.type || event['@type'] || (event.constructor && event.constructor.name);

    if (type === 'TaskCreated') {
      const task = event.task;
      if (task) {
        const idx = state.tasks.findIndex(t => t.id === task.id);
        if (idx >= 0) {
          state.tasks[idx] = task;
        } else {
          state.tasks.push(task);
        }
        renderBoard();
        updateFilterOptions();
      }
    } else if (type === 'TaskMoved') {
      const task = event.task;
      if (task) {
        const idx = state.tasks.findIndex(t => t.id === task.id);
        if (idx >= 0) {
          state.tasks[idx] = task;
        } else {
          state.tasks.push(task);
        }
        renderBoard();
        if (state.selectedTaskId === task.id) {
          updateDrawerStatus(task.status);
          refreshDrawerLogs(task.id);
        }
      }
    } else if (type === 'TaskUpdated' || type === 'TaskClaimed' || type === 'TaskReleased') {
      const task = event.task;
      if (task) {
        const idx = state.tasks.findIndex(t => t.id === task.id);
        if (idx >= 0) {
          state.tasks[idx] = task;
        } else {
          state.tasks.push(task);
        }
        renderBoard();
        updateFilterOptions();
        if (state.selectedTaskId === task.id) {
          populateDrawer(task);
          refreshDrawerLogs(task.id);
        }
      }
    } else if (type === 'TaskDeleted') {
      const taskId = event.taskId;
      state.tasks = state.tasks.filter(t => t.id !== taskId);
      renderBoard();
      updateFilterOptions();
      if (state.selectedTaskId === taskId) {
        closeDrawer();
        showToast(`Task #${taskId} was deleted remotely`, 'info');
      }
    } else if (type === 'TaskCommentAdded') {
      const taskId = event.taskId;
      if (state.selectedTaskId === taskId) {
        refreshDrawerLogs(taskId);
      }
      // Re-fetch task to update logs count
      api.getTask(taskId).then(task => {
        const idx = state.tasks.findIndex(t => t.id === task.id);
        if (idx >= 0) {
          state.tasks[idx] = task;
          renderBoard();
        }
      }).catch(() => {});
    } else if (type === 'ColumnCreated' || type === 'ColumnUpdated' || type === 'ColumnDeleted') {
      loadInitialData();
    }
  }

  // ==========================================================================
  // Board & Card Rendering
  // ==========================================================================

  function getFilteredTasks() {
    const query = state.filters.search.trim().toLowerCase();
    const priority = state.filters.priority;
    const assignee = state.filters.assignee;
    const tag = state.filters.tag;

    return state.tasks.filter(task => {
      if (priority && task.priority !== priority) return false;
      if (assignee) {
        if (assignee === '__unassigned__' && task.assignee) return false;
        if (assignee !== '__unassigned__' && task.assignee !== assignee) return false;
      }
      if (tag && (!task.tags || !task.tags.includes(tag))) return false;

      if (query) {
        const idMatch = `#${task.id}`.includes(query) || `${task.id}` === query;
        const titleMatch = task.title && task.title.toLowerCase().includes(query);
        const descMatch = task.description && task.description.toLowerCase().includes(query);
        const tagMatch = task.tags && task.tags.some(t => t.toLowerCase().includes(query));
        const assigneeMatch = task.assignee && task.assignee.toLowerCase().includes(query);
        if (!idMatch && !titleMatch && !descMatch && !tagMatch && !assigneeMatch) {
          return false;
        }
      }

      return true;
    });
  }

  function renderBoard() {
    const boardEl = document.getElementById('board');
    if (!boardEl) return;

    boardEl.innerHTML = '';
    const filteredTasks = getFilteredTasks();

    // Sort columns by order
    const sortedColumns = [...state.columns].sort((a, b) => a.order - b.order);

    sortedColumns.forEach(column => {
      const colTasks = filteredTasks.filter(t => t.status === column.id);

      const colEl = document.createElement('div');
      colEl.className = 'column';
      colEl.dataset.columnId = column.id;

      // Header
      const headerEl = document.createElement('div');
      headerEl.className = 'column-header';
      headerEl.innerHTML = `
        <div class="column-title-group">
          <span class="column-color-indicator" style="background-color: ${escapeHtml(column.color || '#6366f1')}"></span>
          <span class="column-title" title="${escapeHtml(column.name)}">${escapeHtml(column.name)}</span>
          <span class="column-count">${colTasks.length}</span>
        </div>
        <div class="column-actions">
          <button class="column-btn-add" data-column-id="${escapeHtml(column.id)}" title="Add Task in ${escapeHtml(column.name)}">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
        </div>
      `;

      headerEl.querySelector('.column-btn-add').addEventListener('click', (e) => {
        e.stopPropagation();
        openNewTaskModal(column.id);
      });

      // Cards List (Drop target)
      const cardsListEl = document.createElement('div');
      cardsListEl.className = 'column-cards';
      cardsListEl.dataset.columnId = column.id;

      // Drag and drop event listeners on column
      setupColumnDropTarget(cardsListEl, column.id);

      if (colTasks.length === 0) {
        const emptyEl = document.createElement('div');
        emptyEl.className = 'empty-column-msg';
        emptyEl.textContent = 'No tasks in this column';
        cardsListEl.appendChild(emptyEl);
      } else {
        colTasks.forEach(task => {
          const cardEl = createCardElement(task);
          cardsListEl.appendChild(cardEl);
        });
      }

      colEl.appendChild(headerEl);
      colEl.appendChild(cardsListEl);
      boardEl.appendChild(colEl);
    });
  }

  function createCardElement(task) {
    const cardEl = document.createElement('div');
    cardEl.className = 'task-card';
    cardEl.draggable = true;
    cardEl.dataset.taskId = task.id;

    const priorityClass = `priority-${(task.priority || 'medium').toLowerCase()}`;

    let tagsHtml = '';
    if (task.tags && task.tags.length > 0) {
      tagsHtml = `
        <div class="card-tags">
          ${task.tags.map(t => `<span class="tag-badge">#${escapeHtml(t)}</span>`).join('')}
        </div>
      `;
    }

    let githubHtml = '';
    if (task.githubIssueUrl || task.githubPrUrl) {
      githubHtml = '<div class="card-github-links">';
      if (task.githubIssueUrl) {
        githubHtml += `
          <a href="${escapeHtml(task.githubIssueUrl)}" target="_blank" rel="noopener noreferrer" class="github-badge" title="GitHub Issue" onclick="event.stopPropagation();">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"></circle><circle cx="12" cy="12" r="4"></circle></svg>
            Issue
          </a>
        `;
      }
      if (task.githubPrUrl) {
        githubHtml += `
          <a href="${escapeHtml(task.githubPrUrl)}" target="_blank" rel="noopener noreferrer" class="github-badge" title="GitHub PR" onclick="event.stopPropagation();">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><polyline points="6 9 12 15 18 9"></polyline></svg>
            PR
          </a>
        `;
      }
      githubHtml += '</div>';
    }

    const assigneeIcon = (task.assignee && (task.assignee.includes('agent') || task.assignee.includes('bot'))) ? '🤖' : '👤';
    const assigneeText = task.assignee ? `${assigneeIcon} ${escapeHtml(task.assignee)}` : '';

    cardEl.innerHTML = `
      <div class="card-top">
        <span class="card-id-badge">#${task.id}</span>
        <span class="card-priority-badge ${priorityClass}">${escapeHtml(task.priority || 'MEDIUM')}</span>
      </div>
      <div class="card-title">${escapeHtml(task.title)}</div>
      ${tagsHtml}
      ${githubHtml}
      <div class="card-footer">
        <div class="card-assignee">${assigneeText}</div>
        <div class="card-meta-right">
          <span class="card-time">${formatTimeAgo(task.updatedAt || task.createdAt)}</span>
        </div>
      </div>
    `;

    // Click to open detail drawer
    cardEl.addEventListener('click', () => {
      openTaskDrawer(task.id);
    });

    // Drag and drop handlers on card
    cardEl.addEventListener('dragstart', (e) => {
      e.dataTransfer.setData('text/plain', String(task.id));
      e.dataTransfer.effectAllowed = 'move';
      cardEl.classList.add('dragging');
    });

    cardEl.addEventListener('dragend', () => {
      cardEl.classList.remove('dragging');
      document.querySelectorAll('.column-cards').forEach(col => col.classList.remove('drag-over'));
    });

    return cardEl;
  }

  function setupColumnDropTarget(columnCardsEl, columnId) {
    columnCardsEl.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      columnCardsEl.classList.add('drag-over');
    });

    columnCardsEl.addEventListener('dragleave', (e) => {
      if (e.relatedTarget && columnCardsEl.contains(e.relatedTarget)) {
        return;
      }
      columnCardsEl.classList.remove('drag-over');
    });

    columnCardsEl.addEventListener('drop', async (e) => {
      e.preventDefault();
      columnCardsEl.classList.remove('drag-over');

      const taskIdStr = e.dataTransfer.getData('text/plain');
      const taskId = parseInt(taskIdStr, 10);
      if (!taskId) return;

      const task = state.tasks.find(t => t.id === taskId);
      if (!task || task.status === columnId) return;

      const oldStatus = task.status;
      // Optimistic update
      task.status = columnId;
      renderBoard();

      try {
        await api.moveTask(taskId, {
          toStatus: columnId,
          operator: state.operator,
          comment: `Moved card from ${oldStatus} to ${columnId} via Web UI`,
        });
        showToast(`Moved #${taskId} to ${columnId}`, 'success');
      } catch (err) {
        console.error('Failed to move task:', err);
        task.status = oldStatus;
        renderBoard();
        showToast(`Failed to move task: ${err.message}`, 'error');
      }
    });
  }

  // ==========================================================================
  // Task Detail Drawer Controller
  // ==========================================================================

  function populateDrawer(task) {
    document.getElementById('drawer-task-id').textContent = `#${task.id}`;
    document.getElementById('drawer-title').value = task.title || '';

    // Status dropdown
    const statusSelect = document.getElementById('drawer-status-select');
    statusSelect.innerHTML = '';
    state.columns.forEach(col => {
      const opt = document.createElement('option');
      opt.value = col.id;
      opt.textContent = col.name;
      if (col.id === task.status) opt.selected = true;
      statusSelect.appendChild(opt);
    });

    // Priority dropdown
    const prioritySelect = document.getElementById('drawer-priority-select');
    prioritySelect.value = task.priority || 'MEDIUM';

    // Description
    const descEditor = document.getElementById('drawer-desc-editor');
    const descPreview = document.getElementById('drawer-desc-preview');
    descEditor.value = task.description || '';
    descPreview.innerHTML = renderMarkdown(task.description || '');

    // Metadata fields
    document.getElementById('drawer-assignee').value = task.assignee || '';
    document.getElementById('drawer-tags').value = task.tags ? task.tags.join(', ') : '';
    document.getElementById('drawer-github-repo').value = task.githubRepo || '';

    const issueInput = document.getElementById('drawer-github-issue');
    const issueLink = document.getElementById('link-github-issue');
    issueInput.value = task.githubIssueUrl || '';
    if (task.githubIssueUrl) {
      issueLink.href = task.githubIssueUrl;
      issueLink.style.display = 'inline-flex';
    } else {
      issueLink.style.display = 'none';
    }

    const prInput = document.getElementById('drawer-github-pr');
    const prLink = document.getElementById('link-github-pr');
    prInput.value = task.githubPrUrl || '';
    if (task.githubPrUrl) {
      prLink.href = task.githubPrUrl;
      prLink.style.display = 'inline-flex';
    } else {
      prLink.style.display = 'none';
    }

    document.getElementById('drawer-save-status').textContent = '';
  }

  function updateDrawerStatus(statusId) {
    const statusSelect = document.getElementById('drawer-status-select');
    if (statusSelect) {
      statusSelect.value = statusId;
    }
  }

  async function openTaskDrawer(taskId) {
    state.selectedTaskId = taskId;
    const task = state.tasks.find(t => t.id === taskId);
    if (!task) return;

    populateDrawer(task);
    setDescMode('preview');

    const backdrop = document.getElementById('task-drawer-backdrop');
    backdrop.classList.add('open');

    await refreshDrawerLogs(taskId);
  }

  function closeDrawer() {
    state.selectedTaskId = null;
    const backdrop = document.getElementById('task-drawer-backdrop');
    backdrop.classList.remove('open');
  }

  function setDescMode(mode) {
    const previewEl = document.getElementById('drawer-desc-preview');
    const editorEl = document.getElementById('drawer-desc-editor');
    const btnPreview = document.getElementById('btn-desc-preview');
    const btnEdit = document.getElementById('btn-desc-edit');

    if (mode === 'edit') {
      previewEl.style.display = 'none';
      editorEl.style.display = 'block';
      btnEdit.classList.add('active');
      btnPreview.classList.remove('active');
      editorEl.focus();
    } else {
      previewEl.innerHTML = renderMarkdown(editorEl.value);
      previewEl.style.display = 'block';
      editorEl.style.display = 'none';
      btnPreview.classList.add('active');
      btnEdit.classList.remove('active');
    }
  }

  async function refreshDrawerLogs(taskId) {
    const timelineEl = document.getElementById('drawer-logs-timeline');
    const countEl = document.getElementById('logs-count');
    if (!timelineEl) return;

    try {
      const logs = await api.getTaskLogs(taskId);
      state.taskLogs = logs;
      countEl.textContent = logs.length;

      if (logs.length === 0) {
        timelineEl.innerHTML = '<p class="text-muted" style="font-size: 0.8rem; padding: 8px 0;">No log entries recorded yet.</p>';
        return;
      }

      // Render logs descending
      const sortedLogs = [...logs].reverse();
      timelineEl.innerHTML = sortedLogs.map(log => {
        let metaHtml = '';
        if (log.commitHash || log.prUrl) {
          metaHtml = '<div class="log-meta-links">';
          if (log.commitHash) {
            metaHtml += `<span>Commit: <code>${escapeHtml(log.commitHash.substring(0, 7))}</code></span>`;
          }
          if (log.prUrl) {
            metaHtml += `<a href="${escapeHtml(log.prUrl)}" target="_blank" rel="noopener noreferrer">PR Link ↗</a>`;
          }
          metaHtml += '</div>';
        }

        return `
          <div class="log-item">
            <div class="log-header">
              <span class="log-action-badge">${escapeHtml(log.action)}</span>
              <span class="log-operator">${escapeHtml(log.operator || 'system')}</span>
              <span class="log-time">${formatDateTime(log.timestamp)}</span>
            </div>
            <div class="log-content">${escapeHtml(log.details || '')}</div>
            ${metaHtml}
          </div>
        `;
      }).join('');
    } catch (err) {
      console.error('Failed to load logs:', err);
      timelineEl.innerHTML = `<p class="text-muted" style="color: var(--priority-critical)">Failed to load logs: ${escapeHtml(err.message)}</p>`;
    }
  }

  async function saveDrawerChanges() {
    if (!state.selectedTaskId) return;

    const title = document.getElementById('drawer-title').value.trim();
    const desc = document.getElementById('drawer-desc-editor').value;
    const priority = document.getElementById('drawer-priority-select').value;
    const assignee = document.getElementById('drawer-assignee').value.trim();
    const tagsStr = document.getElementById('drawer-tags').value.trim();
    const tags = tagsStr ? tagsStr.split(',').map(t => t.trim()).filter(Boolean) : [];
    const githubRepo = document.getElementById('drawer-github-repo').value.trim() || null;
    const githubIssueUrl = document.getElementById('drawer-github-issue').value.trim() || null;
    const githubPrUrl = document.getElementById('drawer-github-pr').value.trim() || null;

    if (!title) {
      showToast('Task title cannot be empty', 'error');
      return;
    }

    const saveStatus = document.getElementById('drawer-save-status');
    saveStatus.textContent = 'Saving...';

    try {
      const updated = await api.updateTask(state.selectedTaskId, {
        title,
        description: desc,
        priority,
        assignee: assignee || null,
        tags,
        githubRepo,
        githubIssueUrl,
        githubPrUrl,
        operator: state.operator,
      });

      const idx = state.tasks.findIndex(t => t.id === state.selectedTaskId);
      if (idx >= 0) {
        state.tasks[idx] = updated;
      }
      renderBoard();
      populateDrawer(updated);
      saveStatus.textContent = 'Saved!';
      setTimeout(() => { saveStatus.textContent = ''; }, 2000);
      showToast(`Task #${updated.id} updated`, 'success');
    } catch (err) {
      console.error('Failed to save task:', err);
      saveStatus.textContent = 'Save failed';
      showToast(`Save failed: ${err.message}`, 'error');
    }
  }

  // ==========================================================================
  // Modals (New Task, Columns)
  // ==========================================================================

  function openNewTaskModal(defaultColumnId = null) {
    const modal = document.getElementById('modal-new-task');
    const form = document.getElementById('form-new-task');
    form.reset();

    // Populate columns
    const colSelect = document.getElementById('new-task-column');
    colSelect.innerHTML = '';
    state.columns.forEach(col => {
      const opt = document.createElement('option');
      opt.value = col.id;
      opt.textContent = col.name;
      if (defaultColumnId && col.id === defaultColumnId) {
        opt.selected = true;
      }
      colSelect.appendChild(opt);
    });

    modal.classList.add('open');
    document.getElementById('new-task-title').focus();
  }

  function closeNewTaskModal() {
    document.getElementById('modal-new-task').classList.remove('open');
  }

  function openManageColumnsModal() {
    const modal = document.getElementById('modal-manage-columns');
    renderColumnTable();
    modal.classList.add('open');
  }

  function closeManageColumnsModal() {
    document.getElementById('modal-manage-columns').classList.remove('open');
  }

  function renderColumnTable() {
    const tbody = document.getElementById('column-table-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    const sorted = [...state.columns].sort((a, b) => a.order - b.order);

    sorted.forEach(col => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><code>${escapeHtml(col.id)}</code></td>
        <td><input type="text" class="col-edit-name" value="${escapeHtml(col.name)}" style="background:transparent; border:1px solid var(--border-muted); color:var(--text-primary); padding:3px 6px; border-radius:4px;"></td>
        <td><input type="number" class="col-edit-order" value="${col.order}" style="width:50px; background:transparent; border:1px solid var(--border-muted); color:var(--text-primary); padding:3px 6px; border-radius:4px;"></td>
        <td><input type="color" class="col-edit-color" value="${escapeHtml(col.color || '#6366f1')}" style="border:none; width:28px; height:28px; background:transparent; cursor:pointer;"></td>
        <td><input type="checkbox" class="col-edit-terminal" ${col.isTerminal ? 'checked' : ''}></td>
        <td>
          <button class="btn btn-sm btn-outline btn-save-col" data-col-id="${escapeHtml(col.id)}">Save</button>
          <button class="btn btn-sm btn-danger-ghost btn-del-col" data-col-id="${escapeHtml(col.id)}">Delete</button>
        </td>
      `;

      // Save column update
      tr.querySelector('.btn-save-col').addEventListener('click', async () => {
        const name = tr.querySelector('.col-edit-name').value.trim();
        const order = parseInt(tr.querySelector('.col-edit-order').value, 10) || 0;
        const color = tr.querySelector('.col-edit-color').value;
        const isTerminal = tr.querySelector('.col-edit-terminal').checked;

        try {
          await api.updateColumn(col.id, { name, order, color, isTerminal });
          showToast(`Column '${col.id}' updated`, 'success');
          loadInitialData();
        } catch (err) {
          showToast(`Update column failed: ${err.message}`, 'error');
        }
      });

      // Delete column
      tr.querySelector('.btn-del-col').addEventListener('click', async () => {
        if (!confirm(`Are you sure you want to delete column '${col.name}' (${col.id})?`)) {
          return;
        }
        try {
          await api.deleteColumn(col.id);
          showToast(`Column '${col.id}' deleted`, 'success');
          loadInitialData();
        } catch (err) {
          showToast(`Delete column failed: ${err.message}`, 'error');
        }
      });

      tbody.appendChild(tr);
    });
  }

  // ==========================================================================
  // Filters & Dynamic Population
  // ==========================================================================

  function updateFilterOptions() {
    // Assignee dropdown
    const assigneeSelect = document.getElementById('filter-assignee');
    const currentAssignee = state.filters.assignee;
    const assignees = new Set();
    let hasUnassigned = false;

    state.tasks.forEach(t => {
      if (t.assignee) assignees.add(t.assignee);
      else hasUnassigned = true;
    });

    assigneeSelect.innerHTML = '<option value="">All Assignees</option>';
    if (hasUnassigned) {
      const opt = document.createElement('option');
      opt.value = '__unassigned__';
      opt.textContent = 'Unassigned';
      if (currentAssignee === '__unassigned__') opt.selected = true;
      assigneeSelect.appendChild(opt);
    }
    Array.from(assignees).sort().forEach(a => {
      const opt = document.createElement('option');
      opt.value = a;
      opt.textContent = a;
      if (currentAssignee === a) opt.selected = true;
      assigneeSelect.appendChild(opt);
    });

    // Tag dropdown
    const tagSelect = document.getElementById('filter-tag');
    const currentTag = state.filters.tag;
    const tags = new Set();
    state.tasks.forEach(t => {
      if (t.tags) t.tags.forEach(tag => tags.add(tag));
    });

    tagSelect.innerHTML = '<option value="">All Tags</option>';
    Array.from(tags).sort().forEach(t => {
      const opt = document.createElement('option');
      opt.value = t;
      opt.textContent = `#${t}`;
      if (currentTag === t) opt.selected = true;
      tagSelect.appendChild(opt);
    });
  }

  // ==========================================================================
  // Initialization & Event Binding
  // ==========================================================================

  async function loadInitialData() {
    try {
      const [columns, tasks] = await Promise.all([
        api.getColumns(),
        api.getTasks(),
      ]);

      state.columns = columns;
      state.tasks = tasks;
      renderBoard();
      updateFilterOptions();
      if (document.getElementById('modal-manage-columns').classList.contains('open')) {
        renderColumnTable();
      }
    } catch (err) {
      console.error('Failed to load initial board data:', err);
      showToast(`Error connecting to Kanban server: ${err.message}`, 'error');
    }
  }

  function bindEvents() {
    // Operator input
    const operatorInput = document.getElementById('operator-input');
    operatorInput.value = state.operator;
    operatorInput.addEventListener('change', () => {
      const val = operatorInput.value.trim() || 'web-user';
      state.operator = val;
      localStorage.setItem('aikanban_operator', val);
      showToast(`Operator set to: ${val}`, 'info');
    });

    // Search input
    const searchInput = document.getElementById('search-input');
    const clearBtn = document.getElementById('clear-search');
    searchInput.addEventListener('input', () => {
      state.filters.search = searchInput.value;
      clearBtn.style.display = searchInput.value ? 'block' : 'none';
      renderBoard();
    });

    clearBtn.addEventListener('click', () => {
      searchInput.value = '';
      state.filters.search = '';
      clearBtn.style.display = 'none';
      renderBoard();
    });

    // Priority filter
    document.getElementById('filter-priority').addEventListener('change', (e) => {
      state.filters.priority = e.target.value;
      renderBoard();
    });

    // Assignee filter
    document.getElementById('filter-assignee').addEventListener('change', (e) => {
      state.filters.assignee = e.target.value;
      renderBoard();
    });

    // Tag filter
    document.getElementById('filter-tag').addEventListener('change', (e) => {
      state.filters.tag = e.target.value;
      renderBoard();
    });

    // Refresh button
    document.getElementById('btn-refresh').addEventListener('click', () => {
      loadInitialData();
      showToast('Board refreshed', 'info');
    });

    // Modal buttons
    document.getElementById('btn-new-task').addEventListener('click', () => openNewTaskModal());
    document.getElementById('btn-manage-columns').addEventListener('click', () => openManageColumnsModal());

    // Drawer buttons & backdrop
    document.getElementById('drawer-close').addEventListener('click', closeDrawer);
    document.getElementById('task-drawer-backdrop').addEventListener('click', (e) => {
      if (e.target.id === 'task-drawer-backdrop') {
        closeDrawer();
      }
    });

    // Markdown description toggle
    document.getElementById('btn-desc-preview').addEventListener('click', () => setDescMode('preview'));
    document.getElementById('btn-desc-edit').addEventListener('click', () => setDescMode('edit'));

    // Drawer status change
    document.getElementById('drawer-status-select').addEventListener('change', async (e) => {
      if (!state.selectedTaskId) return;
      const toStatus = e.target.value;
      try {
        await api.moveTask(state.selectedTaskId, {
          toStatus,
          operator: state.operator,
          comment: `Status changed to ${toStatus} via Drawer`,
        });
        const task = state.tasks.find(t => t.id === state.selectedTaskId);
        if (task) task.status = toStatus;
        renderBoard();
        showToast(`Task moved to ${toStatus}`, 'success');
      } catch (err) {
        showToast(`Failed to move task: ${err.message}`, 'error');
      }
    });

    // Drawer priority change
    document.getElementById('drawer-priority-select').addEventListener('change', async (e) => {
      if (!state.selectedTaskId) return;
      const priority = e.target.value;
      try {
        const updated = await api.updateTask(state.selectedTaskId, {
          priority,
          operator: state.operator,
        });
        const idx = state.tasks.findIndex(t => t.id === state.selectedTaskId);
        if (idx >= 0) state.tasks[idx] = updated;
        renderBoard();
        showToast(`Priority updated to ${priority}`, 'success');
      } catch (err) {
        showToast(`Failed to update priority: ${err.message}`, 'error');
      }
    });

    // Drawer Save button
    document.getElementById('drawer-btn-save').addEventListener('click', saveDrawerChanges);

    // Claim shortcut in drawer
    document.getElementById('drawer-btn-claim').addEventListener('click', async () => {
      if (!state.selectedTaskId) return;
      try {
        const updated = await api.updateTask(state.selectedTaskId, {
          assignee: state.operator,
          operator: state.operator,
          comment: `Claimed task by ${state.operator}`,
        });
        const idx = state.tasks.findIndex(t => t.id === state.selectedTaskId);
        if (idx >= 0) state.tasks[idx] = updated;
        populateDrawer(updated);
        renderBoard();
        showToast(`Claimed task #${state.selectedTaskId}`, 'success');
      } catch (err) {
        showToast(`Claim failed: ${err.message}`, 'error');
      }
    });

    // Release shortcut in drawer
    document.getElementById('drawer-btn-release').addEventListener('click', async () => {
      if (!state.selectedTaskId) return;
      try {
        const updated = await api.releaseTask(state.selectedTaskId, {
          operator: state.operator,
          comment: `Released task by ${state.operator}`,
        });
        const idx = state.tasks.findIndex(t => t.id === state.selectedTaskId);
        if (idx >= 0) state.tasks[idx] = updated;
        populateDrawer(updated);
        renderBoard();
        showToast(`Released task #${state.selectedTaskId}`, 'success');
      } catch (err) {
        showToast(`Release failed: ${err.message}`, 'error');
      }
    });

    // Delete task in drawer
    document.getElementById('drawer-btn-delete').addEventListener('click', async () => {
      if (!state.selectedTaskId) return;
      const taskId = state.selectedTaskId;
      if (!confirm(`Are you sure you want to delete task #${taskId}?`)) return;

      try {
        await api.deleteTask(taskId);
        state.tasks = state.tasks.filter(t => t.id !== taskId);
        renderBoard();
        closeDrawer();
        showToast(`Task #${taskId} deleted`, 'success');
      } catch (err) {
        showToast(`Delete failed: ${err.message}`, 'error');
      }
    });

    // Add comment in drawer
    document.getElementById('form-add-comment').addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!state.selectedTaskId) return;

      const comment = document.getElementById('comment-text').value.trim();
      const commitHash = document.getElementById('comment-commit').value.trim() || null;
      const prUrl = document.getElementById('comment-pr').value.trim() || null;

      if (!comment) return;

      try {
        await api.addComment(state.selectedTaskId, {
          operator: state.operator,
          comment,
          commitHash,
          prUrl,
        });

        document.getElementById('comment-text').value = '';
        document.getElementById('comment-commit').value = '';
        document.getElementById('comment-pr').value = '';
        await refreshDrawerLogs(state.selectedTaskId);
        showToast('Comment posted', 'success');
      } catch (err) {
        showToast(`Post comment failed: ${err.message}`, 'error');
      }
    });

    // New task form submission
    document.getElementById('form-new-task').addEventListener('submit', async (e) => {
      e.preventDefault();

      const title = document.getElementById('new-task-title').value.trim();
      const description = document.getElementById('new-task-desc').value;
      const status = document.getElementById('new-task-column').value;
      const priority = document.getElementById('new-task-priority').value;
      const assignee = document.getElementById('new-task-assignee').value.trim() || null;
      const tagsStr = document.getElementById('new-task-tags').value.trim();
      const tags = tagsStr ? tagsStr.split(',').map(t => t.trim()).filter(Boolean) : [];
      const githubRepo = document.getElementById('new-task-repo').value.trim() || null;
      const githubIssueUrl = document.getElementById('new-task-issue').value.trim() || null;

      try {
        const created = await api.createTask({
          title,
          description,
          status,
          priority,
          assignee,
          tags,
          githubRepo,
          githubIssueUrl,
          operator: state.operator,
        });

        state.tasks.push(created);
        renderBoard();
        updateFilterOptions();
        closeNewTaskModal();
        showToast(`Created task #${created.id}`, 'success');
      } catch (err) {
        showToast(`Create task failed: ${err.message}`, 'error');
      }
    });

    // Add column form submission
    document.getElementById('form-add-column').addEventListener('submit', async (e) => {
      e.preventDefault();

      const id = document.getElementById('new-col-id').value.trim();
      const name = document.getElementById('new-col-name').value.trim();
      const order = parseInt(document.getElementById('new-col-order').value, 10) || 0;
      const color = document.getElementById('new-col-color').value;
      const isTerminal = document.getElementById('new-col-terminal').checked;

      try {
        const created = await api.createColumn({ id, name, order, color, isTerminal });
        state.columns.push(created);
        renderBoard();
        renderColumnTable();
        document.getElementById('form-add-column').reset();
        showToast(`Added column '${created.name}'`, 'success');
      } catch (err) {
        showToast(`Add column failed: ${err.message}`, 'error');
      }
    });

    // Modal close buttons
    document.querySelectorAll('.modal-close, .modal-cancel').forEach(btn => {
      btn.addEventListener('click', () => {
        closeNewTaskModal();
        closeManageColumnsModal();
      });
    });

    // Global keyboard listener
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        closeDrawer();
        closeNewTaskModal();
        closeManageColumnsModal();
      }
    });
  }

  // ==========================================================================
  // App Bootstrapper
  // ==========================================================================
  window.addEventListener('DOMContentLoaded', () => {
    bindEvents();
    loadInitialData();
    setupSseConnection();
  });
})();
