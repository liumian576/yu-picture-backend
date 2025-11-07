package com.yupi.yupicturebackend.controller;

import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import com.yupi.yupicturebackend.annotation.AuthCheck;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.common.ResultUtils;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/file")
public class FileController {

    @Resource
    private CosManager cosManager;

    /**
     * 测试文件上传
     *
     * @param multipartFile
     * @return
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)  // 当前测试接口只有管理员才可以调用
    @PostMapping("/test/upload")  // 文件一般是接收前端的 form 表单, 所以一定要使用 Post 请求

    // 1. @RequestPart("file") 注解用于从 HTTP 请求中提取名为 "file" 的部分, 并将其绑定到方法参数 MultipartFile multipartFile 中; 它主要用于处理多部分请求（multipart/form-data）, 例如文件上传
    public BaseResponse<String> testUploadFile(@RequestPart("file")MultipartFile multipartFile){

        // 2. 获取当前用户上传的文件的名称
        String filename = multipartFile.getOriginalFilename();

        // 3. 指定对象存储的路径
        String filepath = String.format("/test/%s",filename);
        // format("/test/%s",filename), 相当于拼接为 /test/filename

        // 4. 创建临时文件
        File file = null;
        try {
            // 5. 创建临时文件需要捕获异常
            file = File.createTempFile(filepath, null);
            // 第一个参数: 指定创建临时文件的本地路径;
            // 第二个参数: 文件后缀, 这里不指定后缀

            // 6. 把用户上传的文件, 传输到临时文件
            multipartFile.transferTo(file);

            // 7. 从 spring 中获取 cosManager, 调用上传文件的方法
            cosManager.putObject(filepath, file);
            // 以 filepath 为唯一键 key 参数

            // 8. 返回上传文件的地址
            return ResultUtils.success(filepath);
        } catch (Exception e) {
            // 9. 初学 COS, 先把 catch 的 IOException 修改为 Exception

            // 10. 打日志, 方便 COS 初学者排查错误
            log.error("file upload error, filepath = " + filepath, e);

            // 11. 抛出自定义的业务异常
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }finally {
            // 12. 文件上传到 COS 后, 需要删除本地的临时文件
            if(file != null){
                boolean delete = file.delete();
                if(!delete){
                    log.error("file delete error, filepath = {}", filepath);
                }
            }
        }
    }

    /**
     * 测试下载文件
     * @param filepath 目标文件的路径
     * @param response 响应对象
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        // 1. 该方法无需返回值

        // 11. 将步骤 3 创建的流从 try 中拿出来, 方便关闭
        COSObjectInputStream cosObjectInput = null;
        // 下面步骤 3, 创建一个流, 使用完成后一定要关闭, 就像上传文件后, 也要删除临时文件

        // 8. 捕获 toByteArray() 异常, 并把后面设置响应体的操作, 同时放入 try 中
        try {
            // 10. 下载文件也可能会报错, 把下载文件的代码也放到 try 中

            // 2. 以文件路径为唯一键 key, 获取对应 key 的 COS 对象
            COSObject cosObject = cosManager.getObject(filepath);

            // 3. 获取文件具体内容
            cosObjectInput = cosObject.getObjectContent();

            // 7. write() 的参数是一个字节数组, 先把文件流转为具体数组
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            // IOUtils 导的包是腾讯云的

            // 4. 设置响应类型, 提示浏览器下载文件
            response.setContentType("application/octet-stream;charset=UTF-8");
            // 设置的响应类型为 application/octet-stream, 表示让浏览器下载文件

            // 5. 在响应头中携带目标文件路径
            response.setHeader("Content-Disposition", "attachment; filename=" + filepath);

            // 6. 将文件具体内容的流 cosObjectInput 写入到响应中
            response.getOutputStream().write(bytes);
            // getOutputStream() 先获取一个缓冲区, 往输出流写内容

            // 9. 刷新缓冲区, 在写入内容到缓冲区后, 一定要记得刷新
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file delete error, filepath = {}", filepath);
            // 13. 将 IOException 修改为 Exception
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        }finally {
            // 12. 释放流
            if(cosObjectInput != null){
                cosObjectInput.close();
            }
        }
    }

}






