package com.yupi.yupicturebackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;


@Service
public class UrlPictureUpload extends PictureUploadTemplate {
    @Override
    protected void validPicture(Object inputSource) {
        String fileUrl = (String) inputSource;
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

                    ThrowUtils.throwIf(contentLength > 8 * ONE_M, ErrorCode.PARAMS_ERROR, "文件大小不能超过 8MB");

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

    @Override
    protected String getOriginalFilename(Object inputSource) {
        String fileUrl = (String) inputSource;
        // 从 URL 中提取文件名
        return FileUtil.getName(fileUrl);
    }

    @Override
    protected void processFile(Object inputSource, File file) {
        String fileUrl = (String) inputSource;
        // 下载文件到临时目录
        HttpUtil.downloadFile(fileUrl, file);
    }
}
