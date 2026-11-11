package com.campus.lf.module.item.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.Result;
import com.campus.lf.module.item.entity.CampusArea;
import com.campus.lf.module.item.entity.Category;
import com.campus.lf.module.item.mapper.CampusAreaMapper;
import com.campus.lf.module.item.mapper.CategoryMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 公开字典接口（M3）：发布表单的分类/校区楼栋下拉数据源。
 * 只读、游客可见（SecurityConfig GET 白名单），写口治理归 M8 分类管理。
 */
@Tag(name = "字典", description = "分类与校区楼栋（公开只读）")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DictController {

    private final CategoryMapper categoryMapper;
    private final CampusAreaMapper areaMapper;

    /** GET /api/categories?kind=ITEM|BOARD：字典按用途隔离（uk 含 kind 的读侧镜像） */
    @Operation(summary = "分类/板块字典（sort_order 升序）")
    @GetMapping("/categories")
    public Result<List<Category>> categories(@RequestParam(defaultValue = "ITEM") String kind) {
        return Result.ok(categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getKind, kind)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId)));
    }

    /** GET /api/areas：校区+楼栋平铺（前端按 parentId 组树——两级字典组树成本在前端一行 filter） */
    @Operation(summary = "校区楼栋平铺列表")
    @GetMapping("/areas")
    public Result<List<Map<String, Object>>> areas() {
        List<CampusArea> all = areaMapper.selectList(new LambdaQueryWrapper<CampusArea>()
                .orderByAsc(CampusArea::getLevel)
                .orderByAsc(CampusArea::getSortOrder)
                .orderByAsc(CampusArea::getId));
        return Result.ok(all.stream().map(a -> Map.<String, Object>of(
                "id", a.getId(), "name", a.getName(),
                "level", a.getLevel(), "parentId", a.getParentId() == null ? 0L : a.getParentId()
        )).toList());
    }
}
