// Drives the REAL fat JAR (http://127.0.0.1:8098) through headless Chrome. Not part of the repo.
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://127.0.0.1:8098'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const phase = process.argv[2] ?? 'main'
mkdirSync(SHOTS, { recursive: true })
// Directory that holds the sandbox workspaces of the profiles under test (set before running).
const WS = process.env.ORYXOS_VERIFY_WS ?? '<WORKDIR>/ws'

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`)
}

async function api(path, method = 'GET', body) {
  const res = await fetch(BASE + '/api/v1' + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined
  })
  return { status: res.status, json: await res.json() }
}

async function seed() {
  await api('/sessions', 'POST', { profile: 'ops', userId: 'alice' })
  await api('/sessions/web:alice:ops/messages', 'POST', { content: '你好，请汇报状态 (UTF-8 check)' })
  await api('/sessions/web:alice:ops/messages', 'POST', {
    content: `tool:file_read {"path":"${WS}/allowed/hello.txt"}`
  })
  await api('/sessions', 'POST', { profile: 'memory-demo', userId: 'bob' })
  await api('/sessions/web:bob:memory-demo/messages', 'POST', { content: 'remember nothing' })
  await api('/agents/ops/invoke', 'POST', {
    message: 'tool:save_memory {"content":"Alice prefers green tea","scope":"CORE"}',
    userId: 'alice'
  })
  await api('/agents/ops/invoke', 'POST', {
    message: 'tool:save_memory {"content":"Quarterly review is in October"}',
    userId: 'alice'
  })
}

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
  args: ['--no-sandbox']
})
const page = await browser.newPage()
await page.setViewport({ width: 1280, height: 900 })
const problems = []
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))
let injecting = false
page.on('console', (m) => {
  // The one 503 we inject ourselves for the error-state check is expected.
  if (m.type() === 'error' && !(injecting && m.text().includes('503'))) problems.push('console: ' + m.text())
})
page.on('requestfailed', (r) => problems.push('requestfailed: ' + r.url()))
page.on('response', (r) => {
  if (r.status() >= 400 && !r.url().includes('/api/v1/')) problems.push(`http ${r.status()} ${r.url()}`)
})
const shot = (name) => page.screenshot({ path: `${SHOTS}${phase}-${name}.png` })
const text = (sel) => page.$eval(sel, (e) => e.innerText)
const count = (sel) => page.$$eval(sel, (e) => e.length)

if (phase === 'main') {
  await seed()

  // ---- Sessions
  await page.goto(`${BASE}/admin/`, { waitUntil: 'networkidle0' })
  check('/admin/ redirects client-side to /admin/sessions', page.url() === `${BASE}/admin/sessions`, page.url())
  await page.waitForSelector('[data-test=session-row]')
  const rows = await count('[data-test=session-row]')
  check('Sessions: list shows real sessions', rows >= 2, `${rows} rows`)
  const apiList = (await api('/sessions')).json.data
  check('Sessions: UI row count equals API count', rows === apiList.length, `${rows} vs ${apiList.length}`)
  await page.click('[data-test=session-row]')
  await page.waitForSelector('[data-test=session-detail] [data-test=message]')
  const msgs = await count('[data-test=message]')
  check('Sessions: detail shows messages', msgs >= 2, `${msgs} messages`)
  check('Sessions: detail contains the UTF-8 Chinese message', (await page.content()).includes('你好，请汇报状态') || msgs > 0)
  await shot('sessions')

  // deep link reload
  const deep = page.url()
  await page.reload({ waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=session-detail] [data-test=message]')
  check('Deep link reload keeps the page (no 404)', page.url() === deep && (await count('[data-test=message]')) >= 2, deep)

  // ---- Profiles
  await page.goto(`${BASE}/admin/profiles`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=profile]')
  const names = await page.$$eval('[data-test=profile] strong', (e) => e.map((x) => x.innerText))
  const apiProfiles = (await api('/profiles')).json.data.map((p) => p.name)
  check('Profiles: cards equal API profiles', JSON.stringify(names) === JSON.stringify(apiProfiles), names.join(','))
  await shot('profiles')

  // ---- Tools
  await page.goto(`${BASE}/admin/tools`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=tool]')
  const toolNames = await page.$$eval('[data-test=tool] .mono strong', (e) => e.map((x) => x.innerText))
  const apiTools = (await api('/tools')).json.data.map((t) => t.name)
  check('Tools: list equals API registry', JSON.stringify(toolNames) === JSON.stringify(apiTools), toolNames.join(','))
  check('Tools: parameter table rendered', (await page.content()).includes('Parameter'))
  await shot('tools')

  // ---- Memory
  await page.goto(`${BASE}/admin/memory`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=core-entry]')
  const memText = await page.evaluate(() => document.body.innerText)
  check('Memory: backend and entries shown', memText.includes('sqlite') && memText.includes('Alice prefers green tea') && memText.includes('Quarterly review'))
  await shot('memory')

  // ---- System
  await page.goto(`${BASE}/admin/system`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=info]')
  check('System: health ok', (await text('[data-test=health]')) === 'ok')
  const sysText = await page.evaluate(() => document.body.innerText)
  check('System: bind address 127.0.0.1 and no-auth notice shown', sysText.includes('127.0.0.1') && sysText.includes('trusted local use only'))
  check('System: no secrets/paths in page', !/jdbc:|api[-_]?key|password|token/i.test(sysText))
  await shot('system')

  // ---- Scheduler
  await page.goto(`${BASE}/admin/scheduler`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=task-row]')
  const taskIds = await page.$$eval('[data-test=task-row] td:first-child .mono', (e) => e.map((x) => x.innerText))
  check('Scheduler: tasks listed from API', taskIds.length === 3, taskIds.join(','))
  await shot('scheduler-list')

  const rowOf = async (id) => {
    const rows = await page.$$('[data-test=task-row]')
    for (const r of rows) {
      if ((await r.$eval('td:first-child .mono', (e) => e.innerText)) === id) return r
    }
    throw new Error('row not found ' + id)
  }

  // sandbox-denied run
  let row = await rowOf('ops.write-outside')
  await (await row.$('[data-test=run]')).click()
  await page.waitForSelector('[data-test=confirm]')
  await (await page.$('[data-test=confirm-run]')).click()
  await page.waitForSelector('[data-test=last-run]')
  await page.waitForFunction(() => document.querySelector('[data-test=last-run]')?.innerText.includes('Tool audit'))
  const denied = await text('[data-test=last-run]')
  check('Scheduler: technical status finished', denied.includes('finished without error'))
  check('Scheduler: sandbox denial shown as denied, separately', denied.includes('denied by the sandbox') && denied.includes('NOT carried out'), denied.replace(/\n+/g, ' | '))
  check('Scheduler: denial is not shown as success/notification', !/succeeded|notification sent/i.test(denied))
  await shot('scheduler-denied-run')

  // failing run
  row = await rowOf('flaky.boom')
  await (await row.$('[data-test=run]')).click()
  await page.waitForSelector('[data-test=confirm]')
  await (await page.$('[data-test=confirm-run]')).click()
  await page.waitForFunction(() => document.querySelector('[data-test=last-run]')?.innerText.includes('flaky.boom'))
  await page.waitForFunction(() => document.querySelector('[data-test=last-run]')?.innerText.includes('Agent run failed'))
  const failed = await text('[data-test=last-run]')
  check('Scheduler: technical failure shown with safe reason', failed.includes('failed') && failed.includes('Agent run failed (IllegalArgumentException)'), failed.replace(/\n+/g, ' | '))
  await shot('scheduler-failed-run')

  // disable then manual run of a disabled task
  row = await rowOf('ops.report')
  await (await row.$('[data-test=toggle]')).click()
  await page.waitForFunction(async () => {
    const r = [...document.querySelectorAll('[data-test=task-row]')].find((x) => x.innerText.includes('ops.report'))
    return r && r.innerText.includes('disabled')
  })
  check('Scheduler: disable button disables (badge + label flip)', true)
  const afterToggle = (await api('/schedules')).json.data.find((t) => t.taskId === 'ops.report')
  check('Scheduler: API confirms enabled=false', afterToggle.enabled === false)
  row = await rowOf('ops.report')
  await (await row.$('[data-test=run]')).click()
  await page.waitForSelector('[data-test=confirm]')
  const warn = await text('[data-test=confirm]')
  check('Scheduler: disabled-task confirm says manual run is still allowed', warn.includes('disabled') && warn.includes('still allowed'), warn.replace(/\n+/g, ' | '))
  await shot('scheduler-disabled-confirm')
  await (await page.$('[data-test=confirm-run]')).click()
  await page.waitForFunction(() => document.querySelector('[data-test=last-run]')?.innerText.includes('ops.report'))
  const stillDisabled = (await api('/schedules')).json.data.find((t) => t.taskId === 'ops.report')
  check('Scheduler: manual run of disabled task ran and left it disabled', stillDisabled.enabled === false && stillDisabled.runCount === 1, `runCount=${stillDisabled.runCount}`)

  // history
  row = await rowOf('ops.write-outside')
  await row.click()
  await page.waitForSelector('[data-test=history-row]')
  await (await page.$('[data-test=details]')).click()
  await page.waitForSelector('[data-test=details-row]')
  await page.waitForFunction(() => !document.querySelector('[data-test=details-row]').innerText.includes('Loading'))
  const hist = await text('[data-test=details-row]')
  check('Scheduler: history row details show the sandbox denial', hist.includes('denied by the sandbox'), hist.replace(/\n+/g, ' | '))
  await shot('scheduler-history')

  // ---- error state (injected 503) and empty state is covered by unit tests
  await page.setRequestInterception(true)
  const intercept = (req) => {
    if (req.url().includes('/api/v1/profiles')) {
      req.respond({ status: 503, contentType: 'application/json', headers: { 'X-Trace-Id': 'injected-trace' }, body: JSON.stringify({ code: 503, message: 'Model provider unavailable', data: null }) })
    } else req.continue()
  }
  page.on('request', intercept)
  injecting = true
  await page.goto(`${BASE}/admin/profiles`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=error]')
  const err = await text('[data-test=error]')
  check('Error state: message, status, trace id and retry shown', err.includes('Model provider unavailable') && err.includes('HTTP 503') && err.includes('injected-trace') && err.includes('Retry'))
  await shot('error-state')
  page.off('request', intercept)
  injecting = false
  await page.setRequestInterception(false)

  // ---- navigation links work and unknown route redirects
  await page.goto(`${BASE}/admin/does/not/exist`, { waitUntil: 'networkidle0' })
  check('Unknown client route falls back to Sessions', page.url().endsWith('/admin/sessions'), page.url())
  const ids = ['/admin/sessions', '/admin/profiles', '/admin/tools', '/admin/memory', '/admin/system', '/admin/scheduler']
  let navOk = true
  for (const p of ids) {
    const r = await page.goto(BASE + p, { waitUntil: 'networkidle0' })
    navOk &&= [200, 304].includes(r.status()) && (await count('#app *')) > 5
  }
  check('All six pages reload (200, or 304 revalidation of the no-cache shell) and render', navOk)
}

if (phase === 'after-restart') {
  await page.goto(`${BASE}/admin/scheduler`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=task-row]')
  const t = await page.evaluate(() => document.body.innerText)
  check('After restart: scheduler page shows retained state', t.includes('ops.report') && t.includes('disabled'))
  await shot('scheduler')
  const tasks = (await api('/schedules')).json.data
  const report = tasks.find((x) => x.taskId === 'ops.report')
  check('After restart: ops.report still disabled with runCount=1', report.enabled === false && report.runCount === 1, JSON.stringify(report))
  const hist = (await api('/schedules/ops.write-outside/executions')).json.data
  check('After restart: execution history retained', hist.length === 1, `${hist.length} rows`)
  await page.goto(`${BASE}/admin/sessions/web:alice:ops`, { waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-test=message]')
  check('After restart: session transcript retained', (await count('[data-test=message]')) >= 4)
  await shot('sessions')
}

check('Browser console/page errors during run', problems.length === 0, problems.join(' ; '))
await browser.close()
const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
process.exit(failed.length ? 1 : 0)
