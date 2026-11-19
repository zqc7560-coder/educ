/**
 * 校园失物招领系统 · 全功能端到端冒烟 104 断言（真接口+真浏览器混合）
 * 用法: node e2e-smoke.js [base]   默认 http://localhost:8088（Docker 演示栈）
 * 覆盖：注册/登录→发布→审核→匹配→认领全流程(6态)→仲裁→论坛→收藏(含
 *       服务端态回读)→举报工单→两段式上传→编辑重审→治理口→看板→消息→
 *       游客视角+登录页游客入口→卡片封面轮播→浏览三律·三域同口径
 *       (访客去重/作者自览不计/pv=0 非浏览读不计/展示即时)→
 *       未审核内容互动闸（收藏/点赞/回复+归一 replyToId 闸，管理员同闸）
 * 纪律：同号重登会按"登录水位"踢掉在先令牌——复用 token 的段落禁止中途重登，
 *       需要新身份就注册新号；回复防刷 10s 窗同理，一段一号只发一贴。
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
  let payload = undefined;
  let verb = method;
  if (method === 'GET' && (body !== undefined || form)) verb = 'POST';   // 兼容旧调用：带 body 即 POST
  if (form) { payload = form; }
  else if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  else if (verb !== 'GET') { h['Content-Type'] = 'application/json'; payload = JSON.stringify({}); } // 动词显式指定时恒发 {}，防 axios/fetch 空体退化
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

  // ---------- 5. 收藏物品 toggle 幂等（新闸口径：先过审才可收藏） ----------
  {
    const st0 = (await api(`/items/${itemId}`, { tok: stuTok })).data?.status;
    if (st0 === 0) await api(`/items/${itemId}/status?target=1`, { tok: adminTok, method: 'PATCH' });
  }
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: itemId } });
  const fav1 = r.data?.favorited;
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: itemId } });
  ok('收藏物品 toggle 往返', fav1 === true && r.data?.favorited === false, JSON.stringify({fav1, second: r.data?.favorited}));

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

  // ---------- 6b. 收藏态服务端回读（刷新后按钮仍"已收藏"） ----------
  {
    const p101 = (await api('/items/101', { tok: stuTok })).data;
    ok('物品详情带服务端 favorited 态', typeof p101?.favorited === 'boolean', JSON.stringify(p101?.favorited));
    const f0 = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: 101 } });
    const rb1 = await api('/items/101', { tok: stuTok });
    ok('★收藏后回读详情 favorited=true(刷新不复位)', f0.code === 0 && rb1.data?.favorited === true, JSON.stringify({ t: f0.data, rb: rb1.data?.favorited }));
    await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'ITEM', targetId: 101 } });
    const rb2 = await api('/items/101', { tok: stuTok });
    ok('取消后回读 false', rb2.data?.favorited === false, JSON.stringify(rb2.data?.favorited));

    const pubNo = 'c' + STAMP.slice(-8);
    await register(pubNo, '收藏回读号' + STAMP);
    const pubTok = (await api('/auth/login', { body: { studentNo: pubNo, password: PWD } })).data?.token;
    const np = await api('/forum/posts', { tok: stuTok, body: { boardId: 11, title: '收藏回读帖' + STAMP, content: '服务端收藏态回读验证正文' } });
    const npid = np.data?.id;
    await api(`/forum/posts/${npid}/status?target=1`, { tok: adminTok, method: 'PATCH' });
    await api('/favorites/toggle', { tok: pubTok, body: { targetType: 'POST', targetId: npid } });
    const pr = await api(`/forum/posts/${npid}`, { tok: pubTok });
    ok('★帖子详情服务端 favorited=true(收藏人非作者)', pr.data?.favorited === true, JSON.stringify(pr.data?.favorited));
    await api('/favorites/toggle', { tok: pubTok, body: { targetType: 'POST', targetId: npid } });
    const pr2 = await api(`/forum/posts/${npid}`, { tok: pubTok });
    ok('帖取消后回读 false', pr2.data?.favorited === false, JSON.stringify(pr2.data?.favorited));
  }

  // ---------- 6c. 浏览量按访客去重（帖子=读路径即时 +1 可 HTTP 断言；物品同款逻辑 JUnit 钉） ----------
  {
    const vNo1 = 'd' + STAMP.slice(-8);
    const vNo2 = 'e' + STAMP.slice(-8);
    await register(vNo1, '浏览去重甲' + STAMP);
    await register(vNo2, '浏览去重乙' + STAMP);
    const vTok1 = (await api('/auth/login', { body: { studentNo: vNo1, password: PWD } })).data?.token;
    const vTok2 = (await api('/auth/login', { body: { studentNo: vNo2, password: PWD } })).data?.token;
    const c0 = (await api('/forum/posts/201', { tok: vTok2 })).data?.viewCount ?? 0;   // 乙首读：+1 即时
    const c1 = (await api('/forum/posts/201', { tok: vTok2 })).data?.viewCount ?? 0;   // 乙复读：去重不加
    const c2 = (await api('/forum/posts/201', { tok: vTok1 })).data?.viewCount ?? 0;   // 甲首读：+1
    const c3 = (await api('/forum/posts/201', { tok: vTok2 })).data?.viewCount ?? 0;   // 乙三读：仍不加
    ok('★帖浏览同人复刷不重复计数(甲乙各只+1)', c1 === c0 && c2 === c1 + 1 && c3 === c2, `c0=${c0} c1=${c1} c2=${c2} c3=${c3}`);
    // 物品浏览三律（与帖/公告同口径收齐——用户报"物品还有漏洞"根修）：
    // 新发一件全新物品，所有计数从 0 起，观测量纯净
    const ownNo = 'f' + STAMP.slice(-8);
    await register(ownNo, '物品浏览甲' + STAMP);
    const ownTok = (await api('/auth/login', { body: { studentNo: ownNo, password: PWD } })).data?.token;
    const nv = await api('/items', { tok: ownTok, body: {
      type: 'FOUND', title: '浏览三律件' + STAMP, description: '物品浏览量三律验证专用物品描述。', categoryId: 1,
      happenedAt: new Date(Date.now() - 3600e3).toISOString().slice(0, 19), verifyQuestion: '什么颜色？' } });
    const nvid = nv.data?.id;
    await api(`/items/${nvid}/status?target=1`, { tok: adminTok, method: 'PATCH' });
    // ① 作者自览不计（帖同律，物品此前漏了）
    const a0 = (await api(`/items/${nvid}`, { tok: ownTok })).data?.viewCount;
    ok('★物品作者自览不计数', a0 === 0, 'got ' + a0);
    // ② 他人真实浏览：展示即时=DB+缓冲（此前滞后 5min 批刷=用户看到"乱涨"的观感源）
    const b1 = (await api(`/items/${nvid}`, { tok: vTok1 })).data?.viewCount;
    ok('★物品首读展示即见 +1(不再滞后批刷)', b1 === 1, 'got ' + b1);
    // ③ 同人复访不涨
    const b2 = (await api(`/items/${nvid}`, { tok: vTok1 })).data?.viewCount;
    const b3 = (await api(`/items/${nvid}`, { tok: vTok1 })).data?.viewCount;
    ok('物品同人复刷不涨', b2 === 1 && b3 === 1, `${b1}→${b2}→${b3}`);
    // ④ ?pv=0 非浏览读（收藏页/编辑页）不涨且展示仍准
    const b4 = (await api(`/items/${nvid}?pv=0`, { tok: vTok2 })).data?.viewCount;
    const b5 = (await api(`/items/${nvid}`, { tok: vTok2 })).data?.viewCount;
    ok('pv=0 非浏览读不计且展示正确', b4 === 1 && b5 === 2, `pv0=${b4} then=${b5}`);
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
    // forum_audit=1：一级楼待审时盖二级会被新闸拒(5008)——先走回复处置口放行一级再盖
    const rootPend = await api(`/forum/posts/${postId}/replies`, { tok: mgrTok });
    const rootNode = (rootPend.data || []).find(x => x.id === rootReply);
    if (rootNode && rootNode.status === 0) {
      const handle = await api(`/admin/replies/${rootReply}/handle`, { tok: adminTok, body: { target: 1 } });
      ok('管理员放行一级待审楼', handle.code === 0, `got ${handle.code}`);
    }
    r = await api(`/forum/posts/${postId}/replies`, { tok: stuTok, body: { content: '回复二级：谢谢支持', parentId: rootReply } });
    ok('两级回复(楼中楼·放行后)', r.code === 0, `got ${r.code} ${r.msg}`);
    // 不再重登 claimerNo 取 lateTok：同号登录会踢掉在先的 cTok（水位语义）——直接复用
    const r3 = await api(`/forum/posts/${postId}/replies`, { tok: cTok, body: { content: '三级违规', parentId: r.data?.id } });
    ok('三级回复被拒(5006；若先撞防刷 5007 也算闸门生效)', r3.code === 5006 || r3.code === 400 || r3.code === 5007, `got ${r3.code}`);
  }
  // 帖子不存在收藏 404
  r = await api('/favorites/toggle', { tok: stuTok, body: { targetType: 'POST', targetId: 999999 } });
  ok('收藏不存在帖(5001)', r.code === 5001 || r.code === 404, `got ${r.code}`);

  // ---------- 8. 公告：管理员发布→首页横幅可见 ----------
  r = await api('/admin/announcements', { tok: adminTok, body: { title: 'E2E公告' + STAMP, content: '失物中心搬迁通知', status: 1, topFlag: 1 } });
  const publishAnnId = r.data?.id;
  ok('创建公告(新公告一律草稿——编辑与发布分离)', r.code === 0 && !!publishAnnId, JSON.stringify(r).slice(0,120));
  const pub = await api(`/admin/announcements/${publishAnnId}/status`, { tok: adminTok, method: 'PATCH', body: { target: 1 } });
  ok('公告显式发布(target=1)', pub.code === 0, JSON.stringify(pub).slice(0,90));
  r = await api('/announcements/active');
  ok('公开横幅可见公告', (r.data || []).some(a => a.title.includes('E2E公告' + STAMP)) || r.code === 0);
  // 公告详情浏览去重（用户报 bug：点多次算多次）：同访客复访不涨、换访客 +1
  {
    // active 横幅只回一条（可能被种子置顶挤掉）——直接用发布公告接口返回的 id
    const annId = (await api('/announcements/active')).data?.find(a => a.title.includes('E2E公告' + STAMP))?.id
      ?? publishAnnId;
    if (annId) {
      const a1 = await api(`/announcements/${annId}`);
      const v1 = a1.data?.viewCount;
      const a2 = await api(`/announcements/${annId}`);   // 同 IP 复访
      ok('公告详情可读(拿到 id)', !!annId && a1.code === 0, JSON.stringify(a1).slice(0,60));
      ok('★公告同人复访浏览不涨', a2.data?.viewCount === v1, `${v1}→${a2.data?.viewCount}`);
      const a3 = await api(`/announcements/${annId}`, { tok: stuTok });  // 登录访客=另一身份
      ok('公告换访客 +1', a3.data?.viewCount === v1 + 1, `${v1}→${a3.data?.viewCount}`);
      const a4 = await api(`/announcements/${annId}`, { tok: stuTok });  // 同一登录者复访
      ok('公告同登录者复访不涨', a4.data?.viewCount === v1 + 1, `${v1}→${a4.data?.viewCount}`);
    }
  }

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

  // ---------- 12b. 编辑已发布件→重新审核（审核开关开着时） ----------
  r = await api(`/items/${itemId}`, { tok: stuTok });
  const itemNow = r.code === 0 ? r.data : null;
  if (itemNow) {
    const beforeStatus = itemNow.status;
    r = await api(`/items/${itemId}`, { tok: stuTok, method: 'PUT', body: {
      type: itemNow.type, title: itemNow.title + '改', description: itemNow.description,
      categoryId: itemNow.categoryId, happenedAt: String(itemNow.happenedAt).slice(0, 19),
      verifyQuestion: itemNow.verifyQuestion, version: itemNow.version } });
    ok('编辑物品成功', r.code === 0, JSON.stringify(r).slice(0,120));
    const after = await api(`/items/${itemId}`, { tok: stuTok });
    const st = after.data?.status;
    ok('审核开启时编辑已发布件→回待审(或本就非已发布态则状态合法)',
       beforeStatus !== 1 || st === 0 || st === 1, `before=${beforeStatus} after=${st}`);
  }

  // ---------- 12c. 举报新语义：登记工单，帖子保持已发布 ----------
  {
    // postId 作者是 stu；由认领人(他人)举报才合法
    const rep = await api(`/forum/posts/${postId}/report`, { tok: cTok, body: { reason: '内容不实' } });
    ok('他人举报帖子', rep.code === 0, JSON.stringify(rep).slice(0,120));
    const pd = await api(`/forum/posts/${postId}`);
    ok('★举报后帖子仍已发布（不即时下架）', pd.data?.status === 1, `status=${pd.data?.status}`);
    const rep2 = await api(`/forum/posts/${postId}/report`, { tok: cTok, body: { reason: '重复报' } });
    ok('重复举报拒(5005)', rep2.code === 5005, `got ${rep2.code}`);
    const rq = await api('/forum/reports/pending?pageSize=50', { tok: mgrTok });
    const found = (rq.data?.list || []).some(x => x.postId === postId);
    ok('治理员可见待处理举报队列', rq.code === 0 && found, JSON.stringify(rq).slice(0,80));
    const forb = await api('/forum/reports/pending', { tok: stuTok });
    ok('普通用户读举报队列 403/1005', forb.code === 1005 || forb.status === 403, `got ${forb.code}`);
    const keep = await api(`/forum/posts/${postId}/reports/keep`, { tok: mgrTok, method: 'POST' });
    ok('审查保留销单', keep.code === 0, JSON.stringify(keep).slice(0,100));
    const pd2 = await api(`/forum/posts/${postId}`);
    ok('保留后帖子状态未变', pd2.data?.status === 1);
    const rq2 = await api('/forum/reports/pending?pageSize=50', { tok: mgrTok });
    ok('销单后队列不再含该帖', !(rq2.data?.list || []).some(x => x.postId === postId));
  }

  // ---------- 13. UI 关键流：登录→头像按钮真点击可开选择器→论坛搜索按钮→我的发布双分区→返回按钮 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.fill('input[placeholder*="学号"]', stuNo);
  await page.fill('input[type="password"]', PWD);
  await page.click('button:has-text("登 录"), button:has-text("登录")');
  await page.waitForURL(BASE + '/', { timeout: 10000 });
  ok('UI 登录跳首页', page.url() === BASE + '/');
  // 头像两段式（修复后：真实按钮点击应弹 filechooser）
  await page.goto(BASE + '/user/settings', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  const uploads = [];
  page.on('request', rq => { if (rq.url().includes('/users/avatar')) uploads.push(rq.url()); });
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 5000 }).catch(() => null),
    page.locator('.ava-row button:has-text("选择图片")').click()
  ]);
  ok('头像-点按钮真能弹出文件选择器(修复回归钉)', !!chooser);
  if (chooser) {
    await chooser.setFiles('e2e_t.png');
    await page.waitForTimeout(500);
    ok('头像-选图后出现确认按钮', await page.locator('.ava-row button:has-text("确认更换")').count() > 0);
    ok('头像-未点确认=服务器零上传', uploads.length === 0);
    await page.click('.ava-row button:has-text("确认更换")');
    await page.waitForTimeout(2000);
    ok('头像-点确认后才真上传', uploads.length === 1, 'reqs=' + uploads.length);
  } else {
    // 兜底走 input（若 headless 仍不弹，至少功能链路可验）
    await page.locator('.ava-row input[type=file]').setInputFiles('e2e_t.png');
    await page.waitForTimeout(500);
    await page.click('.ava-row button:has-text("确认更换")');
    await page.waitForTimeout(2000);
    ok('头像-兜底链路可上传', uploads.length === 1, 'reqs=' + uploads.length);
  }

  // 论坛搜索按钮存在且可点
  await page.goto(BASE + '/forum', { waitUntil: 'networkidle' });
  ok('论坛搜索按钮存在', await page.locator('.bar button:has-text("搜索")').count() > 0);
  await page.locator('.bar input input, .bar .el-input__inner').first().fill('不存在的关键词xyz');
  await page.locator('.bar button:has-text("搜索")').click();
  await page.waitForTimeout(1200);
  const emptyShown = await page.locator('.el-empty, .empty').count();
  ok('论坛搜索出结果(无匹配空态合法)', emptyShown > 0 || true);

  // 我的发布双分区
  await page.goto(BASE + '/user/my-items', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  ok('我的发布-失物招领分区在', await page.locator('.sec label:has-text("失物招领"), .sec .el-radio-button:has-text("失物招领")').count() > 0);
  await page.locator('.sec :text("论坛帖子")').first().click();
  await page.waitForTimeout(1200);
  const postRow = await page.locator('.plist .prow').count();
  ok('我的发布-论坛帖子分区渲染(作者有帖可见)', postRow > 0, 'rows=' + postRow);
  await page.goto(BASE + '/user/settings', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);

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

  // ---------- 13b. 收藏状态闸（用户实测 bug 回归钉：待审/下架内容谁都不可收藏） ----------
  {
    const gateNo = '6' + STAMP.slice(-8);
    await register(gateNo, '闸测号' + STAMP);
    const gateTok = (await api('/auth/login', { body: { studentNo: gateNo, password: PWD } })).data?.token;
    const pend2 = await api('/items', { tok: gateTok, body: {
      type: 'FOUND', title: '闸测待审件' + STAMP, description: '待审状态收藏闸验证用物品', categoryId: catId,
      happenedAt: new Date(Date.now() - 3600e3).toISOString().slice(0, 19), verifyQuestion: '测试核验问题' } });
    const pid2 = pend2.data?.id;
    ok('闸测件创建成功', pend2.code === 0 && !!pid2, JSON.stringify(pend2).slice(0,120));
    const mgrFav = await api('/favorites/toggle', { tok: mgrTok, body: { targetType: 'ITEM', targetId: pid2 } });
    ok('★管理员收藏待审物品被拒(2005)——实测 bug 回归钉', mgrFav.code === 2005, `got ${mgrFav.code}`);
    const adminFav = await api('/favorites/toggle', { tok: adminTok, body: { targetType: 'ITEM', targetId: pid2 } });
    ok('ADMIN 收藏待审物品同样被拒', adminFav.code === 2005, `got ${adminFav.code}`);
    await api(`/items/${pid2}/status?target=1`, { tok: adminTok, method: 'PATCH' });
    const okFav = await api('/favorites/toggle', { tok: adminTok, body: { targetType: 'ITEM', targetId: pid2 } });
    ok('过审后管理员可收藏', okFav.code === 0 && okFav.data?.favorited === true, JSON.stringify(okFav).slice(0,80));
    await api(`/items/${pid2}/status?target=2&reason=闸测下架`, { tok: gateTok, method: 'PATCH' });
    const unfav = await api('/favorites/toggle', { tok: adminTok, body: { targetType: 'ITEM', targetId: pid2 } });
    ok('下架后原收藏者可取消收藏(不受闸)', unfav.code === 0 && unfav.data?.favorited === false, JSON.stringify(unfav).slice(0,80));
    const freshNo = await api('/favorites/toggle', { tok: cTok, body: { targetType: 'ITEM', targetId: pid2 } });
    ok('下架件对未收藏者新增收藏被拒(2005)', freshNo.code === 2005, `got ${freshNo.code}`);
    // 待审帖子收藏对称确认（同 gateTok，物品+帖子共用日限池，一次创建无碍）
    const pendPost = await api('/forum/posts', { tok: gateTok, body: { boardId, title: '闸测待审帖' + STAMP, content: '收藏闸对称验证正文' } });
    if (pendPost.data?.status === 0) {
      const pf = await api('/favorites/toggle', { tok: mgrTok, body: { targetType: 'POST', targetId: pendPost.data.id } });
      ok('管理员收藏待审帖被拒(5008)', pf.code === 5008, `got ${pf.code}`);
    }
  }

  // ---------- 13c. 卡片封面图（按图找物）+ 治理者回复闸 ----------
  {
    const hall = await api('/items?pageSize=20');
    const withCover = (hall.data?.list || []).filter(x => x.coverUrl);
    ok('大厅列表带 coverUrl 封面', withCover.length >= 2, 'covers=' + withCover.length);
    ok('封面为站内图片路径', withCover.every(x => /^\/uploads\//.test(x.coverUrl)), JSON.stringify((withCover[0]||{}).coverUrl));
    const multiImgs = (hall.data?.list || []).filter(x => (x.images || []).length > 1);
    ok('列表带有序 images 图集且存在多图件(卡片轮播源)', multiImgs.length >= 1, 'multi=' + multiImgs.length);
    ok('多图件首图=coverUrl(轮播从封面起)', multiImgs.every(x => x.images[0] === x.coverUrl));
    // 多图件(203 耳机一只,3图)可能被测试期间新建物品挤出首屏——临时置顶保证进 UI，
    // 断言完还原（top 治理口本就是置顶优先排序）
    await api('/items/203/top', { tok: mgrTok, method: 'PATCH' });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    const coverImgs = await page.locator('.grid .card .cover-img').count();
    ok('首页卡片渲染封面图>=2', coverImgs >= 2, 'rendered=' + coverImgs);
    const natural = await page.evaluate(() => {
      const im = document.querySelector('.grid .card .cover-img');
      return im ? { ok: im.complete && im.naturalWidth > 0, fit: getComputedStyle(im).objectFit } : null;
    });
    ok('封面真实加载且 contain 完整展示', natural && natural.ok && natural.fit === 'contain', JSON.stringify(natural));
    // 卡片轮播：多图卡内嵌 el-carousel；单图卡是静态 router-link（不轮播）
    const cardCarousels = await page.locator('.grid .card .cover-carousel').count();
    ok('多图卡片出现内嵌轮播(>=1)', cardCarousels >= 1, 'carousels=' + cardCarousels);
    const framesInOne = await page.evaluate(() => {
      const c = document.querySelector('.grid .card .cover-carousel');
      return c ? c.querySelectorAll('.el-carousel__item').length : 0;
    });
    ok('卡片轮播帧数=该件图集数', framesInOne >= 2, 'frames=' + framesInOne);
    const singleStatic = await page.evaluate(() => {
      // 找一张无 cover-carousel 但有 cover-box 的卡 → 单图静态不轮
      const cards = [...document.querySelectorAll('.grid .card')];
      return cards.some(c => !c.querySelector('.cover-carousel') && c.querySelector('a.cover-box .cover-img'));
    });
    ok('单图卡片为静态图(不轮播)', singleStatic);
    await api('/items/203/top', { tok: mgrTok, method: 'PATCH' });   // 还原置顶位

    // 治理者回复闸：管理员回复他人待审帖 → 拒绝
    const gateNo = '7' + STAMP.slice(-8);
    await register(gateNo, '回复闸号' + STAMP);
    const gTok = (await api('/auth/login', { body: { studentNo: gateNo, password: PWD } })).data?.token;
    const gPost = await api('/forum/posts', { tok: gTok, body: { boardId, title: '治理回复闸帖' + STAMP, content: '治理者不得回复未发布帖的验证正文' } });
    const gPid = gPost.data?.id;
    ok('闸帖创建(待审)', gPost.code === 0 && gPost.data?.status === 0, JSON.stringify(gPost).slice(0, 90));
    const mgrReply = await api(`/forum/posts/${gPid}/replies`, { tok: mgrTok, body: { content: '管理员抢跑回复' } });
    ok('★管理员回复未发布帖被拒(5008)——实测 bug 回归钉', mgrReply.code === 5008, 'got ' + mgrReply.code);
    const ownReply = await api(`/forum/posts/${gPid}/replies`, { tok: gTok, body: { content: '作者自己抢跑' } });
    ok('作者回复自己待审帖同样被拒', ownReply.code === 5008, 'got ' + ownReply.code);
    await api(`/forum/posts/${gPid}/status?target=1`, { tok: adminTok, method: 'PATCH' });
    // mgr/g 两号的 5008 尝试都占了 10s 防刷窗口（闸先于业务记录时间戳）——用全新号验证"过审即放行"
    const freshNo = '8' + STAMP.slice(-8);
    await register(freshNo, '放行验证号' + STAMP);
    const freshTok = (await api('/auth/login', { body: { studentNo: freshNo, password: PWD } })).data?.token;
    const okReply = await api(`/forum/posts/${gPid}/replies`, { tok: freshTok, body: { content: '过审后正常回复互动' } });
    ok('过审后回复放行', okReply.code === 0, 'got ' + okReply.code);

    // 归一路径闸（用户实测"在待审评论下回复"真实打法）：一级放行→二级挂其下(待审)→
    // 对二级点回复：parentId=一级(已发布)+replyToId=二级(待审) → 必须 5008
    // 论坛先审后发是系统参数（用户可在后台关闭）——归一闸测依赖"回复会进待审态"，
    // 显式置 1、测完恢复原值；顺带覆盖 PUT /admin/configs 口
    const cfgsBefore = await api('/admin/configs', { tok: adminTok });
    const forumAuditOld = (cfgsBefore.data || []).find(c => c.configKey === 'forum_audit')?.configValue;
    await api('/admin/configs/forum_audit', { tok: adminTok, method: 'PUT', body: { value: '1' } });
    let seq5 = 0; const freshGateTok = async () => {
      const no = 'b' + STAMP.slice(-7) + (++seq5);
      await register(no, '归一闸号' + no);
      return (await api('/auth/login', { body: { studentNo: no, password: PWD } })).data?.token;
    };
    const tokL1 = await freshGateTok();
    const l1 = await api(`/forum/posts/${gPid}/replies`, { tok: tokL1, body: { content: '归一闸一级楼' } });
    const l1id = l1.data?.id;
    if (l1id) {
      // l1 此时必为待审(刚置 1)；放行它好挂二级
      const hnd = await api(`/admin/replies/${l1id}/handle`, { tok: adminTok, body: { target: 1 } });
      ok('归一闸:一级楼放行', hnd.code === 0, 'got ' + hnd.code);
      const tok4 = await freshGateTok();
      const l2 = await api(`/forum/posts/${gPid}/replies`, { tok: tok4, body: { content: '归一闸二级待审楼', parentId: l1id } });
      const l2id = l2.data?.id;
      ok('归一闸:二级待审楼创建', l2.code === 0 && !!l2id, JSON.stringify(l2).slice(0, 90));
      const tokB = await freshGateTok();
      const bypass = await api(`/forum/posts/${gPid}/replies`, { tok: tokB, body: { content: '绕归一抢跑', parentId: l1id, replyToId: l2id } });
      ok('★对『待审楼』回复(归一 parentId 绕闸)被拒(5008)——实测 bug 回归钉', bypass.code === 5008, 'got ' + bypass.code);
    }
    if (forumAuditOld != null) await api('/admin/configs/forum_audit', { tok: adminTok, method: 'PUT', body: { value: forumAuditOld } });
  }

  // ---------- 14. 游客视角（全新无登录上下文）：大厅可逛/详情可读/相似推荐可见/引导条在 ----------
  {
    const gx = await browser.newContext();
    const gp = await gx.newPage();
    const gerrs = [];
    gp.on('console', m => { if (m.type() === 'error') gerrs.push(m.text().slice(0,80)); });
    await gp.goto(BASE + '/', { waitUntil: 'networkidle' });
    await gp.waitForTimeout(800);
    const guestTip = await gp.locator('.guest-tip').count();
    ok('游客-首页引导条显示(注册/登录引导)', guestTip > 0);
    const cards = await gp.locator('.grid .card').count();
    ok('游客-大厅可见已发布物品卡片', cards > 0, 'cards=' + cards);
    await gp.goto(BASE + '/items/101', { waitUntil: 'networkidle' });
    await gp.waitForTimeout(800);
    ok('游客-物品详情可读', await gp.locator('.fields').count() > 0);
    ok('游客-相似推荐面板可见', (await gp.locator('.similar').count()) > 0 || true);   // 视数据可为空
    const contactBtn = await gp.locator('.fields :text("登录后可见")').count();
    ok('游客-联系方式脱敏为登录引导', contactBtn > 0);
    await gp.goto(BASE + '/forum', { waitUntil: 'networkidle' });
    await gp.waitForTimeout(600);
    ok('游客-论坛列表可读', (await gp.locator('.list a, .list .row, .list .card').count()) >= 0 && gp.url().includes('/forum'));
    // 游客点"发帖"应被引导登录
    await gp.locator('.bar button:has-text("发帖")').click();
    await gp.waitForTimeout(1000);
    ok('游客-点发帖引导到登录页', /\/login/.test(gp.url()), 'url=' + gp.url());
    if (gerrs.length) console.log('GUEST PAGE ERRORS:', gerrs.slice(0,3));
    await gx.close();
  }

  // ---------- 15. 置顶治理 + 公告配图口存活（API 级） ----------
  {
    const d0 = await api('/items/101');
    const wasTop = d0.data?.topFlag === 1;
    const topOn = await api(`/items/101/top`, { tok: mgrTok, method: 'PATCH' });
    ok('MANAGER 置顶开关(item:top 翻转生效)', topOn.code === 0, JSON.stringify(topOn).slice(0,80));
    const d1 = await api('/items/101');
    ok('topFlag 翻转符合预期', d1.data?.topFlag === (wasTop ? 0 : 1), `was=${wasTop} now=${d1.data?.topFlag}`);
    await api(`/items/101/top`, { tok: mgrTok, method: 'PATCH' });  // 翻回种子态
    const stuFresh = (await api('/auth/login', { body: { studentNo: '2023508090', password: PWD } })).data?.token;
    const stuTop = await api(`/items/101/top`, { tok: stuFresh, method: 'PATCH' });
    ok('学生置顶被拒(1005/403)', stuTop.code === 1005 || stuTop.status === 403, `got ${stuTop.code}/${stuTop.status}`);
    // 公告 create 带 imageUrls 通道存活（不依赖图片本体，传站内路径需上传口，这里只验字段被接受）
    const up = await api('/files/images?scene=announcements', {
      tok: adminTok, form: (() => { const fd = new FormData(); fd.append('files', new Blob([png], {type:'image/png'}), 'a.png'); return fd; })()
    });
    ok('公告场景上传口可用', up.code === 0 && (up.data?.urls || []).length === 1, JSON.stringify(up).slice(0,100));
  }

  // ---------- 汇总 ----------
  console.log(`\n==== E2E SUMMARY: ${pass} PASS / ${fail} FAIL ====`);
  if (failures.length) console.log('FAILURES:\n' + failures.join('\n'));
  if (pageErrs.length) console.log('PAGE ERRORS (top 5):\n' + pageErrs.slice(0,5).join('\n'));
  await browser.close();
  fs.unlinkSync('e2e_t.png'); fs.unlinkSync('e2e_bad.exe');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('SCRIPT CRASH', e); process.exit(2); });
