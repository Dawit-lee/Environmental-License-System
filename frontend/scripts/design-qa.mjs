// Design QA crawl: every screen at 1440/1024/375 → page overflow, card shadows, big radii, colors outside
// DESIGN.md tokens, controls without a name, console/network errors; full-page screenshots + report.json.
//   npm i --no-save playwright-core   (uses the installed Google Chrome; CHROME=path to override)
//   WEB=http://localhost:5173 API=http://localhost:8080 node scripts/design-qa.mjs <outDir> [name-regex]
// Optional: QA_LOGIN=<json file {email,password}> for a company-master account (org screens, project QA_PROJECT),
//           SHARE=/share/<token> for the partner page. The read-only demo account covers the rest.
import { chromium } from 'playwright-core';
import fs from 'fs';
const OUT = process.argv[2]; const FILTER = process.argv[3]; fs.mkdirSync(OUT, { recursive: true });
const B = process.env.WEB ?? 'http://localhost:5173';
const login = async (body) => (await fetch(`${process.env.API ?? 'http://localhost:8080'}/api/auth/login`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
const demo = await login({ email: 'demo@envpermit.local', password: 'demo1234!' });
const qa = process.env.QA_LOGIN ? await login(JSON.parse(fs.readFileSync(process.env.QA_LOGIN, 'utf8'))) : null;
const share = process.env.SHARE;
const tabs = ['overview', 'permits', 'obligations', 'review', 'laws', 'schedule', 'documents', 'settings'];
const pages = [
  ['login', null, '/login'], ['signup', null, '/signup'], ...(share ? [['share', null, share]] : []),
  ...['dashboard', 'projects', 'inbox', 'calendar', 'guide'].map((p) => [p, demo, '/' + p]),
  ['laws', demo, '/inbox?kind=LAW'], ['project-new', demo, '/projects/new'],
  ...tabs.map((t) => ['p1-' + t, demo, '/projects/1/' + t]),
  ...(qa ? [['qa-org', qa, '/org'], ['qa-join', qa, '/join-requests'], ['qa-dashboard', qa, '/dashboard'],
    ...tabs.map((t) => ['qa-' + t, qa, `/projects/${process.env.QA_PROJECT ?? 1}/` + t])] : []),
  ['notfound-project', demo, '/projects/99999/overview'],
].filter(([n]) => !FILTER || n.match(FILTER));
// theme.css tokens (+ black for overlays)
const TOKENS = new Set(`#256ef4 #0b50d0 #083891 #ecf2fe #d8e5fd #063a74 #ffffff #f4f5f6 #e6e8ea #cdd1d5 #b1b8be #8a949e #6d7882 #58616a #464c53 #33363d #1e2124 #131416 #bd2c0f #fdefec #fcdfd9 #de3412 #8a5c00 #fff3db #ffe0a3 #9e6a00 #267337 #eaf6ec #d8eedd #228738 #096ab3 #e7f4fe #d3ebfd #0b78cb #000000 #9a2309 #6e4900 #1e5c2c`.split(' '));
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
const report = [];
for (const w of [1440, 1024, 375]) {
  for (const [name, sess, path] of pages) {
    const ctx = await browser.newContext({ viewport: { width: w, height: 900 } });
    if (sess) await ctx.addInitScript((s) => localStorage.setItem('permit_session', JSON.stringify(s)), sess);
    const page = await ctx.newPage();
    const errs = [];
    page.on('console', (m) => m.type() === 'error' && errs.push('console: ' + m.text().slice(0, 160)));
    page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 160)));
    page.on('response', (r) => r.status() >= 400 && errs.push(`http ${r.status()} ${r.url().replace(B, '')}`));
    page.on('requestfailed', (r) => errs.push('failed ' + r.url().replace(B, '')));
    await page.goto(B + path, { waitUntil: 'networkidle' }).catch((e) => errs.push('goto ' + e.message));
    await page.waitForTimeout(600);
    const r = await page.evaluate((TOK) => {
      const hex = (c) => { const m = c.match(/rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?/); if (!m || m[4] === '0') return null; return '#' + [1, 2, 3].map((i) => (+m[i]).toString(16).padStart(2, '0')).join('') + (m[4] && m[4] !== '1' ? `/${m[4]}` : ''); };
      const sel = (el) => { let s = el.tagName.toLowerCase(); if (el.id) s += '#' + el.id; const c = (el.getAttribute('class') || '').split(' ').slice(0, 4).join('.'); if (c) s += '.' + c; const t = (el.innerText || '').trim().slice(0, 20).replace(/\s+/g, ' '); return s + (t ? ` "${t}"` : ''); };
      const out = { overflow: document.documentElement.scrollWidth - innerWidth, wide: [], shadow: [], radius: [], color: {}, noLabel: [], hidden: [] };
      for (const el of document.querySelectorAll('body *')) {
        const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        const rect = el.getBoundingClientRect(); if (!rect.width && !rect.height) continue;
        if (!el.checkVisibility({ opacityProperty: true, visibilityProperty: true }) || el.closest('[aria-hidden=true],[inert]')) continue;
        if (rect.right > innerWidth + 1 && !el.closest('[class*=overflow-x],[class*=overflow-auto],.gantt,.wx-gantt,.fc')) out.wide.push(sel(el) + ` r=${Math.round(rect.right)}`);
        const inOverlay = el.closest('[role=dialog],[role=menu],[role=listbox],[popover],dialog');
        const realShadow = cs.boxShadow !== 'none' && cs.boxShadow.split(/,(?![^(]*\))/).some((x) => !/rgba\(0, 0, 0, 0\)|0px 0px 0px 0px|rgb\(37, 110, 244\)/.test(x));
        const floating = ['fixed', 'absolute', 'sticky'].includes(cs.position) || el.closest('[class*=fixed],[class*=absolute]');
        if (realShadow && !inOverlay && !floating) out.shadow.push(sel(el) + ' ' + cs.boxShadow.slice(0, 40));
        const rad = parseFloat(cs.borderTopLeftRadius); if (rad > 4 && !(rad >= Math.min(rect.width, rect.height) / 2 - 1)) out.radius.push(sel(el) + ' ' + cs.borderTopLeftRadius);
        for (const p of ['color', 'backgroundColor', 'borderTopColor']) { if (p === 'borderTopColor' && cs.borderTopWidth === '0px') continue; const h = hex(cs[p]); if (h && !TOK.includes(h.split('/')[0])) { (out.color[h] ??= []).length < 3 && out.color[h].push(p[0] + ':' + sel(el)); } }
        if (['INPUT', 'SELECT', 'TEXTAREA'].includes(el.tagName) && el.type !== 'hidden' && !el.labels?.length && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby') && !el.getAttribute('title')) out.noLabel.push('control ' + sel(el) + ' ' + el.outerHTML.slice(0, 100));
        if ((el.tagName === 'BUTTON' || el.getAttribute('role') === 'button' || el.tagName === 'A') && !(el.innerText || '').trim() && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby') && !el.getAttribute('title')) out.noLabel.push(sel(el) + ' ' + el.outerHTML.slice(0, 80));
      }
      out.wide = out.wide.slice(0, 6); out.shadow = [...new Set(out.shadow)].slice(0, 6); out.radius = [...new Set(out.radius)].slice(0, 6);
      return out;
    }, [...TOKENS]);
    r.url = page.url().replace(B, '');
    await page.screenshot({ path: `${OUT}/${name}-${w}.png`, fullPage: true });
    report.push({ name, w, ...r, errs: [...new Set(errs)] });
    await ctx.close();
  }
}
await browser.close();
fs.writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 1));
for (const r of report) {
  const issues = [r.overflow > 0 && `넘침 ${r.overflow}px`, r.wide.length && `wide ${r.wide.length}`, r.shadow.length && `shadow ${r.shadow.length}`, r.radius.length && `radius ${r.radius.length}`, Object.keys(r.color).length && `color ${Object.keys(r.color).join(',')}`, r.noLabel.length && `nolabel ${r.noLabel.length}`, r.errs.length && `errs ${r.errs.length}`].filter(Boolean);
  console.log(`${r.name}@${r.w} ${r.url} ${issues.join(' | ') || 'OK'}`);
}
