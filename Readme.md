## 与教程不同的是
1.非静态字段"data"应该设置为transient或可序列化   
BaseResponse.java  
    原：private  T data;  现：private transient T data;
2.GlobalExceptionHandler.java和ResultUtils.java泛型通配符问题  
    BaseResponse<?>替换为 <T> BaseResponse<T>  
3.ResultUtils.java 和Throwable.java没有私有方法  
  private ResultUtils() {}