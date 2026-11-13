const $ = id => document.getElementById(id);
const NOT_SCHOOL_EMAIL = '가천대학교 이메일로 인증해주세요';
let options = null;
let me = null;
let publicItems = [];

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function status(id, text, isError = false) { $(id).textContent = text || ''; $(id).className = isError ? 'error' : ''; }
function localInput(date) { const d = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); }
function fmt(value) { return value ? new Date(value).toLocaleString('ko-KR', {dateStyle: 'short', timeStyle: 'short'}) : '모름'; }
function fmtDate(value) { return value ? new Date(value).toLocaleDateString('ko-KR') : '모름'; }

// 서버 오류 응답 {"detail": {"message", "questions"}} 을 사람이 읽을 문장으로 바꾼다
function errorText(detail, code) {
  if (!detail) return code === 503 ? '지금은 처리할 수 없어요. 잠시 후 다시 시도해 주세요.' : `요청을 처리하지 못했습니다 (${code}).`;
  if (typeof detail === 'string') return detail;
  return detail.message + (detail.questions?.length ? '\n· ' + detail.questions.join('\n· ') : '');
}
async function api(path, {method = 'GET', body} = {}) {
  const headers = body ? {'Content-Type': 'application/json'} : {};
  const response = await fetch(path, {method, headers, body: body ? JSON.stringify(body) : undefined});
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(errorText(data?.detail, response.status)); error.code = response.status;
    if (response.status === 401 && !path.startsWith('/api/auth/')) showLogin();
    throw error;
  }
  return data;
}
function fillSelect(select, items, toOption) {
  for (const item of items) { const [value, label] = toOption(item); const option = el('option', label); option.value = value; select.append(option); }
}
function button(text, onClick, className = 'secondary') { const b = el('button', text, className); b.type = 'button'; b.onclick = onClick; return b; }
function cell(...children) { const td = el('td'); td.append(...children); return td; }
function stageBadge(stage) { return el('span', stage === 2 ? '2단계 · 보완' : '1단계 · 기본', 'stage s' + stage); }

// ---------- R11 로그인 ----------
function showLogin() { $('app-view').hidden = true; $('login-view').hidden = false; }
// 학교 이메일(또는 등록된 담당자 이메일)이 아니면 서버가 '가천대학교 이메일로 인증해주세요'로 거절하고, 화면은 알림을 띄운다.
// 담당자 목록은 서버만 알기 때문에 화면에서 미리 거르지 않는다.
function authError(id, error) { if (error.message === NOT_SCHOOL_EMAIL) alert(NOT_SCHOOL_EMAIL); status(id, error.message, true); }
function setMode(signup) {
  $('mode-login').setAttribute('aria-pressed', !signup); $('mode-signup').setAttribute('aria-pressed', signup);
  $('login-form').hidden = signup; $('code-form').hidden = !signup; $('verify-form').hidden = true;
}
$('mode-login').onclick = () => setMode(false);
$('mode-signup').onclick = () => setMode(true);

$('login-form').onsubmit = async event => {
  event.preventDefault();
  status('login-status', '로그인 중…');
  try {
    await api('/api/auth/login', {method: 'POST', body: {email: $('login-email').value, password: $('login-password').value}});
    status('login-status', ''); $('login-password').value = ''; await enterApp();
  } catch (error) { authError('login-status', error); }
};
$('code-form').onsubmit = async event => {
  event.preventDefault();
  $('code-button').disabled = true;
  status('code-status', '보내는 중…');
  try {
    const result = await api('/api/auth/code', {method: 'POST', body: {email: $('signup-email').value}});
    status('code-status', result.message);
    $('verify-form').hidden = false; $('code-button').textContent = '인증번호 다시 받기'; $('verify-code').focus();
  } catch (error) { authError('code-status', error); }
  finally { $('code-button').disabled = false; }
};
$('verify-form').onsubmit = async event => {
  event.preventDefault();
  if ($('verify-password').value !== $('verify-password2').value) { status('verify-status', '비밀번호 확인이 맞지 않습니다.', true); return; }
  status('verify-status', '확인 중…');
  try {
    await api('/api/auth/verify', {method: 'POST', body: {email: $('signup-email').value, code: $('verify-code').value, password: $('verify-password').value}});
    $('verify-form').reset(); status('verify-status', ''); await enterApp();
  } catch (error) { authError('verify-status', error); }
};
$('logout').onclick = async () => { await api('/api/auth/logout', {method: 'POST'}).catch(() => null); me = null; setMode(false); showLogin(); };

// ---------- 화면 전환 ----------
function showPage(name) {
  for (const b of document.querySelectorAll('[data-page]')) b.toggleAttribute('aria-current', b.dataset.page === name);
  for (const page of document.querySelectorAll('.page')) page.hidden = page.id !== 'page-' + name;
  if (name === 'list') loadList();
  if (name === 'mine') loadMine();
  if (name === 'office') loadOffice();
}
for (const b of document.querySelectorAll('[data-page]')) b.onclick = () => showPage(b.dataset.page);

// ---------- R3 공개 목록 ----------
// 1단계 물건은 아직 공개 정보가 없으니, 언제 더 공개되는지 알려 준다
function daysSince(date) { return Math.floor((Date.now() - new Date(date + 'T00:00:00+09:00')) / 86400000); }
function publicInfo(item) {
  if (item.stage !== 2) {
    if (!item.found_date) return '담당자 확인 후 추가 공개';
    return daysSince(item.found_date) >= 7 ? '담당자 확인 후 추가 공개 예정' : `보관 7일 후 추가 공개 (${7 - daysSince(item.found_date)}일 남음)`;
  }
  return [item.color, item.place_detail, item.feat_brand, item.feat_attached, item.feat_damage, item.feat_contents]
    .filter(Boolean).join(' · ') || '—';
}
// R3 정렬: 습득일(최신·오래된), 공개 단계(낮은·높은). 같은 단계 안에서는 습득일 최신순. 습득일 모름은 항상 맨 뒤
function byDate(a, b, dir) {
  if (!a.found_date !== !b.found_date) return a.found_date ? -1 : 1;
  const d = (a.found_date || '').localeCompare(b.found_date || '');
  return dir * (d || a.receipt_no.localeCompare(b.receipt_no));
}
const SORTS = {
  'date-desc': (a, b) => byDate(a, b, -1),
  'date-asc': (a, b) => byDate(a, b, 1),
  'stage-asc': (a, b) => a.stage - b.stage || byDate(a, b, -1),
  'stage-desc': (a, b) => b.stage - a.stage || byDate(a, b, -1),
};
function renderList() {
  const building = $('list-building').value;
  const shown = publicItems.filter(i => !building || i.building === building).sort(SORTS[$('list-sort').value]);
  $('item-rows').replaceChildren(...shown.map(item => {
    const tr = el('tr');
    tr.append(el('td', item.receipt_no, 'muted'), el('td', item.category, 'strong'), el('td', item.building),
      el('td', item.found_date || '모름'), cell(stageBadge(item.stage)), el('td', publicInfo(item), item.stage === 2 ? '' : 'muted small'));
    return tr;
  }));
  $('list-count').textContent = `${shown.length}건`;
  status('list-status', shown.length ? '' : '조건에 맞는 보관 중인 물건이 없습니다.');
}
async function loadList() {
  status('list-status', '불러오는 중…');
  try {
    publicItems = await api('/api/items?' + new URLSearchParams({category: $('list-category').value}));
    renderList();
  } catch (error) { status('list-status', error.message, true); }
}
$('list-category').onchange = loadList;
$('list-building').onchange = renderList;
$('list-sort').onchange = renderList;

// ---------- R1 습득자 등록 ----------
$('found-form').onsubmit = async event => {
  event.preventDefault();
  status('found-status', '등록 중…');
  try {
    const result = await api('/api/items', {method: 'POST', body: {
      building_id: Number($('found-building').value), place_detail: $('found-place').value, found_at: $('found-at').value}});
    status('found-status', `접수번호 ${result.receipt_no}. ${result.office}(${result.office_location})에 맡겨 주세요. 담당자가 확인하면 목록에 올라갑니다.`);
    $('found-place').value = '';
  } catch (error) { status('found-status', error.message, true); }
};

// ---------- R4, R5 분실 신고와 매칭 결과 ----------
function renderMatch(box, result) {
  box.replaceChildren(el('h2', `신고번호 ${result.report_no}`));
  if (result.message) box.append(el('p', result.message, result.ai_status === 'unavailable' ? 'notice' : 'muted'));
  if (result.matches.length) {
    const list = el('div', undefined, 'matches');
    for (const m of result.matches) {
      const card = el('article', undefined, 'match');
      const atPolice = m.office !== options.office;
      card.append(el('span', `일치 가능성 ${m.grade}`, m.grade === '높음' ? 'badge strong' : 'badge'),
        el('p', `${m.category} · 접수번호 ${m.receipt_no}`, 'strong'),
        el('p', atPolice ? `${m.office_location}. 파출소에 접수번호를 알려 문의하세요.` : `${m.office}(${m.office_location})에서 확인하세요. 신분증과 신고번호를 가져가세요.`, 'muted'));
      list.append(card);
    }
    box.append(list);
  }
  if (result.fallback?.length) {
    const tbody = el('tbody');
    for (const item of result.fallback) {
      const tr = el('tr');
      tr.append(el('td', item.receipt_no, 'muted'), el('td', item.category, 'strong'), el('td', item.building),
        el('td', item.found_date || '모름'), cell(stageBadge(item.stage)), el('td', publicInfo(item), item.stage === 2 ? '' : 'muted small'));
      tbody.append(tr);
    }
    const table = el('table'); const head = el('thead'); const hr = el('tr');
    for (const h of ['접수번호', '종류', '습득 장소', '습득일', '공개 단계', '공개 정보']) { const th = el('th', h); th.scope = 'col'; hr.append(th); }
    head.append(hr); table.append(head, tbody);
    const wrap = el('div', undefined, 'table-box'); wrap.append(table); box.append(wrap);
  }
}
function selectedBuildings() { return [...document.querySelectorAll('#lost-buildings input:checked')].map(i => Number(i.value)); }
$('lost-form').onsubmit = async event => {
  event.preventDefault();
  $('lost-submit').disabled = true;
  status('lost-status', 'AI가 보관 중인 물건과 비교하고 있어요… (30초 정도 걸릴 수 있어요)');
  $('lost-result').replaceChildren();
  try {
    const result = await api('/api/reports', {method: 'POST', body: {
      category: $('lost-category').value, color: $('lost-color').value,
      feat_brand: $('lost-brand').value, feat_attached: $('lost-attached').value,
      feat_damage: $('lost-damage').value, feat_contents: $('lost-contents').value,
      building_ids: selectedBuildings(), lost_from: $('lost-from').value, lost_to: $('lost-to').value}});
    status('lost-status', '신고했습니다. 나중에 \'내 신고\'에서 다시 찾거나 취소할 수 있습니다.');
    renderMatch($('lost-result'), result);
  } catch (error) { status('lost-status', error.message, true); }
  finally { $('lost-submit').disabled = false; }
};

// ---------- R8 내 신고 ----------
const REPORT_STATUS = {active: '진행 중', resolved: '찾음', cancelled: '취소'};
function features(r) { return [r.color, r.feat_brand, r.feat_attached, r.feat_damage, r.feat_contents].filter(Boolean).join(' · '); }
async function loadMine() {
  status('mine-status', '불러오는 중…');
  try {
    const reports = await api('/api/reports/mine');
    $('mine-rows').replaceChildren(...reports.map(r => {
      const tr = el('tr');
      const actions = el('div', undefined, 'actions');
      if (r.status === 'active') {
        actions.append(button('다시 찾기', async () => {
          status('mine-status', '찾는 중…');
          try { renderMatch($('mine-result'), await api(`/api/reports/${r.report_no}/match`, {method: 'POST'})); status('mine-status', ''); }
          catch (error) { status('mine-status', error.message, true); }
        }, 'primary'), button('취소', async () => {
          try { await api(`/api/reports/${r.report_no}/cancel`, {method: 'POST'}); $('mine-result').replaceChildren(); loadMine(); }
          catch (error) { status('mine-status', error.message, true); }
        }));
      }
      tr.append(el('td', r.report_no, 'muted'), el('td', r.category, 'strong'), el('td', features(r)), el('td', fmtDate(r.created_at)),
        el('td', REPORT_STATUS[r.status]), cell(actions));
      return tr;
    }));
    status('mine-status', reports.length ? '' : '아직 신고한 물건이 없습니다.');
  } catch (error) { status('mine-status', error.message, true); }
}

// ---------- 보관소 (담당자) ----------
function featureText(item) {
  return [['브랜드·모델', item.feat_brand], ['부착물·장식', item.feat_attached], ['손상·흠집', item.feat_damage], ['내용물·기타', item.feat_contents]]
    .filter(([, v]) => v).map(([k, v]) => `${k}: ${v}`);
}
function itemCell(item) {
  const td = el('td');
  td.append(el('p', [item.category || '세부 사항 미입력', item.color].filter(Boolean).join(' · '), 'strong'));
  for (const line of featureText(item)) td.append(el('p', line, 'small'));
  if (item.secret_feature) td.append(el('p', `확인용 특징: ${item.secret_feature}`, 'secret'));
  if (item.registered_by) td.append(el('p', `등록: ${item.registered_by}`, 'small muted'));
  return td;
}
function officeRow(item, reportNo = '') {
  const tr = el('tr');
  const keep = el('td');
  if (item.status !== 'waiting') keep.append(el('p', `${item.days_kept}일째`));
  if (item.needs_review) keep.append(el('span', '보완 필요', 'badge strong'));
  if (item.needs_transfer) keep.append(el('span', '인계 대상', 'badge strong'));
  if (item.reviewed_at) keep.append(el('p', '공개 2단계', 'small muted'));
  const actions = el('div', undefined, 'actions');
  if (item.status === 'waiting') actions.append(button('세부 사항 입력', () => openDetail(item.receipt_no), 'primary'));
  if (item.needs_review) actions.append(button('특징 보완', () => openReview(item), 'primary'));
  if (item.status === 'stored') actions.append(button('수령 처리', () => openReturn(item, reportNo)));
  if (item.needs_transfer) actions.append(button('경찰 인계', () => openTransfer(item)));
  if (item.status === 'transferred') actions.append(el('p', `인계 ${fmt(item.transferred_at)} · ${item.transfer_officer}${item.transfer_note ? ' · ' + item.transfer_note : ''}`, 'small'));
  if (item.status === 'returned') actions.append(el('p', `수령 ${item.receiver_name} (${item.receiver_no}, ${item.receiver_phone}, ${item.id_type}) ${fmt(item.handed_at)} · 처리 ${item.handled_by}`, 'small'));
  tr.append(el('td', item.receipt_no, 'muted'), itemCell(item), el('td', `${item.building || '모름'} ${item.place_detail || ''}\n${fmt(item.found_at)}`, 'pre'), keep, cell(actions));
  return tr;
}
async function loadOffice() {
  status('office-status-text', '불러오는 중…');
  try {
    const items = await api('/api/office/items?status=' + $('office-status').value);
    $('office-rows').replaceChildren(...items.map(i => officeRow(i)));
    officeCount = items.length;
    $('transfer-all').hidden = !($('office-status').value === 'transfer' && items.length);
    status('office-status-text', items.length ? `${items.length}건` : '해당 상태의 물건이 없습니다.');
  } catch (error) { status('office-status-text', error.message, true); }
}
$('office-status').onchange = loadOffice;
let officeCount = 0;
$('transfer-all').onclick = () => openTransfer(null);

// R2 세부 사항 입력 / 직접 등록
let detailTarget = null;
function openDetail(receiptNo) {
  detailTarget = receiptNo;
  $('detail-form').reset();
  $('direct-fields').hidden = receiptNo !== null;
  $('detail-title').textContent = receiptNo ? `${receiptNo} 세부 사항 입력` : '직접 등록';
  status('detail-status', '');
  $('detail-dialog').showModal();
}
$('direct-register').onclick = () => openDetail(null);
$('close-detail').onclick = () => $('detail-dialog').close();
$('detail-form').onsubmit = async event => {
  event.preventDefault();
  const body = {category: $('d-category').value, color: $('d-color').value, feat_brand: $('d-brand').value,
    feat_attached: $('d-attached').value, feat_damage: $('d-damage').value, feat_contents: $('d-contents').value,
    secret_feature: $('d-secret').value};
  try {
    if (detailTarget) await api(`/api/office/items/${detailTarget}/store`, {method: 'POST', body});
    else await api('/api/office/items', {method: 'POST', body: {...body,
      building_id: $('d-building').value ? Number($('d-building').value) : null, place_detail: $('d-place').value,
      found_at: $('d-found-at').value || null}});
    $('detail-dialog').close(); loadOffice();
  } catch (error) { status('detail-status', error.message, true); }
};

// R9 공개 정보 보완
let reviewTarget = null;
function openReview(item) {
  reviewTarget = item.receipt_no;
  $('review-form').reset();
  // 처음 입력한 특징을 채워 두고, 담당자가 고치거나 더한다
  $('v-brand').value = item.feat_brand || ''; $('v-attached').value = item.feat_attached || '';
  $('v-damage').value = item.feat_damage || ''; $('v-contents').value = item.feat_contents || '';
  $('review-title').textContent = `${item.receipt_no} 특징 보완 (보관 ${item.days_kept}일째)`;
  $('review-secret').textContent = `확인용 특징(공개 금지): ${item.secret_feature}`;
  status('review-status', '');
  $('review-dialog').showModal();
}
$('close-review').onclick = () => $('review-dialog').close();
$('review-form').onsubmit = async event => {
  event.preventDefault();
  try {
    await api(`/api/office/items/${reviewTarget}/review`, {method: 'POST', body: {
      feat_brand: $('v-brand').value, feat_attached: $('v-attached').value,
      feat_damage: $('v-damage').value, feat_contents: $('v-contents').value}});
    $('review-dialog').close(); loadOffice();
  } catch (error) { status('review-status', error.message, true); }
};

// R10 경찰 인계
let transferTarget = null;
// item이 없으면 인계 대상 전체를 한 번에 인계
function openTransfer(item) {
  transferTarget = item ? item.receipt_no : null;
  $('transfer-form').reset();
  $('transfer-title').textContent = item ? `${item.receipt_no} 경찰 인계 (보관 ${item.days_kept}일째)` : `인계 대상 ${officeCount}건 모두 경찰 인계`;
  status('transfer-status', '');
  $('transfer-dialog').showModal();
}
$('close-transfer').onclick = () => $('transfer-dialog').close();
$('transfer-form').onsubmit = async event => {
  event.preventDefault();
  try {
    const body = {officer_name: $('t-officer').value, note: $('t-note').value};
    let done = '';
    if (transferTarget) await api(`/api/office/items/${transferTarget}/transfer`, {method: 'POST', body});
    else { const r = await api('/api/office/transfer-all', {method: 'POST', body}); done = `${r.count}건 인계 완료: ${r.receipt_nos.join(', ')}`; }
    $('transfer-dialog').close();
    await loadOffice();
    if (done) status('office-status-text', done);
  } catch (error) { status('transfer-status', error.message, true); }
};

// R6 신고 조회: 신고 내용, AI 점수와 칸별 판정
const VERDICT_LABELS = {brand: '브랜드·모델', attached: '부착물·장식', damage: '손상·흠집', contents: '내용물·기타', secret: '확인용 특징'};
$('report-lookup').onsubmit = async event => {
  event.preventDefault();
  status('lookup-status', '조회 중…');
  try {
    const data = await api(`/api/office/reports/${encodeURIComponent($('lookup-no').value.trim())}`);
    const r = data.report;
    const view = $('report-view');
    const info = el('div', undefined, 'panel');
    info.append(el('p', `${r.report_no} · ${r.category} · ${r.color} (${REPORT_STATUS[r.status]})`, 'strong'),
      el('p', `신고자 ${r.user_email} · 장소 ${r.buildings.join(', ') || '모름'} · ${fmt(r.lost_from)} ~ ${fmt(r.lost_to)}`, 'small'),
      ...featureText(r).map(line => el('p', line, 'small')));
    view.replaceChildren(info);
    if (!data.matches.length) view.append(el('p', 'AI 매칭 결과가 없습니다. 아래는 종류·시간 조건이 맞는 후보입니다.', 'notice'));
    const tbody = el('tbody');
    for (const m of data.matches) {
      const row = officeRow(m, r.status === 'active' ? r.report_no : '');
      const score = el('td');
      score.append(el('span', `${m.score}점`, m.score >= 85 ? 'badge strong' : 'badge'));
      const verdicts = el('div', undefined, 'verdicts');
      for (const [key, label] of Object.entries(VERDICT_LABELS)) verdicts.append(el('span', `${label} ${m.field_verdicts[key]}`, 'verdict v-' + m.field_verdicts[key].replace(' ', '')));
      score.append(verdicts, el('p', m.reason, 'small muted'));
      row.children[3].replaceWith(score);
      tbody.append(row);
    }
    for (const c of data.candidates) tbody.append(officeRow(c, r.status === 'active' ? r.report_no : ''));
    const table = el('table'); const head = el('thead'); const hr = el('tr');
    for (const h of ['접수번호', '물건', '습득', data.matches.length ? 'AI 판정' : '보관', '동작']) { const th = el('th', h); th.scope = 'col'; hr.append(th); }
    head.append(hr); table.append(head, tbody);
    const wrap = el('div', undefined, 'table-box'); wrap.append(table); view.append(wrap);
    status('lookup-status', '');
  } catch (error) { status('lookup-status', error.message, true); }
};

// R6 수령 처리
let returnTarget = null;
function openReturn(item, reportNo) {
  returnTarget = {receipt: item.receipt_no, reportNo};
  $('return-form').reset();
  $('return-title').textContent = `${item.receipt_no} 수령 처리${reportNo ? ' · ' + reportNo : ''}`;
  $('return-secret').textContent = `확인용 특징: ${item.secret_feature}`;
  status('return-status', '');
  $('return-dialog').showModal();
}
$('close-return').onclick = () => $('return-dialog').close();
$('return-form').onsubmit = async event => {
  event.preventDefault();
  try {
    await api(`/api/office/items/${returnTarget.receipt}/return`, {method: 'POST', body: {
      report_no: returnTarget.reportNo, receiver_name: $('r-name').value, receiver_no: $('r-no').value,
      receiver_phone: $('r-phone').value, id_type: $('r-id-type').value}});
    $('return-dialog').close(); loadOffice();
    if (returnTarget.reportNo) $('report-lookup').requestSubmit();
  } catch (error) { status('return-status', error.message, true); }
};

// ---------- 시작 ----------
async function enterApp() {
  me = await api('/api/auth/me');
  $('me-email').textContent = me.email;
  for (const node of document.querySelectorAll('[data-staff]')) node.hidden = !me.staff;
  $('login-view').hidden = true; $('app-view').hidden = false;
  showPage('list');
}
(async () => {
  try {
    options = await api('/api/options');
    fillSelect($('list-category'), options.categories, c => [c, c]);
    fillSelect($('list-building'), options.buildings, b => [b.name, b.name]);
    for (const id of ['lost-category', 'd-category']) fillSelect($(id), options.categories, c => [c, c]);
    fillSelect($('lost-color'), ['모름', ...options.colors], c => [c, c]);
    fillSelect($('d-color'), options.colors, c => [c, c]);
    for (const id of ['found-building', 'd-building']) fillSelect($(id), options.buildings, b => [b.id, b.name]);
    const office = `보관 장소: ${options.office} (${options.office_location})`;
    $('office-line').textContent = office; $('office-line2').textContent = office;
    $('office-hint').textContent = `등록한 뒤 ${options.office}(${options.office_location})에 맡겨 주세요.`;
    for (const b of options.buildings) {
      const label = el('label', undefined, 'check'); const box = el('input'); box.type = 'checkbox'; box.value = b.id;
      label.append(box, document.createTextNode(b.name)); $('lost-buildings').append(label);
    }
    const now = new Date();
    $('found-at').value = localInput(now); $('lost-to').value = localInput(now);
    $('lost-from').value = localInput(new Date(now.getTime() - 8 * 3600000));
    $('runtime').textContent = `${options.version} · AI ${options.ai ? '사용' : '꺼짐'}`;
  } catch (error) { document.body.prepend(el('p', '서버에 연결할 수 없습니다: ' + error.message, 'error')); return; }
  try { await enterApp(); } catch { showLogin(); }
})();
