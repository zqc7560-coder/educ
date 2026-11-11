package com.campus.lf.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.admin.SensitiveWordGate;
import com.campus.lf.module.admin.entity.SensitiveWord;
import com.campus.lf.module.admin.mapper.SensitiveWordMapper;
import com.campus.lf.module.claim.service.ClaimService;
import com.campus.lf.module.forum.entity.ForumReply;
import com.campus.lf.module.forum.mapper.ForumReplyMapper;
import com.campus.lf.module.notice.service.NotificationService;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端治理服务（M8）：审核队列三合一、回复屏蔽、词库 CRUD、参数白名单、看板。
 *
 * <p>三队列合一的呈现方式：GET /api/admin/audit-queue 返回"物品待审/帖子待审/
 * 回复待审"三组分页合一的聚合响应（各取前 N，总数透出）——治理台一屏看全，
 * 处置动作仍回各业务域自己的状态机口（items/{id}/status、forum/posts/{id}/status），
 * 队列口只读不写：避免"审核动作有两套实现"的漂移。
 *
 * <p>回复（forum_reply）没有独立治理状态机的简化：屏蔽=直接 status=2（读者侧
 * 过滤隐藏），复用 forum:audit 权限（回复治理不需要独立权限点——粒度到"论坛域"
 * 已经够用，见开发方案权限矩阵）。计数不变式：reply_count=本帖未删回复数，
 * 屏蔽不删行所以不动计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGovernService {

    private final ClaimService claimService;
    private final NotificationService notificationService;
    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordGate wordGate;
    private final ForumReplyMapper replyMapper;
    private final com.campus.lf.module.item.mapper.LostItemMapper itemMapper;
    private final com.campus.lf.module.forum.mapper.ForumPostMapper postMapper;
    private final com.campus.lf.module.admin.mapper.SysConfigMapper configMapper;
    private final OperationLogService opLog;

    // ==================================================================
    // 审核队列三合一（只读聚合；处置回各域口）
    // ==================================================================

    public Map<String, Object> auditQueue(LoginUser me, int size) {
        assertAny(me, "item:audit", "forum:audit");
        int n = Math.min(Math.max(size, 1), 50);
        Map<String, Object> out = new HashMap<>();
        out.put("items", pagePendingItems(1, n));
        out.put("posts", pagePendingPosts(1, n));
        out.put("replies", pagePendingReplies(1, n));
        out.put("claims", claimService.pageArbitration(1, n));
        return out;
    }

    /** 物品待审队列（直接 mapper 组简化视图——ItemService.pageItems 口径恒为已发布，不混用） */
    private PageResult<Map<String, Object>> pagePendingItems(long pageNum, long pageSize) {
        var page = itemMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                        .eq(com.campus.lf.module.item.entity.LostItem::getStatus, 0)
                        .orderByAsc(com.campus.lf.module.item.entity.LostItem::getId));
        List<Map<String, Object>> list = page.getRecords().stream().map(i -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", i.getId());
            m.put("type", i.getType());
            m.put("title", i.getTitle());
            m.put("userId", i.getUserId());
            m.put("createdAt", i.getCreatedAt());
            return m;
        }).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), list);
    }

    /** 帖子待审队列 */
    private PageResult<Map<String, Object>> pagePendingPosts(long pageNum, long pageSize) {
        var page = postMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<com.campus.lf.module.forum.entity.ForumPost>()
                        .eq(com.campus.lf.module.forum.entity.ForumPost::getStatus, 0)
                        .orderByAsc(com.campus.lf.module.forum.entity.ForumPost::getId));
        List<Map<String, Object>> list = page.getRecords().stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("boardId", p.getBoardId());
            m.put("title", p.getTitle());
            m.put("userId", p.getUserId());
            m.put("createdAt", p.getCreatedAt());
            return m;
        }).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), list);
    }

    /** 回复审核队列：status=0 待审（M5 起回复也进先审后发） */
    public PageResult<Map<String, Object>> pagePendingReplies(long pageNum, long pageSize) {
        Page<ForumReply> page = replyMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ForumReply>()
                        .eq(ForumReply::getStatus, 0).orderByDesc(ForumReply::getId));
        List<Map<String, Object>> list = page.getRecords().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("postId", r.getPostId());
            m.put("userId", r.getUserId());
            m.put("content", r.getContent());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), list);
    }

    /** 回复处置：1=通过 2=屏蔽（理由必填走 detail 日志+通知作者） */
    @Transactional
    public void handleReply(LoginUser me, Long id, int target, String reason) {
        assertAny(me, "forum:audit");
        if (target != 1 && target != 2) {
            throw new BizException(BizCode.PARAM_ERROR, "回复处置仅支持 1通过/2屏蔽");
        }
        if (target == 2 && (reason == null || reason.isBlank())) {
            throw new BizException(BizCode.PARAM_ERROR, "屏蔽必须填写理由");
        }
        ForumReply r = replyMapper.selectById(id);
        if (r == null || r.getStatus() != 0) {
            throw new BizException(BizCode.PARAM_ERROR, "回复不存在或不在待审");
        }
        ForumReply patch = new ForumReply();
        patch.setId(id);
        patch.setStatus(target);
        replyMapper.updateById(patch);
        // 计数不变式（M5 口径）：reply_count=本帖未删回复数，提交时已 +1——
        // 审核动作不再碰计数，否则通过一笔就多算一次（M8 评审自查纠正的初版缺陷）
        if (target == 1) {
            opLog.record(me.getUserId(), OperationLogService.POST_AUDIT_PASS, "reply:" + id, null);
        } else {
            opLog.record(me.getUserId(), OperationLogService.REPLY_BLOCK, "reply:" + id, reason.trim());
        }
        notificationService.push(r.getUserId(),
                target == 1 ? com.campus.lf.module.notice.NotifyType.POST_AUDIT
                        : com.campus.lf.module.notice.NotifyType.POST_AUDIT,
                r.getPostId(),
                target == 1 ? "回复已通过审核" : "回复被屏蔽",
                target == 1 ? "你在论坛的回复已展示。" : "你的回复被管理员屏蔽：" + reason.trim());
    }

    // ==================================================================
    // 敏感词库 CRUD（word:manage；写后 refresh 让拦截即时生效）
    // ==================================================================

    public PageResult<SensitiveWord> pageWords(LoginUser me, String keyword, long pageNum, long pageSize) {
        assertAny(me, "word:manage");
        LambdaQueryWrapper<SensitiveWord> w = new LambdaQueryWrapper<SensitiveWord>()
                .orderByDesc(SensitiveWord::getId);
        if (keyword != null && !keyword.isBlank()) {
            w.like(SensitiveWord::getWord, keyword.trim());
        }
        Page<SensitiveWord> page = wordMapper.selectPage(new Page<>(pageNum, pageSize), w);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords());
    }

    @Transactional
    public Long addWord(LoginUser me, String word) {
        assertAny(me, "word:manage");
        String w = word == null ? "" : word.trim();
        if (w.isEmpty() || w.length() > 50) {
            throw new BizException(BizCode.PARAM_ERROR, "敏感词需为 1–50 字符");
        }
        if (wordMapper.selectCount(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getWord, w)) > 0) {
            throw new BizException(BizCode.PARAM_ERROR, "该词已在词库中");
        }
        SensitiveWord e = new SensitiveWord();
        e.setWord(w);
        e.setCreatedAt(LocalDateTime.now());
        e.setDeleted(0);
        wordMapper.insert(e);
        wordGate.refresh();
        opLog.record(me.getUserId(), OperationLogService.WORD_ADD, "word:" + e.getId(), w);
        return e.getId();
    }

    @Transactional
    public void deleteWord(LoginUser me, Long id) {
        assertAny(me, "word:manage");
        wordMapper.deleteById(id);   // 词表逻辑删（有 @TableLogic）
        wordGate.refresh();
        opLog.record(me.getUserId(), OperationLogService.WORD_DELETE, "word:" + id, null);
    }

    // ==================================================================
    // 系统参数（config:manage；白名单键 + editable 红线）
    // ==================================================================

    public List<Map<String, Object>> listConfigs(LoginUser me) {
        assertAny(me, "config:manage");
        return configMapper.selectList(new LambdaQueryWrapper<com.campus.lf.module.admin.entity.SysConfig>()
                        .orderByAsc(com.campus.lf.module.admin.entity.SysConfig::getId))
                .stream().map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("key", c.getConfigKey());
                    m.put("value", c.getConfigValue());
                    m.put("type", c.getConfigType());
                    m.put("description", c.getDescription());
                    m.put("editable", c.getEditable() == 1);
                    return m;
                }).toList();
    }

    /** 改值：editable=0 拒；BOOL 只收 0/1；NUMBER 必须整数（类型闸防把开关改成"true"） */
    @Transactional
    public void updateConfig(LoginUser me, String key, String value) {
        assertAny(me, "config:manage");
        var row = configMapper.selectOne(
                new LambdaQueryWrapper<com.campus.lf.module.admin.entity.SysConfig>()
                        .eq(com.campus.lf.module.admin.entity.SysConfig::getConfigKey, key));
        if (row == null) {
            throw new BizException(BizCode.PARAM_ERROR, "参数不存在");
        }
        if (row.getEditable() != 1) {
            throw new BizException(BizCode.FORBIDDEN, "该参数不可在线修改", 403);
        }
        String v = value == null ? "" : value.trim();
        switch (row.getConfigType()) {
            case "BOOL" -> {
                if (!v.equals("0") && !v.equals("1")) {
                    throw new BizException(BizCode.PARAM_ERROR, "BOOL 参数只接受 0/1");
                }
            }
            case "NUMBER" -> {
                try {
                    if (Integer.parseInt(v) < 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new BizException(BizCode.PARAM_ERROR, "NUMBER 参数必须是非负整数");
                }
            }
            default -> {
                if (v.isEmpty() || v.length() > 200) {
                    throw new BizException(BizCode.PARAM_ERROR, "STRING 参数需 1–200 字符");
                }
            }
        }
        row.setConfigValue(v);
        configMapper.updateById(row);
        opLog.record(me.getUserId(), OperationLogService.CONFIG_UPDATE, key, v);
        log.info("参数更新 {}={} by {}", key, v, me.getUserId());
    }

    // ==================================================================
    // 闸门
    // ==================================================================

    private void assertAny(LoginUser me, String... perms) {
        for (String p : perms) {
            if (me.getPerms().contains(p)) {
                return;
            }
        }
        throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
    }
}
