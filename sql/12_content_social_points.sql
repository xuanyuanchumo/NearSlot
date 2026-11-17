-- FR-7 内容社区 + FR-8 社交 + FR-9 签到积分（ADR-026/027/028/029）
--
-- 本项目化适配要点（与两个参考项目的差异）：
-- 1. blog 关联 order_id：履约驱动，只有 DONE 订单可发（ADR-026）；
-- 2. blog.liked / follow 计数用冗余列，禁止 COUNT（ADR-028）；
-- 3. feed 只用"小 V 收件箱"，大 V 读取时合并（ADR-027），故 feed 结构复用 ZSet；
-- 4. 签到用 BitMap（复用 PickNear 已验证实现），积分落表可审计（ADR-029）。
USE nearslot;

-- 内容社区
CREATE TABLE IF NOT EXISTS blog (
  id BIGINT PRIMARY KEY COMMENT '雪花ID',
  user_id BIGINT NOT NULL COMMENT '作者',
  shop_id BIGINT NULL COMMENT '关联店铺（可空）',
  order_id BIGINT NULL COMMENT '关联履约订单（ADR-026：履约驱动才有值）',
  title VARCHAR(128) NOT NULL DEFAULT '',
  content TEXT NOT NULL,
  images VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '逗号分隔图片URL',
  liked INT NOT NULL DEFAULT 0 COMMENT '点赞数（冗余列，禁 COUNT）',
  comments INT NOT NULL DEFAULT 0 COMMENT '评论数（冗余列）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1已发布 2隐藏',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_blog_user (user_id),
  KEY idx_blog_shop (shop_id),
  KEY idx_blog_liked (liked),
  UNIQUE KEY uk_blog_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店笔记（履约驱动）';

-- 评论
CREATE TABLE IF NOT EXISTS blog_comment (
  id BIGINT PRIMARY KEY,
  blog_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  parent_id BIGINT NULL COMMENT '回复的父评论ID（楼中楼）',
  content VARCHAR(1024) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 2删除',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_comment_blog (blog_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记评论';

-- 社交关系（ADR-028：关系表 + Redis 双向缓存 + 冗余计数）
CREATE TABLE IF NOT EXISTS user_follow (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '关注者',
  follow_user_id BIGINT NOT NULL COMMENT '被关注者',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_follow (user_id, follow_user_id),
  KEY idx_follow_target (follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系';

-- 用户社交统计（冗余计数，避免 COUNT）
CREATE TABLE IF NOT EXISTS user_social_stat (
  user_id BIGINT PRIMARY KEY,
  following_count INT NOT NULL DEFAULT 0 COMMENT '关注数',
  follower_count INT NOT NULL DEFAULT 0 COMMENT '粉丝数',
  blog_count INT NOT NULL DEFAULT 0 COMMENT '笔记数',
  liked_received INT NOT NULL DEFAULT 0 COMMENT '获赞数',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户社交统计';

-- 积分账户（ADR-029）
CREATE TABLE IF NOT EXISTS user_points (
  user_id BIGINT PRIMARY KEY,
  balance INT NOT NULL DEFAULT 0 COMMENT '可用积分',
  total_earned INT NOT NULL DEFAULT 0 COMMENT '累计获得',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分账户';

-- 积分流水（可审计，禁止只改余额不记流水）
CREATE TABLE IF NOT EXISTS points_log (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  change_amount INT NOT NULL COMMENT '正=获得 负=消耗',
  balance_after INT NOT NULL,
  source VARCHAR(32) NOT NULL COMMENT 'SIGN/ORDER/ADMIN/REFUND',
  biz_id BIGINT NULL COMMENT '业务ID（签到日期戳/订单号）',
  remark VARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_points_user (user_id, created_at),
  UNIQUE KEY uk_points_once (user_id, source, biz_id) COMMENT '同一业务只发一次（幂等）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分流水';

-- 店铺分类（补齐参考项目的 shop_type，此前 NearSlot 只有 type_id 字段无管理）
CREATE TABLE IF NOT EXISTS shop_type (
  id BIGINT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  icon VARCHAR(255) NOT NULL DEFAULT '',
  sort INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺分类';

SELECT table_name, (SELECT COUNT(*) FROM information_schema.columns c
  WHERE c.table_schema='nearslot' AND c.table_name=t.table_name) AS cols
FROM information_schema.tables t
WHERE t.table_schema='nearslot' AND t.table_name IN
  ('blog','blog_comment','user_follow','user_social_stat','user_points','points_log','shop_type')
ORDER BY table_name;
