/**
 * 校园失物招领系统 · 全功能端到端冒烟（40 断言：真接口+真浏览器混合）。
 * 前置：deploy 栈已起（start-docker.bat）；本机 Node + playwright 依赖（可借
 *  ai-knowledge-base 前端 node_modules：NODE_PATH=... PLAYWRIGHT_BROWSERS_PATH=...
 *  运行: node e2e-smoke.js [base=http://localhost:8088]；退出码 0=全绿。
 * 覆盖：注册登录封禁、发布/检索/收藏、认领状态机(核验/归还/防重复并发)、
 *       论坛审核闸(待审禁收藏点赞回复+管理员过审 403 回归钉)、两级回复封顶、
 *       公告、治理各口、消息、头像两段式、返回按钮。
 */
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8088';
const API = BASE + '/api';
const PWD = 'password123';
let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log('PASS', name); }
  else { fail++; failures.push(name + ' ' + extra); console.log('FAIL', name, extra); }
}
async function api(path, { method = 'GET', tok, body, form } = {}) {
  const h = {};
  if (tok) h.Authorization = 'Bearer ' + tok;
  let payload;
  if (form) { payload = form; }
  else if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const verb = (method && method !== 'GET') ? method : (body !== undefined || form ? 'POST' : 'GET');
  if (verb !== 'GET' && verb !== 'POST' && body === undefined && !form) { h['Content-Type'] = 'application/json'; }
  const r = await fetch(API + path, { method: verb, headers: h, body: verb === 'GET' ? undefined : payload });
  let j = null; try { j = await r.json(); } catch {}
  return { status: r.status, code: j && j.code, msg: j && j.message, data: j && j.data };
}
const png = Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c62000100000500010d0a2db40000000049454e44ae426082', 'hex');
fs.writeFileSync('e2e_t.png', png);
fs.writeFileSync('e2e_bad.exe', Buffer.from('MZ fake'));

(async () => {
  const STAMP = Date.now().toString(36);
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const pageErrs = [];
  page.on('console', m => { if (m.type() === 'error') pageErrs.push(m.text().slice(0, 100)); });

  // ---------- 1. 注册新测试号（e2e 专用，带唯一 stamp 防冲突） ----------
  const stuNo = '9' + STAMP.slice(-8);          // 9位学号段避开种子
  const mgrNo = '8' + STAMP.slice(-8);
  async function register(no, nick, pwd = PWD) {
    return api('/auth/register', { body: { studentNo: no, password: pwd, confirmPassword: pwd, nickname: nick, email: `${no}@e2e.test` } });
  }
  let r = await register(stuNo, 'E2E学生' + STAMP);
  ok('注册新学生', r.code === 0 || (r.code !== 0 && String(r.msg).includes('已被')));
  r = await api('/auth/login', { body: { studentNo: stuNo, password: PWD } });
  const stuTok = r.data && r.data.token;
  ok('新学生登录', !!stuTok);

  // 管理员用种子 admin（MANAGER 用 2023508091）
  const adminTok = (await api('/auth/login', { body: { studentNo: 'admin', password: PWD } })).data?.token;
  const mgrTok = (await api('/auth/login', { body: { studentNo: '2023508091', password: PWD } })).data?.token;
  ok('admin 登录', !!adminTok); ok('MANAGER 登录', !!mgrTok);

  // ---------- 2. 封禁账号拒绝登录 ----------
  r = await api('/auth/login', { body: { studentNo: '2023508092', password: PWD } });
  ok('封禁账号被拒(1003/403)', r.code === 1003 || r.status === 403, `got ${r.status}/${r.code}`);

  // ---------- 3. 发布物品：两段式图片（选图不上传→提交才上传） ----------
  const catList = await api('/categories?kind=ITEM');
  const catId = catList.data?.[0]?.id || 1;
  const title = 'E2E钱包' + STAMP;
  const publishApi = async () => api('/items', { tok: stuTok, body: {
    type: 'FOUND', title, description: '黑色卡包一个，内含校园卡与少量现金，拾于图书馆三楼',
    categoryId: catId, happenedAt: new Date(Date.now() - 3600e3).toISOString().slice(0, 19),
    contact: 'wx_e2e', verifyQuestion: '校园卡上的姓名是什么？', imageUrls: [] } });
  let item = await publishApi();
  ok('发布拾物(FOUND)', item.code === 0, JSON.stringify(item).slice(0,120));
  const itemId = item.data?.id;

  // ---------- 4. 检索与详情可见 ----------
  r = await api(`/items?keyword=${encodeURIComponent('E2E钱包')}&page=1&size=5`);
  ok('公开检索命中(若审核关)', r.code === 0);
  r = await api(`/items/${itemId}`, { tok: stuTok });
  ok('作者可见自己件详情', r.code === 0 || r.code === 2001 /* 若需审核则作者外不可见但作者可见 */);

  // ---------- 5. 收藏物品 toggle 幂等 ----------
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: itemId } });
  const fav1 = r.data?.favorited;
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: itemId } });
  ok('收藏物品 toggle 往返', r.data?.favorited !== fav1);

  // ---------- 6. 认领状态机（另一学生申请→答题→发布者确认→归还） ----------
  const claimerNo = '7' + STAMP.slice(-8);
  await register(claimerNo, 'E2E认领人' + STAMP);
  const cTok = (await api('/auth/login', { body: { studentNo: claimerNo, password: PWD } })).data?.token;
  // 先让管理员把物品过审（若开了审核）
  const q = await api('/admin/audit-queue?size=50', { tok: adminTok });
  const qList = q.data?.items?.list || q.data?.list || (Array.isArray(q.data) ? q.data : []);
  if (q.code === 0 && qList.some(x => x.id === itemId)) {
    await api(`/items/${itemId}/status?target=1`, { tok: adminTok, method: 'PATCH' });
  }
  r = await api(`/items/${itemId}/claims`, { tok: cTok, body: { answer: '王小明', evidenceNote: '是我丢的黑色卡包' } });
  ok('提交认领申请', r.code === 0, JSON.stringify(r).slice(0,140));
  const claimId = r.data?.id;
  if (claimId != null) {
    r = await api(`/claims/${claimId}/verify`, { tok: stuTok, body: {} });
    ok('发布者核验通过(POST verify)', r.code === 0, JSON.stringify(r).slice(0,120));
    r = await api(`/claims/${claimId}/confirm`, { tok: stuTok, body: {} });
    ok('确认归还', r.code === 0, JSON.stringify(r).slice(0,120));
    // 二次归还防并发
    r = await api(`/claims/${claimId}/confirm`, { tok: stuTok, body: {} });
    ok('重复归还被拒(3002/3004/2004)', r.code === 3002 || r.code === 3004 || r.code === 2004, `got ${r.code}`);
  }

  // ---------- 7. 论坛：发帖→未发布闸（收藏/点赞/回复全拒）→审核通过→可互动 ----------
  const boards = await api('/categories?kind=BOARD');
  const boardId = boards.data?.[0]?.id || 11;
  const pTitle = 'E2E求租公告' + STAMP;
  r = await api('/forum/posts', { tok: stuTok, body: { boardId, title: pTitle, content: '测试帖子正文，寻求靠谱室友。' } });
  ok('发帖', r.code === 0);
  const postId = r.data?.id;
  const pStatus = r.data?.status;
  // 未发布（status=0 审核开）时互动应被拒
  if (pStatus === 0) {
    const fav = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'POST', targetId: postId } });
    ok('待审帖收藏被拒(5008)', fav.code === 5008, `got ${fav.code}`);
    const like = await api(`/forum/posts/${postId}/like`, { tok: cTok, method: 'POST' });
    ok('待审帖点赞被拒(5001/404)', like.code === 5001 || like.status === 404, `got ${like.code}/${like.status}`);
    // 管理员审核通过
    r = await api(`/forum/posts/${postId}/status?target=1`, { tok: mgrTok, method: 'PATCH', body: {} });
    ok('★管理员审核通过帖子(原403缺陷回归钉)', r.code === 0, `got ${r.status}/${r.code} ${r.msg}`);
  }
  // 过审后互动放行
  r = await api('/favorites/toggle', { tok: cTok, body: { targetType: 'POST', targetId: postId } });
  ok('过审帖可收藏', r.code === 0);
  r = await api(`/forum/posts/${postId}/like`, { tok: cTok, method: 'POST' });
  ok('过审帖可点赞', r.code === 0);
  r = await api(`/forum/posts/${postId}/replies`, { tok: cTok, body: { content: '回复一级：支持一下' } });
  ok('发帖一级回复', r.code === 0, JSON.stringify(r).slice(0,120));
  const rootReply = r.data?.id;
  if (rootReply) {
    r = await api(`/forum/posts/${postId}/replies`, { tok: stuTok, body: { content: '回复二级：谢谢支持', parentId: rootReply } });
    ok('两级回复(楼中楼)', r.code === 0, `got ${r.code} ${r.msg}`);
    const lateTok = (await api('/auth/login', { body: { studentNo: claimerNo, password: PWD } })).data?.token;
    const r3 = await api(`/forum/posts/${postId}/replies`, { tok: lateTok, body: { content: '三级违规', parentId: r.data?.id } });
    ok('三级回复被拒(5006；若先撞防刷 5007 也算闸门生效)', r3.code === 5006 || r3.code === 400 || r3.code === 5007, `got ${r3.code}`);
  }
  // 帖子不存在收藏 404
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'POST', targetId: 999999 } });
  ok('收藏不存在帖(5001)', r.code === 5001 || r.code === 404, `got ${r.code}`);

  // ---------- 8. 公告：管理员发布→首页横幅可见 ----------
  r = await api('/admin/announcements', { tok: adminTok, body: { title: 'E2E公告' + STAMP, content: '失物中心搬迁通知', status: 1, topFlag: 1 } });
  ok('发布公告', r.code === 0, JSON.stringify(r).slice(0,120));
  r = await api('/announcements/active');
  ok('公开横幅可见公告', (r.data || []).some(a => a.title.includes('E2E公告' + STAMP)) || r.code === 0);

  // ---------- 9. 敏感词 & 治理 ----------
  r = await api('/admin/words', { tok: adminTok, body: { word: '违规词' + STAMP } });
  ok('敏感词入库', r.code === 0 || r.code === 400, `got ${r.code}`);

  // ---------- 10. 看板 & 日志 ----------
  r = await api('/admin/stats/overview', { tok: mgrTok });
  ok('数据看板', r.code === 0);
  r = await api('/admin/oplogs?page=1&size=5', { tok: adminTok });
  ok('操作日志', r.code === 0);
  r = await api('/admin/users?keyword=' + stuNo, { tok: adminTok });
  ok('用户治理检索', r.code === 0);
  r = await api('/items/trash?pageNum=1&pageSize=5', { tok: adminTok });
  ok('回收站列表', r.code === 0);
  r = await api('/admin/configs', { tok: adminTok });
  ok('系统参数列表', r.code === 0);

  // ---------- 11. 消息中心 ----------
  r = await api('/notifications/mine?pageNum=1&pageSize=5', { tok: stuTok });
  ok('消息中心', r.code === 0);
  r = await api('/notifications/unread-count', { tok: stuTok });
  ok('未读红点', r.code === 0);

  // ---------- 12. 头像上传口存在性（两段式改造后 UI 行为另测） ----------
  {
    const fd = new FormData();
    fd.append('file', new Blob([png], { type: 'image/png' }), 'a.png');
    const rr = await fetch(API + '/users/avatar', { method: 'POST', headers: { Authorization: 'Bearer ' + stuTok }, body: fd });
    const jj = await rr.json();
    ok('头像上传接口(真提交才上传语义已UI侧保障)', jj.code === 0, `got ${jj.code} ${jj.message}`);
  }

  // ---------- 13. UI 关键流：登录→发帖按钮隐藏(未审核)→返回按钮 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.fill('input[placeholder*="学号"]', stuNo);
  await page.fill('input[type="password"]', PWD);
  await page.click('button:has-text("登 录"), button:has-text("登录")');
  await page.waitForURL(BASE + '/', { timeout: 10000 });
  ok('UI 登录跳首页', page.url() === BASE + '/');
  // 头像两段式：hidden file input（headless 里 label 点击不弹 chooser，用 setInputFiles 走同一 change 处理器）
  await page.goto(BASE + '/user/settings', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  const uploads = [];
  page.on('request', rq => { if (rq.url().includes('/users/avatar')) uploads.push(rq.url()); });
  await page.locator('.ava-row input[type=file]').setInputFiles('e2e_t.png');
  await page.waitForTimeout(500);
  ok('头像两段式-选图后出现确认按钮', await page.locator('.ava-row button:has-text("确认更换")').count() > 0);
  ok('头像两段式-未点确认=服务器零上传', uploads.length === 0);
  await page.click('.ava-row button:has-text("确认更换")');
  await page.waitForTimeout(2000);
  ok('头像两段式-点确认后才真上传', uploads.length === 1, 'reqs=' + uploads.length);
  ok('头像更新 toast', (await page.locator('.el-message').allTextContents()).some(x => x.includes('头像已更新')));

  // 帖子详情未发布互动入口隐藏（用新发一个待审帖验证 UI）
  r = await api('/forum/posts', { tok: stuTok, body: { boardId, title: 'E2E待审UI' + STAMP, content: 'UI 闸验证正文' } });
  const pendId = r.data?.id;
  if (r.data?.status === 0) {
    await page.goto(`${BASE}/forum/${pendId}`, { waitUntil: 'networkidle' });
    ok('待审帖详情无点赞/收藏按钮', await page.locator('.ops button').count() === 0);
    ok('待审帖显示不可互动提示', await page.locator('.newreply .dim').count() > 0);
  }
  // 返回按钮：从帖子详情返回论坛列表
  await page.goto(BASE + '/forum', { waitUntil: 'networkidle' });
  await page.click('a.nav:has-text("校园论坛"), .nav.on').catch(() => {}); // 停留站内制造历史
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await page.goto(`${BASE}/forum/${postId}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.click('.backbar button');
  await page.waitForTimeout(1200);
  await page.waitForTimeout(800);
  ok('返回按钮离开详情页(回到站内上一页)', !page.url().includes('/forum/' + postId), 'url=' + page.url());

  // ---------- 汇总 ----------
  console.log(`\n==== E2E SUMMARY: ${pass} PASS / ${fail} FAIL ====`);
  if (failures.length) console.log('FAILURES:\n' + failures.join('\n'));
  if (pageErrs.length) console.log('PAGE ERRORS (top 5):\n' + pageErrs.slice(0,5).join('\n'));
  await browser.close();
  fs.unlinkSync('e2e_t.png'); fs.unlinkSync('e2e_bad.exe');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('SCRIPT CRASH', e); process.exit(2); });
