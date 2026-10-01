# interview-guide 本机开发环境与启动/关闭说明

> 适用电脑：当前这台 Windows 11 开发机  
> 项目目录：`C:\Users\zhy\IdeaProjects\interview-guide`

---

## 1. 当前环境配置

### 操作系统

- Windows 11
- Windows build：`10.0.26200.9168`
- 架构：`amd64`

### Java / Gradle

- JDK：Eclipse Temurin OpenJDK `25.0.4.1 LTS`
- Java 路径：
  `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`
- Gradle：`9.6.1`
- Gradle 使用项目自带 Wrapper，不需要单独安装 Gradle

验证：

```powershell
java -version

cd C:\Users\zhy\IdeaProjects\interview-guide
.\gradlew.bat --version
```

### Node.js / pnpm

- Node.js：`v24.19.0`
- Corepack：`0.35.0`
- pnpm：`10.26.2`

验证：

```powershell
node -v
npm -v
corepack --version
pnpm -v
```

如果 PowerShell 报 `npm.ps1` 被禁止执行，可将当前用户执行策略设置为：

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Corepack 第一次启用时如果提示没有权限写入：

```text
C:\Program Files\nodejs\
```

可使用一次“管理员 PowerShell”执行：

```powershell
corepack enable
```

之后普通 PowerShell 可正常使用 `pnpm`。

### WSL 2

- WSL：`2.7.12.0`
- Linux Kernel：`6.18.33.2-2`
- 默认 WSL 版本：`2`

验证：

```powershell
wsl --version
wsl --status
```

### Docker

Docker Desktop 已安装，并使用 WSL 2 后端。

Docker 已通过：

```powershell
docker run --rm hello-world
```

验证 Docker：

```powershell
docker --version
docker compose version
docker info
```

---

## 2. 项目依赖服务

项目开发环境使用：

```text
docker-compose.dev.yml
```

当前运行的基础服务：

| 服务 | Docker 镜像 | 本机端口 |
|---|---|---|
| PostgreSQL + pgvector | `pgvector/pgvector:pg16` | `5432` |
| Redis | `redis:7` | `6379` |
| RustFS | `rustfs/rustfs:latest` | `9000-9001` |

默认 PostgreSQL 配置：

```text
Host: localhost
Port: 5432
Database: interview_guide
User: postgres
Password: 123456
```

项目根目录 `.env` 中当前对应配置：

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456
```

> `.env` 中可能还包含 AI API Key、加密密钥等敏感内容，不要提交到公开 Git 仓库，也不要随意分享。

---

## 3. 第一次安装前端依赖

进入前端目录：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide\frontend
```

安装：

```powershell
pnpm install
```

当前已经成功安装。

生产构建测试也已通过：

```powershell
pnpm run build
```

构建过程中曾出现：

- Browserslist 数据较旧
- CSS minify warning
- chunk 大于 500 kB
- `Ignored build scripts`

这些没有阻止当前项目正常构建和运行，暂时无需处理。

---

# 4. 每次启动项目

推荐按以下顺序启动。

## 第一步：启动 Docker Desktop

先打开 Windows 中的 **Docker Desktop**。

等待 Docker Engine 正常启动。

可验证：

```powershell
docker info
```

---

## 第二步：启动 PostgreSQL / Redis / RustFS

打开 PowerShell：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide
```

启动：

```powershell
docker compose -f docker-compose.dev.yml up -d
```

检查状态：

```powershell
docker compose -f docker-compose.dev.yml ps
```

正常情况下三个服务应显示：

```text
healthy
```

也可以：

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

预期主要看到：

```text
interview-postgres
interview-redis
interview-rustfs
```

---

## 第三步：启动后端

在项目根目录：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide
```

运行：

```powershell
.\gradlew.bat :app:bootRun
```

这个 PowerShell 窗口需要一直保持开启。

### 注意

Gradle 可能一直显示类似：

```text
80% EXECUTING
> :app:bootRun
```

这是正常现象，不代表卡住。

`bootRun` 是持续运行的服务器任务，只要后端正在运行，Gradle 就不会自动变成 100% 并退出。

后端地址：

```text
http://localhost:8080
```

Swagger：

```text
http://localhost:8080/swagger-ui.html
```

---

## 第四步：启动前端

再打开一个新的 PowerShell：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide\frontend
```

运行：

```powershell
pnpm dev
```

这个 PowerShell 窗口同样需要保持开启。

前端默认地址：

```text
http://localhost:5173
```

浏览器打开：

```text
http://localhost:5173
```

当前已确认能够正常进入 **AI Interview / 智能面试助手** 页面。

---

# 5. 每次关闭项目

推荐按以下顺序关闭。

## 第一步：关闭前端

在运行：

```powershell
pnpm dev
```

的 PowerShell 窗口中按：

```text
Ctrl + C
```

---

## 第二步：关闭后端

在运行：

```powershell
.\gradlew.bat :app:bootRun
```

的 PowerShell 窗口中按：

```text
Ctrl + C
```

---

## 第三步：关闭 Docker 项目服务

进入项目根目录：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide
```

推荐执行：

```powershell
docker compose -f docker-compose.dev.yml down
```

这会：

- 停止项目容器
- 删除项目容器
- 删除 Compose 网络
- **保留数据库等 Docker Volume 数据**

下次重新：

```powershell
docker compose -f docker-compose.dev.yml up -d
```

数据仍然存在。

### 只想暂停容器

也可以使用：

```powershell
docker compose -f docker-compose.dev.yml stop
```

恢复：

```powershell
docker compose -f docker-compose.dev.yml start
```

---

## 第四步：Docker Desktop

如果之后不再使用 Docker，可以退出 Docker Desktop。

如果还要使用其他 Docker 项目，可以保持 Docker Desktop 运行。

---

# 6. 非常重要：不要随便删除 Docker Volume

不要把下面命令当成日常关闭命令：

```powershell
docker compose -f docker-compose.dev.yml down -v
```

其中：

```text
-v
```

表示同时删除 Docker Volume。

这样可能删除：

- PostgreSQL 数据
- Redis 持久化数据
- RustFS 文件数据

只有明确想“彻底重置本地开发数据”时才使用。

---

# 7. PostgreSQL 密码问题记录

本机第一次配置时曾出现：

```text
FATAL: password authentication failed for user "postgres"
SQL State: 28P01
```

确认：

- `.env` 中密码为 `123456`
- Docker 内 PostgreSQL 用户实际密码曾与 `.env` 不一致

最终可通过容器内部修改 PostgreSQL 用户密码：

```powershell
docker exec interview-postgres `
  psql -U postgres -d interview_guide `
  -c "ALTER USER postgres WITH PASSWORD '123456';"
```

然后验证宿主机映射路径：

```powershell
docker exec -e PGPASSWORD=123456 interview-postgres `
  psql -h host.docker.internal -p 5432 -U postgres -d interview_guide `
  -c "SELECT current_user,current_database();"
```

日常使用不需要重复执行这些命令。

---

# 8. 快速状态检查

## Docker 服务

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide

docker compose -f docker-compose.dev.yml ps
```

## 8080 后端端口

```powershell
Test-NetConnection localhost -Port 8080
```

正常：

```text
TcpTestSucceeded : True
```

## 前端

浏览器打开：

```text
http://localhost:5173
```

## Swagger

浏览器打开：

```text
http://localhost:8080/swagger-ui.html
```

---

# 9. Git

Git 命令需要在项目仓库目录执行：

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide
```

常用检查：

```powershell
git status
git log -1 --oneline
git remote -v
```

如果在：

```text
C:\Users\zhy
```

直接执行：

```powershell
git log -1 --oneline
```

会出现：

```text
fatal: not a git repository
```

这是因为当前目录不是 Git 仓库，并非 Git 安装有问题。

---

# 10. 日常启动速查版

以后正常启动项目，只需要：

### PowerShell 1：基础服务

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide

docker compose -f docker-compose.dev.yml up -d

docker compose -f docker-compose.dev.yml ps
```

### PowerShell 2：后端

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide

.\gradlew.bat :app:bootRun
```

### PowerShell 3：前端

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide\frontend

pnpm dev
```

然后打开：

```text
http://localhost:5173
```

---

# 11. 日常关闭速查版

### 前端窗口

```text
Ctrl + C
```

### 后端窗口

```text
Ctrl + C
```

### Docker

```powershell
cd C:\Users\zhy\IdeaProjects\interview-guide

docker compose -f docker-compose.dev.yml down
```

**不要在日常关闭时加 `-v`。**
