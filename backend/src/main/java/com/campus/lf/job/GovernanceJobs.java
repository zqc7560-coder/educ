package com.campus.lf.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.lf.module.announce.entity.Announcement;
import com.campus.lf.module.announce.mapper.AnnouncementMapper;
import com.campus.lf.module.announce.service.AnnouncementService;
import com.campus.lf.module.claim.service.ClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 治理节拍任务（M9 收口 M4/M6 预留的两个调度键）。
 *
 * <p>ClaimReminder：每小时扫超过 claim_timeout_hours 未核验的认领单，
 * 提醒发布者（M4 的 remindTimeout 纯函数早已就位，缺的就是这副节拍器）。
 *
 * <p>AnnouncementExpire：把 expire_at 已过但仍挂"已发布"的公告主动落 2。
 * M6 的读侧懒下架保证了读者永远看不到过期件，但 DB 状态会一直挂着 1——
 * 管理台列表与统计口径都想要"状态即事实"，所以调度兜底主动收敛
 * （懒执行管正确性，定时管状态卫生，两轨并行不冲突：transition 走的是
 * 同一条 PUBLISHED→OFFLINE 合法迁移）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GovernanceJobs {

    private final ClaimService claimService;
    private final AnnouncementMapper annMapper;
    private final com.campus.lf.module.admin.mapper.SysConfigMapper configMapper;

    @Scheduled(initialDelayString = "${lf.claim.remind-initial-delay-ms:60000}",
               fixedDelayString = "${lf.claim.remind-interval-ms:3600000}")
    public void claimReminder() {
        try {
            int hours = readClaimTimeout();
            int n = claimService.remindTimeout(hours);
            if (n > 0) {
                log.info("认领超时提醒：{} 单（阈值 {}h）", n, hours);
            }
        } catch (Exception e) {
            log.error("认领提醒任务异常（下轮重试）", e);
        }
    }

    @Scheduled(initialDelayString = "${lf.announcement.expire-initial-delay-ms:90000}",
               fixedDelayString = "${lf.announcement.expire-interval-ms:3600000}")
    public void announcementExpireSweep() {
        try {
            int n = annMapper.update(null, new LambdaUpdateWrapper<Announcement>()
                    .eq(Announcement::getStatus, AnnouncementService.STATUS_PUBLISHED)
                    .isNotNull(Announcement::getExpireAt)
                    .le(Announcement::getExpireAt, LocalDateTime.now())
                    .set(Announcement::getStatus, AnnouncementService.STATUS_OFFLINE));
            if (n > 0) {
                log.info("公告过期兜底下架 {} 条", n);
            }
        } catch (Exception e) {
            log.error("公告下架清扫异常（下轮重试）", e);
        }
    }

    private int readClaimTimeout() {
        // claim_timeout_hours 是管理员可调参数（M8 白名单键）：每轮现读，非法/缺失兜底 72
        try {
            var row = configMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                            com.campus.lf.module.admin.entity.SysConfig>()
                            .eq(com.campus.lf.module.admin.entity.SysConfig::getConfigKey,
                                    "claim_timeout_hours"));
            return row == null ? 72 : Math.max(Integer.parseInt(row.getConfigValue()), 1);
        } catch (Exception e) {
            return 72;
        }
    }
}
