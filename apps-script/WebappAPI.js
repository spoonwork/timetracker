/**
 * 타임트래커 웹앱 백엔드
 * Google Sheets ↔ 웹앱/폰 위젯 양방향 동기화
 */

// 시트 ID는 배포할 때 자동으로 채워짐 (공개 저장소라 코드에는 넣지 않음)
const SPREADSHEET_ID = '__SHEET_ID__';

function openSS() {
  try {
    const active = SpreadsheetApp.getActiveSpreadsheet();
    if (active) return active;
  } catch (err) {}
  return SpreadsheetApp.openById(SPREADSHEET_ID);
}

// 웹앱 데이터 저장용 시트
const APP_DATA_SHEET = '앱데이터';

// =============== 초기 설정 ===============
function setupAppDataSheet() {
  const ss = openSS();
  let sheet = ss.getSheetByName(APP_DATA_SHEET);
  if (!sheet) {
    sheet = ss.insertSheet(APP_DATA_SHEET);
    sheet.getRange('A1:B1').setValues([['key', 'value']]);
    sheet.setFrozenRows(1);
    sheet.setColumnWidth(1, 200);
    sheet.setColumnWidth(2, 800);
    Logger.log('앱데이터 시트 생성됨');
  }
  return sheet;
}

// =============== GET (웹앱이 데이터 요청) ===============
function doGet(e) {
  try {
    setupAppDataSheet();
    const ss = openSS();

    // 작업캘린더 구조 확인용 (날짜·색만 반환)
    if (e && e.parameter && e.parameter.mode === 'workcal_shape') {
      return makeJsonResponse(workCalShape(e.parameter.gid));
    }
    if (e && e.parameter && e.parameter.mode === 'workcal_debug') {
      return makeJsonResponse(workCalDebug(e.parameter.gid));
    }

    // ?mode=widget&date=YYYY-MM-DD → 폰 위젯용 요약
    if (e && e.parameter && e.parameter.mode === 'widget') {
      return makeJsonResponse(buildWidgetSummary(ss, e.parameter.date, e.parameter.from, e.parameter.to));
    }

    // ?mode=appdata 이면 logs/수동입력은 건너뜀 (PC 타이머 위젯용, 가볍게)
    const appDataOnly = e && e.parameter && e.parameter.mode === 'appdata';

    // logs 가져오기
    const logsSheet = appDataOnly ? null : ss.getSheetByName('logs');
    const logsData = logsSheet ? logsSheet.getDataRange().getValues() : [];

    // 수동입력 가져오기
    const manualSheet = appDataOnly ? null : ss.getSheetByName('수동입력');
    const manualData = manualSheet ? manualSheet.getDataRange().getValues() : [];

    // 앱 데이터 가져오기 (manualBlocks, todos, plans, see, markers)
    const appSheet = ss.getSheetByName(APP_DATA_SHEET);
    const appData = appSheet ? appSheet.getDataRange().getValues() : [];
    const appDataObj = {};
    for (let i = 1; i < appData.length; i++) {
      const key = appData[i][0];
      const val = appData[i][1];
      if (key && val) {
        try {
          appDataObj[key] = JSON.parse(val);
        } catch (e) {
          appDataObj[key] = val;
        }
      }
    }

    return makeJsonResponse({
      ok: true,
      logs: logsData,
      workCal: appDataOnly ? undefined : workCalColors(e && e.parameter && e.parameter.fresh === '1'),
      workTasks: appDataOnly ? undefined : (function () {
        const n = new Date();
        const f = new Date(n.getFullYear(), n.getMonth() - 1, 1), l = new Date(n.getFullYear(), n.getMonth() + 2, 0);
        const fmt = function (d) { return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2); };
        return workTasks(fmt(f), fmt(l));
      })(),
      manual: manualData,
      appData: appDataObj,
      timestamp: new Date().toISOString(),
    });
  } catch (err) {
    return makeJsonResponse({ ok: false, error: err.toString() });
  }
}

// =============== POST (웹앱이 데이터 저장) ===============
function doPost(e) {
  try {
    setupAppDataSheet();
    const ss = openSS();
    const appSheet = ss.getSheetByName(APP_DATA_SHEET);

    const body = JSON.parse(e.postData.contents);
    if (body.ops) return makeJsonResponse(applyWidgetOps(ss, appSheet, body.ops));
    const key = body.key;
    const value = body.value;

    if (!key) {
      return makeJsonResponse({ ok: false, error: 'No key' });
    }

    // 기존 키 찾기
    const data = appSheet.getDataRange().getValues();
    let foundRow = -1;
    for (let i = 1; i < data.length; i++) {
      if (data[i][0] === key) {
        foundRow = i + 1;
        break;
      }
    }

    const valueStr = typeof value === 'string' ? value : JSON.stringify(value);

    if (foundRow > 0) {
      appSheet.getRange(foundRow, 2).setValue(valueStr);
    } else {
      appSheet.appendRow([key, valueStr]);
    }

    return makeJsonResponse({ ok: true, saved: key });
  } catch (err) {
    return makeJsonResponse({ ok: false, error: err.toString() });
  }
}

// =============== 폰 위젯 요약 ===============
const WIDGET_DAY_START = 5;

function readAppDataObj(ss) {
  const sheet = ss.getSheetByName(APP_DATA_SHEET);
  const rows = sheet ? sheet.getDataRange().getValues() : [];
  const obj = {};
  for (let i = 1; i < rows.length; i++) {
    if (!rows[i][0] || !rows[i][1]) continue;
    try { obj[rows[i][0]] = JSON.parse(rows[i][1]); } catch (err) { obj[rows[i][0]] = rows[i][1]; }
  }
  return obj;
}

function shiftDate(ds, n) {
  const p = ds.split('-').map(Number);
  const d = new Date(p[0], p[1] - 1, p[2] + n);
  return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
}

function sleepForDay(mb, date) {
  // 수면: 전날 18시 이후 + 당일 11시 전 (웹앱과 같은 규칙)
  let sleepMin = 0;
  (mb[shiftDate(date, -1)] || []).forEach(function (b) {
    if (b.category !== '수면') return;
    const s = Math.max(b.startMin, 780);
    if (b.endMin > s) sleepMin += b.endMin - s;
  });
  (mb[date] || []).forEach(function (b) {
    if (b.category !== '수면') return;
    const s = Math.max(b.startMin, 0), en = Math.min(b.endMin, 360);
    if (en > s) sleepMin += en - s;
  });
  return sleepMin;
}

function toDateVal(v) {
  if (v instanceof Date) return v;
  const d = new Date(v);
  return isNaN(d) ? null : d;
}

/** 날짜별 작업시간(초). 이틀 이상 지난 날은 저장해두고 다시 계산 안 함 */
function workByDay(ss, fromDs, toDs, todayDs, tz) {
  const props = PropertiesService.getScriptProperties();
  let cache = {};
  try { cache = JSON.parse(props.getProperty('workDaily2') || '{}'); } catch (err) { cache = {}; }
  const settled = shiftDate(todayDs, -1);          // 이 날짜보다 전이면 확정
  const out = {}, need = [];
  for (let d = fromDs; d <= toDs && d <= todayDs; d = shiftDate(d, 1)) {
    if (d < settled && cache[d] != null) out[d] = cache[d];
    else need.push(d);
  }
  if (need.length) {
    const minNeed = need[0];
    const acc = {};
    const logs = ss.getSheetByName('logs');
    if (logs && logs.getLastRow() > 1) {
      let end = logs.getLastRow(), done = false, guard = 0;
      while (end >= 2 && !done && guard++ < 40) {
        const start = Math.max(2, end - 1499);
        const rows = logs.getRange(start, 1, end - start + 1, 5).getValues();
        for (let i = rows.length - 1; i >= 0; i--) {
          const r = rows[i];
          const st = toDateVal(r[0]);
          if (!st) continue;
          const ds = Utilities.formatDate(new Date(st.getTime() - WIDGET_DAY_START * 3600000), tz, 'yyyy-MM-dd');
          if (ds < minNeed) { done = true; break; }
          if (String(r[4]) !== 'work') continue;
          const en = toDateVal(r[1]);
          if (!en || en <= st) continue;
          (acc[ds] = acc[ds] || []).push([st.getTime(), en.getTime()]);
        }
        end = start - 1;
      }
    }
    // 겹치거나 중복된 기록은 한 번만 (트래커가 두 개 켜져 있었던 경우 등)
    Object.keys(acc).forEach(function (d) {
      const iv = acc[d].sort(function (a, b) { return a[0] - b[0]; });
      let total = 0, cs = -1, ce = -1;
      iv.forEach(function (x) {
        if (x[0] > ce) { if (ce > cs) total += ce - cs; cs = x[0]; ce = x[1]; }
        else if (x[1] > ce) ce = x[1];
      });
      if (ce > cs) total += ce - cs;
      acc[d] = total / 1000;
    });
    need.forEach(function (d) {
      out[d] = Math.round(acc[d] || 0);
      if (d < settled) cache[d] = out[d];
    });
    const keys = Object.keys(cache).sort();
    const keep = {};
    keys.slice(-300).forEach(function (k) { keep[k] = cache[k]; });
    try { props.setProperty('workDaily2', JSON.stringify(keep)); } catch (err) {}
  }
  return out;
}

/** 작업 캘린더 날짜칸 색 → {yyyy-MM-dd: '#rrggbb'} (3시간 캐시)
 *  녹색·파랑 = 작업일, 빨강 = 쉬는 날, 회색 = 콘티일. 노란색(오늘 표시)이면 옆 칸 색을 씀 */
function workCalColors(fresh) {
  if (!WORKCAL_ID || WORKCAL_ID.indexOf('__') === 0) return {};
  // 작업일은 거의 안 바뀌니까 한 번 읽은 걸 계속 씀 (웹 트래커 🔄 누를 때만 다시 읽음)
  const props = PropertiesService.getScriptProperties();
  const saved = props.getProperty('workcal_saved');
  if (saved && !fresh) { try { return JSON.parse(saved); } catch (err) {} }
  const out = {}, prio = {};
  try {
    const wb = SpreadsheetApp.openById(WORKCAL_ID);
    const tz = wb.getSpreadsheetTimeZone();
    wb.getSheets().forEach(function (sh) {
      const mm = sh.getName().match(/^(?:\d{4}년\s*)?(\d{1,2})월$/);
      if (!mm) return;
      const tabMonth = +mm[1];
      const nr = Math.min(sh.getLastRow(), 200), nc = Math.min(sh.getLastColumn(), 30);
      if (nr < 1 || nc < 1) return;
      const rng = sh.getRange(1, 1, nr, nc);
      const disp = rng.getDisplayValues(), vals = rng.getValues(), bgs = rng.getBackgrounds();
      for (let r = 0; r < nr; r++) for (let c = 0; c < nc; c++) {
        const v = vals[r][c];
        if (!(v instanceof Date) || !DATE_CELL_RE.test(disp[r][c])) continue;
        let bg = String(bgs[r][c] || '').toLowerCase();
        const next = c + 1 < nc ? String(bgs[r][c + 1] || '').toLowerCase() : '';
        if (bg === '#ffd966') { if (!next || next === '#ffffff') continue; bg = next; }   // 노란 오늘 표시 / 제목칸
        if (!bg || bg === '#ffffff') continue;
        const ds = Utilities.formatDate(v, tz, 'yyyy-MM-dd');
        const p = (v.getMonth() + 1 === tabMonth) ? 2 : 1;      // 그 달 탭에 있는 날짜를 우선
        if (!prio[ds] || p >= prio[ds]) { out[ds] = bg; prio[ds] = p; }
      }
    });
  } catch (err) {
    return { _error: String(err) };
  }
  // 저장은 지난 2달 ~ 앞으로 5달만 (저장 용량 제한)
  const lo = Utilities.formatDate(new Date(Date.now() - 62 * 86400000), 'Asia/Seoul', 'yyyy-MM-dd');
  const hi = Utilities.formatDate(new Date(Date.now() + 155 * 86400000), 'Asia/Seoul', 'yyyy-MM-dd');
  const keep = {};
  Object.keys(out).forEach(function (k) { if (k >= lo && k <= hi) keep[k] = out[k]; });
  try { props.setProperty('workcal_saved', JSON.stringify(keep)); } catch (err) {}
  return keep;
}

// =============== 작업 캘린더 '슾' 줄 (내 작업) ===============
const ME_LABEL = '슾';

/** 한 탭을 읽어 주(週) 단위 블록으로: [{dates:{col:ds}, meRows:[r], end:r}] (r, col은 1부터) */
function readWorkTab(sh, tz) {
  const nr = sh.getLastRow(), nc = Math.min(sh.getLastColumn(), 20);
  if (nr < 1 || nc < 3) return { blocks: [], disp: [], vals: [] };
  const rng = sh.getRange(1, 1, nr, nc);
  const disp = rng.getDisplayValues(), vals = rng.getValues();
  const blocks = [];
  let cur = null, label = '';
  for (let r = 0; r < nr; r++) {
    const dates = {};
    let n = 0;
    for (let c = 2; c < nc; c++) {
      const v = vals[r][c];
      if (v instanceof Date && DATE_CELL_RE.test(disp[r][c])) { dates[c + 1] = Utilities.formatDate(v, tz, 'yyyy-MM-dd'); n++; }
    }
    if (n >= 3) {
      if (cur) { cur.end = r; blocks.push(cur); }
      cur = { dates: dates, meRows: [], end: nr };
      label = '';
      continue;
    }
    const a = String(disp[r][0] || '').trim();
    if (a) label = a;
    if (cur && label === ME_LABEL) cur.meRows.push(r + 1);
  }
  if (cur) blocks.push(cur);
  return { blocks: blocks, disp: disp, vals: vals };
}

function workTabsFor(wb, dsList) {
  const months = {};
  dsList.forEach(function (ds) { months[+ds.slice(5, 7)] = true; });
  return wb.getSheets().filter(function (s) {
    const m = s.getName().match(/^(?:\d{4}년\s*)?(\d{1,2})월$/);
    return m && months[+m[1]];
  });
}

/** fromDs~toDs 내 작업 목록: {ds: [{text, done}]} */
function workTasks(fromDs, toDs) {
  if (!WORKCAL_ID || WORKCAL_ID.indexOf('__') === 0) return {};
  const out = {};
  try {
    const wb = SpreadsheetApp.openById(WORKCAL_ID);
    const tz = wb.getSpreadsheetTimeZone();
    const list = [];
    for (let d = fromDs; d <= toDs; d = shiftDate(d, 1)) list.push(d);
    workTabsFor(wb, list).forEach(function (sh) {
      const tab = readWorkTab(sh, tz);
      tab.blocks.forEach(function (b) {
        Object.keys(b.dates).forEach(function (col) {
          const ds = b.dates[col];
          if (ds < fromDs || ds > toDs) return;
          b.meRows.forEach(function (r) {
            const text = String(tab.disp[r - 1][col - 1] || '').trim();
            if (!text) return;
            const done = tab.vals[r - 1][col] === true;
            (out[ds] = out[ds] || []);
            if (!out[ds].some(function (x) { return x.text === text; })) out[ds].push({ text: text, done: done });
          });
        });
      });
    });
  } catch (err) {
    return { _error: String(err) };
  }
  return out;
}

/** 그 날 '슾' 칸 찾기 → {sh, col, rows} */
function locateMe(wb, ds) {
  const tz = wb.getSpreadsheetTimeZone();
  const tabs = workTabsFor(wb, [ds]);
  for (let i = 0; i < tabs.length; i++) {
    const tab = readWorkTab(tabs[i], tz);
    for (let j = 0; j < tab.blocks.length; j++) {
      const b = tab.blocks[j];
      const col = Object.keys(b.dates).filter(function (c) { return b.dates[c] === ds; })[0];
      if (col && b.meRows.length) return { sh: tabs[i], col: +col, rows: b.meRows, tab: tab, block: b };
    }
  }
  return null;
}

function applyWorkOp(wb, o) {
  const loc = locateMe(wb, o.d);
  if (!loc) throw new Error('작업 캘린더에서 ' + o.d + ' 칸을 못 찾았어요');
  const sh = loc.sh, col = loc.col;
  const textAt = function (r) { return String(sh.getRange(r, col).getDisplayValue() || '').trim(); };
  const find = function (text) {
    for (let i = 0; i < loc.rows.length; i++) if (textAt(loc.rows[i]) === text) return loc.rows[i];
    return -1;
  };
  if (o.t === 'wt_set') {
    const r = find(o.text); if (r > 0) sh.getRange(r, col + 1).setValue(!!o.done);
  } else if (o.t === 'wt_del') {
    const r = find(o.text); if (r > 0) { sh.getRange(r, col).clearContent(); sh.getRange(r, col + 1).setValue(false); }
  } else if (o.t === 'wt_edit') {
    const r = find(o.old); if (r > 0) sh.getRange(r, col).setValue(o.text);
    else o = { t: 'wt_add', d: o.d, text: o.text };
  }
  if (o.t === 'wt_add') {
    let r = -1;
    for (let i = 0; i < loc.rows.length; i++) if (!textAt(loc.rows[i])) { r = loc.rows[i]; break; }
    if (r < 0) {
      // 빈 칸이 없으면 내 줄 아래에 한 줄 추가 (모양·체크박스 복사)
      const last = loc.rows[loc.rows.length - 1];
      sh.insertRowAfter(last);
      r = last + 1;
      const lc = sh.getLastColumn();
      sh.getRange(last, 1, 1, lc).copyTo(sh.getRange(r, 1, 1, lc), { formatOnly: true });
      Object.keys(loc.block.dates).forEach(function (c) {
        const cb = sh.getRange(r, +c + 1);
        if (sh.getRange(last, +c + 1).getValue() === true || sh.getRange(last, +c + 1).getValue() === false) {
          cb.insertCheckboxes(); cb.setValue(false);
        }
      });
    }
    sh.getRange(r, col).setValue(o.text);
    const cb = sh.getRange(r, col + 1);
    if (cb.getValue() !== true && cb.getValue() !== false) cb.insertCheckboxes();
    cb.setValue(false);
  }
}

function buildWidgetSummary(ss, date, fromDs, toDs) {
  const tz = ss.getSpreadsheetTimeZone();
  const todayDs = Utilities.formatDate(new Date(Date.now() - WIDGET_DAY_START * 3600 * 1000), tz, 'yyyy-MM-dd');
  if (!date) date = todayDs;
  if (!fromDs || fromDs > date) fromDs = date;
  if (!toDs || toDs < date) toDs = date;
  const app = readAppDataObj(ss);
  const mb = app.manualBlocks || {};
  const hp = app.hourPlans || {};

  const work = workByDay(ss, fromDs, toDs, todayDs, tz);
  const wtAll = workTasks(fromDs, toDs);
  const wt = wtAll._error ? {} : wtAll;
  // PC 트래커가 직접 올린 하루 작업시간이 있으면 그걸 우선 (진행 중 작업까지 포함)
  const td = app.trackerDaily || {};
  Object.keys(td).forEach(function (d) {
    if (d >= fromDs && d <= toDs && td[d] > 0) work[d] = td[d];
  });
  const days = {};
  for (let d = fromDs; d <= toDs; d = shiftDate(d, 1)) {
    const item = {};
    if (work[d]) item.w = work[d];
    const sl = sleepForDay(mb, d);
    if (sl) item.s = sl;
    if (hp[d] && Object.keys(hp[d]).length) item.p = hp[d];
    const td = (app.todos || {})[d];
    if (td && td.length) item.t = td;
    if (mb[d] && mb[d].length) item.b = mb[d];
    if (wt[d] && wt[d].length) item.wt = wt[d];
    const hc = (app.hourChecks || {})[d];
    if (hc && Object.keys(hc).length) item.c = hc;
    days[d] = item;
  }

  return {
    ok: true,
    date: date,
    workSec: work[date] || 0,
    sleepMin: sleepForDay(mb, date),
    blocks: mb[date] || [],
    plans: hp[date] || {},
    checks: (app.hourChecks || {})[date] || {},
    todos: (app.todos || {})[date] || [],
    days: days,
    dayColors: (function () {
      const all = workCalColors(), sel = {};
      if (all._error) return { _error: all._error };
      Object.keys(all).forEach(function (k) { if (k >= fromDs && k <= toDs) sel[k] = all[k]; });
      return sel;
    })(),
    timestamp: new Date().toISOString(),
  };
}

// =============== 작업캘린더(스튜디오 시트) 날짜 색 ===============
const WORKCAL_ID = '__WORKCAL_ID__';
const DATE_CELL_RE = /^\s*(\d{1,2})\s*[.\/]\s*(\d{1,2})\s*\.?\s*$/;

/** 작업캘린더 구조만 (글 내용 없이): 행 이름표, 날짜행, 내 줄(슾) 칸 모양 */
function workCalShape(gid) {
  const ss = SpreadsheetApp.openById(WORKCAL_ID);
  const sh = ss.getSheets().filter(function (s) { return String(s.getSheetId()) === String(gid); })[0] || ss.getSheets()[0];
  const nr = Math.min(sh.getLastRow(), 80), nc = Math.min(sh.getLastColumn(), 20);
  const rng = sh.getRange(1, 1, nr, nc);
  const disp = rng.getDisplayValues(), vals = rng.getValues();
  const merges = sh.getRange(1, 1, nr, 2).getMergedRanges().map(function (m) { return m.getA1Notation(); });
  const rows = [];
  let label = '';
  for (let r = 0; r < nr; r++) {
    const a = String(disp[r][0] || '').trim(), b = String(disp[r][1] || '').trim();
    if (a) label = a;
    const isDateRow = vals[r].some(function (v, c) { return v instanceof Date && DATE_CELL_RE.test(disp[r][c]); });
    if (isDateRow) label = '';
    const cells = [];
    for (let c = 2; c < nc; c++) {
      const v = vals[r][c];
      cells.push(v === true ? 'T' : v === false ? 'F' : v instanceof Date ? 'D' : (String(disp[r][c]).trim() ? 'x' : '.'));
    }
    rows.push({ r: r + 1, a: a ? (a === '슾' ? 'ME' : 'other') : '', b: b ? 'b' : '', me: label === '슾', date: isDateRow, cells: cells.join('') });
  }
  return { ok: true, sheet: sh.getName(), merges: merges.slice(0, 40), rows: rows };
}

function workCalDebug(gid) {
  const ss = SpreadsheetApp.openById(WORKCAL_ID);
  const sheets = ss.getSheets().map(function (s) { return { name: s.getName(), gid: s.getSheetId(), rows: s.getLastRow(), cols: s.getLastColumn() }; });
  const sh = ss.getSheets().filter(function (s) { return String(s.getSheetId()) === String(gid); })[0] || ss.getSheets()[0];
  const nr = Math.min(sh.getLastRow(), 80), nc = Math.min(sh.getLastColumn(), 30);
  const rng = sh.getRange(1, 1, nr, nc);
  const disp = rng.getDisplayValues(), vals = rng.getValues(), bgs = rng.getBackgrounds();
  const cells = [];
  for (let r = 0; r < nr; r++) for (let c = 0; c < nc; c++) {
    if (DATE_CELL_RE.test(disp[r][c]) || vals[r][c] instanceof Date) {
      cells.push({ r: r + 1, c: c + 1, d: disp[r][c], isDate: vals[r][c] instanceof Date,
        v: vals[r][c] instanceof Date ? Utilities.formatDate(vals[r][c], ss.getSpreadsheetTimeZone(), 'yyyy-MM-dd') : '',
        bg: bgs[r][c], bgNext: c + 1 < nc ? bgs[r][c + 1] : '' });
    }
  }
  return { ok: true, sheet: sh.getName(), sheets: sheets, cells: cells };
}

// =============== 폰 위젯에서 바로 고치기 (한 번에 처리) ===============
function paintRangeGs(mb, ds, s, e, cat) {
  if (e <= s) return;
  const out = [];
  (mb[ds] || []).forEach(function (b) {
    if (!(b.endMin > b.startMin)) return;
    if (b.endMin <= s || b.startMin >= e) { out.push(b); return; }
    if (b.startMin < s) out.push(Object.assign({}, b, { endMin: s }));
    if (b.endMin > e) out.push(Object.assign({}, b, { startMin: e }));
  });
  out.push({ startMin: s, endMin: e, category: cat });
  out.sort(function (a, b) { return a.startMin - b.startMin; });
  const merged = [];
  out.forEach(function (b) {
    const last = merged[merged.length - 1];
    if (last && last.endMin === b.startMin && last.category === b.category) last.endMin = b.endMin;
    else merged.push(Object.assign({}, b));
  });
  mb[ds] = merged;
}

function applyWidgetOps(ss, appSheet, ops) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const data = appSheet.getDataRange().getValues();
    const rowOf = {}, app = {};
    for (let i = 1; i < data.length; i++) {
      if (!data[i][0]) continue;
      rowOf[data[i][0]] = i + 1;
      try { app[data[i][0]] = JSON.parse(data[i][1]); } catch (err) { app[data[i][0]] = data[i][1]; }
    }
    const changed = {};
    const obj = function (k) { if (!app[k] || typeof app[k] !== 'object') app[k] = {}; changed[k] = true; return app[k]; };

    let wb = null;
    ops.forEach(function (o) {
      if (String(o.t).indexOf('wt_') === 0) {
        if (!wb) wb = SpreadsheetApp.openById(WORKCAL_ID);
        applyWorkOp(wb, o);
        return;
      }
      if (o.t === 'todo_add') {
        const td = obj('todos'); (td[o.d] = td[o.d] || []).push({ text: o.text, done: false });
      } else if (o.t === 'todo_del') {
        const td = obj('todos'); const arr = td[o.d] || [];
        const i = arr.findIndex(function (x) { return x.text === o.text; });
        if (i >= 0) arr.splice(i, 1);
        td[o.d] = arr;
      } else if (o.t === 'todo_set') {
        const td = obj('todos'); const arr = td[o.d] || [];
        const it = arr.find(function (x) { return x.text === o.text; });
        if (it) {
          it.done = !!o.done;
          if (it.planKey && it.planKey.indexOf('__plan_') === 0) {
            const hc = obj('hourChecks'); (hc[o.d] = hc[o.d] || {})[it.planKey.slice(7)] = !!o.done;
          }
        }
      } else if (o.t === 'plan_set') {
        const hp = obj('hourPlans'); const day = hp[o.d] = hp[o.d] || {};
        if (o.text && String(o.text).trim()) day[o.h] = o.text; else delete day[o.h];
      } else if (o.t === 'paint') {
        paintRangeGs(obj('manualBlocks'), o.d, o.s, o.e, o.cat);
      } else if (o.t === 'block_del') {
        const mb = obj('manualBlocks');
        mb[o.d] = (mb[o.d] || []).filter(function (b) { return !(b.startMin === o.s && b.endMin === o.e); });
      }
    });

    Object.keys(changed).forEach(function (k) {
      const v = JSON.stringify(app[k]);
      if (rowOf[k]) appSheet.getRange(rowOf[k], 2).setValue(v);
      else appSheet.appendRow([k, v]);
    });
    return { ok: true, changed: Object.keys(changed) };
  } finally {
    lock.releaseLock();
  }
}

// =============== 헬퍼 ===============
function makeJsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

// =============== 테스트 ===============
function test() {
  const result = doGet({});
  Logger.log(result.getContent());
}
