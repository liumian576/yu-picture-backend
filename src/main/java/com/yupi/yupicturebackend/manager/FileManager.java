package com.yupi.yupicturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
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
import java.net.MalformedURLException;
import java.net.URL;
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
        // format() 第一个参数是拼接的形式, %s_%s.%s 每一个 %s 都是一个需要拼接的字符串
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


    /**
     * 根据图片 url 上传文件
     * @param fileUrl 上传的图片文件的 URL
     * @param uploadPathPrefix 上传文件的路径前缀
     * 由于这个方法是通用的上传图片文件的方法, 因此我们使用上传路径前缀, 而不是具体路径
     * 具体的路径, 可解析上传文件的具体信息
     * @return 上传图片后解析出的结果
     */

// 修改 1 : 修改方法名和方法参数
    public UploadPictureResult uploadPictureByUrl(String fileUrl, String uploadPathPrefix){
        // 1. 校验图片(校验逻辑比较复杂, 单独写一个方法)

        // validPicture(multipartFile);
        // todo
        // 修改 2: 根据 fileUrl 校验图片, 原来是根据文件对象校验
        validPicture(fileUrl);

        // 2. 图片上传地址
        String uuid = RandomUtil.randomString(16);
        // 文件可以重名, 使用 UUID 标识不同的文件, RandomUtil 是 hutool 工具类, 生成 16 位唯一且随机字符串

        // String originalFilename = multipartFile.getOriginalFilename();
        // todo
        // 修改 3. 根据 URL 后缀获取文件原始名称, 而不是根据文件对象获取文件名
        String originalFilename = FileUtil.getName(fileUrl);
        // FileUtil 是 hutool 工具类, 通过 url 后缀获取文件名称

        // 4. 确定最终上传文件的文件名: 创建文件时间戳-uuid-原始文件名后缀
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, originalFilename);
        // format() 第一个参数是拼接的形式, %s_%s.%s 每一个 %s 都是一个需要拼接的字符串
        // 最终文件上传名称由我们自己拼接, 不能用原始文件名, 可以提高安全性, 否则可能会导致 URL 冲突%s_%s.%s

        // 5.确定最终上传文件的文件路径: /uploadPathPrefix/uploadFilename
        String uploadPath = String.format("/%s/%s", uploadPathPrefix,uploadFilename);
        // 最终路径: 文件前缀参数(可以由用户自己指定, 比如短视频放入不同收藏夹) + 拼接好的最终文件名称

        // 6. 将文件上传到对象存储中(FileController 有现成代码)
        File file = null;
        try {
            // 11. 将原来的 filepath 修改为 uploadPath
            file = File.createTempFile(uploadPath, null);

            // multipartFile.transferTo(file);
            // todo
            // 修改 4 , 使用 hutool 工具 HttpUtil, 先根据 fileUrl 将文件下载到本地
            HttpUtil.downloadFile(fileUrl, file);

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
     * 修改 5 : 增加根据 url 校验文件的方法
     * 重点: 仅对 url 能获取到的信息进行校验, 使用 try...finally... 强制校验结束释放资源
     * @param fileUrl
     */
    private void validPicture(String fileUrl) {
        // 1. 校验非空
        ThrowUtils.throwIf(StrUtil.isBlank(fileUrl), ErrorCode.PARAMS_ERROR, "文件地址为空");

        // 2. 校验 url 格式
        try {
            new URL(fileUrl);
            // 利用 JAVA 本身的 URL 对象, 来校验 fileUrl 是否可以被解析出来
            // 需要自动 try catch 捕获异常
        } catch (MalformedURLException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址格式不正确");
        }

        // 3. 校验 url 协议(前缀 http/https)
        ThrowUtils.throwIf(
                !fileUrl.startsWith("http://") && !fileUrl.startsWith("https://"),
                ErrorCode.PARAMS_ERROR,
                "仅支出 HTTP 或 HTTPS 协议的文件地址"
        );

        // 4. 发送 HEAD 请求验证图片是否存在
        HttpResponse httpResponse = null;

        try {
            httpResponse = HttpUtil.createRequest(Method.HEAD, fileUrl).execute();
            // hutool 工具类创建请求 , 以 fileUrl 为 url 发送一个 HEAD 方法的请求, 并接收响应结果

            // 5. 校验 HEAD 请求的响应结果(校验响应状态码)
            if(httpResponse.getStatus() != HttpStatus.HTTP_OK){
                // 未正常返回, 无需执行其他判断
                return;
                // 不报错, 而是直接返回, 是因为有些浏览器不支持 HEAD 请求, 并不是要校验的文件不存在
            }

            // 7. 文件存在, 获取文件的类型用于后续校验
            String contentType = httpResponse.header("Content-Type");

            // 8. 文件类型存在, 才校验文件 url 类型是否合法
            if(StrUtil.isNotBlank(contentType)){
                // 允许的图片类型
                final List<String> ALLOW_CONTENT_TYPES = Arrays.asList("image/jpeg", "image/jpg", "image/png", "image/webp");
                // 当前图片类型, 不在允许的图片类型的列表中, 抛出文件类型错误的异常
                ThrowUtils.throwIf(!ALLOW_CONTENT_TYPES.contains(contentType.toLowerCase()),
                        ErrorCode.PARAMS_ERROR, "文件类型错误");
            }
            // 9. 文件存在, 对文件大小进行校验
            String contentLengthStr = httpResponse.header("Content-Length");

            // 10. 文件大小存在, 才校验文件大小是否合法, 前面约定过, 文件大小最大不能超过 2 MB
            if(StrUtil.isNotBlank(contentLengthStr)){
                // 13. 点 parseLong() 源码, 发现会抛异常 NumberFormatException, 捕获该异常
                try{
                    // 11. 将字符串转为 long 类型
                    long contentLength = Long.parseLong(contentLengthStr);

                    // 定义单位 MB
                    final long ONE_M = 1024*1024;

                    ThrowUtils.throwIf(contentLength > 2 * ONE_M, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2MB");

                }catch (NumberFormatException e){
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小格式异常");
                }
            }
        }finally {
            // 6. 根据第五点, 有浏览器不支持 HEAD 请求, 直接返回
            // 但是一定要释放资源, 所以使用 try....finally, 确保释放资源操作一定会被执行
            if(httpResponse != null){
                httpResponse.close();
            }
        }
    }


}

















