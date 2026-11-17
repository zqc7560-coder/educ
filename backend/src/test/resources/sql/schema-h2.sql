-- =====================================================================
-- H2（MODE=MySQL）测试建表脚本 —— 与生产 schema（db/mysql/schema.sql）
-- 逐表同步维护（本文件由机械转换生成，人工改表两侧同改；差异口径：
-- 无 UNSIGNED/FULLTEXT ngram/ON UPDATE CURRENT_TIMESTAMP；普通索引改独立
-- CREATE INDEX；UNIQUE KEY 转表外 CONSTRAINT 且名字全局唯一）。
-- =====================================================================

-- ---------- sys_user ----------
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  student_no    VARCHAR(20)  NOT NULL,
  password      VARCHAR(100) NOT NULL,
  nickname      VARCHAR(30)  NOT NULL,
  email         VARCHAR(100) NOT NULL,
  phone         VARCHAR(20)  NULL,
  avatar        VARCHAR(255) NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  role_id       BIGINT       NULL,
  last_login_at DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_student_no UNIQUE (student_no),
  CONSTRAINT uk_email UNIQUE (email)
);

-- ---------- sys_role ----------
CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  role_code   VARCHAR(30) NOT NULL,
  role_name   VARCHAR(50) NOT NULL,
  description VARCHAR(200) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_code UNIQUE (role_code)
);

-- ---------- sys_user_role ----------
CREATE TABLE IF NOT EXISTS sys_user_role (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  role_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

-- ---------- sys_permission ----------
CREATE TABLE IF NOT EXISTS sys_permission (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  perm_code   VARCHAR(50) NOT NULL,
  perm_name   VARCHAR(50) NOT NULL,
  module      VARCHAR(30) NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_perm_code UNIQUE (perm_code)
);

-- ---------- sys_role_permission ----------
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  role_id    BIGINT   NOT NULL,
  perm_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_perm UNIQUE (role_id, perm_id)
);

-- ---------- category ----------
CREATE TABLE IF NOT EXISTS category (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  name       VARCHAR(30) NOT NULL,
  kind       VARCHAR(10) NOT NULL DEFAULT 'ITEM',
  icon       VARCHAR(255) NULL,
  sort_order INT         NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_category_name_kind UNIQUE (name, kind)
);

-- ---------- campus_area ----------
CREATE TABLE IF NOT EXISTS campus_area (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  name       VARCHAR(50) NOT NULL,
  level      TINYINT     NOT NULL DEFAULT 1,
  parent_id  BIGINT      NULL,
  sort_order INT         NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_area_name_parent UNIQUE (name, parent_id)
);

-- ---------- lost_item ----------
CREATE TABLE IF NOT EXISTS lost_item (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  type             VARCHAR(10)  NOT NULL,
  user_id          BIGINT       NOT NULL,
  title            VARCHAR(100) NOT NULL,
  description      TEXT         NOT NULL,
  category_id      BIGINT       NOT NULL,
  campus_id        BIGINT       NULL,
  building_id      BIGINT       NULL,
  location_text    VARCHAR(200) NULL,
  happened_at      DATETIME     NOT NULL,
  contact          VARCHAR(100) NULL,
  verify_question  VARCHAR(200) NULL,
  status           TINYINT      NOT NULL DEFAULT 0,
  offline_reason   VARCHAR(200) NULL,
  view_count       INT          NOT NULL DEFAULT 0,
  claim_count      INT          NOT NULL DEFAULT 0,
  like_count       INT          NOT NULL DEFAULT 0,
  top_flag         TINYINT      NOT NULL DEFAULT 0,
  published_at     DATETIME     NULL,
  version          INT          NOT NULL DEFAULT 0,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- item_image ----------
CREATE TABLE IF NOT EXISTS item_image (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  item_id    BIGINT       NOT NULL,
  url        VARCHAR(255) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- item_claim ----------
CREATE TABLE IF NOT EXISTS item_claim (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  item_id         BIGINT       NOT NULL,
  claimant_id     BIGINT       NOT NULL,
  answer          VARCHAR(500) NOT NULL,
  evidence_note   VARCHAR(500) NULL,
  status          TINYINT      NOT NULL DEFAULT 0,
  reject_reason   VARCHAR(200) NULL,
  arbitrated_by   BIGINT       NULL,
  handled_at      DATETIME     NULL,
  version         INT          NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_claim_item_user UNIQUE (item_id, claimant_id)
);

-- ---------- claim_log ----------
CREATE TABLE IF NOT EXISTS claim_log (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  claim_id   BIGINT      NOT NULL,
  actor_id   BIGINT      NULL,
  action     VARCHAR(30) NOT NULL,
  detail     VARCHAR(300) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- ---------- forum_post ----------
CREATE TABLE IF NOT EXISTS forum_post (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  board_id     BIGINT       NOT NULL,
  user_id      BIGINT       NOT NULL,
  title        VARCHAR(100) NOT NULL,
  content      TEXT         NOT NULL,
  status       TINYINT      NOT NULL DEFAULT 0,
  offline_reason VARCHAR(200) NULL,
  view_count   INT          NOT NULL DEFAULT 0,
  reply_count  INT          NOT NULL DEFAULT 0,
  like_count   INT          NOT NULL DEFAULT 0,
  top_flag     TINYINT      NOT NULL DEFAULT 0,
  published_at DATETIME     NULL,
  version      INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- post_image ----------
CREATE TABLE IF NOT EXISTS post_image (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  post_id    BIGINT       NOT NULL,
  url        VARCHAR(255) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- forum_reply ----------
CREATE TABLE IF NOT EXISTS forum_reply (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  post_id       BIGINT       NOT NULL,
  parent_id     BIGINT       NULL,
  user_id       BIGINT       NOT NULL,
  reply_to_uid  BIGINT       NULL,
  content       VARCHAR(1000) NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 0,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- forum_like ----------
CREATE TABLE IF NOT EXISTS forum_like (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  post_id    BIGINT   NOT NULL,
  user_id    BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_like_post_user UNIQUE (post_id, user_id)
);

-- ---------- post_report（治理工单：举报不即时下架，管理员处置时 resolve） ----------
CREATE TABLE IF NOT EXISTS post_report (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  post_id    BIGINT       NOT NULL,
  user_id    BIGINT       NOT NULL,
  reason     VARCHAR(200) NULL,
  status     TINYINT      NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  handled_at DATETIME     NULL,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_report_post_user UNIQUE (post_id, user_id)
);

-- ---------- announcement ----------
CREATE TABLE IF NOT EXISTS announcement (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  title       VARCHAR(120) NOT NULL,
  content     TEXT         NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0,
  top_flag    TINYINT      NOT NULL DEFAULT 0,
  effect_at   DATETIME     NULL,
  expire_at   DATETIME     NULL,
  view_count  INT          NOT NULL DEFAULT 0,
  author_id   BIGINT       NOT NULL,
  version     INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- announcement_image ----------
CREATE TABLE IF NOT EXISTS announcement_image (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  announcement_id BIGINT       NOT NULL,
  url             VARCHAR(255) NOT NULL,
  sort_order      INT          NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- notification ----------
CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  biz_type   VARCHAR(20) NOT NULL,
  biz_id     BIGINT      NULL,
  title      VARCHAR(120) NOT NULL,
  content    VARCHAR(500) NOT NULL,
  read_flag  TINYINT     NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ---------- sensitive_word ----------
CREATE TABLE IF NOT EXISTS sensitive_word (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  word       VARCHAR(50) NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_word UNIQUE (word)
);

-- ---------- sys_config ----------
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(50)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  config_type  VARCHAR(10)  NOT NULL DEFAULT 'STRING',
  description  VARCHAR(200) NULL,
  editable     TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_config_key UNIQUE (config_key)
);

-- ---------- operation_log ----------
CREATE TABLE IF NOT EXISTS operation_log (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  action     VARCHAR(50) NOT NULL,
  target     VARCHAR(100) NULL,
  detail     VARCHAR(500) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- ---------- user_favorite ----------
CREATE TABLE IF NOT EXISTS user_favorite (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  target_type VARCHAR(10) NOT NULL,
  target_id   BIGINT      NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_fav_user_target UNIQUE (user_id, target_type, target_id)
);

CREATE INDEX IF NOT EXISTS idx_user_status_deleted ON sys_user (status, deleted);
CREATE INDEX IF NOT EXISTS idx_category_kind ON category (kind, deleted);
CREATE INDEX IF NOT EXISTS idx_area_parent ON campus_area (parent_id, deleted);
CREATE INDEX IF NOT EXISTS idx_item_status_publish ON lost_item (status, published_at);
CREATE INDEX IF NOT EXISTS idx_item_type_category ON lost_item (type, category_id, status);
CREATE INDEX IF NOT EXISTS idx_item_user ON lost_item (user_id, status);
CREATE INDEX IF NOT EXISTS idx_item_time_location ON lost_item (happened_at, building_id);
CREATE INDEX IF NOT EXISTS idx_image_item ON item_image (item_id, deleted);
CREATE INDEX IF NOT EXISTS idx_claim_status ON item_claim (status, created_at);
CREATE INDEX IF NOT EXISTS idx_claim_claimant ON item_claim (claimant_id, status);
CREATE INDEX IF NOT EXISTS idx_clog_claim ON claim_log (claim_id, created_at);
CREATE INDEX IF NOT EXISTS idx_post_board_status ON forum_post (board_id, status, published_at);
CREATE INDEX IF NOT EXISTS idx_post_user ON forum_post (user_id, status);
CREATE INDEX IF NOT EXISTS idx_pimg_post ON post_image (post_id, deleted);
CREATE INDEX IF NOT EXISTS idx_reply_post_status ON forum_reply (post_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_reply_parent ON forum_reply (parent_id);
CREATE INDEX IF NOT EXISTS idx_like_user ON forum_like (user_id);
CREATE INDEX IF NOT EXISTS idx_ann_status ON announcement (status, top_flag, effect_at);
CREATE INDEX IF NOT EXISTS idx_aimg_ann ON announcement_image (announcement_id, deleted);
CREATE INDEX IF NOT EXISTS idx_notify_user_read ON notification (user_id, read_flag, created_at);
CREATE INDEX IF NOT EXISTS idx_oplog_time ON operation_log (created_at);
CREATE INDEX IF NOT EXISTS idx_oplog_user ON operation_log (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_fav_target ON user_favorite (target_type, target_id);
