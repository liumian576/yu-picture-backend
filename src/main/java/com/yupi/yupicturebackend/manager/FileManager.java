package com.yupi.yupicturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;

import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 文件服务
 * @deprecated 已废弃，改为使用 upload 包的模板方法优化
 */
@Slf4j
@Service
@Deprecated
public class FileManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private CosManager cosManager;

    /**
     *
     * @param multipartFile 上传的文件
     * @param uploadPathPrefix 上传文件的路径前缀
     * 由于这个方法是通用的上传图片文件的方法, 因此我们使用上传路径前缀, 而不是具体路径
     * 具体的路径, 可解析上传文件的具体信息
     * @return 上传图片后解析出的结果
     */
    public UploadPictureResult uploadPicture(MultipartFile multipartFile, String uploadPathPrefix){
        // 1. 校验图片(校验逻辑比较复杂, 单独写一个方法)
        validPicture(multipartFile);

        // 2. 图片上传地址
        String uuid = RandomUtil.randomString(16);
        // 文件可以重名, 使用 UUID 标识不同的文件, RandomUtil 是 hutool 工具类, 生成 16 位唯一且随机字符串
        // 3. 获取文件原始名称
        String originalFilename = multipartFile.getOriginalFilename();

        // 4. 确定最终上传文件的文件名: 创建文件时间戳-uuid.原始文件名后缀
        String uploadFilename = String.format("%s_%s.%s",
                DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originalFilename));
        // format() 第一个参数是拼接的形式, %s_%s_%s 每一个 %s 都是一个需要拼接的字符串
        // 最终文件上传名称由我们自己拼接, 不能用原始文件名, 可以提高安全性, 否则可能会导致 URL 冲突

        // 5.确定最终上传文件的文件路径: /uploadPathPrefix/uploadFilename
        String uploadPath = String.format("/%s/%s", uploadPathPrefix,uploadFilename);
        // 最终路径: 文件前缀参数(可以由用户自己指定, 比如短视频放入不同收藏夹) + 拼接好的最终文件名称

        // 6. 将文件上传到对象存储中(FileController 有现成代码)
        File file = null;
        try {
            // 11. 将原来的 filepath 修改为 uploadPath
            file = File.createTempFile(uploadPath, null);
            multipartFile.transferTo(file);

            // 12. 获取上传结果对象
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            // 之前不需要获取, 是因为我们不需要解析文件信息, 现在获取结果对象, 方便后续对文件进行解析

            // 13. 从文件的结果对象中, 获取文件的原始信息, 再从原始信息中获取图片对象
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();

            // 14. 封装返回结果
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            // uploadPictureResult.allset
            uploadPictureResult.setUrl( cosClientConfig.getHost() + "/" + uploadPath);  //   域名/上传路径 = 绝对路径
            uploadPictureResult.setPicName(originalFilename);
            uploadPictureResult.setPicSize(FileUtil.size(file));

            // 15. 计算图片的宽、高、宽高比  imageInfo.allget
            int picWidth = imageInfo.getWidth();
            int picHeight = imageInfo.getHeight();

            // 16. 计算宽高比
            double picScale = NumberUtil.round(picWidth * 1.0/picHeight, 2).doubleValue();
            // NumberUtil.round() 的两个参数是小数, 精度
            // int/int 可能会造成精度丢失, 将 picWidth/picHeight 改为 picWidth * 1.0/picHeight,

            uploadPictureResult.setPicWidth(picWidth);
            uploadPictureResult.setPicHeight(picHeight);
            uploadPictureResult.setPicScale(picScale);
            uploadPictureResult.setPicFormat(imageInfo.getFormat());  // 从图片对象中获取格式

            // 17. 设置返回结果
            return uploadPictureResult;
        } catch (Exception e) {
            // 18. 修改异常错误日志
            log.error("图片上传到对象存储失败 " , e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }finally {
            // 7. finally 删除临时文件的逻辑可以抽出来(选中代码 + ctrl+alt+m)
            deleteTempFile(file);
            // 8. 修改封装的方法名, 该方法 private 改为 public
        }
    }



    /**
     * 校验文件
     * @param multipartFile
     */
    private void validPicture(MultipartFile multipartFile) {
        // 1. 文件为空, 抛异常
        ThrowUtils.throwIf(multipartFile == null, ErrorCode.PARAMS_ERROR, "文件不能为空");

        // 2. 文件不为空, 校验文件大小
        long fileSize = multipartFile.getSize();
        // getSize() 可以获取文件大小, 以字节为单位
        final long ONE_M = 1024*1024;
        // 定义单位 MB
        ThrowUtils.throwIf(fileSize > 4 * ONE_M, ErrorCode.PARAMS_ERROR, "文件大小不能超过 4MB");

        // 3. 校验文件后缀
        String fileSuffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        // FileUtil 是 hutool 工具类, FileUtil.getSuffix() 可以获取文件后缀

        // 4. 定义允许上传的文件的后缀列表
        final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpg", "png", "jpeg", "webp");

        // 5. 校验当前文件是否包含后缀列表
        ThrowUtils.throwIf(!ALLOW_FORMAT_LIST.contains(fileSuffix), ErrorCode.PARAMS_ERROR, "文件类型错误");
    }

    /**
     * 删除临时文件
     * @param file
     */
    public void deleteTempFile(File file) {
        if(file != null){
            // 9. 修改变量名 delete 为 deleteResult
            boolean deleteResult = file.delete();
            if(!deleteResult){
                // 10. 日志第二个参数 filepath 改为 file.getAbsoluteFile()
                log.error("file delete error, filepath = {}", file.getAbsoluteFile());
            }
        }
    }
}

















