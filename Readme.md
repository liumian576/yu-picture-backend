# Yu Picture Backend

## 项目简介

Yu Picture Backend 是一个基于 Spring Boot 的图片管理后端系统，提供图片上传、存储、管理、检索等功能。系统支持多种图片格式，提供图片分类、标签、审核等功能，并且支持空间（Space）概念，可以为不同用户或场景提供独立的图片存储空间。

## 技术栈

- **框架**: Spring Boot 2.7.6
- **数据库**: MySQL
- **ORM**: MyBatis Plus 3.5.9
- **缓存**: Redis + Caffeine
- **认证**: Sa-Token + Redis
- **对象存储**: 腾讯云 COS
- **文档工具**: Knife4j (Swagger)
- **工具库**: Hutool
- **其他**: WebSocket、AOP、JSoup

## 项目结构

```
yu-picture-backend
├── sql/                    # SQL脚本文件
│   └── create_table.sql    # 数据库表创建脚本
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/yupi/yupicturebackend/
│   │   │       ├── YuPictureBackendApplication.java  # 主启动类
│   │   │       ├── annotation/        # 自定义注解
│   │   │       ├── aop/              # 切面编程
│   │   │       ├── api/              # API接口
│   │   │       ├── common/           # 公共类
│   │   │       ├── config/           # 配置类
│   │   │       ├── constant/         # 常量
│   │   │       ├── controller/       # 控制器
│   │   │       ├── exception/        # 异常处理
│   │   │       ├── manager/          # 业务管理器
│   │   │       ├── mapper/           # 数据访问层
│   │   │       ├── model/            # 数据模型
│   │   │       ├── service/          # 业务逻辑层
│   │   │       └── utils/            # 工具类
│   │   └── resources/
│   │       ├── application.yml       # 配置文件
│   │       ├── application-local.yml # 本地配置文件
│   │       ├── mapper/               # MyBatis映射文件
│   │       └── static/               # 静态资源
│   └── test/                         # 测试代码
├── pom.xml                           # Maven依赖配置
└── README.md                         # 项目说明文档
```

## 核心功能

### 1. 用户管理
- 用户注册、登录
- 用户信息管理
- 权限控制（普通用户、管理员）

### 2. 图片管理
- 图片上传（支持文件和URL两种方式）
- 图片信息管理（名称、简介、分类、标签等）
- 图片审核（待审核、通过、拒绝）
- 图片检索（按名称、简介、分类、标签等）
- 图片删除

### 3. 空间管理
- 创建图片空间
- 空间级别管理（普通版、专业版、旗舰版）
- 空间容量和数量限制
- 空间图片统计

### 4. 文件管理
- 文件上传
- 文件存储（腾讯云COS）
- 文件类型检查
- 文件大小限制

## 数据库设计

### 用户表 (user)
- 用户基本信息（账号、密码、昵称、头像等）
- 用户角色（user/admin）

### 图片表 (picture)
- 图片基本信息（URL、名称、简介等）
- 图片属性（大小、宽度、高度、格式等）
- 图片分类和标签
- 审核状态和信息
- 创建用户关联

### 空间表 (space)
- 空间基本信息（名称、级别等）
- 空间限制（最大容量、最大数量）
- 空间使用统计
- 创建用户关联

## 快速开始

### 环境要求
- JDK 11+
- Maven 3.6+
- MySQL 5.7+
- Redis 6.0+

### 安装步骤

1. 克隆项目
```bash
git clone [项目地址]
```

2. 创建数据库并执行SQL脚本
```sql
create database yu_picture;
-- 执行 sql/create_table.sql 脚本
```

3. 修改配置文件
- 修改 `src/main/resources/application.yml` 中的数据库和Redis连接信息
- 配置腾讯云COS（如果使用）

4. 启动项目
```bash
mvn spring-boot:run
```

5. 访问接口文档
- 启动后访问：http://localhost:8123/api/doc.html

## API文档

项目使用Knife4j生成API文档，主要接口包括：

- 用户相关：`/api/user/*`
- 图片相关：`/api/picture/*`
- 空间相关：`/api/space/*`
- 文件相关：`/api/file/*`

## 开发规范

### 代码结构
- Controller层：处理HTTP请求，参数校验
- Service层：业务逻辑处理
- Manager层：复杂业务逻辑编排
- Mapper层：数据访问

### 异常处理
- 使用全局异常处理器 `GlobalExceptionHandler`
- 自定义业务异常 `BusinessException`
- 统一错误码 `ErrorCode`

### 权限控制
- 使用 `@AuthCheck` 注解进行权限校验
- 基于Sa-Token实现认证授权

## 部署说明

1. 打包项目
```bash
mvn clean package
```

2. 运行jar包
```bash
java -jar target/yu-picture-backend-0.0.1-SNAPSHOT.jar
```

3. 使用Docker部署（可选）
```bash
# 构建镜像
docker build -t yu-picture-backend .

# 运行容器
docker run -d -p 8123:8123 yu-picture-backend
```

## 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 打开 Pull Request

## 许可证

本项目采用 MIT 许可证，详情请参阅 [LICENSE](LICENSE) 文件。

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 Issue
- 发送邮件至：[your-email@example.com]
