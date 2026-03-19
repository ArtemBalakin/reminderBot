/* ═══════════════════════════════════════════════════════════════
   MiniApp SPA — Планировщик дел
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg) { tg.ready(); tg.expand(); }

  /* ── State ──────────────────────────────────────────────────── */
  let chatId = 0;
  const app = document.getElementById('app');
  const nav = document.getElementById('nav');
  let currentPage = 'tasks';
  let pageStack = [];

  // try to get chatId from Telegram or from URL for debug
  if (tg && tg.initDataUnsafe && tg.initDataUnsafe.user) {
    chatId = tg.initDataUnsafe.user.id;
  } else {
    const params = new URLSearchParams(location.search);
    if (params.get('chatId')) chatId = parseInt(params.get('chatId'), 10) || 0;
  }

  /* ── API helper ─────────────────────────────────────────────── */
  async function api(path, body) {
    const sep = path.includes('?') ? '&' : '?';
    const url = path + sep + 'chatId=' + chatId;
    const opts = body
      ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
      : {};
    const r = await fetch(url, opts);
    return r.json();
  }

  /* ── Toast ──────────────────────────────────────────────────── */
  function toast(msg) {
    let el = document.querySelector('.toast');
    if (!el) { el = document.createElement('div'); el.className = 'toast'; document.body.appendChild(el); }
    el.textContent = msg;
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 2500);
  }

  /* ── Navigation ─────────────────────────────────────────────── */
  nav.addEventListener('click', e => {
    const btn = e.target.closest('.nav-btn');
    if (!btn) return;
    const page = btn.dataset.page;
    pageStack = [];
    navigate(page);
  });

  function navigate(page, params) {
    currentPage = page;
    nav.querySelectorAll('.nav-btn').forEach(b => b.classList.toggle('active', b.dataset.page === page));
    app.innerHTML = '<div class="loader"><div class="spinner"></div></div>';
    switch (page) {
      case 'tasks': renderTasks(params); break;
      case 'task': renderTaskCard(params); break;
      case 'subs': renderSubs(); break;
      case 'today': renderToday(); break;
      case 'board': renderBoard(); break;
      case 'stats': renderStats(); break;
      case 'settings': renderSettings(); break;
      case 'newTask': renderNewTask(); break;
      case 'editTask': renderEditTask(params); break;
      case 'subscribe': renderSubscribe(params); break;
      default: renderTasks();
    }
  }

  function pushPage(page, params) {
    pageStack.push({ page: currentPage, params: null });
    navigate(page, params);
  }

  function goBack() {
    if (pageStack.length > 0) {
      const prev = pageStack.pop();
      navigate(prev.page, prev.params);
    } else {
      navigate('tasks');
    }
  }

  function backRow(label) {
    return `<div class="back-row" onclick="window.__goBack()">
      <svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" fill="currentColor"/></svg>
      ${label || 'Назад'}
    </div>`;
  }
  window.__goBack = goBack;

  /* ═══════════════════════════════════════════════════════════════
     Pages
     ═══════════════════════════════════════════════════════════════ */

  /* ── Tasks List ─────────────────────────────────────────────── */
  async function renderTasks(params) {
    const page = (params && params.page) || 0;
    const data = await api('/api/tasks?page=' + page);
    if (data.total === 0) {
      app.innerHTML = `
        <div class="page-header">Дела</div>
        <div class="empty"><div class="empty-icon">📋</div><div class="empty-text">Каталог пуст</div></div>
        <button class="btn btn-primary" onclick="window.__nav('newTask')">＋ Добавить дело</button>`;
      return;
    }
    let html = `<div class="page-header">Дела <span style="font-size:14px;color:var(--hint);font-weight:400">${data.total} шт.</span></div>`;
    for (const t of data.tasks) {
      html += `<div class="card card-clickable" onclick="window.__showTask('${t.number}')">
        <div class="card-title">${esc(t.title)}</div>
        <div class="card-sub">${esc(t.frequency)}</div>
      </div>`;
    }
    html += paginationHtml(data.page, data.totalPages, 'window.__tasksPage');
    html += `<button class="btn btn-secondary" style="margin-top:12px" onclick="window.__nav('newTask')">＋ Добавить дело</button>`;
    html += `<div class="btn-row" style="margin-top:8px">
      <button class="btn btn-secondary btn-sm" onclick="window.__nav('board')">📋 Доска</button>
      <button class="btn btn-secondary btn-sm" onclick="window.__nav('stats')">📊 Стата</button>
    </div>`;
    app.innerHTML = html;
  }
  window.__tasksPage = p => navigate('tasks', { page: p });
  window.__showTask = ref => pushPage('task', { ref });
  window.__nav = page => { pageStack.push({ page: currentPage }); navigate(page); };

  /* ── Task Card ──────────────────────────────────────────────── */
  async function renderTaskCard(params) {
    const data = await api('/api/task?ref=' + encodeURIComponent(params.ref));
    if (data.error) { app.innerHTML = backRow() + `<div class="empty"><div class="empty-text">${esc(data.error)}</div></div>`; return; }
    let html = backRow();
    html += `<div class="page-header">${esc(data.title)}</div>`;
    html += `<div class="card">
      <div class="info-row"><span class="info-label">Номер</span><span class="info-value">${data.number}</span></div>
      <div class="info-row"><span class="info-label">Тип</span><span class="info-value">${esc(data.kindHuman)}</span></div>
      <div class="info-row"><span class="info-label">Повторение</span><span class="info-value">${esc(data.frequency)}</span></div>
      <div class="info-row"><span class="info-label">Подписчиков</span><span class="info-value">${data.subscribers}</span></div>
      ${data.note ? `<div class="info-row"><span class="info-label">Заметка</span><span class="info-value">${esc(data.note)}</span></div>` : ''}
    </div>`;

    if (data.mySubscription) {
      html += `<div class="section-title">Моя настройка</div>`;
      html += `<div class="card">
        <div class="card-sub">${esc(data.mySubscription.schedule)}</div>
        ${data.mySubscription.nextRunAt ? `<div class="card-sub" style="margin-top:4px">Ближайший пинг: ${esc(data.mySubscription.nextRunAt)}</div>` : ''}
      </div>`;
    }

    if (!data.isManual) {
      html += `<div class="btn-row">`;
      html += `<button class="btn btn-primary" onclick="window.__subscribe('${data.number}')">🗓 ${data.mySubscription ? 'Изменить' : 'Подписаться'}</button>`;
      if (data.mySubscription) {
        html += `<button class="btn btn-danger btn-sm" onclick="window.__unsub('${data.number}')">Отписаться</button>`;
      }
      html += `</div>`;
    }
    html += `<button class="btn btn-secondary" style="margin-top:8px" onclick="window.__editTask('${esc(data.id)}')">✏️ Редактировать</button>`;
    app.innerHTML = html;
  }
  window.__subscribe = ref => pushPage('subscribe', { ref });
  window.__unsub = async ref => {
    const r = await api('/api/unsubscribe', { taskRef: ref });
    if (r.error) { toast(r.error); return; }
    toast('Подписка удалена: ' + r.taskTitle);
    goBack();
  };
  window.__editTask = id => pushPage('editTask', { taskId: id });

  /* ── Subscribe / Configure Schedule ─────────────────────────── */
  async function renderSubscribe(params) {
    const data = await api('/api/task?ref=' + encodeURIComponent(params.ref));
    if (data.error) { app.innerHTML = backRow() + `<div class="empty"><div class="empty-text">${esc(data.error)}</div></div>`; return; }

    const isRecurring = data.kind === 'RECURRING';
    const isDaily = isRecurring && data.scheduleUnit === 'DAY';
    const isWeekly = isRecurring && data.scheduleUnit === 'WEEK';
    const isMonthly = isRecurring && data.scheduleUnit === 'MONTH';
    
    window.__subTimes = (data.mySubscription && data.mySubscription.dailyTimes) ? [...data.mySubscription.dailyTimes] : [];
    window.__subDaysOfWeek = (data.mySubscription && data.mySubscription.daysOfWeek) ? [...data.mySubscription.daysOfWeek] : [];
    window.__subDaysOfMonth = (data.mySubscription && data.mySubscription.daysOfMonth) ? [...data.mySubscription.daysOfMonth] : [];

    let html = backRow();
    html += `<div class="page-header">Настройка: ${esc(data.title)}</div>`;
    html += `<div class="card-sub">${esc(data.frequency)}</div>`;

    if (isRecurring) {
      if (isWeekly) {
        html += `<div class="input-group" style="margin-top:16px"><label>Дни недели</label><div class="dow-selector" style="margin-top:8px">`;
        const dows = [{v:'MONDAY',l:'Пн'},{v:'TUESDAY',l:'Вт'},{v:'WEDNESDAY',l:'Ср'},{v:'THURSDAY',l:'Чт'},{v:'FRIDAY',l:'Пт'},{v:'SATURDAY',l:'Сб'},{v:'SUNDAY',l:'Вс'}];
        window.__toggleDow = (val) => {
           if (window.__subDaysOfWeek.includes(val)) window.__subDaysOfWeek = window.__subDaysOfWeek.filter(x => x !== val);
           else window.__subDaysOfWeek.push(val);
           renderDowChips();
        };
        dows.forEach(d => {
          html += `<label style="display:inline-flex; align-items:center; margin-right:12px; margin-bottom:8px;">
                     <input type="checkbox" value="${d.v}" ${window.__subDaysOfWeek.includes(d.v) ? 'checked' : ''} onchange="window.__toggleDow('${d.v}')" style="margin-right:4px"> ${d.l}
                   </label>`;
        });
        html += `</div></div>`;
      }
      
      if (isMonthly) {
        html += `
          <div class="input-group" style="margin-top:16px">
            <label>Числа месяца</label>
            <div style="display:flex;gap:8px">
              <input type="number" id="sub-dom" class="input" min="1" max="31" style="flex:1" placeholder="1-31">
              <button class="btn btn-secondary btn-sm" style="width:auto;flex:none" onclick="window.__addDom()">Добавить</button>
            </div>
          </div>
          <div id="dom-chips" class="time-chips"></div>`;
      }

      html += `
        <div class="input-group" style="margin-top:16px">
          <label>Время напоминаний</label>
          <div style="display:flex;gap:8px">
            <input type="time" id="sub-time" class="input" step="60" style="flex:1">
            <button class="btn btn-secondary btn-sm" style="width:auto;flex:none" onclick="window.__addTime()">Добавить</button>
          </div>
        </div>
        <div id="time-chips" class="time-chips"></div>`;
    } else {
      const existingDate = data.mySubscription && data.mySubscription.nextRunAt
        ? '' : new Date().toISOString().slice(0, 10);
      html += `
        <div class="input-group" style="margin-top:16px">
          <label>Дата</label>
          <input type="date" id="sub-date" class="input" value="${existingDate}">
        </div>
        <div class="input-group">
          <label>Время</label>
          <input type="time" id="sub-dated-time" class="input" step="60">
        </div>`;
    }
    html += `<button class="btn btn-primary" style="margin-top:16px" onclick="window.__saveSub('${data.number}', '${data.kind}', '${data.scheduleUnit}')">Сохранить</button>`;
    app.innerHTML = html;

    if (isRecurring) {
      renderTimeChips();
      if (isMonthly) renderDomChips();
    }
  }

  function renderDowChips() {} // Only state toggle is needed, UI is naturally handled by checkboxes
  
  function renderDomChips() {
    const el = document.getElementById('dom-chips');
    if (!el) return;
    const doms = window.__subDaysOfMonth || [];
    el.innerHTML = doms.sort((a,b)=>a-b).map((d, i) =>
      `<div class="time-chip"><span>${d}</span><button class="remove" onclick="window.__removeDom(${i})">×</button></div>`
    ).join('');
  }
  
  window.__addDom = () => {
    const input = document.getElementById('sub-dom');
    const val = parseInt(input.value, 10);
    if (!val || val < 1 || val > 31) return;
    if (!window.__subDaysOfMonth) window.__subDaysOfMonth = [];
    if (!window.__subDaysOfMonth.includes(val)) window.__subDaysOfMonth.push(val);
    input.value = '';
    renderDomChips();
  };
  
  window.__removeDom = i => {
    window.__subDaysOfMonth.splice(i, 1);
    renderDomChips();
  };

  function renderTimeChips() {
    const el = document.getElementById('time-chips');
    if (!el) return;
    const times = window.__subTimes || [];
    el.innerHTML = times.sort().map((t, i) =>
      `<div class="time-chip"><span>${t}</span><button class="remove" onclick="window.__removeTime(${i})">×</button></div>`
    ).join('');
  }
  window.__addTime = () => {
    const input = document.getElementById('sub-time');
    if (!input || !input.value) return;
    if (!window.__subTimes) window.__subTimes = [];
    if (!window.__subTimes.includes(input.value)) window.__subTimes.push(input.value);
    input.value = '';
    renderTimeChips();
  };
  window.__removeTime = i => {
    window.__subTimes.splice(i, 1);
    renderTimeChips();
  };

  window.__saveSub = async (ref, kind, scheduleUnit) => {
    let body;
    const isRecurring = kind === 'RECURRING';
    if (isRecurring) {
      const input = document.getElementById('sub-time');
      if (input && input.value && !window.__subTimes.includes(input.value)) {
        window.__subTimes.push(input.value);
      }
      if (!window.__subTimes || window.__subTimes.length === 0) { toast('Добавь хотя бы одно время'); return; }
      
      let mode = 'daily';
      if (scheduleUnit === 'WEEK') {
          mode = 'weekly';
          if (!window.__subDaysOfWeek || window.__subDaysOfWeek.length === 0) { toast('Добавь хотя бы один день недели'); return; }
      } else if (scheduleUnit === 'MONTH') {
          mode = 'monthly';
          const domInput = document.getElementById('sub-dom');
          const domVal = domInput ? parseInt(domInput.value, 10) : 0;
          if (domVal && !window.__subDaysOfMonth.includes(domVal)) window.__subDaysOfMonth.push(domVal);
          if (!window.__subDaysOfMonth || window.__subDaysOfMonth.length === 0) { toast('Добавь хотя бы одно число месяца'); return; }
      }
      
      body = { 
          taskRef: ref, 
          mode: mode, 
          times: window.__subTimes,
          daysOfWeek: window.__subDaysOfWeek,
          daysOfMonth: window.__subDaysOfMonth
      };
    } else {
      const date = document.getElementById('sub-date')?.value;
      const time = document.getElementById('sub-dated-time')?.value;
      if (!date || !time) { toast('Выбери дату и время'); return; }
      body = { taskRef: ref, mode: 'dated', date, time };
    }
    const r = await api('/api/subscribe', body);
    if (r.error) { toast(r.error); return; }
    toast('✅ ' + r.taskTitle + ' — сохранено');
    goBack();
  };

  /* ── My Subscriptions ───────────────────────────────────────── */
  async function renderSubs() {
    const data = await api('/api/subs');
    if (!data || data.length === 0) {
      app.innerHTML = `
        <div class="page-header">Мои подписки</div>
        <div class="empty"><div class="empty-icon">🔔</div><div class="empty-text">У тебя пока нет подписок.<br>Открой «Дела» и подпишись.</div></div>`;
      return;
    }
    let html = `<div class="page-header">Мои подписки <span style="font-size:14px;color:var(--hint);font-weight:400">${data.length}</span></div>`;
    for (const s of data) {
      html += `<div class="card card-clickable" onclick="window.__showTask('${esc(s.taskId)}')">
        <div class="card-title">${esc(s.taskTitle)}</div>
        <div class="card-sub">${esc(s.schedule)}</div>
        ${s.nextRunAt ? `<div class="card-sub" style="margin-top:2px">⏰ ${esc(s.nextRunAt)}</div>` : ''}
      </div>`;
    }
    app.innerHTML = html;
  }

  /* ── Today Board ────────────────────────────────────────────── */
  async function renderToday() {
    const data = await api('/api/today');
    let html = `<div class="page-header">Сегодня</div>`;
    if (!data.sections || data.sections.length === 0) {
      html += `<div class="empty"><div class="empty-icon">☀️</div><div class="empty-text">На сегодня нет активных дел</div></div>`;
      app.innerHTML = html;
      return;
    }
    for (const section of data.sections) {
      html += `<div class="section-title">${esc(section.user)}</div>`;
      for (const t of section.tasks) {
        const chipClass = t.done ? 'chip-done' : 'chip-waiting';
        html += `<div class="card">
          <div class="card-title">${esc(t.taskTitle)}</div>
          <div class="chip-row"><span class="chip ${chipClass}">${esc(t.status)}</span></div>
        </div>`;
      }
    }
    app.innerHTML = html;
  }

  /* ── Board ──────────────────────────────────────────────────── */
  async function renderBoard() {
    const data = await api('/api/board');
    let html = backRow() + `<div class="page-header">Доска дел</div>`;
    if (data.taken && data.taken.length > 0) {
      html += `<div class="section-title">🔒 Забранные</div>`;
      for (const t of data.taken) {
        html += `<div class="card">
          <div class="card-title">${esc(t.title)}</div>
          <div class="card-sub">${t.count}/${t.slots} · ${t.users.map(esc).join(', ')}</div>
          <div class="slot-dots">${slotDots(t.count, t.slots)}</div>
        </div>`;
      }
    }
    if (data.free && data.free.length > 0) {
      html += `<div class="section-title">🆓 Свободные</div>`;
      for (const t of data.free) {
        html += `<div class="card card-clickable" onclick="window.__showTask('${esc(t.id)}')">
          <div class="card-title">${esc(t.title)}</div>
          <div class="card-sub">0/${t.slots}</div>
          <div class="slot-dots">${slotDots(0, t.slots)}</div>
        </div>`;
      }
    }
    app.innerHTML = html;
  }

  function slotDots(filled, total) {
    let r = '';
    for (let i = 0; i < Math.max(total, 1); i++) {
      r += `<div class="slot-dot ${i < filled ? 'filled' : ''}"></div>`;
    }
    return r;
  }

  /* ── Stats ──────────────────────────────────────────────────── */
  async function renderStats() {
    const data = await api('/api/stats');
    let html = backRow() + `<div class="page-header">Статистика</div>`;
    if (!data.items || data.items.length === 0) {
      html += `<div class="empty"><div class="empty-icon">📊</div><div class="empty-text">Пока нет активных подписок</div></div>`;
      app.innerHTML = html;
      return;
    }
    const max = data.items[0].count;
    for (const item of data.items) {
      const pct = max > 0 ? Math.max(8, (item.count / max) * 100) : 0;
      html += `<div class="stat-bar-wrap">
        <div class="stat-bar-label">${esc(item.taskTitle)}</div>
        <div class="stat-bar"><div class="stat-bar-fill" style="width:${pct}%">${item.count}</div></div>
      </div>`;
    }
    html += `<div class="card-sub" style="margin-top:16px;text-align:center">Всего активных подписок: ${data.totalActive}</div>`;
    app.innerHTML = html;
  }

  /* ── Settings ───────────────────────────────────────────────── */
  async function renderSettings() {
    const data = await api('/api/settings');
    const tzOptions = ['Asia/Almaty', 'Europe/Moscow', 'Europe/Vilnius', 'Europe/Berlin', 'UTC'];
    let html = `<div class="page-header">Настройки</div>`;
    html += `<div class="card">
      <div class="info-row">
        <span class="info-label">Пользователь</span>
        <span class="info-value">${esc(data.firstName || '')} ${data.username ? '(@' + esc(data.username) + ')' : ''}</span>
      </div>
    </div>`;

    html += `<div class="section-title">Таймзона</div>`;
    html += `<div class="card">
      <div class="input-group" style="margin-bottom:0">
        <select id="set-tz" class="input" onchange="window.__saveTz()">
          ${tzOptions.map(z => `<option value="${z}" ${z === data.zoneId ? 'selected' : ''}>${z}</option>`).join('')}
          ${!tzOptions.includes(data.zoneId) ? `<option value="${esc(data.zoneId)}" selected>${esc(data.zoneId)}</option>` : ''}
        </select>
      </div>
    </div>`;

    html += `<div class="section-title">Алерты</div>`;
    html += `<div class="card">
      <div class="info-row">
        <span class="info-label">Получать общие алерты</span>
        <label class="toggle">
          <input type="checkbox" id="set-alerts" ${data.alertsEnabled ? 'checked' : ''} onchange="window.__saveAlerts()">
          <span class="slider"></span>
        </label>
      </div>
      <div class="card-sub">Бот сообщит, если кто-то долго игнорирует дело</div>
    </div>`;

    html += `<div class="section-title">Перепинг</div>`;
    html += `<div class="card">
      <div class="input-group" style="margin-bottom:0">
        <label>Интервал перепинга (мин.)</label>
        <div style="display:flex;gap:8px">
          <input type="number" id="set-reping" class="input" value="${data.repingMinutes}" min="1" max="180" style="flex:1">
          <button class="btn btn-secondary btn-sm" style="width:auto;flex:none" onclick="window.__saveReping()">Сохранить</button>
        </div>
      </div>
    </div>`;

    app.innerHTML = html;
  }
  window.__saveTz = async () => {
    const tz = document.getElementById('set-tz')?.value;
    if (!tz) return;
    await api('/api/settings', { zoneId: tz });
    toast('Таймзона: ' + tz);
  };
  window.__saveAlerts = async () => {
    const on = document.getElementById('set-alerts')?.checked;
    await api('/api/settings', { alertsEnabled: on });
    toast(on ? 'Алерты включены' : 'Алерты выключены');
  };
  window.__saveReping = async () => {
    const val = parseInt(document.getElementById('set-reping')?.value, 10);
    if (!val || val < 1 || val > 180) { toast('От 1 до 180 минут'); return; }
    await api('/api/settings', { repingMinutes: val });
    toast('Перепинг: ' + val + ' мин.');
  };

  /* ── New Task ───────────────────────────────────────────────── */
  function renderNewTask() {
    let html = backRow();
    html += `<div class="page-header">Новое дело</div>`;
    html += `<div class="input-group">
      <label>Название</label>
      <input type="text" id="new-title" class="input" placeholder="Как назвать дело?">
    </div>`;
    html += `<div class="input-group">
      <label>Тип</label>
      <div class="select-grid">
        <button class="btn btn-secondary kind-btn active" data-kind="DAY" onclick="window.__selectKind(this)">Каждый N день</button>
        <button class="btn btn-secondary kind-btn" data-kind="WEEK" onclick="window.__selectKind(this)">Каждую N нед.</button>
        <button class="btn btn-secondary kind-btn" data-kind="MONTH" onclick="window.__selectKind(this)">Каждый N мес.</button>
        <button class="btn btn-secondary kind-btn" data-kind="THIS_WEEK" onclick="window.__selectKind(this)">Разово на этой</button>
        <button class="btn btn-secondary kind-btn" data-kind="NEXT_WEEK" onclick="window.__selectKind(this)">Разово на след.</button>
        <button class="btn btn-secondary kind-btn" data-kind="MANUAL" onclick="window.__selectKind(this)">Ручное</button>
      </div>
    </div>`;
    html += `<div id="interval-group" class="input-group">
      <label>Интервал повторения</label>
      <input type="number" id="new-interval" class="input" value="1" min="1">
    </div>`;
    html += `<div class="input-group">
      <label>Заметка (необязательно)</label>
      <input type="text" id="new-note" class="input" placeholder="Короткая заметка">
    </div>`;
    html += `<button class="btn btn-primary" onclick="window.__createTask()">Создать</button>`;
    app.innerHTML = html;
    window.__selectedKind = 'DAY';
  }
  window.__selectKind = btn => {
    document.querySelectorAll('.kind-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    window.__selectedKind = btn.dataset.kind;
    const intGroup = document.getElementById('interval-group');
    if (['THIS_WEEK', 'NEXT_WEEK', 'MANUAL'].includes(btn.dataset.kind)) {
      intGroup.style.display = 'none';
    } else {
      intGroup.style.display = '';
    }
  };
  window.__createTask = async () => {
    const title = document.getElementById('new-title')?.value?.trim();
    if (!title) { toast('Введи название'); return; }
    const kind = window.__selectedKind || 'DAY';
    const interval = parseInt(document.getElementById('new-interval')?.value, 10) || 1;
    const note = document.getElementById('new-note')?.value?.trim() || null;
    const r = await api('/api/task/new', { title, kindCode: kind, unit: kind, interval, note });
    if (r.error) { toast(r.error); return; }
    toast('✅ Добавлено: ' + r.title);
    goBack();
  };

  /* ── Edit Task ──────────────────────────────────────────────── */
  async function renderEditTask(params) {
    const data = await api('/api/task?ref=' + encodeURIComponent(params.taskId));
    if (data.error) { app.innerHTML = backRow() + `<div class="empty"><div class="empty-text">${esc(data.error)}</div></div>`; return; }

    let html = backRow();
    html += `<div class="page-header">Редактировать: ${esc(data.title)}</div>`;
    html += `<div class="input-group">
      <label>Название</label>
      <input type="text" id="edit-title" class="input" value="${esc(data.title)}">
    </div>`;
    if (data.scheduleInterval) {
      html += `<div class="input-group">
        <label>Интервал</label>
        <input type="number" id="edit-interval" class="input" value="${data.scheduleInterval}" min="1">
      </div>`;
    }
    html += `<div class="input-group">
      <label>Заметка</label>
      <input type="text" id="edit-note" class="input" value="${esc(data.note || '')}" placeholder="Заметка или - для удаления">
    </div>`;
    html += `<button class="btn btn-primary" onclick="window.__saveEdit('${esc(data.id)}', ${!!data.scheduleInterval})">Сохранить</button>`;
    app.innerHTML = html;
  }
  window.__saveEdit = async (taskId, hasInterval) => {
    const title = document.getElementById('edit-title')?.value?.trim();
    const note = document.getElementById('edit-note')?.value?.trim();
    const interval = hasInterval ? document.getElementById('edit-interval')?.value?.trim() : null;
    let ok = true;
    if (title) {
      const r = await api('/api/task/edit', { taskId, property: 'title', value: title });
      if (r.error) { toast(r.error); ok = false; }
    }
    if (note !== undefined) {
      const r = await api('/api/task/edit', { taskId, property: 'note', value: note || '-' });
      if (r.error) { toast(r.error); ok = false; }
    }
    if (interval) {
      const r = await api('/api/task/edit', { taskId, property: 'interval', value: interval });
      if (r.error) { toast(r.error); ok = false; }
    }
    if (ok) toast('✅ Дело обновлено');
    goBack();
  };

  /* ═══════════════════════════════════════════════════════════════
     Helpers
     ═══════════════════════════════════════════════════════════════ */

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function paginationHtml(page, totalPages, fnName) {
    if (totalPages <= 1) return '';
    return `<div class="pagination">
      <button ${page <= 0 ? 'disabled' : ''} onclick="${fnName}(${page - 1})">◀️</button>
      <span>${page + 1} / ${totalPages}</span>
      <button ${page >= totalPages - 1 ? 'disabled' : ''} onclick="${fnName}(${page + 1})">▶️</button>
    </div>`;
  }

  /* ── Initial render ─────────────────────────────────────────── */
  navigate('tasks');

})();
