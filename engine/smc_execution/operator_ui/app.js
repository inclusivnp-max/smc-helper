(() => {
  'use strict';

  const state = {
    token: sessionStorage.getItem('smc_operator_token') || '',
    actor: sessionStorage.getItem('smc_operator_actor') || 'operator',
    status: null,
    modes: [],
    socket: null,
    socketTimer: null,
    modalAction: null,
    researchDatasets: [],
    researchRuns: [],
    researchApprovals: [],
    researchDataJobs: [],
    currentResearchReport: null,
  };

  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value ?? '').replace(/[&<>'"]/g, (ch) => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
  const num = (value, digits = 2) => {
    const n = Number(value);
    return Number.isFinite(n) ? n.toLocaleString('ru-RU', {minimumFractionDigits: digits, maximumFractionDigits: digits}) : '—';
  };
  const pct = (value) => `${num(Number(value || 0) * 100, 2)}%`;
  const timeText = (ms) => ms ? new Date(Number(ms)).toLocaleString('ru-RU') : '—';
  const uuid = () => crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set('Authorization', `Bearer ${state.token}`);
    headers.set('X-Operator-Actor', state.actor);
    if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(path, {...options, headers, cache: 'no-store'});
    let payload = null;
    try { payload = await response.json(); } catch (_) { payload = {}; }
    if (!response.ok) throw new Error(payload.detail || `HTTP ${response.status}`);
    return payload;
  }

  function showToast(message, kind = '') {
    const item = document.createElement('div');
    item.className = `toast ${kind}`;
    item.textContent = message;
    $('toastContainer').appendChild(item);
    setTimeout(() => item.remove(), 4800);
  }

  function showLogin(error = '') {
    $('loginOverlay').classList.remove('hidden');
    $('tokenInput').value = state.token;
    $('actorInput').value = state.actor;
    $('loginError').textContent = error;
  }

  function hideLogin() { $('loginOverlay').classList.add('hidden'); }

  async function authenticate(event) {
    event.preventDefault();
    state.token = $('tokenInput').value.trim();
    state.actor = $('actorInput').value.trim() || 'operator';
    try {
      await api('/api/v1/status');
      sessionStorage.setItem('smc_operator_token', state.token);
      sessionStorage.setItem('smc_operator_actor', state.actor);
      hideLogin();
      connectSocket();
      await refreshAll();
    } catch (error) {
      showLogin(error.message);
    }
  }

  function logout() {
    sessionStorage.removeItem('smc_operator_token');
    sessionStorage.removeItem('smc_operator_actor');
    state.token = '';
    if (state.socket) state.socket.close();
    showLogin();
  }

  function connectSocket() {
    if (!state.token) return;
    if (state.socket) state.socket.close();
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${scheme}//${location.host}/api/v1/ws`);
    state.socket = socket;
    socket.addEventListener('open', () => socket.send(JSON.stringify({token: state.token, actor: state.actor})));
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'status') {
        state.status = message.payload;
        renderStatus(message.payload);
      }
    });
    socket.addEventListener('close', () => {
      setConnection(false);
      clearTimeout(state.socketTimer);
      state.socketTimer = setTimeout(connectSocket, 2500);
    });
    socket.addEventListener('error', () => setConnection(false));
  }

  function setConnection(connected) {
    $('streamDot').className = `status-dot ${connected ? 'good' : 'bad'}`;
    $('streamLabel').textContent = connected ? 'Dashboard online' : 'Переподключение…';
  }

  function renderStatus(data) {
    setConnection(true);
    const account = data.account || {};
    const risk = data.risk_ledger || {};
    const counts = data.counts || {};
    const admission = data.admission || {};
    const governor = data.governor || {};
    const pnl = Number(data.unrealized_pnl || 0);

    const liveProhibited = String(data.live_capital_status || '').startsWith('PROHIBITED');
    $('environmentBadge').textContent = data.testnet && liveProhibited
      ? 'BINANCE TESTNET — LIVE ЗАПРЕЩЁН'
      : (data.testnet ? 'BINANCE TESTNET' : 'LIVE — REAL MONEY');
    $('environmentBadge').title = `Live capital status: ${data.live_capital_status || 'UNKNOWN'}`;
    $('environmentBadge').className = `environment ${data.testnet ? '' : 'negative'}`;
    $('equityMetric').textContent = num(account.equity);
    $('availableMetric').textContent = num(account.available_balance);
    $('pnlMetric').textContent = num(pnl);
    $('pnlMetric').className = pnl > 0 ? 'positive' : pnl < 0 ? 'negative' : '';
    $('riskMetric').textContent = num(account.current_open_risk);
    $('ledgerMetric').textContent = num(Number(risk.reserved || 0) + Number(risk.committed || 0));
    $('modeMetric').textContent = data.mode?.title || '—';
    $('modeRisk').textContent = data.mode?.risk_label || '—';

    const banner = $('admissionBanner');
    banner.className = `status-banner ${admission.open ? 'open' : 'closed'}`;
    banner.querySelector('.status-dot').className = `status-dot ${admission.open ? 'good' : 'bad'}`;
    $('admissionTitle').textContent = admission.open ? 'Новые входы разрешены' : 'Новые входы остановлены';
    $('admissionReason').textContent = admission.reason || 'Все обязательные проверки пройдены';

    $('healthStream').textContent = data.user_stream_healthy ? 'HEALTHY' : 'UNHEALTHY';
    $('healthStream').className = data.user_stream_healthy ? 'positive' : 'negative';
    $('healthPositions').textContent = counts.open_positions ?? '—';
    $('healthRegular').textContent = counts.regular_orders ?? '—';
    $('healthAlgo').textContent = counts.algo_orders ?? '—';
    $('healthSaga').textContent = counts.nonterminal_executions ?? '—';
    $('healthDailyLoss').textContent = pct(governor.last_daily_loss_pct);
    $('healthDrawdown').textContent = pct(governor.last_drawdown_pct);
    const automation = data.strategy_automation || {enabled:false};
    $('healthAutomation').textContent = !automation.enabled ? 'DISABLED' : automation.running ? `${String(automation.environment || '').toUpperCase()} · ${automation.run_count || 1} RUN(S)` : 'STOPPED';
    $('healthAutomation').className = automation.running ? 'positive' : automation.start_error ? 'negative' : '';
    $('healthApprovedRun').textContent = automation.run_id ? String(automation.run_id).slice(0,12) : automation.run_ids?.length ? `${automation.run_ids.length} approved runs` : '—';

    renderModes(state.modes, data.mode?.mode);
  }

  function renderModes(items, currentMode) {
    $('modeCards').innerHTML = items.map((profile) => {
      const e = profile.execution_overrides || {};
      const p = profile.dynamic_protection || {};
      const current = profile.mode === currentMode;
      return `<article class="mode-card ${current ? 'current' : ''}">
        <span class="mode-badge">${esc(profile.risk_label)}</span>
        <h3>${esc(profile.title)}</h3>
        <p>${esc(profile.description)}</p>
        <dl>
          <dt>Риск / сделку</dt><dd>${e.risk_per_trade_pct ? pct(e.risk_per_trade_pct) : '—'}</dd>
          <dt>Портфельный риск</dt><dd>${e.max_portfolio_risk_pct ? pct(e.max_portfolio_risk_pct) : '—'}</dd>
          <dt>Max notional</dt><dd>${e.max_position_notional ? `${num(e.max_position_notional, 0)} USDT` : '—'}</dd>
          <dt>Breakeven</dt><dd>${p.enabled ? `${p.breakeven_activation_r}R` : 'off'}</dd>
        </dl>
        <button class="button ${current ? 'success' : 'secondary'} mode-select" data-mode="${esc(profile.mode)}" ${current ? 'disabled' : ''}>${current ? 'Активен' : 'Выбрать'}</button>
      </article>`;
    }).join('');
    document.querySelectorAll('.mode-select').forEach((button) => button.addEventListener('click', () => confirmMode(button.dataset.mode)));
  }

  function renderPositions(items) {
    const body = $('positionsBody');
    if (!items.length) { body.innerHTML = '<tr><td colspan="7" class="empty-row">Открытых позиций нет</td></tr>'; return; }
    body.innerHTML = items.map((p) => {
      const side = p.position_side === 'BOTH' ? (Number(p.quantity) > 0 ? 'LONG' : 'SHORT') : p.position_side;
      const pnl = Number(p.unrealized_pnl || 0);
      return `<tr><td><strong>${esc(p.symbol)}</strong></td><td><span class="pill ${side === 'LONG' ? 'good' : 'bad'}">${esc(side)}</span></td><td>${num(Math.abs(Number(p.quantity)), 6)}</td><td>${num(p.entry_price, 4)}</td><td>${num(p.mark_price, 4)}</td><td>${num(p.notional, 2)}</td><td class="${pnl > 0 ? 'positive' : pnl < 0 ? 'negative' : ''}">${num(pnl, 2)}</td></tr>`;
    }).join('');
  }

  function renderOrders(items) {
    const body = $('ordersBody');
    if (!items.length) { body.innerHTML = '<tr><td colspan="8" class="empty-row">Активных ордеров нет</td></tr>'; return; }
    body.innerHTML = items.map((o) => `<tr><td><span class="pill info">${esc(o.kind)}</span></td><td>${esc(o.symbol)}</td><td>${esc(o.order_type)}</td><td>${esc(o.side)}</td><td>${esc(o.status)}</td><td>${num(o.remaining_qty ?? (Number(o.orig_qty)-Number(o.executed_qty)), 6)}</td><td>${num(Number(o.stop_price) > 0 ? o.stop_price : o.price, 4)}</td><td class="mono">${esc(o.client_order_id)}</td></tr>`).join('');
  }

  function renderExecutions(items) {
    const body = $('executionsBody');
    if (!items.length) { body.innerHTML = '<tr><td colspan="7" class="empty-row">Saga пока отсутствуют</td></tr>'; return; }
    body.innerHTML = items.map((e) => {
      const result = e.result?.status || '—';
      const mode = e.payload?.trading_mode || '—';
      const bad = ['FAILED_UNCERTAIN','FAILED_SAFE'].includes(e.stage);
      return `<tr><td>${timeText(e.updated_at_ms)}</td><td class="mono">${esc(e.execution_id)}</td><td>${esc(e.symbol)}</td><td>${esc(e.action)}</td><td><span class="pill ${bad ? 'bad' : e.stage === 'PROTECTED' ? 'good' : 'info'}">${esc(e.stage)}</span></td><td>${esc(mode)}</td><td>${esc(result)}</td></tr>`;
    }).join('');
  }

  function renderAudit(items) {
    const body = $('auditBody');
    if (!items.length) { body.innerHTML = '<tr><td colspan="5" class="empty-row">Действий оператора пока нет</td></tr>'; return; }
    body.innerHTML = items.map((a) => `<tr><td>${timeText(a.ts_ms)}</td><td>${esc(a.actor)}</td><td>${esc(a.action)}</td><td><span class="pill ${a.outcome === 'SUCCESS' ? 'good' : a.outcome === 'UNRESOLVED' ? 'bad' : 'info'}">${esc(a.outcome)}</span></td><td class="mono">${esc(JSON.stringify(a.details).slice(0, 180))}</td></tr>`).join('');
  }

  function renderResearchDataJobs(items) {
    state.researchDataJobs = items || [];
    const body = $('researchDataJobsBody');
    body.innerHTML = state.researchDataJobs.length ? state.researchDataJobs.map((job) => {
      const bad = job.status === 'FAILED';
      const good = job.status === 'COMPLETED';
      return `<tr><td>${timeText(job.created_at_ms)}</td><td>${esc(job.symbol)} ${esc(job.interval)}</td><td><span class="pill ${bad ? 'bad' : good ? 'good' : 'info'}">${esc(job.status)}</span></td><td>${job.rows ?? '—'}</td><td class="mono" title="${esc(job.error || job.fingerprint || '')}">${esc(job.dataset_id || job.progress || '—')}</td></tr>`;
    }).join('') : '<tr><td colspan="5" class="empty-row">Загрузок пока нет</td></tr>';
  }

  function renderResearchDatasets(items) {
    state.researchDatasets = items || [];
    const select = $('researchDataset');
    const selected = select.value;
    select.innerHTML = '<option value="">Выберите dataset</option>' + state.researchDatasets.map((d) => `<option value="${esc(d.dataset_id)}">${esc(d.symbol)} ${esc(d.interval)} · ${Number(d.rows || 0).toLocaleString('ru-RU')} bars · ${esc(String(d.fingerprint || '').slice(0,10))}</option>`).join('');
    if (state.researchDatasets.some((d) => d.dataset_id === selected)) select.value = selected;
  }

  function renderResearchRuns(items) {
    state.researchRuns = items || [];
    const body = $('researchRunsBody');
    if (!state.researchRuns.length) { body.innerHTML = '<tr><td colspan="6" class="empty-row">Research runs пока отсутствуют</td></tr>'; return; }
    body.innerHTML = state.researchRuns.map((r) => {
      const passed = r.gate_passed === 1 || r.gate_passed === true;
      const gate = r.gate_passed == null ? '—' : passed ? 'PASS' : 'FAIL';
      const strategy = r.strategy_id ? `${r.strategy_id}:${r.strategy_version || '—'}` : (r.mode || 'queued');
      return `<tr class="clickable-row" data-run-id="${esc(r.run_id)}"><td>${timeText(r.created_at_ms)}</td><td class="mono">${esc(String(r.run_id).slice(0,12))}</td><td>${esc(r.dataset_id)}</td><td>${esc(strategy)}</td><td><span class="pill ${r.status === 'FAILED' ? 'bad' : r.status === 'COMPLETED' ? 'good' : 'info'}">${esc(r.status)}</span></td><td><span class="pill ${passed ? 'good' : r.gate_passed == null ? 'info' : 'bad'}">${gate}</span></td></tr>`;
    }).join('');
    body.querySelectorAll('[data-run-id]').forEach((row) => row.addEventListener('click', () => loadResearchReport(row.dataset.runId)));
  }

  function renderResearchReport(report) {
    state.currentResearchReport = report && report.metrics ? report : null;
    const metrics = report?.metrics || {};
    const gate = report?.gate || null;
    const bootstrap = report?.bootstrap || {};
    $('researchGate').textContent = gate ? (gate.passed ? 'PASS' : 'FAIL') : '—';
    $('researchGate').className = gate ? (gate.passed ? 'positive' : 'negative') : '';
    $('researchGateScore').textContent = gate ? `score ${num(gate.score,1)} / 100` : 'нет отчёта';
    $('researchTrades').textContent = metrics.trades ?? '—';
    $('researchExpectancy').textContent = metrics.expectancy_r != null ? num(metrics.expectancy_r,3) : '—';
    $('researchProfitFactor').textContent = metrics.profit_factor != null ? num(metrics.profit_factor,2) : '—';
    $('researchDrawdown').textContent = metrics.max_drawdown_pct != null ? pct(metrics.max_drawdown_pct) : '—';
    $('researchProbability').textContent = bootstrap.probability_positive_expectancy != null ? pct(bootstrap.probability_positive_expectancy) : '—';
    const reasons = gate?.reasons?.length ? gate.reasons : gate?.passed ? ['Все заданные пороги пройдены. Ручное продвижение всё равно обязательно.'] : ['Выберите завершённый run.'];
    $('researchReasons').innerHTML = reasons.map((x) => `<li>${esc(x)}</li>`).join('');

    const folds = report?.folds || [];
    $('researchFoldsBody').innerHTML = folds.length ? folds.map((fold) => {
      const m = fold.test_metrics || {};
      return `<tr><td>${fold.fold}</td><td>${timeText(fold.test_start_ms)} → ${timeText(fold.test_end_ms)}</td><td>${m.trades ?? '—'}</td><td>${num(m.expectancy_r,3)}</td><td>${num(m.profit_factor,2)}</td><td>${pct(m.max_drawdown_pct)}</td></tr>`;
    }).join('') : '<tr><td colspan="6" class="empty-row">Нет OOS folds</td></tr>';

    const baselines = report?.baselines || {};
    const baselineBody = $('researchBaselinesBody');
    const baselineRows = Object.entries(baselines);
    baselineBody.innerHTML = baselineRows.length ? baselineRows.map(([name,m]) => `<tr><td>${esc(name)}</td><td>${m.trades ?? '—'}</td><td>${num(m.expectancy_r,3)}</td><td>${num(m.profit_factor,2)}</td><td>${pct(m.return_pct)}</td><td>${pct(m.max_drawdown_pct)}</td></tr>`).join('') : '<tr><td colspan="6" class="empty-row">Нет данных</td></tr>';

    const stress = report?.stress || [];
    $('researchStressBody').innerHTML = stress.length ? stress.map((x) => `<tr><td>${esc(x.name)}</td><td>${num(x.expectancy_r,3)}</td><td>${num(x.profit_factor,2)}</td><td>${pct(x.max_drawdown_pct)}</td><td><span class="pill ${x.passed ? 'good' : 'bad'}">${x.passed ? 'PASS' : 'FAIL'}</span></td></tr>`).join('') : '<tr><td colspan="5" class="empty-row">Нет данных</td></tr>';
    ['promoteResearchButton','promoteShadowButton','promoteTestnetButton'].forEach((id) => { $(id).disabled = !(gate?.passed && report?.run_id); });
  }

  function renderShadow(summary, trades) {
    $('shadowSignals').textContent = summary?.signals ?? '—';
    $('shadowOpen').textContent = summary ? Number(summary.pending || 0) + Number(summary.open || 0) : '—';
    $('shadowClosed').textContent = summary?.closed ?? '—';
    $('shadowExpectancy').textContent = summary ? num(summary.expectancy_r,3) : '—';
    $('shadowWinRate').textContent = summary ? pct(summary.win_rate) : '—';
    const items = trades || [];
    $('shadowTradesBody').innerHTML = items.length ? items.map((item) => `<tr><td>${timeText(item.updated_at_ms)}</td><td>${esc(item.symbol)}</td><td>${esc(item.action)}</td><td><span class="pill ${item.status === 'CLOSED' ? 'good' : 'info'}">${esc(item.status)}</span></td><td>${item.r_multiple == null ? '—' : num(item.r_multiple,3)}</td><td>${esc(item.exit_reason || '—')}</td></tr>`).join('') : '<tr><td colspan="6" class="empty-row">Shadow-сделок пока нет</td></tr>';
  }

  function renderResearchApprovals(items) {
    state.researchApprovals = items || [];
    const body = $('researchApprovalsBody');
    body.innerHTML = state.researchApprovals.length ? state.researchApprovals.map((a) => `<tr><td>${timeText(a.created_at_ms)}</td><td>${esc(a.strategy_id)}:${esc(a.strategy_version)}</td><td><span class="pill ${a.stage === 'REVOKED' ? 'bad' : 'good'}">${esc(a.stage)}</span></td><td>${esc(a.environment)}</td><td class="mono">${esc(String(a.run_id).slice(0,12))}</td><td>${esc(a.actor)}</td><td>${esc(a.reason)}</td></tr>`).join('') : '<tr><td colspan="7" class="empty-row">Допусков пока нет</td></tr>';
  }

  async function refreshResearch() {
    try {
      const [datasets, dataJobs, runs, approvals] = await Promise.all([
        api('/api/v1/research/datasets'), api('/api/v1/research/data-jobs'),
        api('/api/v1/research/runs'), api('/api/v1/research/approvals')
      ]);
      $('researchUnavailable').classList.add('hidden');
      renderResearchDatasets(datasets.items || []);
      renderResearchDataJobs(dataJobs.items || []);
      renderResearchRuns(runs.items || []);
      renderResearchApprovals(approvals.items || []);
      if (state.currentResearchReport?.run_id) {
        const runId = encodeURIComponent(state.currentResearchReport.run_id);
        const [latest, shadowSummary, shadowTrades] = await Promise.all([
          api(`/api/v1/research/runs/${runId}`),
          api(`/api/v1/research/shadow/${runId}/summary`),
          api(`/api/v1/research/shadow?run_id=${runId}&limit=50`),
        ]);
        renderResearchReport(latest);
        renderShadow(shadowSummary, shadowTrades.items || []);
      }
    } catch (error) {
      if (/disabled|503|Research service/i.test(error.message)) $('researchUnavailable').classList.remove('hidden');
      else console.warn('research refresh failed', error);
    }
  }

  async function loadResearchReport(runId) {
    try {
      const report = await api(`/api/v1/research/runs/${encodeURIComponent(runId)}`);
      if (!report.metrics) { showToast(`Run ${String(runId).slice(0,8)}: ${report.status || report.progress || 'в работе'}`); return; }
      renderResearchReport(report);
      const runIdEncoded = encodeURIComponent(runId);
      const [shadowSummary, shadowTrades] = await Promise.all([
        api(`/api/v1/research/shadow/${runIdEncoded}/summary`),
        api(`/api/v1/research/shadow?run_id=${runIdEncoded}&limit=50`),
      ]);
      renderShadow(shadowSummary, shadowTrades.items || []);
      location.hash = '#research';
    } catch (error) { showToast(error.message, 'error'); }
  }

  function startDatasetDownload() {
    const symbol = $('dataSymbol').value.trim().toUpperCase();
    const interval = $('dataInterval').value;
    const start = $('dataStart').value;
    const end = $('dataEnd').value;
    if (!symbol || !start || !end) { showToast('Заполните symbol, начало и конец', 'error'); return; }
    const startMs = Date.parse(`${start}T00:00:00Z`);
    const endMs = Date.parse(`${end}T23:59:59.999Z`);
    if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) { showToast('Неверный диапазон дат', 'error'); return; }
    const expected = `DOWNLOAD ${symbol} ${interval}`;
    openModal({title:'Загрузить исследовательские данные', text:'Backend загрузит публичные закрытые свечи, mark price, funding и доступную историю open interest. Задача выполняется в фоне и фиксирует SHA-256 dataset.', expected, danger:false, onConfirm:async()=>{
      const result = await command('/api/v1/research/datasets/download',{symbol,interval,start_time_ms:startMs,end_time_ms:endMs,include_mark_price:true,include_funding:true,include_open_interest:true,dataset_id:null,confirmation:expected});
      showToast(`Data job ${String(result.job_id).slice(0,8)} поставлен в очередь`,'success');
      await refreshResearch();
    }});
  }

  function startResearchRun() {
    const datasetId = $('researchDataset').value;
    const mode = $('researchMode').value;
    if (!datasetId) { showToast('Сначала выберите dataset', 'error'); return; }
    const expected = `RUN ${datasetId}`;
    openModal({title:'Запустить walk-forward', text:`Будет выполнен purged walk-forward, bootstrap, stress suite и сравнение baseline. Профиль симуляции: ${mode}.`, expected, danger:false, onConfirm:async()=>{
      const result = await command('/api/v1/research/runs',{dataset_id:datasetId,mode,confirmation:expected});
      showToast(`Research run ${String(result.run_id).slice(0,8)} поставлен в очередь`,'success');
      await refreshResearch();
    }});
  }

  function promoteResearch(stage) {
    const report = state.currentResearchReport;
    if (!report?.run_id || !report?.gate?.passed) { showToast('Выберите run с PASS', 'error'); return; }
    const expected = `PROMOTE ${stage}`;
    openModal({title:`Продвижение стратегии: ${stage}`, text:'Это не включает live автоматически. Fingerprint стратегии и dataset будут зафиксированы в durable approval.', expected, danger:stage === 'TESTNET', onConfirm:async()=>{
      await command(`/api/v1/research/runs/${encodeURIComponent(report.run_id)}/promote`,{stage,reason:`Approved from dashboard for ${stage}`,confirmation:expected,expires_at_ms:null});
      showToast(`Создан допуск ${stage}`,'success');
      await refreshResearch();
    }});
  }

  function drawChart(items) {
    const canvas = $('equityChart');
    const ctx = canvas.getContext('2d');
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth || 700;
    const height = 250;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    ctx.scale(ratio, ratio);
    ctx.clearRect(0, 0, width, height);
    const values = items.map((x) => Number(x.equity)).filter(Number.isFinite);
    if (values.length < 2) {
      ctx.fillStyle = '#8692a8'; ctx.font = '12px system-ui'; ctx.fillText('Недостаточно телеметрии', 18, 32); return;
    }
    const min = Math.min(...values), max = Math.max(...values);
    const span = Math.max(max - min, Math.abs(max) * .001, 1);
    const pad = 24;
    ctx.strokeStyle = '#263043'; ctx.lineWidth = 1;
    for (let i=0;i<4;i++) { const y = pad + i*(height-2*pad)/3; ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(width-pad,y); ctx.stroke(); }
    const points = values.map((value, i) => ({x: pad + i*(width-2*pad)/(values.length-1), y: height-pad-(value-min)/span*(height-2*pad)}));
    const gradient = ctx.createLinearGradient(0,0,0,height); gradient.addColorStop(0,'rgba(124,140,255,.30)'); gradient.addColorStop(1,'rgba(124,140,255,0)');
    ctx.beginPath(); ctx.moveTo(points[0].x,height-pad); points.forEach((p)=>ctx.lineTo(p.x,p.y)); ctx.lineTo(points.at(-1).x,height-pad); ctx.closePath(); ctx.fillStyle=gradient; ctx.fill();
    ctx.beginPath(); points.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y)); ctx.strokeStyle='#8e9aff'; ctx.lineWidth=2; ctx.stroke();
    ctx.fillStyle='#8692a8'; ctx.font='11px system-ui'; ctx.fillText(num(max,2), pad, 14); ctx.fillText(num(min,2), pad, height-5);
    $('chartPeriod').textContent = `${items.length} точек`;
  }

  async function refreshAll() {
    if (!state.token) return;
    try {
      const [status, modes, positions, orders, executions, audit, telemetry] = await Promise.all([
        api('/api/v1/status'), api('/api/v1/modes'), api('/api/v1/positions'), api('/api/v1/orders'), api('/api/v1/executions?limit=100'), api('/api/v1/audit?limit=100'), api('/api/v1/telemetry?limit=720')
      ]);
      state.status = status; state.modes = modes.items || [];
      renderStatus(status); renderPositions(positions.items || []); renderOrders(orders.items || []); renderExecutions(executions.items || []); renderAudit(audit.items || []); drawChart(telemetry.items || []);
      await refreshResearch();
    } catch (error) {
      if (/401|token|Unauthorized|Invalid bearer/i.test(error.message)) showLogin(error.message);
      else showToast(error.message, 'error');
    }
  }

  function openModal({title, text, expected, confirmLabel = 'Подтвердить', danger = true, extra = '', onConfirm}) {
    $('modalTitle').textContent = title;
    $('modalText').textContent = text;
    $('modalInput').value = '';
    $('modalInputLabel').firstChild.textContent = `Введите: ${expected} `;
    $('modalExtra').innerHTML = extra;
    $('modalError').textContent = '';
    $('modalConfirm').textContent = confirmLabel;
    $('modalConfirm').className = `button ${danger ? 'danger' : 'success'}`;
    state.modalAction = async () => {
      if ($('modalInput').value.trim() !== expected) { $('modalError').textContent = 'Фраза подтверждения не совпадает.'; return; }
      $('modalConfirm').disabled = true;
      try { await onConfirm(); closeModal(); await refreshAll(); }
      catch (error) { $('modalError').textContent = error.message; }
      finally { $('modalConfirm').disabled = false; }
    };
    $('modal').classList.remove('hidden');
    setTimeout(() => $('modalInput').focus(), 50);
  }
  function closeModal() { $('modal').classList.add('hidden'); state.modalAction = null; }
  async function command(path, body) { return api(path, {method:'POST', headers:{'Idempotency-Key':uuid()}, body:JSON.stringify(body)}); }

  function confirmMode(mode) {
    const profile = state.modes.find((item) => item.mode === mode);
    openModal({title:`Переключить режим: ${profile?.title || mode}`, text:'Профиль применяется только при плоском портфеле. Backend остановит admission, проверит позиции, ордера и saga, затем выполнит reconciliation.', expected:mode, danger:mode === 'PROFITABILITY' || mode === 'PAUSED', onConfirm:async()=>{ await command('/api/v1/commands/mode',{mode,confirmation:mode}); showToast(`Режим ${profile?.title || mode} применён`,'success'); }});
  }
  function confirmHalt() { openModal({title:'Остановить новые входы', text:'Существующие стопы, supervisor и recovery продолжат работу. Процесс не будет завершён.', expected:'HALT', onConfirm:async()=>{ await command('/api/v1/commands/halt',{reason:'Manual halt from dashboard',confirmation:'HALT'}); showToast('Новые входы остановлены','success'); }}); }
  function confirmReconcile(resume) { const expected=resume?'RESUME':'RECONCILE'; openModal({title:resume?'Сверить и открыть admission':'Запустить reconciliation', text:resume?'Admission откроется только при unresolved=0 и здоровом private stream.':'Состояние будет сверено, но admission останется закрытым.', expected, danger:false, onConfirm:async()=>{ const result=await command('/api/v1/commands/reconcile',{resume_on_success:resume,confirmation:expected}); showToast(`Reconciliation: unresolved=${result.unresolved}`,'success'); }}); }

  async function confirmFlatten() {
    try {
      const plan = await api('/api/v1/commands/flatten/plan',{method:'POST',body:JSON.stringify({symbol:null})});
      const targets = (plan.targets || []).map((x)=>`${esc(x.symbol)} ${esc(x.position_side)} qty=${esc(x.quantity)}`).join('<br>');
      const extra = `<div class="plan-box"><strong>План ${esc(plan.plan_id.slice(0,10))}</strong><br>${targets}<br>Regular cancel: ${plan.regular_orders_to_cancel}<br>Algo cancel после flat: ${plan.algo_orders_to_cancel_after_flat}<br>Истекает: ${timeText(plan.expires_at_ms)}</div>`;
      openModal({title:'Аварийное закрытие портфеля', text:'Сначала отменяются regular-ордера, затем отправляются reduce-only MARKET закрытия. Защитные algo-стопы сохраняются до подтверждения flat. Admission останется закрытым.', expected:plan.confirmation_phrase, extra, onConfirm:async()=>{ await command('/api/v1/commands/flatten/confirm',{plan_id:plan.plan_id,confirmation_phrase:plan.confirmation_phrase}); showToast('Портфель подтверждён как flat','success'); }});
    } catch (error) { showToast(error.message,'error'); }
  }

  function setup() {
    $('loginForm').addEventListener('submit', authenticate);
    $('logoutButton').addEventListener('click', logout);
    $('haltButton').addEventListener('click', confirmHalt);
    $('reconcileButton').addEventListener('click', () => confirmReconcile(false));
    $('resumeButton').addEventListener('click', () => confirmReconcile(true));
    $('flattenButton').addEventListener('click', confirmFlatten);
    $('downloadDatasetButton').addEventListener('click', startDatasetDownload);
    $('researchRunButton').addEventListener('click', startResearchRun);
    $('promoteResearchButton').addEventListener('click', () => promoteResearch('RESEARCH_PASSED'));
    $('promoteShadowButton').addEventListener('click', () => promoteResearch('SHADOW'));
    $('promoteTestnetButton').addEventListener('click', () => promoteResearch('TESTNET'));
    $('modalClose').addEventListener('click', closeModal); $('modalCancel').addEventListener('click', closeModal); $('modalConfirm').addEventListener('click', () => state.modalAction?.());
    $('modal').addEventListener('click', (e) => { if (e.target === $('modal')) closeModal(); });
    const endDate = new Date();
    endDate.setUTCDate(endDate.getUTCDate() - 1);
    const startDate = new Date(endDate);
    startDate.setUTCDate(startDate.getUTCDate() - 180);
    $('dataEnd').value = endDate.toISOString().slice(0,10);
    $('dataStart').value = startDate.toISOString().slice(0,10);
    setInterval(() => { $('clock').textContent = new Date().toLocaleTimeString('ru-RU'); }, 1000);
    setInterval(refreshAll, 10000);
    window.addEventListener('resize', async () => { try { const data=await api('/api/v1/telemetry?limit=720'); drawChart(data.items||[]); } catch (_) {} });
    document.querySelectorAll('.sidebar nav a').forEach((link)=>link.addEventListener('click',()=>{document.querySelectorAll('.sidebar nav a').forEach((x)=>x.classList.remove('active'));link.classList.add('active');}));
    if (state.token) { hideLogin(); connectSocket(); refreshAll(); } else showLogin();
  }

  document.addEventListener('DOMContentLoaded', setup);
})();
