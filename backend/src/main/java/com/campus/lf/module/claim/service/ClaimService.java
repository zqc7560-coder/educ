package com.campus.lf.module.claim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.notice.entity.Notification;
import com.campus.lf.module.notice.mapper.NotificationMapper;
import com.campus.lf.module.claim.dto.ClaimApplyDTO;
import com.campus.lf.module.claim.entity.ClaimLog;
import com.campus.lf.module.claim.entity.ItemClaim;
import com.campus.lf.module.claim.mapper.ClaimLogMapper;
import com.campus.lf.module.claim.mapper.ItemClaimMapper;
import com.campus.lf.module.claim.vo.ClaimVO;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemService;
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
 * 认领流程服务（M4 核心）—— 本系统的灵魂状态机。
 *
 * <p>认领单状态机（item_claim.status）：
 * <pre>
 *   0 待核验 ──(发布者通过)──→ 1 约定交接 ──(发布者确认归还)──→ 2 已完成
 *      │  ▲                        │
 *      │  └──(申请人补充/重申请另开单)  │
 *      ├──(发布者拒绝,理由必填)──→ 3 已拒绝
 *      ├──(任一方申请仲裁)──→ 4 仲裁中 ──(管理员裁决)──→ 2 或 3
 *      └──(该物品被他人先确认归还)──→ 5 已关闭（批量，actor=null 系统动作）
 * </pre>
 *
 * <p>三条关键并发/正确性纪律：
 * <ol>
 *   <li><b>先到先得防重复归还</b>：确认归还用乐观锁（@Version）——同一物品多个在途
 *       申请，发布者对 A 点了"确认归还"，物品即下架"已归还"，此时对 B 的在途单再
 *       确认会因物品状态前置校验失败（2005）；B 的单由 closeOtherClaims 批量置 5，
 *       防止"两人都以为拿到了失物"。</li>
 *   <li><b>uk(item_id, claimant_id)</b>：同一申请人对同一物品只能有一张单（3003），
 *       被拒后想再申请需先撤或走仲裁——避免刷屏式重复申请淹没发布者。</li>
 *   <li><b>claim_log append-only</b>：每一步（申请/通过/拒绝/确认/仲裁申请/裁决/
 *       关闭/超时提醒）都留痕，actor=null 表示系统自动动作。这是仲裁裁决与
 *       答辩演示"可审计"的事实源。</li>
 * </ol>
 *
 * <p>模块边界：claim 单向依赖 item（读物品、改物品状态），item 绝不 import claim——
 * 物品被删时认领单的失效由本域读物品时按 2001 处置，不在 item 删除路径里反向结算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimService {

    public static final int ST_PENDING = 0;     // 待核验
    public static final int ST_AGREED = 1;      // 约定交接（核验通过）
    public static final int ST_RETURNED = 2;    // 已完成（确认归还）
    public static final int ST_REJECTED = 3;    // 已拒绝
    public static final int ST_ARBITRATING = 4; // 仲裁中
    public static final int ST_CLOSED = 5;      // 已关闭（他人先归还，批量）

    /** 在途状态（未落定）：可被"他人归还"批量关闭的集合 */
    private static final Set<Integer> LIVE_STATES = Set.of(ST_PENDING, ST_AGREED, ST_ARBITRATING);

    private final ItemClaimMapper claimMapper;
    private final ClaimLogMapper logMapper;
    private final LostItemMapper itemMapper;
    private final SysUserMapper userMapper;
    private final NotificationMapper notificationMapper;

    // ==================================================================
    // 申请
    // ==================================================================

    /**
     * 提交认领申请（POST /api/items/{id}/claims，登录）。
     *
     * <p>闸门链：禁言回查 → 物品必须 FOUND 且已发布 → 不能认领自己发布的物品 →
     * uk 同人重复拒（3003）。LOST（寻物启事）不接受"认领"——失主看到自己的
     * 寻物帖点认领语义荒谬，寻物的匹配走"联系发布者"通道（详情里的 contact）。
     */
    @Transactional
    public Long apply(LoginUser me, Long itemId, ClaimApplyDTO dto) {
        assertWritable(me.getUserId());
        LostItem item = requirePublishedFound(itemId);
        if (item.getUserId().equals(me.getUserId())) {
            throw new BizException(BizCode.CLAIM_NOT_PARTICIPANT, "不能认领自己发布的物品", 403);
        }
        if (claimMapper.selectCount(new LambdaQueryWrapper<ItemClaim>()
                .eq(ItemClaim::getItemId, itemId)
                .eq(ItemClaim::getClaimantId, me.getUserId())) > 0) {
            throw new BizException(BizCode.CLAIM_DUPLICATE);
        }

        ItemClaim claim = new ItemClaim();
        claim.setItemId(itemId);
        claim.setClaimantId(me.getUserId());
        claim.setAnswer(dto.getAnswer().trim());
        claim.setEvidenceNote(dto.getEvidenceNote() == null ? null : dto.getEvidenceNote().trim());
        claim.setStatus(ST_PENDING);
        claim.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        claimMapper.insert(claim);

        // 冗余计数 +1（物品详情页展示"N 人申请"）
        itemMapper.update(null, new LambdaUpdateWrapper<LostItem>()
                .eq(LostItem::getId, itemId).setSql("claim_count = claim_count + 1"));
        writeLog(claim.getId(), me.getUserId(), "APPLY", null);
        notify(item.getUserId(), "CLAIM_NEW", itemId, "有新的认领申请",
                "你发布的「" + item.getTitle() + "」收到一条认领申请，请核验答案。");
        log.info("用户 {} 申请认领物品 {} (claim={})", me.getUserId(), itemId, claim.getId());
        return claim.getId();
    }

    // ==================================================================
    // 核验（发布者）
    // ==================================================================

    /** 通过核验（0→1 约定交接）；reason 可选（交接备注）。 */
    @Transactional
    public void verifyPass(LoginUser me, Long claimId, String reason) {
        ItemClaim claim = requireClaim(claimId);
        LostItem item = requireItem(claim.getItemId());
        assertPublisher(me, item);
        if (claim.getStatus() != ST_PENDING) {
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "仅待核验的申请可通过");
        }
        ItemClaim patch = new ItemClaim();
        patch.setId(claimId);
        patch.setVersion(claim.getVersion());
        patch.setStatus(ST_AGREED);
        patch.setHandledAt(LocalDateTime.now());
        if (claimMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        writeLog(claimId, me.getUserId(), "VERIFY_PASS", reason);
        notify(claim.getClaimantId(), "CLAIM_RESULT", claim.getItemId(),
                "认领申请已通过", "发布者同意了你的认领，请与对方联系约定交接。");
    }

    /** 拒绝核验（0→3 或 4→3）；理由必填。 */
    @Transactional
    public void verifyReject(LoginUser me, Long claimId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(BizCode.PARAM_ERROR, "拒绝必须填写理由");
        }
        ItemClaim claim = requireClaim(claimId);
        LostItem item = requireItem(claim.getItemId());
        assertPublisher(me, item);
        if (claim.getStatus() != ST_PENDING && claim.getStatus() != ST_ARBITRATING) {
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "当前状态不允许拒绝");
        }
        ItemClaim patch = new ItemClaim();
        patch.setId(claimId);
        patch.setVersion(claim.getVersion());
        patch.setStatus(ST_REJECTED);
        patch.setRejectReason(reason.trim());
        patch.setHandledAt(LocalDateTime.now());
        if (claimMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        writeLog(claimId, me.getUserId(), "VERIFY_REJECT", reason.trim());
        notify(claim.getClaimantId(), "CLAIM_RESULT", claim.getItemId(),
                "认领申请被拒绝", "发布者拒绝了你的认领：" + reason.trim());
    }

    /**
     * 确认归还（1→2 已完成，本状态机的终点动作）。
     *
     * <p>三重结算（同一事务）：① 认领单置 2（乐观锁）；② 物品置下架"已归还"；
     * ③ 该物品其余在途单批量置 5 关闭——先到先得防重复归还，其余申请人收通知。
     */
    @Transactional
    public void confirmReturn(LoginUser me, Long claimId) {
        ItemClaim claim = requireClaim(claimId);
        LostItem item = requireItem(claim.getItemId());
        assertPublisher(me, item);
        if (claim.getStatus() != ST_AGREED && claim.getStatus() != ST_PENDING
                && claim.getStatus() != ST_ARBITRATING) {
            // 允许发布者越过"约定交接"直接确认（线下已交接的常态），但不允许对已拒绝/已完成/已关闭再确认
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "该认领单当前状态不能确认归还");
        }
        if (item.getStatus() == ItemService.STATUS_OFFLINE && "已归还".equals(item.getOfflineReason())) {
            throw new BizException(BizCode.CLAIM_ALREADY_SETTLED);   // 物品已被判定归还（防御，正常走不到——closeOtherClaims 已置 5）
        }
        ItemClaim patch = new ItemClaim();
        patch.setId(claimId);
        patch.setVersion(claim.getVersion());
        patch.setStatus(ST_RETURNED);
        patch.setHandledAt(LocalDateTime.now());
        if (claimMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.CLAIM_ALREADY_SETTLED);
        }

        // 物品下架=已归还
        LostItem itemPatch = new LostItem();
        itemPatch.setId(item.getId());
        itemPatch.setStatus(ItemService.STATUS_OFFLINE);
        itemPatch.setOfflineReason("已归还");
        itemMapper.updateById(itemPatch);

        writeLog(claimId, me.getUserId(), "CONFIRM_RETURN", null);
        notify(claim.getClaimantId(), "RETURN_CONFIRM", item.getId(),
                "认领已完成", "「" + item.getTitle() + "」已确认归还，感谢使用失物招领。");

        int closed = closeOtherClaims(item.getId(), claimId, me.getUserId());
        log.info("认领单 {} 确认归还，物品 {} 下架，连带关闭其余在途单 {} 张",
                claimId, item.getId(), closed);
    }

    /** 批量关闭某物品除 keepId 外的在途单（系统动作 actor=null），返回关闭数。 */
    private int closeOtherClaims(Long itemId, Long keepId, Long actorId) {
        List<ItemClaim> others = claimMapper.selectList(new LambdaQueryWrapper<ItemClaim>()
                .eq(ItemClaim::getItemId, itemId)
                .ne(ItemClaim::getId, keepId)
                .in(ItemClaim::getStatus, LIVE_STATES));
        for (ItemClaim c : others) {
            ItemClaim patch = new ItemClaim();
            patch.setId(c.getId());
            patch.setStatus(ST_CLOSED);
            patch.setHandledAt(LocalDateTime.now());
            claimMapper.updateById(patch);
            writeLog(c.getId(), null, "CLOSED_BY_RETURN", null);
            notify(c.getClaimantId(), "CLAIM_RESULT", itemId, "认领已关闭",
                    "该物品已被他人确认归还，你的认领申请自动关闭。");
        }
        return others.size();
    }

    // ==================================================================
    // 仲裁
    // ==================================================================

    /** 申请仲裁（0/1→4）：申请人或发布者任一方发起，进管理员裁决队列。 */
    @Transactional
    public void applyArbitration(LoginUser me, Long claimId) {
        ItemClaim claim = requireClaim(claimId);
        LostItem item = requireItem(claim.getItemId());
        boolean party = claim.getClaimantId().equals(me.getUserId())
                || item.getUserId().equals(me.getUserId());
        if (!party) {
            throw new BizException(BizCode.CLAIM_NOT_PARTICIPANT);
        }
        if (claim.getStatus() != ST_PENDING && claim.getStatus() != ST_AGREED
                && claim.getStatus() != ST_REJECTED) {
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "当前状态不能申请仲裁");
        }
        ItemClaim patch = new ItemClaim();
        patch.setId(claimId);
        patch.setVersion(claim.getVersion());
        patch.setStatus(ST_ARBITRATING);
        if (claimMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        writeLog(claimId, me.getUserId(), "ARBITRATE_APPLY", null);
        log.info("认领单 {} 进入仲裁（申请方 uid={}）", claimId, me.getUserId());
    }

    /** 管理员裁决（4→2 或 4→3）：归属判给申请人(2)+物品下架，或拒绝认领(3)。 */
    @Transactional
    public void arbitrate(LoginUser me, Long claimId, boolean awardToClaimant, String judgment) {
        if (!me.getPerms().contains("claim:arbitrate")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
        if (judgment == null || judgment.isBlank()) {
            throw new BizException(BizCode.PARAM_ERROR, "裁决理由必填");
        }
        ItemClaim claim = requireClaim(claimId);
        if (claim.getStatus() != ST_ARBITRATING) {
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "该单不在仲裁中");
        }
        ItemClaim patch = new ItemClaim();
        patch.setId(claimId);
        patch.setVersion(claim.getVersion());
        patch.setArbitratedBy(me.getUserId());
        patch.setHandledAt(LocalDateTime.now());
        if (awardToClaimant) {
            patch.setStatus(ST_RETURNED);
            if (claimMapper.updateById(patch) == 0) {
                throw new BizException(BizCode.VERSION_CONFLICT);
            }
            LostItem item = requireItem(claim.getItemId());
            LostItem itemPatch = new LostItem();
            itemPatch.setId(item.getId());
            itemPatch.setStatus(ItemService.STATUS_OFFLINE);
            itemPatch.setOfflineReason("仲裁判归");
            itemMapper.updateById(itemPatch);
            closeOtherClaims(item.getId(), claimId, me.getUserId());
        } else {
            patch.setStatus(ST_REJECTED);
            patch.setRejectReason(judgment.trim());
            if (claimMapper.updateById(patch) == 0) {
                throw new BizException(BizCode.VERSION_CONFLICT);
            }
        }
        writeLog(claimId, me.getUserId(), "ARBITRATE_JUDGE", judgment.trim());
        notify(claim.getClaimantId(), "CLAIM_RESULT", claim.getItemId(),
                "仲裁结果", "管理员裁决：" + judgment.trim());
        log.info("认领单 {} 仲裁裁决 判归={} by 管理员 {}", claimId, awardToClaimant, me.getUserId());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 某物品的认领单列表（发布者核验视图 / 管理员仲裁视图）：answer 对发布者可见。 */
    public List<ClaimVO> listForItem(LoginUser me, Long itemId) {
        LostItem item = requireItem(itemId);
        boolean publisher = item.getUserId().equals(me.getUserId());
        boolean auditor = me.getPerms().contains("claim:arbitrate");
        if (!publisher && !auditor) {
            throw new BizException(BizCode.CLAIM_NOT_PARTICIPANT, BizCode.CLAIM_NOT_PARTICIPANT.getMessage(), 403);
        }
        List<ItemClaim> claims = claimMapper.selectList(new LambdaQueryWrapper<ItemClaim>()
                .eq(ItemClaim::getItemId, itemId).orderByDesc(ItemClaim::getId));
        return claims.stream().map(c -> toVO(c, publisher || auditor)).toList();
    }

    /** 我的认领（申请人视角，全状态对自己透明）。 */
    public PageResult<ClaimVO> pageMine(LoginUser me, long pageNum, long pageSize) {
        Page<ItemClaim> page = claimMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ItemClaim>()
                        .eq(ItemClaim::getClaimantId, me.getUserId())
                        .orderByDesc(ItemClaim::getId));
        Set<Long> userIds = page.getRecords().stream().map(ItemClaim::getClaimantId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> nicks = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname));
        List<ClaimVO> list = page.getRecords().stream().map(c -> ClaimVO.builder()
                .id(c.getId()).itemId(c.getItemId()).claimantId(c.getClaimantId())
                .claimantNickname(nicks.get(c.getClaimantId()))
                .answer(c.getAnswer()).evidenceNote(c.getEvidenceNote())
                .status(c.getStatus()).rejectReason(c.getRejectReason())
                .handledAt(c.getHandledAt()).createdAt(c.getCreatedAt()).version(c.getVersion())
                .build()).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), list);
    }

    /** 待仲裁队列（claim:arbitrate）：status=4 全量分页。 */
    public PageResult<ClaimVO> pageArbitration(long pageNum, long pageSize) {
        Page<ItemClaim> page = claimMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ItemClaim>()
                        .eq(ItemClaim::getStatus, ST_ARBITRATING)
                        .orderByDesc(ItemClaim::getId));
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(c -> toVO(c, true)).toList());
    }

    // ==================================================================
    // 超时提醒（定时任务调用，claim_timeout_hours 阈值）
    // ==================================================================

    /** 扫超时未处理的待核验单（status=0 且 created_at < now-N 小时），提醒发布者。 */
    @Transactional
    public int remindTimeout(int hours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(hours);
        List<ItemClaim> stale = claimMapper.selectList(new LambdaQueryWrapper<ItemClaim>()
                .eq(ItemClaim::getStatus, ST_PENDING)
                .lt(ItemClaim::getCreatedAt, cutoff));
        for (ItemClaim c : stale) {
            LostItem item = itemMapper.selectById(c.getItemId());
            if (item == null) {
                continue;
            }
            writeLog(c.getId(), null, "TIMEOUT_REMIND", null);
            notify(item.getUserId(), "SYSTEM", c.getItemId(), "认领申请待处理",
                    "「" + item.getTitle() + "」有认领申请超过 " + hours + " 小时未核验，请及时处理。");
        }
        return stale.size();
    }

    // ==================================================================
    // 装配与闸门
    // ==================================================================

    private ClaimVO toVO(ItemClaim c, boolean canSeeAnswer) {
        SysUser claimant = userMapper.selectById(c.getClaimantId());
        return ClaimVO.builder()
                .id(c.getId()).itemId(c.getItemId()).claimantId(c.getClaimantId())
                .claimantNickname(claimant == null ? null : claimant.getNickname())
                .answer(canSeeAnswer ? c.getAnswer() : null)
                .evidenceNote(canSeeAnswer ? c.getEvidenceNote() : null)
                .status(c.getStatus()).rejectReason(c.getRejectReason())
                .handledAt(c.getHandledAt()).createdAt(c.getCreatedAt()).version(c.getVersion())
                .build();
    }

    private ItemClaim requireClaim(Long id) {
        ItemClaim c = claimMapper.selectById(id);
        if (c == null) {
            throw new BizException(BizCode.CLAIM_NOT_FOUND, BizCode.CLAIM_NOT_FOUND.getMessage(), 404);
        }
        return c;
    }

    private LostItem requireItem(Long id) {
        LostItem item = itemMapper.selectById(id);
        if (item == null) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, BizCode.ITEM_NOT_FOUND.getMessage(), 404);
        }
        return item;
    }

    private LostItem requirePublishedFound(Long itemId) {
        LostItem item = requireItem(itemId);
        if (item.getStatus() != ItemService.STATUS_PUBLISHED) {
            throw new BizException(BizCode.ITEM_NOT_FOUND);   // 非发布态按不存在处理（防枚举）
        }
        if (!"FOUND".equals(item.getType())) {
            throw new BizException(BizCode.CLAIM_STATUS_INVALID, "寻物启事不支持认领，请直接联系发布者");
        }
        return item;
    }

    /** 发布者校验：只有物品发布者可核验/确认（管理员走仲裁口，不越俎代庖） */
    private void assertPublisher(LoginUser me, LostItem item) {
        if (!item.getUserId().equals(me.getUserId())) {
            throw new BizException(BizCode.CLAIM_NOT_PARTICIPANT, "仅物品发布者可核验认领", 403);
        }
    }

    private void assertWritable(Long userId) {
        SysUser db = userMapper.selectById(userId);
        if (db == null || !UserStatus.canWrite(db.getStatus())) {
            throw new BizException(BizCode.ACCOUNT_DISABLED, "账号被限制发言", 403);
        }
    }

    private void writeLog(Long claimId, Long actorId, String action, String detail) {
        ClaimLog log = new ClaimLog();
        log.setClaimId(claimId);
        log.setActorId(actorId);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    /** 站内消息（M7 前先用最简落库，M7 补齐未读计数/分页/红点） */
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
