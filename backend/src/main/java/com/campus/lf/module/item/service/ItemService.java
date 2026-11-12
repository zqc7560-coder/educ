package com.campus.lf.module.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.admin.SensitiveWordGate;
import com.campus.lf.module.admin.entity.SysConfig;
import com.campus.lf.module.admin.mapper.SysConfigMapper;
import com.campus.lf.module.item.TextCleaner;
import com.campus.lf.module.item.dto.ItemSaveDTO;
import com.campus.lf.module.item.entity.CampusArea;
import com.campus.lf.module.item.entity.Category;
import com.campus.lf.module.item.entity.ItemImage;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.CampusAreaMapper;
import com.campus.lf.module.item.mapper.CategoryMapper;
import com.campus.lf.module.item.mapper.ItemImageMapper;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.vo.ItemVO;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 物品域服务（M3 核心）—— 失物/拾物发布、检索、详情、编辑、状态流转、删除。
 *
 * <p>状态机（lost_item.status）：
 * <pre>
 *   0 待审核 →(审核通过)→ 1 已发布 ⇄(下架/重上架) 2 已下架
 *   直发（跳过审核）仅当 item_audit=0：create 时 status=1 直接落 1
 * </pre>
 * 流转矩阵只允许 1⇄2 与 0→1（审核动作）；下架是终态之一，重上架回 1。
 * 认领域（M4）的"确认归还"会把 status 置 2 并写 offline_reason="已归还"。
 *
 * <p>可见性矩阵集中在 {@link #assertReadable}：非发布态物品只有作者与
 * 治理角色（item:audit/item:offline 任一）可见——对无关登录者同样回 2001，
 * 不暴露"存在但不可见"（防按 id 枚举草稿/被下架帖）。contact 字段级脱敏
 * 在 VO 装配处按登录态判定（列表与详情同口径）。
 *
 * <p>审核开关读 sys_config.item_audit（1=先审后发）：每次发布实时读库，
 * 不做进程镜像——该开关翻转的生效实时性要求高于 QPS 成本（发布频率
 * 受 daily_publish_limit 闸门约束，读一次 config 可忽略）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_OFFLINE = 2;

    /** 合法 type 集合（DTO 正则之外的服务端兜底，防直接喂 Service 的旁路） */
    private static final Set<String> TYPES = Set.of("LOST", "FOUND");

    /** 图片数量上限（《开发方案.md》§3.1：1–6 张） */
    private static final int MAX_IMAGES = 6;

    /** 允许下架方向的管理动作集合 */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            STATUS_PENDING, Set.of(STATUS_PUBLISHED, STATUS_OFFLINE),   // 审核通过 / 审核驳回
            STATUS_PUBLISHED, Set.of(STATUS_OFFLINE),                    // 下架（作者自助或治理）
            STATUS_OFFLINE, Set.of(STATUS_PUBLISHED));                   // 重上架（作者/治理）

    private final LostItemMapper itemMapper;
    private final ItemImageMapper imageMapper;
    private final CategoryMapper categoryMapper;
    private final CampusAreaMapper areaMapper;
    private final SysUserMapper userMapper;
    private final SysConfigMapper configMapper;
    private final SensitiveWordGate wordGate;
    // M7 旁路：匹配推荐+站内消息都是"尽力而为"的增强，push/notify 内部吞异常
    private final com.campus.lf.module.notice.service.MatchService matchService;
    private final com.campus.lf.module.notice.service.NotificationService notificationService;
    // M9 缓存：读侧 Cache-Aside、写侧事务双删；Redis 任何故障都在服务内部
    // try-catch 降级，业务方法签名与正确性口径零变化
    private final ItemCacheService cache;

    // ==================================================================
    // 检索
    // ==================================================================

    /**
     * 分页检索（GET /api/items）。status 永远强制为已发布（游客与列表页同口径，
     * 作者的"我的发布"带状态过滤走 mine 专用查询 pageMine，不混在这一个口里）。
     *
     * <p>关键词本期 LIKE：H2 无 ngram、生产索引已建，LIKE 万级 <500ms 达标即不预付
     * FTS 复杂度（博客 M3 同款决策，压测不达标再切 MATCH AGAINST 一行 SQL）。
     */
    public PageResult<ItemVO> pageItems(String type, Long categoryId, String keyword,
                                        Long campusId, Long buildingId,
                                        LocalDateTime from, LocalDateTime to,
                                        String sort, long pageNum, long pageSize) {
        // M9：列表缓存只罩"公开检索"这一个口（mine/审核台视图带身份维度不缓存）。
        // key=规范化参数 md5；命中直接返回缓存信封；miss 回源后回填并登记键清单。
        String cacheKey = cache.listKey(ItemCacheService.canonical(
                type, categoryId, keyword, campusId, buildingId,
                from == null ? null : from.toString(), to == null ? null : to.toString(),
                sort, pageNum, pageSize));
        ItemCacheService.CachePage hit = cache.getList(cacheKey);
        if (hit != null) {
            return new PageResult<>(hit.total(), hit.pages(), hit.pageNum(), hit.pageSize(), hit.list());
        }
        LambdaQueryWrapper<LostItem> qw = new LambdaQueryWrapper<LostItem>()
                .eq(LostItem::getStatus, STATUS_PUBLISHED);
        if (type != null && !type.isBlank()) {
            qw.eq(LostItem::getType, type.trim().toUpperCase());
        }
        if (categoryId != null) {
            qw.eq(LostItem::getCategoryId, categoryId);
        }
        if (campusId != null) {
            qw.eq(LostItem::getCampusId, campusId);
        }
        if (buildingId != null) {
            qw.eq(LostItem::getBuildingId, buildingId);
        }
        if (from != null) {
            qw.ge(LostItem::getHappenedAt, from);
        }
        if (to != null) {
            qw.le(LostItem::getHappenedAt, to);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(LostItem::getTitle, kw).or().like(LostItem::getDescription, kw));
        }
        // 排序：hot=浏览+认领合成热度（表达式列 Lambda API 不吃，走 last() 原样
        // 追加常量 SQL 零注入面，MySQL/H2 双库同语法）；默认时间倒序；置顶恒先
        if ("hot".equals(sort)) {
            qw.last("ORDER BY top_flag DESC, (view_count + claim_count * 3) DESC, id DESC");
        } else {
            qw.orderByDesc(LostItem::getTopFlag)
              .orderByDesc(LostItem::getPublishedAt)
              .orderByDesc(LostItem::getId);
        }

        Page<LostItem> page = itemMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        PageResult<ItemVO> result = assemblePage(page);
        cache.putList(cacheKey, new ItemCacheService.CachePage(
                result.getTotal(), result.getPages(), result.getPageNum(),
                result.getPageSize(), result.getList()));
        return result;
    }

    /** 我的发布（GET /api/items/mine，登录）：全状态对自己透明（草稿/待审/下架都在） */
    public PageResult<ItemVO> pageMine(LoginUser me, Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<LostItem> qw = new LambdaQueryWrapper<LostItem>()
                .eq(LostItem::getUserId, me.getUserId());
        if (status != null) {
            qw.eq(LostItem::getStatus, status);
        }
        qw.orderByDesc(LostItem::getId);
        return assemblePage(itemMapper.selectPage(new Page<>(pageNum, pageSize), qw));
    }

    /** 详情（GET /api/items/{id}）：可见性矩阵 + 浏览计数 + 登录态字段脱敏分级 */
    public ItemVO detail(Long id, LoginUser me) {
        // M9 读路径。缓存安全前提=「任何人读到的完全一致」，本系统只有游客视图满足
        // （contact 登录才填充）——所以缓存只罩 me=null 分支：游客吃缓存（全站最大读
        // 流量），登录者直查 DB 走完整可见性矩阵。回填同理只在游客读路径做，
        // 且仅发布态（待审/下架对作者要求"所见即最新"，缓存制造改而未生效假象）。
        if (me == null) {
            ItemVO cached = cache.getDetail(id);
            if (cached != null) {
                cache.countPv(id);
                return cached;
            }
        }
        LostItem item = requireItem(id);
        assertReadable(item, me);
        // PV 计数在可见性闸之后：404/403 路径不计数——"看不见的东西被浏览"
        // 会污染趋势数据（博客 M9 同口径）
        cache.countPv(id);
        ItemVO vo = assemble(item, me, true);
        if (me == null && item.getStatus() == STATUS_PUBLISHED) {
            cache.putDetail(id, vo);   // vo 此刻即游客视图（me=null 装配），无个人字段
        }
        return vo;
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 发布（POST /api/items，登录）。
     *
     * <p>三道入口闸：禁言回查（写口）→ 每日发布上限（daily_publish_limit，
     * 按当日 created_at 计数，含草稿——上限治理的是"灌水"不是"发布成功数"）→
     * 审核开关决定初始态（开=0 待审；关=1 直发）。FOUND 必带 verify_question（2003）。
     */
    @Transactional
    public Map<String, Object> create(LoginUser me, ItemSaveDTO dto) {
        assertWritable(me.getUserId());
        assertDailyLimit(me.getUserId());

        String type = dto.getType().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BizException(BizCode.PARAM_ERROR, "type 仅支持 LOST/FOUND");
        }
        LostItem item = new LostItem();
        item.setType(type);
        item.setUserId(me.getUserId());
        applyEditable(item, dto);

        if ("FOUND".equals(type)
                && (item.getVerifyQuestion() == null || item.getVerifyQuestion().isBlank())) {
            throw new BizException(BizCode.VERIFY_QUESTION_REQUIRED);
        }

        // 初始态：请求指定 1 且审核开关关闭 → 直发；否则一律进待审
        boolean auditOn = isAuditOn();
        int requested = dto.getStatus() == null ? STATUS_PENDING : dto.getStatus();
        if (requested == STATUS_PUBLISHED && auditOn) {
            requested = STATUS_PENDING;   // 开关优先于客户端意图，静默降级不是错误
        }
        if (requested != STATUS_PENDING && requested != STATUS_PUBLISHED) {
            throw new BizException(BizCode.PARAM_ERROR, "新建状态仅支持 0(待审)/1(发布)");
        }
        item.setStatus(requested);
        item.setViewCount(0);
        item.setClaimCount(0);
        item.setLikeCount(0);
        item.setTopFlag(0);
        item.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        item.setPublishedAt(requested == STATUS_PUBLISHED ? now : null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemMapper.insert(item);

        rebuildImages(item.getId(), dto.getImageUrls());
        cache.evictTx(item.getId());   // 列表键登记制全删：直发件要出现在"最新发布"里
        if (requested == STATUS_PUBLISHED) {
            matchService.matchAndNotify(item);   // 直发件：入库即可见，立刻找对端
        }
        log.info("用户 {} 发布物品 id={} type={} status={}", me.getUserId(),
                item.getId(), type, requested);
        return Map.of("id", item.getId(), "status", requested);
    }

    /**
     * 编辑（PUT /api/items/{id}）：属主 + 乐观锁 + 白名单字段。
     * 已发布物品的编辑不回退审核状态（改错字不该重新排队）；
     * 若需下架走 PATCH status 显式动作。
     */
    @Transactional
    public void update(LoginUser me, Long id, ItemSaveDTO dto) {
        assertWritable(me.getUserId());
        LostItem item = requireItem(id);
        assertOwner(me, item);
        if (dto.getVersion() == null) {
            throw new BizException(BizCode.PARAM_ERROR, "version 缺失（编辑窗口需携带详情返回的版本号）");
        }

        LostItem patch = new LostItem();
        patch.setId(id);
        patch.setVersion(dto.getVersion());
        applyEditable(patch, dto);
        // type 不允许编辑（LOST↔FOUND 翻转会让在途认领单语义坍塌，宁可让用户删除重建）
        patch.setType(null);

        int rows = itemMapper.updateById(patch);
        if (rows == 0) {
            // 乐观锁冲突或行已被删：区分成本高且语义同为"刷新重试"，统一 2004
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        if (dto.getImageUrls() != null) {
            rebuildImages(id, dto.getImageUrls());
        }
        cache.evictTx(id);
    }

    /**
     * 状态流转（PATCH /api/items/{id}/status?target=）——作者自助下架/重上架；
     * 待审→发布/驳回 走治理动作 changeStatusByAudit（审核队列口，挂 item:audit）。
     */
    @Transactional
    public void changeStatus(LoginUser me, Long id, int target, String reason) {
        LostItem item = requireItem(id);
        boolean owner = item.getUserId().equals(me.getUserId());
        boolean auditor = me.getPerms().contains("item:audit") || me.getPerms().contains("item:offline");
        if (!owner && !auditor) {
            throw new BizException(BizCode.ITEM_NOT_OWNER, BizCode.ITEM_NOT_OWNER.getMessage(), 403);
        }
        // 作者自助只允许 1→2（下架）与 2→1（重上架）；待审相关的流转只属于审核动作
        if (!auditor && !(item.getStatus() == STATUS_PUBLISHED || item.getStatus() == STATUS_OFFLINE)) {
            throw new BizException(BizCode.FORBIDDEN, "待审核物品请等待审核结果", 403);
        }
        assertTransition(item.getStatus(), target);
        if (target == STATUS_OFFLINE && (reason == null || reason.isBlank())) {
            throw new BizException(BizCode.PARAM_ERROR, "下架必须填写理由");
        }

        LostItem patch = new LostItem();
        patch.setId(id);
        patch.setStatus(target);
        patch.setOfflineReason(target == STATUS_OFFLINE ? reason.trim() : null);
        if (target == STATUS_PUBLISHED && item.getPublishedAt() == null) {
            patch.setPublishedAt(LocalDateTime.now());
        }
        itemMapper.updateById(patch);
        // 进入发布态的每一跳都跑一次匹配（审核通过/重上架同通道；幂等=重复推送可接受，
        // 匹配本身按当前在架集合算，没有"已推送"标记表的复杂度）
        if (target == STATUS_PUBLISHED) {
            LostItem fresh = itemMapper.selectById(id);
            matchService.matchAndNotify(fresh);
            if (item.getStatus() == STATUS_PENDING) {
                // 待审→发布 = 审核通过：作者收结果通知（重上架 2→1 不重发）
                notificationService.push(item.getUserId(),
                        com.campus.lf.module.notice.NotifyType.ITEM_AUDIT, id,
                        "物品审核通过", "《" + item.getTitle() + "》已发布，可在大厅被检索到。");
            }
        } else if (target == STATUS_OFFLINE && !item.getUserId().equals(me.getUserId())) {
            // 治理下架：作者必须收到理由（自助下架自己操作无需通知）
            notificationService.push(item.getUserId(),
                    com.campus.lf.module.notice.NotifyType.ITEM_AUDIT, id,
                    item.getStatus() == STATUS_PENDING ? "物品审核未通过" : "物品被下架",
                    "《" + item.getTitle() + "》" + (item.getStatus() == STATUS_PENDING ? "审核驳回：" : "已被管理员下架：") + reason);
        }
        cache.evictTx(id);
        log.info("状态流转 id={} {}→{} by uid={} reason={}", id, item.getStatus(), target,
                me.getUserId(), reason);
    }

    /** 删除（DELETE /api/items/{id}）：逻辑删除；作者自助或治理口。 */
    @Transactional
    public void delete(LoginUser me, Long id) {
        LostItem item = requireItem(id);
        boolean owner = item.getUserId().equals(me.getUserId());
        boolean auditor = me.getPerms().contains("item:audit") || me.getPerms().contains("item:offline");
        if (!owner && !auditor) {
            throw new BizException(BizCode.ITEM_NOT_OWNER, BizCode.ITEM_NOT_OWNER.getMessage(), 403);
        }
        itemMapper.deleteById(id);   // @TableLogic：物理行保留，回收站/审计语义
        cache.evictTx(id);
        // 在途认领单不在这里结算——M4 的 claim 域读物品时按"2001 已失效"处置，
        // 避免物品删除反向依赖认领模块（模块边界：item 不 import claim）
        log.info("物品删除 id={} by uid={} owner={}", id, me.getUserId(), owner);
    }

    // ==================================================================
    // 回收站（M10，管理员治理通道；逻辑删行的读/复活/物理删必须走自定义 SQL——
    // MP 拦截器恒拼 deleted=0，内置 API 对已删行语义上不可达，LostItemMapper 注释详述）
    // ==================================================================

    /** 回收站分页（GET /api/items/trash，item:audit）：只列已删行，keyword 打标题 */
    public PageResult<ItemVO> pageTrash(LoginUser me, String keyword, long pageNum, long pageSize) {
        assertGovernor(me);
        var page = itemMapper.selectTrashPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize),
                keyword == null || keyword.isBlank() ? null : keyword.trim());
        List<LostItem> rows = page.getRecords();
        Set<Long> userIds = rows.stream().map(LostItem::getUserId).collect(Collectors.toSet());
        Map<Long, String> nicknames = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname));
        List<ItemVO> list = rows.stream().map(r -> ItemVO.builder()
                .id(r.getId()).type(r.getType()).title(r.getTitle())
                .categoryId(r.getCategoryId()).userId(r.getUserId())
                .userNickname(nicknames.get(r.getUserId()))
                .status(r.getStatus()).viewCount(r.getViewCount())
                .createdAt(r.getCreatedAt()).build()).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(),
                page.getSize(), list);
    }

    /** 复活（POST /api/items/{id}/restore）：deleted 1→0，状态保持删除前原值；幂等 0 行报语义错 */
    @Transactional
    public void restore(LoginUser me, Long id) {
        assertGovernor(me);
        LostItem trashed = itemMapper.selectTrashedById(id);
        if (trashed == null) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, "回收站中没有这条物品（可能已恢复或不存在）", 404);
        }
        if (itemMapper.reviveById(id) == 0) {
            throw new BizException(BizCode.ITEM_STATUS_INVALID, "该物品已在库中");
        }
        cache.evictTx(id);
        log.info("管理员 {} 复活物品 id={}（原状态 {}）", me.getUserId(), id, trashed.getStatus());
    }

    /** 彻底删除（DELETE /api/items/{id}/purge）：物理删行，图片随删；SQL 自带 deleted=1 安全锁 */
    @Transactional
    public void purge(LoginUser me, Long id) {
        assertGovernor(me);
        if (itemMapper.selectTrashedById(id) == null) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, "回收站中没有这条物品（彻底删除只对已删行生效）", 404);
        }
        imageMapper.delete(new LambdaQueryWrapper<ItemImage>().eq(ItemImage::getItemId, id));
        itemMapper.purgeById(id);
        cache.evictTx(id);
        log.info("管理员 {} 彻底删除物品 id={}（物理删行，不可恢复）", me.getUserId(), id);
    }

    private void assertGovernor(LoginUser me) {
        if (me == null || !(me.getPerms().contains("item:audit") || me.getPerms().contains("item:offline"))) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
    }

    /** 发布图片（POST /api/items/{id}/images 之外的独立上传通道由 M8 通用口承接；
     *  这里给 save/编辑流程提供 URL 重建） */
    private void rebuildImages(Long itemId, List<String> urls) {
        if (urls == null) {
            return; // null=不改动图片集；空列表=清空
        }
        if (urls.size() > MAX_IMAGES) {
            throw new BizException(BizCode.PARAM_ERROR, "图片最多 " + MAX_IMAGES + " 张");
        }
        imageMapper.delete(new LambdaQueryWrapper<ItemImage>().eq(ItemImage::getItemId, itemId));
        int order = 0;
        for (String url : urls) {
            if (url == null || url.isBlank() || !url.startsWith("/uploads/")) {
                throw new BizException(BizCode.PARAM_ERROR, "图片 URL 非法（须经上传通道签发）");
            }
            ItemImage img = new ItemImage();
            img.setItemId(itemId);
            img.setUrl(url.trim());
            img.setSortOrder(order++);
            img.setDeleted(0);
            imageMapper.insert(img);
        }
    }

    // ==================================================================
    // 装配与闸门
    // ==================================================================

    /** DTO 白名单搬运 + 清洗 + 校验（create/update 共用） */
    private void applyEditable(LostItem item, ItemSaveDTO dto) {
        item.setTitle(dto.getTitle().trim());
        String desc = TextCleaner.clean(dto.getDescription());
        wordGate.assertClean(desc, "描述");
        wordGate.assertClean(dto.getTitle().trim(), "标题");
        item.setDescription(desc);
        item.setCategoryId(dto.getCategoryId());
        validateCategory(dto.getCategoryId());
        validateArea(dto);
        item.setCampusId(dto.getCampusId());
        item.setBuildingId(dto.getBuildingId());
        item.setLocationText(trimOrNull(dto.getLocationText()));
        if (dto.getHappenedAt().isAfter(LocalDateTime.now())) {
            throw new BizException(BizCode.PARAM_ERROR, "发生时间不能晚于当前");
        }
        item.setHappenedAt(dto.getHappenedAt());
        item.setContact(trimOrNull(dto.getContact()));
        item.setVerifyQuestion(trimOrNull(dto.getVerifyQuestion()));
    }

    private void validateArea(ItemSaveDTO dto) {
        if (dto.getBuildingId() != null) {
            CampusArea b = areaMapper.selectById(dto.getBuildingId());
            if (b == null || b.getLevel() != 2) {
                throw new BizException(BizCode.AREA_NOT_FOUND, "楼栋不存在");
            }
            if (dto.getCampusId() != null && !dto.getCampusId().equals(b.getParentId())) {
                throw new BizException(BizCode.PARAM_ERROR, "楼栋与校区不匹配");
            }
        }
        if (dto.getCampusId() != null) {
            CampusArea c = areaMapper.selectById(dto.getCampusId());
            if (c == null || c.getLevel() != 1) {
                throw new BizException(BizCode.AREA_NOT_FOUND, "校区不存在");
            }
        }
    }

    private void validateCategory(Long categoryId) {
        Category c = categoryMapper.selectById(categoryId);
        if (c == null || !"ITEM".equals(c.getKind())) {
            throw new BizException(BizCode.CATEGORY_NOT_FOUND);
        }
    }

    /** 禁言/封禁写口回查（UserStatus 三态口径，JWT 无状态盲区的唯一可靠执法点） */
    private void assertWritable(Long userId) {
        SysUser db = userMapper.selectById(userId);
        if (db == null || !UserStatus.canWrite(db.getStatus())) {
            throw new BizException(BizCode.ACCOUNT_DISABLED, "账号被限制发言", 403);
        }
    }

    /** 当日发布计数闸门（含全部状态的当日新帖——治理灌水的口径是"动手次数"） */
    private void assertDailyLimit(Long userId) {
        int limit = readIntConfig("daily_publish_limit", 10);
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        long today = itemMapper.selectCount(new LambdaQueryWrapper<LostItem>()
                .eq(LostItem::getUserId, userId)
                .ge(LostItem::getCreatedAt, dayStart));
        if (today >= limit) {
            throw new BizException(BizCode.FORBIDDEN, "今日发布已达上限(" + limit + "条)，明天再来", 429);
        }
    }

    private boolean isAuditOn() {
        return readIntConfig("item_audit", 1) == 1;
    }

    private int readIntConfig(String key, int fallback) {
        SysConfig c = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (c == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(c.getConfigValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private LostItem requireItem(Long id) {
        LostItem item = itemMapper.selectById(id);
        if (item == null) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, BizCode.ITEM_NOT_FOUND.getMessage(), 404);
        }
        return item;
    }

    private void assertOwner(LoginUser me, LostItem item) {
        if (!item.getUserId().equals(me.getUserId())) {
            boolean admin = me.getPerms().contains("item:offline");
            if (!admin) {
                throw new BizException(BizCode.ITEM_NOT_OWNER, BizCode.ITEM_NOT_OWNER.getMessage(), 403);
            }
        }
    }

    /** 可见性矩阵：非发布态对无关者=2001（与"不存在"同响应，防枚举草稿） */
    private void assertReadable(LostItem item, LoginUser me) {
        if (item.getStatus() == STATUS_PUBLISHED) {
            return;
        }
        boolean owner = me != null && item.getUserId().equals(me.getUserId());
        boolean auditor = me != null && (me.getPerms().contains("item:audit")
                || me.getPerms().contains("item:offline"));
        if (!owner && !auditor) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, BizCode.ITEM_NOT_FOUND.getMessage(), 404);
        }
    }

    private void assertTransition(Integer from, int to) {
        Set<Integer> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BizException(BizCode.ITEM_STATUS_INVALID,
                    "非法状态流转: " + from + "→" + to);
        }
    }

    /** 实体→VO：联查装配（字典名/作者昵称），contact 按登录态脱敏，详情带图片集 */
    private ItemVO assemble(LostItem item, LoginUser me, boolean withImages) {
        Category cat = categoryMapper.selectById(item.getCategoryId());
        SysUser author = userMapper.selectById(item.getUserId());
        List<String> images = withImages
                ? imageMapper.selectList(new LambdaQueryWrapper<ItemImage>()
                        .eq(ItemImage::getItemId, item.getId())
                        .orderByAsc(ItemImage::getSortOrder))
                .stream().map(ItemImage::getUrl).toList()
                : List.of();

        return ItemVO.builder()
                .id(item.getId())
                .type(item.getType())
                .title(item.getTitle())
                .description(item.getDescription())
                .categoryId(item.getCategoryId())
                .categoryName(cat == null ? null : cat.getName())
                .campusId(item.getCampusId())
                .buildingId(item.getBuildingId())
                .campusName(areaName(item.getCampusId()))
                .buildingName(areaName(item.getBuildingId()))
                .locationText(item.getLocationText())
                .happenedAt(item.getHappenedAt())
                // 字段级脱敏：contact 仅登录可见（游客浏览列表/详情都不给）
                .contact(me == null ? null : item.getContact())
                .verifyQuestion(item.getVerifyQuestion())
                .status(item.getStatus())
                .offlineReason(ownerOrAuditor(item, me) ? item.getOfflineReason() : null)
                .userId(item.getUserId())
                .userNickname(author == null ? null : author.getNickname())
                .images(images)
                .viewCount(item.getViewCount())
                .claimCount(item.getClaimCount())
                .topFlag(item.getTopFlag())
                .publishedAt(item.getPublishedAt())
                .createdAt(item.getCreatedAt())
                .version(withImages ? item.getVersion() : null)
                .build();
    }

    /** 列表批量装配：字典/作者用 IN 批查防 N+1（一页 ≤20 行，两次批查封顶） */
    private PageResult<ItemVO> assemblePage(Page<LostItem> page) {
        List<LostItem> rows = page.getRecords();
        if (rows.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getPages(),
                    page.getCurrent(), page.getSize(), List.of());
        }
        Set<Long> catIds = rows.stream().map(LostItem::getCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = rows.stream().map(LostItem::getUserId).collect(Collectors.toSet());
        Map<Long, String> catNames = catIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(catIds).stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> nicknames = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname));

        List<ItemVO> list = rows.stream().map(r -> ItemVO.builder()
                .id(r.getId())
                .type(r.getType())
                .title(r.getTitle())
                .categoryId(r.getCategoryId())
                .categoryName(catNames.get(r.getCategoryId()))
                .locationText(r.getLocationText())
                .happenedAt(r.getHappenedAt())
                .status(r.getStatus())
                .userId(r.getUserId())
                .userNickname(nicknames.get(r.getUserId()))
                .viewCount(r.getViewCount())
                .claimCount(r.getClaimCount())
                .topFlag(r.getTopFlag())
                .publishedAt(r.getPublishedAt())
                .images(List.of())
                .build()).toList();
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), list);
    }

    private String areaName(Long areaId) {
        if (areaId == null) {
            return null;
        }
        CampusArea a = areaMapper.selectById(areaId);
        return a == null ? null : a.getName();
    }

    private boolean ownerOrAuditor(LostItem item, LoginUser me) {
        if (me == null) {
            return false;
        }
        return item.getUserId().equals(me.getUserId())
                || me.getPerms().contains("item:audit")
                || me.getPerms().contains("item:offline");
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
