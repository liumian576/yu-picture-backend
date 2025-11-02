package com.yupi.yupicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.manager.FileManager;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import com.yupi.yupicturebackend.model.dto.picture.PictureQueryRequest;
import com.yupi.yupicturebackend.model.dto.picture.PictureUploadRequest;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.mapper.PictureMapper;
import com.yupi.yupicturebackend.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
* @author cheramvb
* @description 针对表【picture(图片)】的数据库操作Service实现
* @createDate 2025-11-02 00:50:17
*/
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
    implements PictureService{

    // 6. 引入 FileManager 对象
    @Resource
    private FileManager fileManager;
    //引入用户服务
    @Resource
    private UserService userService;

    /**
     * 验证数据
     *
     * @param picture
     */
    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        // 如果传递了 url，才校验
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    /**
     * 上传更新图片
     *
     * @param multipartFile
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    @Override
    public PictureVO uploadPicture(MultipartFile multipartFile, PictureUploadRequest pictureUploadRequest, User loginUser) {
        // 1. 校验参数, 用户未登录, 抛出没有权限的异常
        ThrowUtils.throwIf(loginUser == null , ErrorCode.NO_AUTH_ERROR);

        // 2. 判断是新增图片, 还是更新图片, 所以先判断图片是否存在
        Long pictureId = null;
        if(pictureUploadRequest != null){
            // 3. 如果传入的请求不为空, 才获取请求中的图片 ID
            pictureId = pictureUploadRequest.getId();
        }

        // 4. 图片 ID 不为空, 查数据库中是否有对应的图片 ID
        if(pictureId != null){
            boolean exists = this.lambdaQuery()
                    .eq(Picture::getId, pictureId)
                    .exists();
            // 5. 如果数据库中没有图片, 则抛异常, 因为这是更新图片的接口
            ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }

        // 7. 定义上传文件的前缀 public/登录用户 ID
        String uploadPathPrefix = String.format("public/%s", loginUser.getId());
        // 根据用户划分前缀, 当前的图片文件上传到公共图库, 因此前缀定义为 public

        // 8. 上传图片, 上传图片 API 需要的参数(原始文件 + 文件前缀), 获取上传文件结果对象,
        UploadPictureResult uploadPictureResult = fileManager.uploadPicture(multipartFile, uploadPathPrefix);

        // 9. 构造要入库的图片信息(样板代码)
        Picture picture = new Picture();
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setName(uploadPictureResult.getPicName());
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        // 从当前登录用户中获取 userId
        picture.setUserId(loginUser.getId());

        // 10. 操作数据库, 如果 pictureId 不为空, 表示更新图片, 否则为新增图片
        if(pictureId!=null){
            // 11. 如果是更新, 需要补充 id 和编辑时间
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }

        // 12. 利用 MyBatis 框架的 API，根据实体对象 picture 是否存在 ID 值, 来决定是执行插入操作还是更新操作
        boolean result = this.saveOrUpdate(picture);

        // 13. result 返回 false, 表示数据库不存在该图片, 不能调用图片上传(更新)接口
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败, 数据库操作失败");

        // 14. 对数据进行脱敏, 并返回
        return PictureVO.objToVo(picture);
    }

    /**
     * 获取查询条件
     *
     * @param pictureQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值 pictureQueryRequest.allget()
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            // sql : and (name like "%xxx%" or introduction like "%xxx%")
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText));
        }

        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            // and (tag like "%\"Java\"%" and like "%\"Python\"%")
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    /**
     * 分页查询
     * @param picturePage
     * @param request
     * @return 将分页 Page 中的 picture 转为分页 Page 中的 pictureVO
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        // 1. 取出分页对象中的值 picturePage.getRecords()
        List<Picture> pictureList = picturePage.getRecords();

        // 2. 创建 Page<PictureVO>, 调用 Page(当前页, 每页尺寸, 总数据量) 的构造方法
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());

        // 3. 判断存放分页对象值的列表是否为空
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }

        // 4. 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // pictureList.stream()：将 pictureList 转换为流。
        //.map(PictureVO::objToVo)：使用 PictureVO.objToVo() 方法, 将流中的每个 Picture 对象转换为 PictureVO 对象。
        //.collect(Collectors.toList())：将转换后的 PictureVO 对象收集到一个新的 List 中。

        // 5. 关联查询用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        // .map(Picture::getUserId) 取出封装图片列表中, 所有用户的 Id, 并将这些 id 收集为一个新的 Set 集合

        // 6. 将一个用户列表, 按照用户 ID 分组, Map<userId, 具有相同 userId 的用户列表>
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // userService.listByIds(userIdSet): 根据 userIdSet 查询出对应的用户列表,  返回值是一个List<User>，包含所有匹配的User 对象
        // Collectors.groupingBy() : 收集器, 对流中的 User 对象进行分组
        // User::getId : 一个方法引用, 表示以 User 对象的 id 属性作为分组依据。

        // 7. 填充图片封装对象 pictureVO 中, 关于作者信息的属性 user
        // 遍历封装的图片列表
        pictureVOList.forEach(pictureVO -> {
            // 获取当前图片的用户ID
            Long userId = pictureVO.getUserId();
            // 初始化用户对象为 null
            User user = null;
            // 检查 Map<userId, List<User>> 中是否存在该 userId 对应的用户列表
            if (userIdUserListMap.containsKey(userId)) {
                // 如果存在，获取该 userId 对应的用户列表，并取第一个用户对象
                user = userIdUserListMap.get(userId).get(0);
            }
            // 将用户对象转换为 UserVO，并设置到当前 pictureVO 的 user 属性中
            pictureVO.setUser(userService.getUserVO(user));
        });

        // 8. 将处理好的图片封装列表, 重新赋值给分页对象的具体值
        pictureVOPage.setRecords(pictureVOList);

        return pictureVOPage;
    }






}




