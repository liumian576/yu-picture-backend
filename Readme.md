## 与教程不同的是
1.非静态字段"data"应该设置为transient或可序列化
BaseResponse.java
    原：private  T data;  现：private transient T data;
  