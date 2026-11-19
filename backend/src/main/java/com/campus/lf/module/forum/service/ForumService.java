package com.campus.lf.module.forum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.admin.SensitiveWordGate;
import com.campus.lf.module.admin.mapper.SysConfigMapper;
import com.campus.lf.module.notice.entity.Notification;
import com.campus.lf.module.notice.mapper.NotificationMapper;
import com.campus.lf.module.forum.dto.PostSaveDTO;
import com.campus.lf.module.forum.dto.ReplyDTO;
import com.campus.lf.module.forum.entity.ForumLike;
import com.campus.lf.module.forum.entity.ForumPost;
import com.campus.lf.module.forum.entity.ForumReply;
import com.campus.lf.module.forum.entity.PostImage;
import com.campus.lf.module.forum.entity.PostReport;
import com.campus.lf.module.forum.mapper.ForumLikeMapper;
import com.campus.lf.module.forum.mapper.ForumPostMapper;
import com.campus.lf.module.forum.mapper.ForumReplyMapper;
import com.campus.lf.module.forum.mapper.PostImageMapper;
import com.campus.lf.module.forum.vo.PostVO;
import com.campus.lf.module.item.TextCleaner;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.entity.Category;
import com.campus.lf.module.item.mapper.CategoryMapper;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 论坛服务（M5）：板块帖子（0–9 图）+ 两级回复 + 幂等点赞 + 举报转审。
 *
 * <p>与物品域共享的三条纪律（同一套语义，读 ItemService 即读这里）：
 * <ol>
 *   <li>先审后发：forum_audit 开关 + 客户端意图静默降级；编辑已发布帖
 *       在开关=1 时回到待审——否则"先发好帖养权重、再改成违规文"绕过审核；</li>
 *   <li>可见性矩阵：待审/下架仅作者与 forum:audit 权限者可见，无关者一律 404/5001
 *       （防枚举，与物品 2001 同构）；</li>
 *   <li>发布日限：daily_publish_limit 按"物品+帖子"合计计数（方案 §3.3.1
 *       台账原文"物品+帖子合计"），防跨模块分摊灌水。</li>
 * </ol>
 *
 * <p>论坛特有：
 * <ul>
 *   <li>两级回复封顶：parent 只允许指向一级回复；二级再挂时由调用方归一
 *       （service 校验抛 5003），@对象用 reply_to_uid 表达并自动回填通知；</li>
 *   <li>回复 10s 防刷：进程内时间戳闸（单机毕设部署足够；多实例换 Redis
 *       键 lf:forum:reply:gate:{uid}，M9 缓存改造时一并评估）；</li>
 *   <li>点赞幂等：uk(post_id,user_id)，重复点赞静默 no-op（返回当前态），
 *       取消点赞不存在同样 no-op——计数只随真实 insert/delete 同步增减。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumService {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_OFFLINE = 2;

    /** 状态机：与 lost_item 同构 */
    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            STATUS_PENDING, Set.of(STATUS_PUBLISHED, STATUS_OFFLINE),
            STATUS_PUBLISHED, Set.of(STATUS_OFFLINE),
            STATUS_OFFLINE, Set.of(STATUS_PUBLISHED));

    private final ForumPostMapper postMapper;
    private final ForumReplyMapper replyMapper;
    private final ForumLikeMapper likeMapper;
    private final com.campus.lf.module.forum.mapper.UserFavoriteMapper favoriteMapper;
    private final com.campus.lf.module.item.service.ViewOnceGate viewOnceGate;
    private final com.campus.lf.module.forum.mapper.PostReportMapper reportMapper;
    private final PostImageMapper postImageMapper;
    private final SysUserMapper userMapper;
    private final SysConfigMapper configMapper;
    private final CategoryMapper categoryMapper;
    private final LostItemMapper itemMapper;          // 跨模块日限合计计数（只读）
    private final NotificationMapper notificationMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final SensitiveWordGate wordGate;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 回复防刷（M9 Redis 化）：SETNX lf:gate:reply:{uid} TTL 10s——
     *  键存在即"10 秒内已回过"。进程内 Map 退役：多实例部署下各自为政形同虚设，
     *  Redis 单点锁键天然全局一致。Redis 故障 fail-open（防刷是体验护栏不是
     *  正确性闸，宁放行不误伤发帖）。 */
    static final long REPLY_MIN_INTERVAL_MS = 10_000L;
    private static final String REPLY_GATE_PREFIX = "lf:gate:reply:";

    // ==================================================================
    // 帖子
    // ==================================================================

    /**
     * 分页列表：boardId/keyword 过滤 + mine=1 只看自己；sort=hot 按回复+点赞。
     * 游客与非治理登录者只见已发布；作者/治理者带 status 参数可看自己的全状态。
     */
    public PageResult<PostVO> pagePosts(LoginUser me, Long boardId, String keyword, Integer status,
                                        boolean mine, String sort, long pageNum, long pageSize) {
        LambdaQueryWrapper<ForumPost> w = new LambdaQueryWrapper<>();
        if (boardId != null) {
            w.eq(ForumPost::getBoardId, boardId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().replace("%", "\\%").replace("_", "\\_");
            w.and(x -> x.like(ForumPost::getTitle, kw).or().like(ForumPost::getContent, kw));
        }
        if (mine) {
            if (me == null) {
                throw new BizException(BizCode.UNAUTHORIZED);
            }
            w.eq(ForumPost::getUserId, me.getUserId());   // 作者看自己的不需要状态过滤
            if (status != null) {
                w.eq(ForumPost::getStatus, status);
            }
        } else if (status != null && me != null && canGovern(me)) {
            w.eq(ForumPost::getStatus, status);           // 治理队列视角（审核台）
        } else {
            w.eq(ForumPost::getStatus, STATUS_PUBLISHED); // 公开视角恒定只见已发布
        }
        if ("hot".equals(sort)) {
            w.last("order by (reply_count + like_count) desc, id desc");
        } else if ("oldest".equals(sort)) {
            w.orderByAsc(ForumPost::getId);
        } else {
            w.orderByDesc(ForumPost::getTopFlag).orderByDesc(ForumPost::getId);
        }
        Page<ForumPost> page = postMapper.selectPage(new Page<>(pageNum, pageSize), w);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                assemble(page.getRecords(), me));
    }

    /** 详情：待审/下架的可见性矩阵；浏览计数排除作者自览 + 按访客 24h 去重 */
    public PostVO detail(LoginUser me, Long id, String viewerKey, boolean countView) {
        ForumPost post = requireVisible(me, id);
        if (countView
                && (me == null || !post.getUserId().equals(me.getUserId()))
                && viewOnceGate.shouldCount("post", id, viewerKey)) {
            postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                    .eq(ForumPost::getId, id).setSql("view_count = view_count + 1"));
            post.setViewCount(post.getViewCount() + 1);
        }
        PostVO vo = assemble(List.of(post), me).get(0);
        vo.setFavorited(me != null && favoriteMapper.selectCount(
                new LambdaQueryWrapper<com.campus.lf.module.forum.entity.UserFavorite>()
                        .eq(com.campus.lf.module.forum.entity.UserFavorite::getUserId, me.getUserId())
                        .eq(com.campus.lf.module.forum.entity.UserFavorite::getTargetType, "POST")
                        .eq(com.campus.lf.module.forum.entity.UserFavorite::getTargetId, id)) > 0);
        vo.setContent(post.getContent());   // 列表=摘要、详情=全文（assemble 统一做摘要化）
        vo.setImages(postImageMapper.selectList(new LambdaQueryWrapper<PostImage>()
                        .eq(PostImage::getPostId, id).orderByAsc(PostImage::getSortOrder))
                .stream().map(PostImage::getUrl).toList());
        return vo;
    }

    /** 发帖：三道闸（禁言/日限合计/敏感词+清洗）→ 审核开关裁决初始态 → 图片整包入库 */
    @Transactional
    public PostVO create(LoginUser me, PostSaveDTO dto) {
        assertWritable(me.getUserId());
        assertDailyLimit(me.getUserId());
        Category board = categoryMapper.selectById(dto.getBoardId());
        if (board == null || !"BOARD".equals(board.getKind())) {
            throw new BizException(BizCode.BOARD_NOT_FOUND, BizCode.BOARD_NOT_FOUND.getMessage(), 404);
        }
        String title = TextCleaner.clean(dto.getTitle());
        String content = TextCleaner.clean(dto.getContent());
        wordGate.assertClean(title + "\n" + content, "帖子");
        assertImageUrls(dto.getImageUrls());

        ForumPost post = new ForumPost();
        post.setBoardId(dto.getBoardId());
        post.setUserId(me.getUserId());
        post.setTitle(title);
        post.setContent(content);
        int requested = dto.getStatus() == null ? STATUS_PENDING : dto.getStatus();
        post.setStatus(isAuditOn() ? STATUS_PENDING : Math.min(requested, STATUS_PUBLISHED));
        post.setViewCount(0);
        post.setReplyCount(0);
        post.setLikeCount(0);
        post.setTopFlag(0);
        post.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        post.setPublishedAt(post.getStatus() == STATUS_PUBLISHED ? now : null);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);
        saveImages(post.getId(), dto.getImageUrls());
        return assemble(List.of(post), me).get(0);
    }

    /**
     * 编辑：仅作者；乐观锁；<b>审核中状态被强制拉回待审</b>（forum_audit=1 时）——
     * "先发正常帖、过审后改成违规文"是本系统对先审后发模型的主要攻击路径，此处收口。
     */
    @Transactional
    public void update(LoginUser me, Long id, PostSaveDTO dto) {
        assertWritable(me.getUserId());
        ForumPost post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        if (!post.getUserId().equals(me.getUserId())) {
            throw new BizException(BizCode.FORBIDDEN, "只能编辑自己的帖子", 403);
        }
        if (post.getStatus() == STATUS_OFFLINE && !canGovern(me)) {
            throw new BizException(BizCode.POST_STATUS_INVALID, "已下架的帖子请联系管理员", 409);
        }
        wordGate.assertClean(dto.getTitle() + "\n" + dto.getContent(), "帖子");
        assertImageUrls(dto.getImageUrls());

        ForumPost patch = new ForumPost();
        patch.setId(id);
        patch.setVersion(dto.getVersion() == null ? post.getVersion() : dto.getVersion());
        patch.setTitle(TextCleaner.clean(dto.getTitle()));
        patch.setContent(TextCleaner.clean(dto.getContent()));
        if (isAuditOn() && post.getStatus() == STATUS_PUBLISHED) {
            patch.setStatus(STATUS_PENDING);   // 编辑即重审（攻击路径收口，见方法注释）
        }
        if (postMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        // 图片整包替换（与 M3 同纪律）
        postImageMapper.delete(new LambdaQueryWrapper<PostImage>().eq(PostImage::getPostId, id));
        saveImages(id, dto.getImageUrls());
    }

    /** 作者删除（逻辑删；回复与点赞随入口消失，不做物理级联——审计留痕） */
    @Transactional
    public void deleteByAuthor(LoginUser me, Long id) {
        ForumPost post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        if (!post.getUserId().equals(me.getUserId())) {
            throw new BizException(BizCode.FORBIDDEN, "只能删除自己的帖子", 403);
        }
        postMapper.deleteById(id);
    }

    /**
     * 治理流转（审核通过/驳回/下架/恢复合一，M3 items 同款通道）：
     * forum:audit 或 forum:offline 权限者可执行；非法迁移 409。
     */
    @Transactional
    public void transition(LoginUser me, Long id, int target, String reason) {
        boolean auditor = me.getPerms().contains("forum:audit");
        boolean offliner = me.getPerms().contains("forum:offline");
        if (!auditor && !offliner) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
        if (target == STATUS_PUBLISHED && !auditor) {
            throw new BizException(BizCode.FORBIDDEN, "发布/恢复需 forum:audit 权限", 403);
        }
        ForumPost post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        Set<Integer> allowed = ALLOWED.getOrDefault(post.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException(BizCode.POST_STATUS_INVALID,
                    "不允许的状态迁移: " + post.getStatus() + "→" + target, 409);
        }
        ForumPost patch = new ForumPost();
        patch.setId(id);
        patch.setStatus(target);
        if (target == STATUS_PUBLISHED && post.getPublishedAt() == null) {
            patch.setPublishedAt(LocalDateTime.now());
        }
        if (target == STATUS_OFFLINE) {
            patch.setOfflineReason(reason == null || reason.isBlank() ? "违规下架" : reason.trim());
        } else {
            patch.setOfflineReason("");
        }
        postMapper.updateById(patch);
        // 举报工单联动销单：下架=处置成立，恢复发布=审查保留（都回执举报人）
        if ((target == STATUS_OFFLINE || target == STATUS_PUBLISHED)
                && post.getStatus() != target) {
            resolveReports(id, me.getUserId(), target == STATUS_OFFLINE);
        }
        notify(post.getUserId(), "POST_AUDIT", id,
                target == STATUS_PUBLISHED ? "帖子已通过审核" : "帖子状态更新",
                "《" + post.getTitle() + "》" + (target == STATUS_PUBLISHED ? "已发布" :
                        target == STATUS_OFFLINE ? "已下架：" + reason : "转为待审"));
    }

    /** 置顶/取消（forum:audit；首页权重排序用 top_flag 列） */
    @Transactional
    public void toggleTop(LoginUser me, Long id) {
        if (!me.getPerms().contains("forum:audit")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
        ForumPost post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                .eq(ForumPost::getId, id)
                .set(ForumPost::getTopFlag, post.getTopFlag() == 1 ? 0 : 1));
    }

    // ==================================================================
    // 回复（两级封顶）
    // ==================================================================

    /**
     * 提交回复。校验链：禁言→10s 防刷→帖可见且已发布→parent 归一两級→
     * 敏感词清洗→reply_to 自动@通知→一级/二级统一 reply_count+1。
     */
    @Transactional
    public Long reply(LoginUser me, Long postId, ReplyDTO dto) {
        assertWritable(me.getUserId());
        assertReplyRateLimit(me.getUserId());
        ForumPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        if (post.getStatus() != STATUS_PUBLISHED) {
            // 互动闸收口（用户实测 bug）：待审/下架帖对所有人一律不可回复——
            // 治理者同样拒绝（审核通过后才可互动），不再给 canGovern 开后门；
            // "作者提前看到回复列表"只读口不受影响（replyTree 的 privileged 保持）
            throw new BizException(BizCode.POST_STATUS_INVALID, "帖子未发布，暂不可回复");
        }

        Long parentId = dto.getParentId();
        Long replyToUid = dto.getReplyToUserId();
        // 归一目标楼层闸：UI"回复某楼"会把 parentId 归一到其一级父，真实目标经 replyToId 传达——
        // 目标楼未过审一律拒绝（否则待审楼下能盖出已发布回复=用户实测漏洞）；目标存在时 @ 对象以它为准
        Long replyToId = dto.getReplyToId();
        if (replyToId != null) {
            ForumReply target = replyMapper.selectById(replyToId);
            if (target == null || !target.getPostId().equals(postId)) {
                throw new BizException(BizCode.PARAM_ERROR, "被回复的楼层不存在或不在本帖");
            }
            if (target.getStatus() == null || target.getStatus() != STATUS_PUBLISHED) {
                throw new BizException(BizCode.POST_STATUS_INVALID, "该回复尚未通过审核，暂不可回复");
            }
            replyToUid = target.getUserId();
            if (parentId == null) {
                parentId = target.getParentId() != null ? target.getParentId() : target.getId();
            }
        }
        if (parentId != null) {
            ForumReply parent = replyMapper.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(postId)) {
                throw new BizException(BizCode.PARAM_ERROR, "父回复不存在或不在本帖");
            }
            if (parent.getParentId() != null) {
                // 两级封顶：拒绝挂到二级回复下面（前端应归一到一级；这里兜底 API 直连攻击）
                // 注意次序：先判层级再判父楼状态——挂在待审二级楼下时 5006 语义更准
                throw new BizException(BizCode.REPLY_LEVEL_EXCEEDED);
            }
            if (parent.getStatus() != null && parent.getStatus() != STATUS_PUBLISHED) {
                // 父楼待审/已下架时不可在其下继续盖楼（否则绕过回复审核造出隐形树）
                throw new BizException(BizCode.POST_STATUS_INVALID, "该回复尚未通过审核，暂不可在其下回复");
            }
            if (replyToUid == null) {
                replyToUid = parent.getUserId();   // 回复某人 → 自动@他
            }
        }

        String content = TextCleaner.clean(dto.getContent());
        wordGate.assertClean(content, "回复");

        ForumReply r = new ForumReply();
        r.setPostId(postId);
        r.setParentId(parentId);
        r.setUserId(me.getUserId());
        r.setReplyToUid(replyToUid);
        r.setContent(content);
        r.setStatus(isAuditOn() ? STATUS_PENDING : STATUS_PUBLISHED);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        replyMapper.insert(r);

        postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                .eq(ForumPost::getId, postId).setSql("reply_count = reply_count + 1"));
        // @通知：回复与被@者不是同一人时才发（自回复不骚扰）
        Long notifyUid = replyToUid != null ? replyToUid : post.getUserId();
        if (!me.getUserId().equals(notifyUid)) {
            SysUser from = userMapper.selectById(me.getUserId());
            notify(notifyUid, "REPLY", postId, "有人@了/回复了你",
                    (from == null ? "有人" : from.getNickname()) + "： " + shorten(content));
        }
        return r.getId();
    }

    /** 帖子回复树：一级（status 可见）+ 各自 children；作者可见自己待审帖的回复 */
    public List<Map<String, Object>> replyTree(LoginUser me, Long postId) {
        ForumPost post = requireVisible(me, postId);
        boolean privileged = post.getUserId().equals(me != null ? me.getUserId() : -1L) || canGovern(me);
        LambdaQueryWrapper<ForumReply> w = new LambdaQueryWrapper<ForumReply>()
                .eq(ForumReply::getPostId, postId).orderByAsc(ForumReply::getId);
        if (!privileged) {
            w.eq(ForumReply::getStatus, STATUS_PUBLISHED);
        }
        List<ForumReply> all = replyMapper.selectList(w);
        Map<Long, SysUser> users = loadUsers(all.stream().map(ForumReply::getUserId)
                .collect(Collectors.toSet()));
        List<Map<String, Object>> roots = new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (ForumReply r : all) {
            SysUser u = users.get(r.getUserId());
            SysUser at = r.getReplyToUid() == null ? null : users.get(r.getReplyToUid());
            Map<String, Object> node = new HashMap<>();
            node.put("id", r.getId());
            node.put("parentId", r.getParentId());
            node.put("userId", r.getUserId());
            node.put("nickname", u == null ? "已注销" : u.getNickname());
            node.put("avatar", u == null ? null : u.getAvatar());
            node.put("replyToNickname", at == null ? null : at.getNickname());
            node.put("content", r.getContent());
            node.put("status", r.getStatus());
            node.put("createdAt", r.getCreatedAt());
            byId.put(r.getId(), node);
            if (r.getParentId() == null) {
                node.put("children", new ArrayList<Map<String, Object>>());
                roots.add(node);
            } else {
                Map<String, Object> parent = byId.get(r.getParentId());
                if (parent != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ch = (List<Map<String, Object>>) parent.get("children");
                    ch.add(node);
                }
            }
        }
        return roots;
    }

    /** 删自己的回复（逻辑删；计数只随已发布回复删除回冲） */
    @Transactional
    public void deleteReply(LoginUser me, Long replyId) {
        ForumReply r = replyMapper.selectById(replyId);
        if (r == null) {
            throw new BizException(BizCode.PARAM_ERROR, "回复不存在");
        }
        ForumPost post = postMapper.selectById(r.getPostId());
        boolean isAuthor = r.getUserId().equals(me.getUserId());
        boolean isPostAuthor = post != null && post.getUserId().equals(me.getUserId());  // 楼主可删自己帖内回复
        if (!isAuthor && !isPostAuthor && !me.getPerms().contains("forum:offline")) {
            throw new BizException(BizCode.FORBIDDEN, "只能删除自己的回复（楼主可删本帖回复）", 403);
        }
        replyMapper.deleteById(replyId);
        // 口径与 reply() 的"+1 不分状态"对称：count=本帖未删回复数（含作者待审视角）
        if (post != null) {
            postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                    .eq(ForumPost::getId, r.getPostId()).gt(ForumPost::getReplyCount, 0)
                    .setSql("reply_count = reply_count - 1"));
        }
    }

    // ==================================================================
    // 点赞（幂等）
    // ==================================================================

    /** 点赞：uk 冲突静默幂等（重复点不报错不重复计数）；返回点踩后当前态 */
    @Transactional
    public boolean like(LoginUser me, Long postId) {
        ForumPost post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != STATUS_PUBLISHED) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        boolean exists = likeMapper.selectCount(new LambdaQueryWrapper<ForumLike>()
                .eq(ForumLike::getPostId, postId).eq(ForumLike::getUserId, me.getUserId())) > 0;
        if (!exists) {
            ForumLike l = new ForumLike();
            l.setPostId(postId);
            l.setUserId(me.getUserId());
            l.setCreatedAt(LocalDateTime.now());
            try {
                likeMapper.insert(l);   // 并发下 uk 兜底
                postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                        .eq(ForumPost::getId, postId).setSql("like_count = like_count + 1"));
                if (!post.getUserId().equals(me.getUserId())) {
                    SysUser from = userMapper.selectById(me.getUserId());
                    notify(post.getUserId(), "LIKE", postId, "有人赞了你的帖子",
                            (from == null ? "有人" : from.getNickname()) + " 赞了《" + post.getTitle() + "》");
                }
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return true;   // 并发重复：另一个线程已插入并完成计数
            }
        }
        return true;
    }

    /** 取消点赞：不存在同样幂等 no-op */
    @Transactional
    public boolean unlike(LoginUser me, Long postId) {
        int deleted = likeMapper.delete(new LambdaQueryWrapper<ForumLike>()
                .eq(ForumLike::getPostId, postId).eq(ForumLike::getUserId, me.getUserId()));
        if (deleted > 0) {
            postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                    .eq(ForumPost::getId, postId).gt(ForumPost::getLikeCount, 0)
                    .setSql("like_count = like_count - 1"));
        }
        return false;
    }

    // ==================================================================
    // 举报（P1 最小实现：转回待审进治理队列）
    // ==================================================================

    @Transactional
    public void report(LoginUser me, Long postId, String reason) {
        ForumPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        // 自举报判定在状态闸之前：举报"自己刚发的待审帖"若先撞 5001，会把真实语义
        // （你不能举报自己的东西）藏进"不存在"——对存在的资源，意图错就该报意图错
        if (post.getUserId().equals(me.getUserId())) {
            throw new BizException(BizCode.PARAM_ERROR, "不能举报自己的帖子");
        }
        if (post.getStatus() != STATUS_PUBLISHED) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        // 举报=登记工单+通知治理员，**帖子状态不因举报改变**（旧"举报即转待审"已废弃：
        // 个别举报不该让在线内容直接消失，管理员看过理由后自行决定下架或保留）
        long dup = reportMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<PostReport>()
                .eq(PostReport::getPostId, postId).eq(PostReport::getUserId, me.getUserId()));
        if (dup > 0) {
            throw new BizException(BizCode.REPORT_DUPLICATE);
        }
        PostReport rep = new PostReport();
        rep.setPostId(postId);
        rep.setUserId(me.getUserId());
        rep.setReason(reason == null ? null : reason.replaceAll("[\\r\\n]", " "));
        rep.setStatus(0);
        rep.setCreatedAt(java.time.LocalDateTime.now());
        rep.setDeleted(0);
        try {
            reportMapper.insert(rep);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(BizCode.REPORT_DUPLICATE);   // 并发双击：uk 兜底
        }
        String brief = rep.getReason() == null || rep.getReason().isBlank() ? "（未填理由）" : rep.getReason();
        for (Long governor : governanceUserIds()) {
            notify(governor, "REPORT_NEW", postId, "有帖子被举报待处理",
                    "《" + post.getTitle() + "》被举报，理由：" + brief);
        }
        log.info("举报登记：post={} by uid={} 理由={}", postId, me.getUserId(), brief);
    }

    /** 持有论坛治理权限点的正常用户（举报通知对象）；三表 JOIN 一次查询，量小 */
    public List<Long> governanceUserIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT ur.user_id FROM sys_role_permission rp "
                        + "JOIN sys_user_role ur ON ur.role_id = rp.role_id "
                        + "JOIN sys_permission p ON p.id = rp.perm_id "
                        + "JOIN sys_user u ON u.id = ur.user_id "
                        + "WHERE p.perm_code IN ('forum:audit','forum:offline') AND u.status = 1 AND u.deleted = 0",
                Long.class);
    }

    /** 待处理举报工单分页（审核台；治理员可读） */
    public PageResult<Map<String, Object>> pagePendingReports(long pageNum, long pageSize) {
        Page<PostReport> page = reportMapper.selectPage(new Page<>(pageNum, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PostReport>()
                        .eq(PostReport::getStatus, 0).orderByDesc(PostReport::getId));
        List<Map<String, Object>> list = page.getRecords().stream().map(r -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", r.getId());
            m.put("postId", r.getPostId());
            m.put("userId", r.getUserId());
            m.put("reason", r.getReason());
            m.put("createdAt", r.getCreatedAt());
            ForumPost p = postMapper.selectById(r.getPostId());
            m.put("postTitle", p == null ? "(帖子已删除)" : p.getTitle());
            m.put("postStatus", p == null ? null : p.getStatus());
            return m;
        }).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), list);
    }

    /** 处置完成销单（管理员对帖执行下架/保留后调用）：工单置 1+回执举报人 */
    @Transactional
    public void resolveReports(Long postId, Long handlerId, boolean takenDown) {
        List<PostReport> open = reportMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PostReport>()
                        .eq(PostReport::getPostId, postId).eq(PostReport::getStatus, 0));
        for (PostReport r : open) {
            PostReport patch = new PostReport();
            patch.setId(r.getId());
            patch.setStatus(1);
            patch.setHandledAt(java.time.LocalDateTime.now());
            reportMapper.updateById(patch);
            notify(r.getUserId(), "REPORT_RESULT", postId,
                    takenDown ? "你举报的帖子已被下架" : "你举报的帖子经审查保留",
                    "感谢你的监督，管理员已对该帖完成处置。");
        }
    }

    // ==================================================================
    // 装配与闸门（沿用 M3/M4 语义）
    // ==================================================================

    private List<PostVO> assemble(List<ForumPost> posts, LoginUser me) {
        if (posts.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = posts.stream().map(ForumPost::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> users = loadUsers(userIds);
        Set<Long> boardIds = posts.stream().map(ForumPost::getBoardId).collect(Collectors.toSet());
        Map<Long, String> boards = boardIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(boardIds).stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName));
        Set<Long> likedIds = me == null ? Set.of()
                : likeMapper.selectList(new LambdaQueryWrapper<ForumLike>()
                        .eq(ForumLike::getUserId, me.getUserId())
                        .in(ForumLike::getPostId, posts.stream().map(ForumPost::getId).toList()))
                    .stream().map(ForumLike::getPostId).collect(Collectors.toSet());
        return posts.stream().map(p -> {
            SysUser u = users.get(p.getUserId());
            boolean full = false;   // 列表摘要化；detail() 走全文路径
            return PostVO.builder()
                    .id(p.getId()).boardId(p.getBoardId())
                    .boardName(boards.get(p.getBoardId()))
                    .userId(p.getUserId())
                    .nickname(u == null ? "已注销" : u.getNickname())
                    .avatar(u == null ? null : u.getAvatar())
                    .title(p.getTitle())
                    .content(full ? p.getContent() : shorten(p.getContent()))
                    .status(p.getStatus()).offlineReason(p.getOfflineReason())
                    .viewCount(p.getViewCount()).replyCount(p.getReplyCount())
                    .likeCount(p.getLikeCount()).topFlag(p.getTopFlag())
                    .liked(likedIds.contains(p.getId()))
                    .publishedAt(p.getPublishedAt()).createdAt(p.getCreatedAt())
                    .version(p.getVersion())
                    .build();
        }).toList();
    }

    private ForumPost requireVisible(LoginUser me, Long id) {
        ForumPost post = postMapper.selectById(id);
        if (post == null) {
            throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
        }
        if (post.getStatus() != STATUS_PUBLISHED) {
            Long myId = me == null ? null : me.getUserId();
            boolean privileged = Objects.equals(post.getUserId(), myId)
                    || (me != null && me.getPerms().contains("forum:audit"));
            if (!privileged) {
                throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
            }
        }
        return post;
    }

    private Map<Long, SysUser> loadUsers(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, x -> x));
    }

    private void assertWritable(Long userId) {
        SysUser db = userMapper.selectById(userId);
        if (db == null || !UserStatus.canWrite(db.getStatus())) {
            throw new BizException(BizCode.ACCOUNT_DISABLED, "账号被限制发言", 403);
        }
    }

    private void assertReplyRateLimit(Long userId) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(REPLY_GATE_PREFIX + userId, "1",
                    java.time.Duration.ofMillis(REPLY_MIN_INTERVAL_MS));
            if (!Boolean.TRUE.equals(ok)) {
                throw new BizException(BizCode.REPLY_TOO_FAST);
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            // fail-open：Redis 抖动不拦正常用户
            log.warn("回复防刷降级放行 uid={}: {}", userId, e.getMessage());
        }
    }

    /** 测试钩子：清掉防刷锁键（等效等过窗口） */
    public void seedLastReplyAt(Long userId, long ts) {
        try {
            redis.delete(REPLY_GATE_PREFIX + userId);
        } catch (Exception ignore) {
            // 测试桩不会失败；真 Redis 抖动时等 TTL 自然过期即可
        }
    }

    /** 日限合计：物品+帖子（方案 §3.3.1）；跨模块只读计数，无循环依赖 */
    private void assertDailyLimit(Long userId) {
        int limit = readIntConfig("daily_publish_limit", 10);
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        long today = itemMapper.selectCount(new LambdaQueryWrapper<LostItem>()
                .eq(LostItem::getUserId, userId).ge(LostItem::getCreatedAt, dayStart))
                + postMapper.selectCount(new LambdaQueryWrapper<ForumPost>()
                .eq(ForumPost::getUserId, userId).ge(ForumPost::getCreatedAt, dayStart));
        if (today >= limit) {
            throw new BizException(BizCode.FORBIDDEN, "今日发布已达上限(" + limit + "条，物品+帖子合计)，明天再来", 429);
        }
    }

    /** 图片 URL 必须是本站 /uploads 直出（外链 400；与 M3 同纪律） */
    private void assertImageUrls(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String u : urls) {
            if (u == null || !u.startsWith("/uploads/")) {
                throw new BizException(BizCode.PARAM_ERROR, "图片必须先经本站上传接口，不接受外链");
            }
        }
    }

    private void saveImages(Long postId, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        int i = 0;
        for (String u : urls) {
            PostImage img = new PostImage();
            img.setPostId(postId);
            img.setUrl(u);
            img.setSortOrder(i++);
            img.setCreatedAt(LocalDateTime.now());
            img.setDeleted(0);
            postImageMapper.insert(img);
        }
    }

    private boolean isAuditOn() {
        return readIntConfig("forum_audit", 1) == 1;
    }

    private boolean canGovern(LoginUser me) {
        return me != null && (me.getPerms().contains("forum:audit") || me.getPerms().contains("forum:offline"));
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

    private String shorten(String s) {
        return s == null ? null : (s.length() <= 120 ? s : s.substring(0, 120) + "…");
    }

    private void notify(Long userId, String bizType, Long bizId, String title, String content) {
        if (userId == null) {
            return;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setTitle(title);
        n.setContent(content);
        n.setReadFlag(0);
        n.setCreatedAt(LocalDateTime.now());
        n.setDeleted(0);
        notificationMapper.insert(n);
    }
}
