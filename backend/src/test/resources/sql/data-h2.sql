-- =====================================================================
-- 校园失物招领系统 · H2 测试种子（与生产 data.sql 逐行对齐）（幂等：全部 INSERT IGNORE，可重复执行）
-- 约定：
--   1) 显式 id，测试 data-h2.sql 与本文件逐行对齐；
--   2) 种子演示账号口令散列为 BCrypt("password") 预计算值（仅演示/测试语义，
--      admin 真实口令由 M2 AdminBootstrapRunner 按 LF_ADMIN_PASSWORD 首启创建，
--      不经此种子——避免仓库里出现可登录生产的管理员凭据）；
--   3) 安全红线：任何真实密钥/口令形态字面量不得出现在本仓库。
-- =====================================================================

-- ---------- RBAC ----------
-- 演示用户（id 固定便于测试引用；status 语义：1正常 0封禁 2禁言）
INSERT IGNORE INTO sys_user (id, student_no, password, nickname, email, status) VALUES
  (1, '2023508090', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '张同学', 'stu1@campus.edu.cn', 1),
  (2, '2023508091', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '李同学', 'stu2@campus.edu.cn', 1),
  (3, '2023508092', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '被封号的', 'stu3@campus.edu.cn', 0),
  (4, '2023508093', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '被禁言的', 'stu4@campus.edu.cn', 2);

-- 角色：1=STUDENT 2=MANAGER(失物中心) 3=ADMIN
INSERT IGNORE INTO sys_role (id, role_code, role_name, description) VALUES
  (1, 'ROLE_STUDENT', '学生',     '注册默认角色：发布/认领/论坛'),
  (2, 'ROLE_MANAGER', '失物中心', '学生会工作人员：审核/仲裁/公告'),
  (3, 'ROLE_ADMIN',   '系统管理员', '全站治理：全部权限点');

-- 用户-角色：1/2/3/4 皆学生；2 号兼失物中心干事；（admin 由 Runner 引导并挂 3）
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
  (1, 1), (2, 1), (3, 1), (4, 1), (2, 2);

-- 权限点（module 分组；接口 @PreAuthorize 与菜单渲染单一事实源）
INSERT IGNORE INTO sys_permission (id, perm_code, perm_name, module) VALUES
  (1,  'item:audit',      '物品审核',     'item'),
  (2,  'item:offline',    '违规下架',     'item'),
  (3,  'item:top',        '置顶/加急',    'item'),
  (4,  'claim:arbitrate', '认领仲裁',     'claim'),
  (5,  'forum:audit',     '帖子/回复审核', 'forum'),
  (6,  'forum:offline',   '帖子下架',     'forum'),
  (7,  'announce:manage', '公告管理',     'announce'),
  (8,  'notice:send',     '群发通知',     'system'),
  (9,  'user:manage',     '用户治理',     'user'),
  (10, 'config:manage',   '系统参数',     'system'),
  (11, 'word:manage',     '敏感词管理',   'system'),
  (12, 'stats:view',      '数据看板',     'stats'),
  (13, 'oplog:view',      '审计日志',     'system');

-- 授权矩阵：MANAGER=审核/下架/仲裁/公告/看板/通知；ADMIN=全部
INSERT IGNORE INTO sys_role_permission (role_id, perm_id) VALUES
  (2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 12),
  (3, 1), (3, 2), (3, 3), (3, 4), (3, 5), (3, 6), (3, 7), (3, 8),
  (3, 9), (3, 10), (3, 11), (3, 12), (3, 13);

-- ---------- 字典：物品分类（6）+ 论坛板块（3） ----------
INSERT IGNORE INTO category (id, name, kind, sort_order) VALUES
  (1, '证件卡类', 'ITEM', 1),
  (2, '电子设备', 'ITEM', 2),
  (3, '书籍文具', 'ITEM', 3),
  (4, '钥匙衣物', 'ITEM', 4),
  (5, '运动器材', 'ITEM', 5),
  (6, '其他物品', 'ITEM', 9),
  (11, '失物交流', 'BOARD', 1),
  (12, '校园生活', 'BOARD', 2),
  (13, '求助问答', 'BOARD', 3);

-- ---------- 字典：校区-楼栋（1/2 校区，11-14 楼栋） ----------
INSERT IGNORE INTO campus_area (id, name, level, parent_id, sort_order) VALUES
  (1, '主校区',   1, NULL, 1),
  (2, '南校区',   1, NULL, 2),
  (11, '一号楼',  2, 1, 1),
  (12, '三号楼',  2, 1, 2),
  (13, '图书馆',  2, 1, 3),
  (14, '体育馆',  2, 2, 1);

-- ---------- 系统参数（审核双开关+治理旋钮；键口径见开发方案 §3.3.1） ----------
INSERT IGNORE INTO sys_config (id, config_key, config_value, config_type, description, editable) VALUES
  (1, 'site_name',            '校园失物招领', 'STRING', '站点名称（前端顶栏）', 1),
  (2, 'item_audit',           '1',    'BOOL', '物品发布先审后发开关', 1),
  (3, 'forum_audit',          '1',    'BOOL', '论坛发帖/回复先审后发开关', 1),
  (4, 'claim_timeout_hours',  '72',   'NUMBER', '认领核验超时提醒阈值（小时）', 1),
  (5, 'daily_publish_limit',  '10',   'NUMBER', '单用户每日发布上限（物品+帖子合计）', 1),
  (6, 'announcement_enabled', '1',    'BOOL', '首页公告横幅开关', 1),
  (7, 'match_recommend_top',  '5',    'NUMBER', '相似推荐条数', 1);

-- ---------- 敏感词（课设演示用占位词表；上线前由管理员扩充） ----------
INSERT IGNORE INTO sensitive_word (id, word) VALUES
  (1, '代刷'), (2, '办证'), (3, '私聊加我');

-- ---------- 演示公告 1 条（已发布置顶，开箱即见横幅） ----------
INSERT IGNORE INTO announcement (id, title, content, status, top_flag, author_id, view_count) VALUES
  (1, '欢迎使用校园失物招领系统',
     '拾到物品请优先在本平台发布招领信息并设置核验问题；贵重物品可同时交至失物中心（三号楼一层服务台）。',
     1, 1, 2, 0);

-- ---------- 演示物品/帖子：M1 只保证字典与治理面种子，业务演示数据随 M3/M5 测试补齐 ----------
