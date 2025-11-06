package com.yupi.yupicturebackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public abstract class PictureUploadTemplate {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private CosManager cosManager;

    /**
     *
     * @param inputSource 输入源
     * @param uploadPathPrefix 上传文件的路径前缀
     * 由于这个方法是通用的上传图片文件的方法, 因此我们使用上传路径前缀, 而不是具体路径
     * 具体的路径, 可解析上传文件的具体信息
     * @return 上传图片后解析出的结果
     */
    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix){
        // 1. 校验图片
        validPicture(inputSource);

        // 2.图片上传地址
        String uuid = RandomUtil.randomString(16);

        String originalFilename = getOriginalFilename(inputSource);

        String uploadFilename = String.format("%s_%s.%s",
                DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originalFilename));

        String uploadPath = String.format("/%s/%s", uploadPathPrefix,uploadFilename);

        File file = null;
        try {
            // 3. 创建临时文件
            file = File.createTempFile(uploadPath, null);

            // 4. 处理文件来源
            processFile(inputSource, file);

            // 5. 上传文件到对象存储
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);

            // 6. 获取图片信息对象
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();

            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)) {
                CIObject compressedCiObject = objectList.get(0);

                CIObject thumbnailCiObject = compressedCiObject;

                if (objectList.size() > 1) {
                    thumbnailCiObject = objectList.get(1);
                }

                return buildResult(originalFilename, compressedCiObject, thumbnailCiObject);
            }
            // 7. 封装返回结果
            return buildResult(originalFilename, file, uploadPath,  imageInfo);
        } catch (Exception e) {
            log.error("图片上传到对象存储失败 " , e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }finally {
            // 8. 清理临时文件
            deleteTempFile(file);
        }
    }
    /**
     * 封装返回结果
     * @param originalFilename
     * @param compressedCiObject
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, CIObject compressedCiObject,CIObject thumbnailCiObject) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = compressedCiObject.getWidth();
        int picHeight = compressedCiObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());


        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        return uploadPictureResult;
    }

    /**
     * 封装返回结果
     * @param originalFilename
     * @param file
     * @param uploadPath
     * @param imageInfo 对象存储返回的图片信息
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, File file, String uploadPath, ImageInfo imageInfo) {
        // 封装返回结果
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        uploadPictureResult.setUrl( cosClientConfig.getHost() + "/" + uploadPath);
        uploadPictureResult.setPicName(originalFilename);
        uploadPictureResult.setPicSize(FileUtil.size(file));
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0/picHeight, 2).doubleValue();
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(imageInfo.getFormat());

        return uploadPictureResult;
    }
    /**
     * 删除临时文件
     * @param file
     */
    public void deleteTempFile(File file) {
        if(file != null){
            boolean deleteResult = file.delete();
            if(!deleteResult){
                log.error("file delete error, filepath = {}", file.getAbsoluteFile());
            }
        }
    }

    /**
     * 校验输入源
     */
    protected abstract void validPicture(Object inputSource);

    /**
     * 获取输入源的原始文件名
     */
    protected abstract String getOriginalFilename(Object inputSource);

    /**
     * 处理输入源并生成本地文件
     */
    protected abstract void processFile(Object inputSource, File file) throws Exception;


}
