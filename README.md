# Excel To DB - 数据导入工具

面向业务人员的 Web 应用，实现 Excel 数据一键导入 MySQL 数据库。

## 功能特点

- **简洁易用**：无需编写 SQL 或了解数据库知识
- **智能推荐**：根据 Excel 列名智能推荐最匹配的数据表（≥90%匹配度）
- **多种导入模式**：支持清空导入和增量导入
- **主键冲突处理**：支持报错、更新（UPSERT）、忽略三种策略

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3 + Element Plus + Vite |
| 后端 | Java 17 + Spring Boot 3 |
| Excel 解析 | Apache POI |
| CSV 解析 | OpenCSV |
| 数据库连接 | HikariCP |
| 数据库 | MySQL |

## 项目结构

```
exceltodb/
├── client/                    # Vue 前端
│   └── src/
│       ├── components/       # Vue 组件
│       ├── App.vue           # 根组件
│       └── main.js           # 入口文件
├── server/                   # Java 后端
│   └── src/main/
│       ├── java/com/exceltodb/
│       │   ├── config/       # 配置类
│       │   ├── controller/   # 控制器
│       │   ├── model/        # 数据模型
│       │   └── service/      # 业务逻辑
│       └── resources/
│           └── application.yml
├── config.yaml               # 数据库配置
├── pom.xml                   # Maven 配置
└── README.md
```

## 快速开始

### 1. 配置数据库

复制 `config.yaml.example` 为 `config.yaml`，修改数据库连接信息：

```yaml
databases:
  - id: prod_erp
    name: 生产ERP系统
    host: localhost
    port: 3306
    username: root
    password: your_password
    database: erp_db
```

### 2. 启动后端

```bash
cd server
mvn spring-boot:run
```

后端服务将在 http://localhost:8080 启动。

### 3. 启动前端

```bash
cd client
npm install
npm run dev
```

前端服务将在 http://localhost:3000 启动。

### 4. 使用工具

1. 选择目标数据库
2. 上传 Excel/CSV 文件
3. 预览数据
4. 系统根据 Excel 列名智能推荐最匹配的数据表（匹配度≥90%）。若未匹配到合适的表，请检查导入文件或联系IT人员
5. 确认推荐表后设置导入模式并开始导入

## 支持的文件格式

| 格式 | 扩展名 | 最大大小 |
|------|--------|----------|
| Excel 97-2003 | .xls | 100MB |
| Excel 2007+ | .xlsx | 100MB |
| CSV | .csv | 100MB |

## 导入模式

| 模式 | 说明 |
|------|------|
| 清空导入 | 导入前清空原表数据（TRUNCATE + INSERT） |
| 增量导入 | 直接追加数据（INSERT） |

## 主键冲突策略

当目标表有主键时，可选择冲突处理方式：

| 策略 | 说明 |
|------|------|
| 报错（默认） | 冲突时终止导入并回滚 |
| 更新（UPSERT） | 冲突时更新现有记录 |
| 忽略 | 冲突时跳过该记录 |

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/databases | 获取可用数据库列表 |
| GET | /api/databases/{id}/test | 测试数据库连接 |
| POST | /api/upload | 上传文件 |
| GET | /api/preview/{filename} | 获取预览数据 |
| GET | /api/tables/{databaseId} | 获取所有表及列信息 |
| POST | /api/recommend | 获取推荐表 |
| POST | /api/import | 执行数据导入 |

## License

MIT
