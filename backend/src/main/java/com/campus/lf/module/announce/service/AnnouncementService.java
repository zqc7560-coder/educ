package com.campus.lf.module.announce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.admin.mapper.SysConfigMapper;
import com.campus.lf.module.announce.dto.AnnouncementSaveDTO;
import com.campus.lf.module.announce.entity.Announcement;
import com.campus.lf.module.announce.entity.AnnouncementImage;
import com.campus.lf.module.announce.mapper.AnnouncementImageMapper;
import com.campus.lf.module.announce.mapper.AnnouncementMapper;
import com.campus.lf.module.announce.vo.AnnouncementVO;
import com.campus.lf.module.item.TextCleaner;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公告服务（M6）：管理员内容 + 状态机 + 时间窗 + 首页横幅只读口。
 *
 * <p>状态机（announcement.status）：0 草稿 →（publish）→ 1 已发布 →（offline）→
 * 2 已下架 →（可再 publish）。非法迁移 409/6002。编辑不限状态，但已发布件编辑
 * 保持已发布（公告是"官方口径"，管理员改错字不该触发重审——与论坛的用户内容
 * 语义不同，这是有意的差异，答辩可讲）。
 *
 * <p>时间窗（方案 §3.3.2）：effect_at 未到 / expire_at 已过的已发布件对读者隐藏。
 * 隐藏采用**读侧过滤**而非定时任务改状态——时钟语义留在查询里（now 参数化可测），
 * 免去一个 cron 组件；expireAt 过期时顺手把 status 落 2（懒下架，一次写换
 * 后续读全部走索引等值）。
 *
 * <p>首页横幅开关 announcement_enabled=0 时 active 口返回空列表（全站隐身，
 * 管理端 CRUD 不受影响——治理开关只影响读者侧）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_OFFLINE = 2;

    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            STATUS_DRAFT, Set.of(STATUS_PUBLISHED),
            STATUS_PUBLISHED, Set.of(STATUS_OFFLINE),
            STATUS_OFFLINE, Set.of(STATUS_PUBLISHED));

    private final AnnouncementMapper annMapper;
    private final com.campus.lf.module.admin.service.OperationLogService opLog;
    private final AnnouncementImageMapper imgMapper;
    private final SysConfigMapper configMapper;

    // ==================================================================
    // 管理端（announce:manage）
    // ==================================================================

    @Transactional
    public Long create(LoginUser me, AnnouncementSaveDTO dto) {
        assertManage(me);
        validateWindow(dto);
        validateImageUrls(dto.getImageUrls());
        Announcement a = new Announcement();
        a.setTitle(TextCleaner.clean(dto.getTitle()));
        a.setContent(TextCleaner.clean(dto.getContent()));
        a.setStatus(STATUS_DRAFT);           // 新公告一律草稿——发布是显式动作
        a.setTopFlag(0);
        a.setEffectAt(dto.getEffectiveAt());
        a.setExpireAt(dto.getExpireAt());
        a.setViewCount(0);
        a.setAuthorId(me.getUserId());
        a.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        annMapper.insert(a);
        saveImages(a.getId(), dto.getImageUrls());
        return a.getId();
    }

    @Transactional
    public void update(LoginUser me, Long id, AnnouncementSaveDTO dto) {
        assertManage(me);
        validateWindow(dto);
        // 图片 URL 校验必须在任何写动作之前：半途抛异常会把已 +1 的 version
        // 留在未回滚的共享事务里（测试事务同理），下一笔编辑就会假性 2004
        validateImageUrls(dto.getImageUrls());
        Announcement a = require(id);
        Announcement patch = new Announcement();
        patch.setId(id);
        // 乐观锁必须用"客户端持有的版本"做 where——用自己刚 load 的版本号
        // 永远命中，双管理员并发编辑会静默互相覆盖（M6 测试抓到后修复）
        patch.setVersion(dto.getVersion() == null ? a.getVersion() : dto.getVersion());
        patch.setTitle(TextCleaner.clean(dto.getTitle()));
        patch.setContent(TextCleaner.clean(dto.getContent()));
        patch.setEffectAt(dto.getEffectiveAt());
        patch.setExpireAt(dto.getExpireAt());
        if (annMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        // 图片"传了才整包替换"：null=本次编辑不动图（前端表单没这个字段时不该
        // 静默清空已挂图——契约缺陷修复，与论坛编辑 imageUrls 可空的口径一致）
        if (dto.getImageUrls() != null) {
            imgMapper.delete(new LambdaQueryWrapper<AnnouncementImage>()
                    .eq(AnnouncementImage::getAnnouncementId, id));
            saveImages(id, dto.getImageUrls());
        }
    }

    /** 状态迁移（publish/offline 动作口） */
    @Transactional
    public void transition(LoginUser me, Long id, int target) {
        assertManage(me);
        Announcement a = require(id);
        if (!ALLOWED.getOrDefault(a.getStatus(), Set.of()).contains(target)) {
            throw new BizException(BizCode.ANNOUNCEMENT_STATUS_INVALID,
                    "不允许的状态迁移: " + a.getStatus() + "→" + target, 409);
        }
        Announcement patch = new Announcement();
        patch.setId(id);
        patch.setStatus(target);
        annMapper.updateById(patch);
        opLog.record(me.getUserId(),
                target == STATUS_PUBLISHED ? com.campus.lf.module.admin.service.OperationLogService.ANNOUNCE_PUBLISH
                        : com.campus.lf.module.admin.service.OperationLogService.ANNOUNCE_OFFLINE,
                "announce:" + id, a.getTitle());
        log.info("公告 {} 状态 {}→{} by {}", id, a.getStatus(), target, me.getUserId());
    }

    /** 置顶切换（横幅只取最新一条已发布，置顶把这条钉到 active 首位） */
    @Transactional
    public void toggleTop(LoginUser me, Long id) {
        assertManage(me);
        Announcement a = require(id);
        annMapper.update(null, new LambdaUpdateWrapper<Announcement>()
                .eq(Announcement::getId, id)
                .set(Announcement::getTopFlag, a.getTopFlag() == 1 ? 0 : 1));
    }

    /** 删除（连带图片行；已发布件先下架再删的约束刻意不设——管理端误删靠 operation_log 追溯） */
    @Transactional
    public void delete(LoginUser me, Long id) {
        assertManage(me);
        require(id);
        annMapper.deleteById(id);
        imgMapper.delete(new LambdaQueryWrapper<AnnouncementImage>()
                .eq(AnnouncementImage::getAnnouncementId, id));
    }

    /** 管理列表：全状态（status 可选过滤），编辑台视角 */
    public PageResult<AnnouncementVO> pageAdmin(LoginUser me, Integer status, long pageNum, long pageSize) {
        assertManage(me);
        LambdaQueryWrapper<Announcement> w = new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getTopFlag).orderByDesc(Announcement::getId);
        if (status != null) {
            w.eq(Announcement::getStatus, status);
        }
        Page<Announcement> page = annMapper.selectPage(new Page<>(pageNum, pageSize), w);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(a -> toVO(a, false)).toList());
    }

    // ==================================================================
    // 读者侧（公开）
    // ==================================================================

    /** 公告列表：只见窗口内的已发布件；置顶在前，时间倒序 */
    public PageResult<AnnouncementVO> pagePublic(long pageNum, long pageSize) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Announcement> w = visibleWindow(now)
                .orderByDesc(Announcement::getTopFlag).orderByDesc(Announcement::getId);
        Page<Announcement> page = annMapper.selectPage(new Page<>(pageNum, pageSize), w);
        lazyExpire(page.getRecords(), now);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(a -> toVO(a, false)).toList());
    }

    /** 首页横幅：announcement_enabled=0 返回空；否则置顶优先取最新一条窗口内已发布 */
    public List<AnnouncementVO> activeBanner() {
        if (readIntConfig("announcement_enabled", 1) == 0) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Announcement> list = annMapper.selectList(visibleWindow(now)
                .orderByDesc(Announcement::getTopFlag).orderByDesc(Announcement::getId)
                .last("limit 1"));
        lazyExpire(list, now);
        return list.stream().map(a -> toVO(a, false)).toList();
    }

    /** 公告详情：非窗口内已发布 → 6001（草稿/下架对读者与不存在同响应，防内部口径泄露） */
    public AnnouncementVO detail(Long id) {
        Announcement a = annMapper.selectById(id);
        if (a == null || a.getStatus() != STATUS_PUBLISHED) {
            throw new BizException(BizCode.ANNOUNCEMENT_NOT_FOUND,
                    BizCode.ANNOUNCEMENT_NOT_FOUND.getMessage(), 404);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!inWindow(a, now)) {
            lazyExpire(List.of(a), now);
            throw new BizException(BizCode.ANNOUNCEMENT_NOT_FOUND,
                    BizCode.ANNOUNCEMENT_NOT_FOUND.getMessage(), 404);
        }
        annMapper.update(null, new LambdaUpdateWrapper<Announcement>()
                .eq(Announcement::getId, id).setSql("view_count = view_count + 1"));
        a.setViewCount(a.getViewCount() + 1);
        return toVO(a, true);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private LambdaQueryWrapper<Announcement> visibleWindow(LocalDateTime now) {
        return new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, STATUS_PUBLISHED)
                .and(w -> w.isNull(Announcement::getEffectAt).or().le(Announcement::getEffectAt, now))
                .and(w -> w.isNull(Announcement::getExpireAt).or().gt(Announcement::getExpireAt, now));
    }

    private boolean inWindow(Announcement a, LocalDateTime now) {
        return (a.getEffectAt() == null || !a.getEffectAt().isAfter(now))
                && (a.getExpireAt() == null || a.getExpireAt().isAfter(now));
    }

    /** 懒下架：读到 expire 已过的记录顺手落 status=2（时间语义唯一执法点在 inWindow/visibleWindow） */
    private void lazyExpire(List<Announcement> list, LocalDateTime now) {
        for (Announcement a : list) {
            if (a.getExpireAt() != null && !a.getExpireAt().isAfter(now)
                    && a.getStatus() == STATUS_PUBLISHED) {
                annMapper.update(null, new LambdaUpdateWrapper<Announcement>()
                        .eq(Announcement::getId, a.getId()).set(Announcement::getStatus, STATUS_OFFLINE));
                log.info("公告 {} 过生效窗自动下架（懒执行）", a.getId());
            }
        }
    }

    private void validateImageUrls(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String u : urls) {
            if (u == null || !u.startsWith("/uploads/")) {
                throw new BizException(BizCode.PARAM_ERROR, "图片必须先经本站上传接口，不接受外链");
            }
        }
    }

    private void validateWindow(AnnouncementSaveDTO dto) {
        if (dto.getEffectiveAt() != null && dto.getExpireAt() != null
                && !dto.getExpireAt().isAfter(dto.getEffectiveAt())) {
            throw new BizException(BizCode.PARAM_ERROR, "失效时间必须晚于生效时间");
        }
    }

    private void assertManage(LoginUser me) {
        if (!me.getPerms().contains("announce:manage")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
    }

    private Announcement require(Long id) {
        Announcement a = annMapper.selectById(id);
        if (a == null) {
            throw new BizException(BizCode.ANNOUNCEMENT_NOT_FOUND,
                    BizCode.ANNOUNCEMENT_NOT_FOUND.getMessage(), 404);
        }
        return a;
    }

    private AnnouncementVO toVO(Announcement a, boolean withImages) {
        return AnnouncementVO.builder()
                .id(a.getId()).title(a.getTitle()).content(a.getContent())
                .status(a.getStatus()).topFlag(a.getTopFlag())
                .effectiveAt(a.getEffectAt()).expireAt(a.getExpireAt())
                .viewCount(a.getViewCount()).authorId(a.getAuthorId())
                .createdAt(a.getCreatedAt()).version(a.getVersion())
                .images(withImages ? imgMapper.selectList(new LambdaQueryWrapper<AnnouncementImage>()
                                .eq(AnnouncementImage::getAnnouncementId, a.getId())
                                .orderByAsc(AnnouncementImage::getSortOrder))
                        .stream().map(AnnouncementImage::getUrl).toList() : null)
                .build();
    }

    private void saveImages(Long annId, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        int i = 0;
        for (String u : urls) {
            AnnouncementImage img = new AnnouncementImage();
            img.setAnnouncementId(annId);
            img.setUrl(u);
            img.setSortOrder(i++);
            img.setCreatedAt(LocalDateTime.now());
            imgMapper.insert(img);
        }
    }

    private int readIntConfig(String key, int fallback) {
        try {
            var row = configMapper.selectOne(new LambdaQueryWrapper<com.campus.lf.module.admin.entity.SysConfig>()
                    .eq(com.campus.lf.module.admin.entity.SysConfig::getConfigKey, key));
            return row == null ? fallback : Integer.parseInt(row.getConfigValue());
        } catch (Exception e) {
            log.warn("读取参数 {} 失败，用兜底 {}: {}", key, fallback, e.getMessage());
            return fallback;
        }
    }
}
