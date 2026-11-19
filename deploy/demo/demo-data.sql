-- =====================================================================
-- 演示数据包 v2（id 201 段内容 + 学号锚定的演示用户，幂等可重放）
-- v1 教训：演示用户曾写死 id=201-203，与本机自动增长的真实账号撞车，
-- INSERT IGNORE 静默跳过后，内容被错误归属到真实账号——本版改为：
--   演示用户只按 student_no 唯一键插入（id 交给自增），
--   全部内容引用一律经 @var（学号子查询）解析，收尾用 UPDATE 对账计数。
-- 覆盖：22 物品（六分类两校区/待审/已归还/违规下架/置顶）、7 认领单（6 态全覆盖）、
--   15 条留痕日志、12 帖、19 两级回复（含 1 待审）、21 赞、9 收藏、12 消息、3 演示学生。
-- =====================================================================
USE lost_found_db;

-- ---------- 0) 重放前清旧 demo 行（id 段专属，不碰业务自增行/真实账号） ----------
DELETE FROM claim_log        WHERE id BETWEEN 201 AND 215;
DELETE FROM item_claim       WHERE id BETWEEN 201 AND 207;
DELETE FROM user_favorite    WHERE id BETWEEN 201 AND 209;
DELETE FROM forum_like       WHERE id BETWEEN 201 AND 221;
DELETE FROM forum_reply      WHERE id BETWEEN 201 AND 218;
DELETE FROM forum_post       WHERE id BETWEEN 201 AND 212;
DELETE FROM lost_item        WHERE id BETWEEN 201 AND 222;
DELETE FROM notification     WHERE id BETWEEN 201 AND 212;
DELETE FROM item_image       WHERE id BETWEEN 201 AND 216;
DELETE FROM sys_user_role    WHERE user_id IN (SELECT id FROM sys_user WHERE student_no IN ('2023508094','2023508095','2023508096'));
DELETE FROM sys_user         WHERE student_no IN ('2023508094','2023508095','2023508096');
-- （防 email 唯一键撞种子账号：演示 email 用 demo 前缀）

-- ---------- 1) 演示学生（口令=种子 BCrypt 常量 password123；id 走自增） ----------
INSERT IGNORE INTO sys_user (student_no, password, nickname, email, status) VALUES
  ('2023508094', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '王小明', 'demo4@campus.edu.cn', 1),
  ('2023508095', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '赵小雨', 'demo5@campus.edu.cn', 1),
  ('2023508096', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '孙浩然', 'demo6@campus.edu.cn', 1);
SET @ua = (SELECT id FROM sys_user WHERE student_no = '2023508094');
SET @ub = (SELECT id FROM sys_user WHERE student_no = '2023508095');
SET @uc = (SELECT id FROM sys_user WHERE student_no = '2023508096');
INSERT INTO sys_user_role (user_id, role_id)
  SELECT t.uid, 1 FROM (SELECT @ua uid UNION SELECT @ub UNION SELECT @uc) t
  WHERE NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = t.uid AND ur.role_id = 1);

-- ---------- 2) 物品 201-222 ----------
INSERT INTO lost_item (id, type, title, description, category_id, campus_id, building_id, location_text,
  happened_at, contact, verify_question, user_id, status, offline_reason, view_count, claim_count, like_count, top_flag, version, published_at) VALUES
  (201, 'FOUND', '一卡通一张（卡面姓王）', '三号楼二层水房捡到一卡通，请失主说明卡号后四位认领。', 1, 1, 12, '二层水房门口',
   DATE_SUB(NOW(), INTERVAL 1 DAY), 'wx_zhang_ts', '卡号后四位是多少？', 1, 1, NULL, 35, 2, 1, 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (202, 'LOST', '白色无线耳机（图书馆丢失）', '昨天下午在图书馆二楼自习，白色耳机连充电盒一起丢了，盒底有划痕。', 2, 1, 13, '二楼靠东自习区',
   DATE_SUB(NOW(), INTERVAL 1 DAY), 'qq_xiaoming', NULL, @ua, 1, NULL, 28, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (203, 'FOUND', '耳机一只（左耳，带猫贴纸充电盒）', '图书馆二楼插座旁捡到白色耳机一只，充电盒贴有橘猫贴纸。', 2, 1, 13, '二楼插座旁',
   NOW(), '三号楼一层失物中心代管', '充电盒上的贴纸是什么图案？', @ub, 1, NULL, 12, 1, 1, 0, 0, NOW()),
  (204, 'LOST', '急寻！我的校园卡（含一卡通和银行卡）', '钱包式卡包丢了，里面有校园卡、建行卡和一张全家福，卡包是深蓝色的，重谢！', 1, 2, 14, '体育馆看台阶梯上',
   DATE_SUB(NOW(), INTERVAL 2 DAY), 'tel_138****2211', NULL, @uc, 1, NULL, 45, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (205, 'FOUND', '高等数学课本（同济第七版）', '图书馆三楼座位夹缝里的一本高数下册，扉页写着"计科2班"。', 3, 1, 13, '三楼文学区',
   DATE_SUB(NOW(), INTERVAL 3 DAY), 'wx_zhang_ts', '扉页上除了班级还写了什么？', 1, 1, NULL, 9, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (206, 'LOST', '宿舍钥匙一串（红色挂绳）', '一号楼到三号楼之间把宿舍钥匙弄丢了，挂着红色编织绳，有一枚小铃铛。', 4, 1, 11, '可能落在一号楼门厅',
   DATE_SUB(NOW(), INTERVAL 4 DAY), 'qq_xiaoming', NULL, @ua, 1, NULL, 6, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (207, 'FOUND', '黑色羽绒服（自习室椅背）', '三号楼自习室椅背上落下一件黑色羽绒服，口袋里有半包纸巾。', 4, 1, 12, '三楼自习室',
   DATE_SUB(NOW(), INTERVAL 5 DAY), '三号楼一层值班室', '羽绒服左口袋还有什么东西？', @ub, 1, NULL, 14, 1, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 5 DAY)),
  (208, 'LOST', '羽毛球拍两支装（球包灰色）', '体育馆打完球把球包忘在场边长椅了，里面有两支尤尼克斯拍子。', 5, 2, 14, '羽毛球馆东侧',
   DATE_SUB(NOW(), INTERVAL 6 DAY), 'tel_138****0776', NULL, @uc, 1, NULL, 5, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 DAY)),
  (209, 'FOUND', '透明长柄伞（图书馆伞架）', '图书馆门口伞架发现一把无人认领的透明长柄伞，伞柄缠了黑胶带。', 6, 1, 13, '正门伞架',
   DATE_SUB(NOW(), INTERVAL 6 DAY), 'wx_zhang_ts', '伞柄上缠的胶带是什么颜色？', 1, 1, NULL, 3, 0, 1, 0, 0, DATE_SUB(NOW(), INTERVAL 6 DAY)),
  (210, 'LOST', '薄荷绿保温杯（一号楼302→教室）', '杯身贴有一枚小恐龙贴纸的保温杯，昨天从宿舍带到教室就找不到了。', 6, 1, 11, '一号楼302/201教室',
   DATE_SUB(NOW(), INTERVAL 7 DAY), 'qq_xiaoming', NULL, @ua, 1, NULL, 4, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 7 DAY)),
  (211, 'FOUND', '学生证（姓名栏：王小明）', '图书馆还书台捡到学生证一张，证主是外语学院的同学。', 1, 1, 13, '还书台',
   NOW(), '三号楼一层失物中心', '学生证上的照片背景是什么颜色？', @ub, 1, NULL, 22, 1, 1, 0, 0, NOW()),
  (212, 'LOST', '《线性代数》教材（第六版）', '南校区自习室丢了线代课本，书角有咖啡渍，里面夹着笔记。', 3, 2, NULL, '南校区自习室B区',
   DATE_SUB(NOW(), INTERVAL 8 DAY), 'tel_138****0776', NULL, @uc, 1, NULL, 7, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 DAY)),
  (213, 'FOUND', '粉色充电宝（水房台面）', '三号楼水房台面捡到粉色充电宝，线是白色的。', 2, 1, 12, '一层水房',
   NOW(), '三号楼一层值班室', '充电宝是什么牌子的？', @ub, 1, NULL, 11, 0, 0, 0, 0, NOW()),
  (214, 'LOST', '蓝牙耳机（左耳单只！）', '就一只左耳耳机掉了，白色，盒都不见了，应该掉在三号楼附近。', 2, 1, 12, '三号楼门口花坛',
   NOW(), 'qq_xiaoming', NULL, @ua, 1, NULL, 16, 0, 1, 0, 0, NOW()),
  (215, 'FOUND', '钥匙一串（蓝色小熊挂件）', '体育馆门口捡到钥匙一串约四五把，带蓝色小熊挂件——已由失物中心归还失主。', 4, 2, 14, '正门',
   DATE_SUB(NOW(), INTERVAL 3 DAY), '三号楼一层服务台', '钥匙串上除了小熊挂件还有什么？', 1, 2, '已归还', 51, 2, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (216, 'LOST', '智能手表（黑色表带）', '手表落在食堂二楼餐桌上，屏幕有贴膜，表盘贴纸是海绵宝宝。', 2, 1, 12, '第二食堂二楼',
   NOW(), 'qq_xiaoyu', NULL, @ub, 0, NULL, 0, 0, 0, 0, 0, NULL),
  (217, 'FOUND', '科学计算器（卡西欧991）', '体育馆更衣室捡到的计算器——与站内重复信息合并后下架，请认准同类件。', 6, 2, 14, '更衣室',
   DATE_SUB(NOW(), INTERVAL 9 DAY), NULL, NULL, @uc, 2, '违规下架：重复发布', 9, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 9 DAY)),
  (218, 'LOST', '棕色钱包（内有两张饭卡）', '钱包里有两张饭卡和重要证件，捡到请联系失物中心，必有重谢。', 1, 1, 11, '一号楼快递柜旁',
   DATE_SUB(NOW(), INTERVAL 2 DAY), 'tel_138****0011', NULL, 1, 1, NULL, 18, 0, 0, 1, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (219, 'FOUND', '黑色针织手套一双', '体育馆看台座位上落下的一双手套，右手食指处有破洞。', 4, 2, 14, '看台C区',
   NOW(), '体育馆前台', '手套右手哪个位置有破洞？', @uc, 1, NULL, 2, 0, 0, 0, 0, NOW()),
  (220, 'LOST', '折叠台灯（暖光款）', '宿舍用的折叠台灯忘在自习室插座上了，底座有姓名标签"赵"。', 2, 1, 13, '图书馆二楼自习区',
   NOW(), 'qq_xiaoyu', NULL, @ub, 1, NULL, 9, 0, 0, 0, 0, NOW()),
  (221, 'FOUND', '学生卡套（动漫图案）', '图书馆服务台发现的卡套，里面卡已交失物中心，卡套先挂这里失主自取。', 1, 1, 13, '一层服务台',
   NOW(), '三号楼一层失物中心', '卡套上印的是什么角色？', @ua, 1, NULL, 4, 0, 0, 0, 0, NOW()),
  (222, 'LOST', '运动水壶（黑色大容量）', '在体育馆跑步机上落下的大水壶，壶身有"HEAVY USER"贴纸。', 5, 2, 14, '二楼跑步机区',
   NOW(), 'tel_138****0011', NULL, 1, 1, NULL, 30, 0, 0, 0, 0, NOW());


-- ---------- 2b) 演示图片（bind 挂载直出 /uploads/demoimg/*，down -v 不丢） ----------
-- 轮播取"已发布带图件"首图：8 件配图 → 首页 8 帧（置顶优先排序）
INSERT INTO item_image (id, item_id, url, sort_order) VALUES
  (201, 201, '/uploads/demoimg/card.png', 0),      -- 一卡通
  (202, 203, '/uploads/demoimg/earbud.png', 0),    -- 耳机一只
  (203, 205, '/uploads/demoimg/book.png', 0),      -- 高数课本
  (204, 207, '/uploads/demoimg/jacket.png', 0),    -- 黑色羽绒服
  (205, 209, '/uploads/demoimg/umbrella.png', 0),  -- 透明长柄伞
  (206, 211, '/uploads/demoimg/card.png', 0),      -- 学生证
  (207, 218, '/uploads/demoimg/wallet.png', 0),    -- 棕色钱包（置顶件）
  (208, 222, '/uploads/demoimg/bottle.png', 0);    -- 运动水壶

-- ---------- 3) 认领单 7 张（6 态）+ 留痕 ----------
INSERT INTO item_claim (id, item_id, claimant_id, answer, evidence_note, status, reject_reason, arbitrated_by, handled_at, version, created_at) VALUES
  (201, 201, @ua, '后四位好像是 6612', '我在一号楼宿舍丢的，卡里还绑了洗澡卡', 0, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (202, 201, @ub, '卡号后四位 6612，姓名王X', '我和失主同班，辅导员可以作证', 0, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
  (203, 211, @ua, '照片背景是天蓝色的', '证件号尾号 2043', 1, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 10 HOUR)),
  (204, 203, @uc, '贴纸应该是个小猫吧', '我在图书馆二楼坐过', 3, '描述与实物不符：贴纸图案答不出，补充描述也无法对应', NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 30 HOUR)),
  (205, 207, @ua, '应该有半包纸巾吧，可能还有钥匙', '我那天穿藏青色外套坐过那个位子', 4, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (206, 215, 2,   '小熊挂件上还挂着一枚校徽', '宿舍门牌 2-301', 2, NULL, NULL, DATE_SUB(NOW(), INTERVAL 20 HOUR), 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (207, 215, @uc, '蓝色小熊，还有超市积分卡', '我的钥匙串肯定是对的', 5, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 22 HOUR));

INSERT INTO claim_log (id, claim_id, actor_id, action, detail) VALUES
  (201, 201, @ua, 'APPLY', NULL),
  (202, 202, @ub, 'APPLY', NULL),
  (203, 203, @ua, 'APPLY', NULL),
  (204, 203, @ub, 'VERIFY_PASS', '答案与证件信息一致'),
  (205, 204, @uc, 'APPLY', NULL),
  (206, 204, @ub, 'VERIFY_REJECT', '描述与实物不符：贴纸图案答不出，补充描述也无法对应'),
  (207, 205, @ua, 'APPLY', NULL),
  (208, 205, @ub, 'VERIFY_REJECT', '左口袋内衬有破洞且挂着小钥匙扣，与你的描述不符'),
  (209, 205, @ua, 'ARBITRATE_APPLY', NULL),
  (210, 206, 1,   'APPLY', NULL),
  (211, 206, 1,   'VERIFY_PASS', '挂件+门牌都对上了'),
  (212, 206, 1,   'CONFIRM_RETURN', NULL),
  (213, 207, @uc, 'APPLY', NULL),
  (214, 207, NULL, 'CLOSED_BY_RETURN', NULL),
  (215, 206, NULL, 'TIMEOUT_REMIND', NULL);

-- ---------- 4) 论坛帖 12 篇 ----------
INSERT INTO forum_post (id, board_id, title, content, user_id, status, view_count, reply_count, like_count, top_flag, version, published_at) VALUES
  (201, 11, '给失物中心点赞：丢了三天的一卡通找回来了', '上周把一卡通丢在三号楼，抱着试试的心态在平台发了寻物，当天就有同学认领申请，核验问题一对就确认归还，比刷群靠谱多了，大家多用平台！', @ub, 1, 66, 4, 5, 1, 0, DATE_SUB(NOW(), INTERVAL 5 DAY)),
  (202, 13, '捡到饭卡怎么还给失主？', '在食堂门口捡到两张饭卡，卡上没有名字，直接交给失物中心可以吗？还是自己等失主来问？', @ua, 1, 30, 3, 2, 0, 0, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (203, 12, '三号楼自习室的橘猫又出现了', '就在三楼靠窗的位置，旁边同学丢了半根火腿肠大概是被它吃的（不是）。言归正传，大家自习记得看管好小件物品。', @ub, 1, 12, 1, 1, 0, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (204, 11, '求扩散：南校区体育馆丢羽毛球拍', '球友丢了一副拍子在灰色球包里，对球友很重要（专业训练用的），看到的帮忙顶一下。', @uc, 1, 45, 2, 3, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (205, 13, '在图书馆丢了借的书会被处分吗', '不小心把图书馆借的线代课本弄丢了，补办流程是什么样的？会进诚信档案吗？有点慌。', 1, 1, 8, 1, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 DAY)),
  (206, 12, '失物招领平台使用一周体验反馈', '统计了下：这周在平台上找回了同学的两件物品，比自己发群效率高，核验问题机制挺好，建议再加个图片模糊处理功能。', @ua, 1, 20, 2, 4, 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (207, 11, '谁丢了一串带挂件的钥匙？已交失物中心', '正门捡到的钥匙串，蓝色小熊挂件，已交三号楼一层服务台，失主直接去报特征领取即可。', @ub, 0, 0, 0, 0, 0, 0, NULL),
  (208, 12, '今天水房的充电宝是谁掉的', '粉色充电宝，先放这儿问问，没人要我就交失物中心了。', @uc, 2, 9, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 10 HOUR)),
  (209, 13, '校园卡补办流程请教', '卡丢了想问下各位：是先挂失还是先去一卡通中心？需要带学生证吗？', 1, 1, 15, 2, 2, 0, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (210, 11, '丢钥匙的同学看过来（三号楼片区）', '刚捡到一串钥匙，红色挂绳，挂着一枚小铃铛，已发布招领信息，特征对的来认领。', @ua, 1, 3, 1, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (211, 12, '图书馆占座现象吐槽（顺便提醒物品安全）', '占座书包上挂耳机盒结果人跑了，这算失物还是被遗弃？开个玩笑，但大家离开座位一定随身带贵重物品。', @uc, 1, 9, 0, 1, 0, 0, NOW()),
  (212, 13, '捡到耳机盒上没标签怎么找失主？', '耳机是白色的左耳一只，盒里没有贴纸标签，这种情况平台认领核验问题该问什么才不泄露隐私？', 1, 1, 25, 2, 3, 0, 0, DATE_SUB(NOW(), INTERVAL 30 HOUR));

-- ---------- 5) 两级回复 19 条（含 1 条待审演示） ----------
INSERT INTO forum_reply (id, post_id, parent_id, user_id, reply_to_uid, content, status, created_at) VALUES
  (201, 201, NULL, @ua, NULL, '核验问题设计得好，我之前在群里问"你的卡号多少"，人家直接报，等于没核验。', 1, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (202, 201, NULL, @uc, NULL, '同点赞，上周的伞也是在这找回去的。', 1, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (203, 201, 201,  @ub, @ua, '对，答不上来的一律拒，省得扯皮。', 1, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (204, 201, 202,  1,   @uc, '伞柄胶带颜色都对上了才算归还，流程很严谨。', 1, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (205, 202, NULL, @uc, NULL, '直接交失物中心最稳，失主报特征领取，免得被冒领。', 1, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (206, 202, 205,  @ua, @uc, '怕失物中心不收饭卡咋办？', 1, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (207, 202, NULL, 1,   NULL, '收的，服务台有登记本，写个备注就行。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (208, 203, NULL, @uc, NULL, '它每天下午三点准时出现在三楼，像来上晚自习的。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (209, 204, NULL, 1,   NULL, '帮忙顶，专业拍不便宜。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (210, 204, 209,  @ub, 1,   '已转发到羽毛球群。', 1, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (211, 206, NULL, @ub, NULL, '图片模糊这个建议不错，有些照片拍到旁人。', 1, DATE_SUB(NOW(), INTERVAL 12 HOUR)),
  (212, 206, NULL, @uc, NULL, '请问失物中心代管是怎么登记的？想去实习看看。', 1, DATE_SUB(NOW(), INTERVAL 8 HOUR)),
  (213, 209, NULL, @ua, NULL, '先在一卡通 App 挂失，再去中心补办，带学生证就行。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (214, 209, 213,  1,   @ua, '补充：挂失后旧卡里的余额可以转新卡，别担心。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (215, 210, NULL, @ub, NULL, '红色挂绳+小铃铛，是我的！马上提交认领申请。', 1, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (216, 212, NULL, @uc, NULL, '核验问题可以问"耳机的配对名称"，只有机主知道。', 1, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (217, 212, 216,  1,   @uc, '这个思路好，配对名称既不泄露隐私又是铁证。', 1, DATE_SUB(NOW(), INTERVAL 18 HOUR)),
  (218, 205, NULL, 2,   NULL, '待审核回复演示：弄丢馆藏书籍按规定照价赔偿并销账，不进诚信档案，别慌。', 0, NOW());

-- ---------- 6) 点赞 21 ----------
INSERT INTO forum_like (id, post_id, user_id) VALUES
  (201, 201, @ua), (202, 201, @uc), (203, 201, 4), (204, 201, 1), (205, 201, 2),
  (206, 202, 1),   (207, 202, @ub),
  (208, 203, 1),
  (209, 204, @ua), (210, 204, @ub), (211, 204, 2),
  (212, 206, 1),   (213, 206, @ub), (214, 206, @uc), (215, 206, 4),
  (216, 209, @ua), (217, 209, @ub),
  (218, 211, @ua),
  (219, 212, 2),   (220, 212, @ua), (221, 212, @uc);

-- ---------- 7) 收藏 9 ----------
INSERT INTO user_favorite (id, user_id, target_type, target_id) VALUES
  (201, @ua, 'ITEM', 201), (202, @ua, 'ITEM', 214),
  (203, @ub, 'ITEM', 203),
  (204, @uc, 'ITEM', 209), (205, @uc, 'ITEM', 211),
  (206, @ua, 'POST', 201), (207, @ua, 'POST', 204),
  (208, @ub, 'POST', 206), (209, @uc, 'POST', 212);

-- ---------- 8) 站内消息 12 ----------
INSERT INTO notification (id, user_id, biz_type, biz_id, title, content, read_flag, created_at) VALUES
  (201, 1,   'CLAIM_NEW', 201, '有新的认领申请', '王小明 对你发布的《一卡通一张（卡面姓王）》提交了认领申请。', 0, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (202, 1,   'CLAIM_NEW', 201, '有新的认领申请', '赵小雨 也提交了认领申请（第二张单，择优核验）。', 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
  (203, @ua, 'CLAIM_RESULT', 203, '认领申请已通过', '你对《学生证（姓名栏：王小明）》的认领申请已通过核验，请与发布者约定交接。', 0, DATE_SUB(NOW(), INTERVAL 9 HOUR)),
  (204, @uc, 'CLAIM_RESULT', 204, '认领申请被拒绝', '你对《耳机一只》提交的认领被拒绝：贴纸图案答不出。可在"我的认领"申请仲裁。', 0, DATE_SUB(NOW(), INTERVAL 28 HOUR)),
  (205, @ub, 'CLAIM_NEW', 207, '有新的认领申请', '王小明 对你发布的《黑色羽绒服（自习室椅背）》提交了认领申请。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (206, 2,   'RETURN_CONFIRM', 215, '物品已归还', '《钥匙一串（蓝色小熊挂件）》已确认归还给你，感谢使用失物招领平台。', 0, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (207, @ua, 'MATCH', 203, '可能匹配的招领（匹配度 66）', '你发布的《蓝牙耳机（左耳单只！）》与招领《耳机一只》特征相近，去看看是不是你的东西。', 0, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
  (208, @uc, 'MATCH', 205, '可能匹配的招领（匹配度 35）', '你发布的《《线性代数》教材》与招领《高等数学课本》同在图书馆丢失，特征相近。', 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (209, @ub, 'REPLY', 201, '有人回复了你', '孙浩然 赞了你的帖子、同学在你的经验帖下讨论热烈。', 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (210, @ua, 'REPLY', 209, '有人回复了你', '张同学 补充回复了你：「挂失后旧卡里的余额可以转新卡。」', 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (211, @ub, 'LIKE', 201, '有人赞了你的帖子', '多位同学赞了《给失物中心点赞：丢了三天的一卡通找回来了》。', 1, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (212, @ub, 'POST_AUDIT', 207, '帖子已提交等待审核', '《谁丢了一串带挂件的钥匙？已交失物中心》正在审核队列排队，通过后即可展示。', 0, DATE_SUB(NOW(), INTERVAL 3 HOUR));

-- ---------- 9) 计数对账（以事实行为准，谁改了数据都不会失配） ----------
UPDATE forum_post p SET
  p.reply_count = (SELECT COUNT(*) FROM forum_reply r WHERE r.post_id = p.id AND r.deleted = 0),
  p.like_count  = (SELECT COUNT(*) FROM forum_like l  WHERE l.post_id = p.id)
WHERE p.id BETWEEN 201 AND 212;
UPDATE lost_item i SET
  i.claim_count = (SELECT COUNT(*) FROM item_claim c WHERE c.item_id = i.id AND c.deleted = 0),
  i.like_count  = (SELECT COUNT(*) FROM user_favorite f WHERE f.target_id = i.id AND f.target_type = 'ITEM')
WHERE i.id BETWEEN 201 AND 222;
