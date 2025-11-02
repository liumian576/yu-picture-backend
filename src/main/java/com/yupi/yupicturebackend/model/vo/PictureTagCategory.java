package com.yupi.yupicturebackend.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 标签列表、分类列表的视图, 用于返回给前端, 放到 model.vo 中
 */
@Data
public class PictureTagCategory {
    /**
     * 标签列表
     */
    private List<String> tagList;

    /**
     * 分类列表
     */
    private List<String> categoryList;
}
