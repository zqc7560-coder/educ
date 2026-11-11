-- =====================================================================
-- 校园失物招领系统 · 生产 DDL（MySQL 8.0 / utf8mb4）
-- 设计依据：《开发方案.md》§5 表概要 + §3 核心设计（认领状态机/两级封顶/点赞幂等）。
-- 维护纪律：
--   1) 本文件与 src/test/resources/sql/schema-h2.sql 逐表同步（H2 剥离清单见其头部注释）；
--   2) 新增/改列必须两侧同改，由 DatabaseInitM1Test 的实体↔表列机械比对兜底；
--   3) 表顺序=依赖顺序（被引用者先建），全部 IF NOT EXISTS + 种子 INSERT IGNORE 幂等；
--   4) ngram_token_size 是建库后不可改项（MySQL 全局参数），compose 启动参数已给死=2。
-- 22 表分域：RBAC(5) sys_user/sys_role/sys_user_role/sys_permission/sys_role_permission
--   物品(3) lost_item/item_image/category（分类+论坛板块合一字典，kind 区分）
--   地点(1) campus_area（校区-楼栋两级自连接，第三级为自由文本 location_text）
--   认领(2) item_claim/claim_log
--   论坛(4) forum_post/post_image/forum_reply/forum_like
--   公告(2) announcement/announcement_image
--   消息治理(5) notification/sensitive_word/sys_config/operation_log/user_favorite
-- =====================================================================

CREATE DATABASE IF NOT EXISTS lost_found_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE lost_found_db;

-- ---------- 域一：RBAC 五件套 ----------

-- 1. 用户（学号即登录凭据；status 承载封禁/禁言位，禁言只拦写口不拦读，博客 M5 同款语义）
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  student_no    VARCHAR(20)  NOT NULL COMMENT '学号（登录名，全校唯一）',
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt散列',
  nickname      VARCHAR(30)  NOT NULL COMMENT '昵称',
  email         VARCHAR(100) NOT NULL COMMENT '邮箱',
  phone         VARCHAR(20)  NULL COMMENT '联系电话（个人资料，认领联系优先展示）',
  avatar        VARCHAR(255) NULL COMMENT '头像URL',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0封禁 2禁言(仅禁写)',
  role_id       BIGINT       NULL COMMENT '主角色ID（RBAC简化：一用户一主角色，授权矩阵见sys_user_role）',
  last_login_at DATETIME     NULL COMMENT '最后登录时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_student_no (student_no),
  UNIQUE KEY uk_email (email),
  KEY idx_user_status_deleted (status, deleted)
) ENGINE=InnoDB COMMENT='用户表';

-- 2. 角色（STUDENT/MANAGER失物中心/ADMIN系统管理员）
CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  role_code   VARCHAR(30) NOT NULL COMMENT '如ROLE_ADMIN',
  role_name   VARCHAR(50) NOT NULL,
  description VARCHAR(200) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB COMMENT='角色表';

-- 3. 用户-角色（多对多：一个人可同是学生+失物中心干事）
CREATE TABLE IF NOT EXISTS sys_user_role (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  role_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB COMMENT='用户角色关联';

-- 4. 权限点（接口 @PreAuthorize 与前端菜单渲染的单一事实源）
CREATE TABLE IF NOT EXISTS sys_permission (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  perm_code   VARCHAR(50) NOT NULL COMMENT '如item:audit',
  perm_name   VARCHAR(50) NOT NULL,
  module      VARCHAR(30) NOT NULL COMMENT '所属模块（item/claim/forum/announce/user/system/stats）',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code (perm_code)
) ENGINE=InnoDB COMMENT='权限点表';

-- 5. 角色-权限
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  role_id    BIGINT   NOT NULL,
  perm_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm (role_id, perm_id)
) ENGINE=InnoDB COMMENT='角色权限关联';

-- ---------- 域二：字典（分类+论坛板块合一；校区楼栋两级自连接） ----------

-- 6. 分类/板块字典（kind 区分两副面孔：ITEM=物品分类，BOARD=论坛板块——
--    结构同构的纯字典不再拆两张表，实体语义靠 kind 隔离，互不串查询）
CREATE TABLE IF NOT EXISTS category (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  name       VARCHAR(30) NOT NULL,
  kind       VARCHAR(10) NOT NULL DEFAULT 'ITEM' COMMENT 'ITEM=物品分类 BOARD=论坛板块',
  icon       VARCHAR(255) NULL COMMENT '图标URL',
  sort_order INT         NOT NULL DEFAULT 0 COMMENT '列表排序',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_category_name_kind (name, kind),
  KEY idx_category_kind (kind, deleted)
) ENGINE=InnoDB COMMENT='分类与板块字典';

-- 7. 校区/楼栋（两级自连接：parent_id NULL=校区，非空=楼栋；三级地点=校区-楼栋-location_text 自由文本）
CREATE TABLE IF NOT EXISTS campus_area (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  name       VARCHAR(50) NOT NULL,
  level      TINYINT     NOT NULL DEFAULT 1 COMMENT '1=校区 2=楼栋',
  parent_id  BIGINT      NULL COMMENT '上级（楼栋→校区）',
  sort_order INT         NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_area_name_parent (name, parent_id),
  KEY idx_area_parent (parent_id, deleted)
) ENGINE=InnoDB COMMENT='校区楼栋字典';

-- ---------- 域三：物品 ----------

-- 8. 物品主表（LOST/FOUND 统一；状态机 0待审核→1已发布⇄2已下架；
--    FOUND 件 verify_question 必填=认领核验的地基约束，Service 层 2003 执法）
CREATE TABLE IF NOT EXISTS lost_item (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  type             VARCHAR(10)  NOT NULL COMMENT 'LOST=寻物 FOUND=招领',
  user_id          BIGINT       NOT NULL COMMENT '发布者',
  title            VARCHAR(100) NOT NULL,
  description      TEXT         NOT NULL COMMENT '纯文本（jsoup清洗后入库）',
  category_id      BIGINT       NOT NULL COMMENT '分类（category kind=ITEM）',
  campus_id        BIGINT       NULL COMMENT '校区（campus_area level=1）',
  building_id      BIGINT       NULL COMMENT '楼栋（campus_area level=2）',
  location_text    VARCHAR(200) NULL COMMENT '具体位置自由文本',
  happened_at      DATETIME     NOT NULL COMMENT '丢失/拾获时间（相似匹配时间窗要素）',
  contact          VARCHAR(100) NULL COMMENT '联系方式（仅登录可见，可见性矩阵）',
  verify_question  VARCHAR(200) NULL COMMENT '物主核验问题（FOUND 必填）',
  status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核 1已发布 2已下架',
  offline_reason   VARCHAR(200) NULL COMMENT '下架理由（物归原主/违规/过期，M4 归还自动置2时写"已归还"）',
  view_count       INT          NOT NULL DEFAULT 0 COMMENT '浏览量冗余（Redis计数定时回写）',
  claim_count      INT          NOT NULL DEFAULT 0 COMMENT '认领申请数冗余',
  like_count       INT          NOT NULL DEFAULT 0 COMMENT '收藏数冗余（user_favorite target=ITEM）',
  top_flag         TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶（管理员加急/置顶治理）',
  published_at     DATETIME     NULL COMMENT '首次发布时间（排序基准，此后不变）',
  version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_item_status_publish (status, published_at),
  KEY idx_item_type_category (type, category_id, status),
  KEY idx_item_user (user_id, status),
  KEY idx_item_time_location (happened_at, building_id),
  FULLTEXT KEY ft_item (title, description) /*!50700 WITH PARSER ngram */
) ENGINE=InnoDB COMMENT='失物/拾物信息主表';

-- 9. 物品图片（1–6 张；上传即落表，未发布可空挂——发布提交时关联校验）
CREATE TABLE IF NOT EXISTS item_image (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  item_id    BIGINT       NOT NULL,
  url        VARCHAR(255) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_image_item (item_id, deleted)
) ENGINE=InnoDB COMMENT='物品图片';

-- ---------- 域四：认领流程（状态机） ----------

-- 10. 认领单（同一物品多申请并存允许；uk(item_id,user_id) 只拦"同人重复申请"=3003）
--     状态机：0待核验 →(通过)1约定交接 →(确认)2已完成；0 →(拒绝)3已拒绝；
--             0/1 →(一方申请)4仲裁中 →(裁决)2或3；物品先被他人归还 →(批量)5已关闭
CREATE TABLE IF NOT EXISTS item_claim (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  item_id         BIGINT       NOT NULL,
  claimant_id     BIGINT       NOT NULL COMMENT '认领申请人',
  answer          VARCHAR(500) NOT NULL COMMENT '核验答案',
  evidence_note   VARCHAR(500) NULL COMMENT '补充自述凭证',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '见头部状态机注释',
  reject_reason   VARCHAR(200) NULL COMMENT '拒绝/仲裁理由',
  arbitrated_by   BIGINT       NULL COMMENT '仲裁管理员',
  handled_at      DATETIME     NULL COMMENT '核验/裁决落定时间',
  version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁：确认归还先到先得防重复(3004)',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_item_user (item_id, claimant_id),
  KEY idx_claim_status (status, created_at),
  KEY idx_claim_claimant (claimant_id, status)
) ENGINE=InnoDB COMMENT='认领申请单';

-- 11. 认领流转留痕（append-only：每步操作人/动作/理由，仲裁裁决与答辩演示的事实依据）
CREATE TABLE IF NOT EXISTS claim_log (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  claim_id   BIGINT      NOT NULL,
  actor_id   BIGINT      NULL COMMENT '操作人（系统自动关闭为 NULL）',
  action     VARCHAR(30) NOT NULL COMMENT 'APPLY/VERIFY_PASS/VERIFY_REJECT/CONFIRM_RETURN/ARBITRATE_APPLY/ARBITRATE_JUDGE/CLOSED_BY_RETURN/TIMEOUT_REMIND',
  detail     VARCHAR(300) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_clog_claim (claim_id, created_at)
) ENGINE=InnoDB COMMENT='认领流转日志';

-- ---------- 域五：论坛（两级封顶 + 点赞幂等） ----------

-- 12. 帖子（状态机同物品 0/1/2；回复数/点赞数/浏览数冗余结算）
CREATE TABLE IF NOT EXISTS forum_post (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  board_id     BIGINT       NOT NULL COMMENT '板块（category kind=BOARD）',
  user_id      BIGINT       NOT NULL,
  title        VARCHAR(100) NOT NULL,
  content      TEXT         NOT NULL COMMENT '纯文本（jsoup清洗）',
  status       TINYINT      NOT NULL DEFAULT 0,
  offline_reason VARCHAR(200) NULL,
  view_count   INT          NOT NULL DEFAULT 0,
  reply_count  INT          NOT NULL DEFAULT 0,
  like_count   INT          NOT NULL DEFAULT 0,
  top_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '精华/置顶（管理员治理）',
  published_at DATETIME     NULL,
  version      INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_post_board_status (board_id, status, published_at),
  KEY idx_post_user (user_id, status),
  FULLTEXT KEY ft_post (title, content) /*!50700 WITH PARSER ngram */
) ENGINE=InnoDB COMMENT='论坛帖子';

-- 13. 帖子图片（0–9 张，复用 item_image 结构语义）
CREATE TABLE IF NOT EXISTS post_image (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  post_id    BIGINT       NOT NULL,
  url        VARCHAR(255) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_pimg_post (post_id, deleted)
) ENGINE=InnoDB COMMENT='帖子图片';

-- 14. 帖子回复（两级封顶：parent_id 只允许指向顶级回复；reply_to_uid 记录被回复人实现自动@，
--     与博客评论表同款执法口径——越级/跨帖/幽灵父三查合一 5002）
CREATE TABLE IF NOT EXISTS forum_reply (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  post_id       BIGINT       NOT NULL,
  parent_id     BIGINT       NULL COMMENT '父回复（必须为顶级回复且同帖）',
  user_id       BIGINT       NOT NULL,
  reply_to_uid  BIGINT       NULL COMMENT '被@用户（回复回复时=父回复作者，可覆盖）',
  content       VARCHAR(1000) NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核 1已发布 2已屏蔽',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_reply_post_status (post_id, status, created_at),
  KEY idx_reply_parent (parent_id)
) ENGINE=InnoDB COMMENT='论坛回复';

-- 15. 点赞（联合主键=幂等的事实源：再点=DELETE，计数随插入/删除同事务结算）
CREATE TABLE IF NOT EXISTS forum_like (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  post_id    BIGINT   NOT NULL,
  user_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_like_post_user (post_id, user_id),
  KEY idx_like_user (user_id)
) ENGINE=InnoDB COMMENT='帖子点赞（无逻辑删除位：取消即物理删，幂等事实源=uk）';

-- ---------- 域六：公告 ----------

-- 16. 公告（状态机 0草稿→1已发布⇄2已下架；expire_at 到点定时任务自动下架）
CREATE TABLE IF NOT EXISTS announcement (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  title       VARCHAR(120) NOT NULL,
  content     TEXT         NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0,
  top_flag    TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶排前',
  effect_at   DATETIME     NULL COMMENT '生效时间（NULL=发布即生效）',
  expire_at   DATETIME     NULL COMMENT '过期时间（NULL=永久；定时任务扫此列自动下架）',
  view_count  INT          NOT NULL DEFAULT 0,
  author_id   BIGINT       NOT NULL COMMENT '发布管理员',
  version     INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ann_status (status, top_flag, effect_at)
) ENGINE=InnoDB COMMENT='管理员公告';

-- 17. 公告配图
CREATE TABLE IF NOT EXISTS announcement_image (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  announcement_id BIGINT       NOT NULL,
  url             VARCHAR(255) NOT NULL,
  sort_order      INT          NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_aimg_ann (announcement_id, deleted)
) ENGINE=InnoDB COMMENT='公告图片';

-- ---------- 域七：消息与治理 ----------

-- 18. 站内消息（target 收敛为 user_id 单人收件；biz_type+biz_id 回链业务对象）
CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL COMMENT '收件人',
  biz_type   VARCHAR(20) NOT NULL COMMENT 'ITEM_AUDIT/CLAIM_NEW/CLAIM_RESULT/RETURN_CONFIRM/POST_AUDIT/POST_INTERACT/MATCH_RECOMMEND/ANNOUNCEMENT/SYSTEM',
  biz_id     BIGINT      NULL COMMENT '关联业务对象 id',
  title      VARCHAR(120) NOT NULL,
  content    VARCHAR(500) NOT NULL,
  read_flag  TINYINT     NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_notify_user_read (user_id, read_flag, created_at)
) ENGINE=InnoDB COMMENT='站内消息';

-- 19. 敏感词（提交时全词匹配拦截；库小全量驻内存，写后失效缓存）
CREATE TABLE IF NOT EXISTS sensitive_word (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  word       VARCHAR(50) NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word)
) ENGINE=InnoDB COMMENT='敏感词';

-- 20. 系统参数（审核开关/每日发布上限等治理旋钮，key 白名单见 sys_config 键设计文档）
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(50)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  config_type  VARCHAR(10)  NOT NULL DEFAULT 'STRING' COMMENT 'STRING/NUMBER/BOOL',
  description  VARCHAR(200) NULL,
  editable     TINYINT      NOT NULL DEFAULT 1 COMMENT '0=系统键不可经接口修改',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB COMMENT='系统参数';

-- 21. 操作日志（管理端写操作审计：who/what/detail，检索按时间倒序）
CREATE TABLE IF NOT EXISTS operation_log (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL COMMENT '操作人',
  action     VARCHAR(50) NOT NULL COMMENT '如 ITEM_OFFLINE/ANN_PUBLISH/USER_BAN',
  target     VARCHAR(100) NULL COMMENT '目标对象标识（item:123）',
  detail     VARCHAR(500) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_oplog_time (created_at),
  KEY idx_oplog_user (user_id, created_at)
) ENGINE=InnoDB COMMENT='管理操作审计日志';

-- 22. 收藏（统一 target_type 支持物品/帖子收藏，P1 功能，表先行免迁移）
CREATE TABLE IF NOT EXISTS user_favorite (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  target_type VARCHAR(10) NOT NULL COMMENT 'ITEM/POST',
  target_id   BIGINT      NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fav_user_target (user_id, target_type, target_id),
  KEY idx_fav_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='收藏（取消即物理删，与点赞同理；幂等事实源=uk）';
