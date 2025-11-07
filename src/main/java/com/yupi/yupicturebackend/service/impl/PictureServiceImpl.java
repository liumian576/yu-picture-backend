package com.yupi.yupicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.manager.upload.FilePictureUpload;
import com.yupi.yupicturebackend.manager.upload.PictureUploadTemplate;
import com.yupi.yupicturebackend.manager.upload.UrlPictureUpload;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import com.yupi.yupicturebackend.model.dto.picture.*;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.mapper.PictureMapper;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
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
@Slf4j
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
    implements PictureService{

    // 6. 引入 FileManager 对象
    // @Resource
    // private FileManager fileManager;
    //引入用户服务
    @Resource
    private UserService userService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;
    @Resource
    private CosManager cosManager;

    @Resource
    private SpaceService spaceService;

    @Resource
    private TransactionTemplate transactionTemplate;
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
     * @param inputSource
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        // 1. 校验参数, 用户未登录, 抛出没有权限的异常
        ThrowUtils.throwIf(loginUser == null , ErrorCode.NO_AUTH_ERROR);

        //校验空间是否存在
        Long spaceId = pictureUploadRequest.getSpaceId();
        if (spaceId != null) {
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

            if (!loginUser.getId().equals(space.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
            }

            if (space.getTotalCount() >= space.getMaxCount()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足");
            }
            if (space.getTotalSize() >= space.getMaxSize()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不足");
            }
        }
        // 2. 判断是新增图片, 还是更新图片, 所以先判断图片是否存在
        Long pictureId = null;
        if(pictureUploadRequest != null){
            // 3. 如果传入的请求不为空, 才获取请求中的图片 ID
            pictureId = pictureUploadRequest.getId();
        }

        // 4. 图片 ID 不为空, 查数据库中是否有对应的图片 ID
        if (pictureId != null) {
            Picture oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            //仅本人或管理员可以编辑
            if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }

            //校验空间id是否一致
            // 空间id为空时，使用旧图片的空间id
            if (spaceId == null) {
                if (oldPicture.getSpaceId() != null) {
                    spaceId = oldPicture.getSpaceId();
                }
            } else {
                // 空间id不为空时，校验空间id是否一致
                if (ObjUtil.notEqual(spaceId, oldPicture.getSpaceId())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间 id 不一致");
                }
            }
        }

        // 7. 定义上传文件的前缀 public/登录用户 ID
        // String uploadPathPrefix = String.format("public/%s", loginUser.getId());
        // 根据用户划分前缀, 当前的图片文件上传到公共图库, 因此前缀定义为 public
        String uploadPathPrefix;
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            uploadPathPrefix = String.format("space/%s", spaceId);
        }
        // 8. 上传图片, 上传图片 API 需要的参数(原始文件 + 文件前缀), 获取上传文件结果对象,
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        if (inputSource instanceof String) {
            pictureUploadTemplate = urlPictureUpload;
        }

        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        // UploadPictureResult uploadPictureResult = fileManager.uploadPicture(multipartFile, uploadPathPrefix);

        // 9. 构造要入库的图片信息(样板代码)
        Picture picture = new Picture();
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setThumbnailUrl(uploadPictureResult.getThumbnailUrl());
        String picName = uploadPictureResult.getPicName();
        picture.setSpaceId(spaceId);
        if(pictureUploadRequest!=null && StrUtil.isNotBlank(pictureUploadRequest.getPicName())){
            // 图片更新请求不为空, 并且图片更新请求中的图片名称属性不为空, 以更新请求的图片名称, 代替图片解析结果的名称
            // pictureUploadRequest 的 PicName 属性是允许用户传递的
            picName = pictureUploadRequest.getPicName();
        }
        picture.setName(picName);
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        // 从当前登录用户中获取 userId
        picture.setUserId(loginUser.getId());
        // fillReviewParams( picture, loginUser);
        this.fillReviewParams( picture, loginUser);

        // 10. 操作数据库, 如果 pictureId 不为空, 表示更新图片, 否则为新增图片
        if(pictureId!=null){
            // 11. 如果是更新, 需要补充 id 和编辑时间
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }
        //更新事务
        Long finalSpaceId = spaceId;
        transactionTemplate.execute(status -> {
            boolean result = this.saveOrUpdate(picture);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
            if (finalSpaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, finalSpaceId)
                        .setSql("totalSize = totalSize + " + picture.getPicSize())
                        .setSql("totalCount = totalCount + 1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return picture;
        });

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
        Long spaceId = pictureQueryRequest.getSpaceId();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
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
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);

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

    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);

        //2. 获取图片信息
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();

        // 3. 从当前图片审核请求中获取状态码, 然后根据该状态码从枚举类中获取对应枚举字段的 text 属性
        PictureReviewStatusEnum pictureReviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);

        String reviewMessage = pictureReviewRequest.getReviewMessage();
        // 4. 校验审核请求
        ThrowUtils.throwIf(id == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatus), ErrorCode.PARAMS_ERROR);
        //5.  判断 图片是否存在
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 6. 校验审核状态是否重复
        if(oldPicture.getReviewStatus().equals(reviewStatus)){
            // 7. 数据库存储的图片状态, 于当前发送审核请求的图片对应的状态相同, 则不能再次调用审核操作, 抛异常
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");}

        // 8. 数据库操作
        // 9. 不能直接以 oldPicture 对象作为更新对象, 而是要重新创建一个新对象
        Picture newPicture = new Picture();
        BeanUtil.copyProperties(pictureReviewRequest, newPicture);

        // 11. 手动填充图片的审核人 id 和审核的时间
        newPicture.setReviewerId(loginUser.getId());
        newPicture.setReviewTime(new Date());
        // 10. 因为 mybatis 的 updateById() 会根据 id 更新有值的属性, 以 oldPicture 为更新对象, 会重新更新所有字段的值
        boolean result = this.updateById(newPicture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

    }

    /**
     * 填充审核参数接口
     * @param picture
     * @param loginUser
     */
    @Override
    public void fillReviewParams(Picture picture, User loginUser){
        if(userService.isAdmin(loginUser)){
            // 1. 管理员自动过审
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("管理员自动过审");
            picture.setReviewTime(new Date());
        }else {
            // 2. 非管理员, 无论编辑图片还是创建图片, 都默认图片审核状态为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        // 1. 获取参数 pictureUploadByBatchRequest.allget
        String searchText = pictureUploadByBatchRequest.getSearchText();
        Integer count = pictureUploadByBatchRequest.getCount();
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }

        // 2. 校验参数
        ThrowUtils.throwIf(count > 30, ErrorCode.PARAMS_ERROR, "最多抓取 30 条");

        // 3. 抓取内容
        // 4. 拼接要抓取的 url, 其中参数 q=%s 是可以动态修改的, 考虑把关键词 searchText 赋值给 q 参数
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);

        Document document;
        try {
            // 5. 提供 Jsoup 获取 url 参数对应的页面文档
            document = Jsoup.connect(fetchUrl).get();
            // Jsoup.connect(fetchUrl) 根据 url 连接页面
            // get() 获取该页面文档, 需要捕获异常
        } catch (IOException e) {
            // 6. 打印日志并修改抛出的异常
            log.error("获取页面失败");
            // 这里捕获的是 get 抛出的异常, 并不是我们的操作错误, 因此修改抛出的异常为我们自定义的业务异常
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }

        // 7. 解析内容
        // 获取到的 document 是整个页面的 HTML 内容; 现在需要从中提取所有图片元素, 并获取它们的类名（class）和 ID, 以便准确定位对应的图片元素

        // 8. 先获取外层的元素
        Element div = document.getElementsByClass("dgControl").first();

        // 9. 判断外层元素是否存在, 存在则继续获取内层的图片
        if (ObjUtil.isEmpty(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        // 10. 获取内层元素, 找外层元素 class = dgControl 中, 内层 class =  img.ming 的元素
        Elements imgElementList = div.select("img");
        // 这些内层元素有多个, 返回的应该是一个数组

        // 11. 遍历数组元素 imgElementList, 依次上传图片
        int uploadCount = 0;
        // 记录上传图片的数量, 因为有一些图片可能上传失败, 导致实际的抓取数与定义的抓取数不同
        for (Element imgElement : imgElementList) {
            // 12. 获取每个元素的 src 属性, 其实就是图片元素的 url
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                // 13. 某个元素无法获取, 因此打印错误的图片 url , 结束对该元素的遍历(无需执行后续上传操作)
                log.info("当前链接为空, 已跳过: {}", fileUrl);
                continue;
            }
            // 14. 处理图片地址, 防止转义和对象存储冲突的问题(去掉 url 中的参数, 也就是 url 中 ? 后面的部分)
            int questionMarkIndex = fileUrl.indexOf("?");
            // 获取 url 中 ? 的下标
            if (questionMarkIndex > -1) {
                // 截取 url 中 ? 前面的部分
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }
            // 15. 构造上传图片方法的参数
            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            if (StrUtil.isNotBlank(namePrefix)) {
                // 设置图片名称，序号连续递增
                pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            }


            // 18. 捕获上传图片操作可能抛出的异常
            try{
                // 16. 上传图片, 其中 loginUser 参数是作为该批量抓取方法的参数传进来的
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);

                // 17. 打印日志: 上传图片返回的结果对象中, 内置的 id
                log.info("图片上传成功, id = {}", pictureVO.getId());

                uploadCount++;
            }catch (Exception e){
                log.error("图片上传失败", e);
                continue;
                // 使用 continue, uploadCount++ 的操作不会被执行
            }

            if(uploadCount >= count){
                break;
            }
        }
        return uploadCount;
    }

    @Override
    public void deletePicture(long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);

        Picture oldPicture = this.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        checkPictureAuth(loginUser, oldPicture);

        transactionTemplate.execute(status -> {

            boolean result = this.removeById(pictureId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

            Long spaceId = oldPicture.getSpaceId();
            if (spaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, spaceId)
                        .setSql("totalSize = totalSize - " + oldPicture.getPicSize())
                        .setSql("totalCount = totalCount - 1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return true;
        });

        this.clearPictureFile(oldPicture);

    }

    @Override
    public void editPicture(PictureEditRequest pictureEditRequest, User loginUser) {
        // 在此处将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        // 设置编辑时间, 不会因为修改数据库, 就自动更新编辑时间, 需要我们手动设置
        picture.setEditTime(new Date());
        // 数据校验
        this.validPicture(picture);
        // 判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        //权限验证   仅本人或管理员可编辑
        checkPictureAuth(loginUser, oldPicture);

        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }


    @Async
    @Override
    public void clearPictureFile(Picture oldPicture) {

        String pictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery()
                .eq(Picture::getUrl, pictureUrl)
                .count();

        if (count > 1) {
            return;
        }

        cosManager.deleteObject(oldPicture.getUrl());

        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)) {
            cosManager.deleteObject(thumbnailUrl);
        }
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {

            if (!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        } else {

            if (!picture.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
    }



}




