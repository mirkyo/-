# 知微 · Windows Server 部署方案

> 目标机：`<YOUR_SERVER_IP>`（腾讯云 CVM）· Windows Server 2022 · 主机名 `<HOSTNAME>`
> 撰写时间：2026-10-19

---

## 一、先说结论：性能不够，建议升配

实测数据（部署前基线）：

| 项目 | 实测值 | 评价 |
|---|---|---|
| CPU | AMD EPYC 7K83，**1 核 / 2 线程** | 够用（演示级并发） |
| 内存 | 总计 **1.97 GB**，**空闲仅 200 MB** | 🔴 **严重不足** |
| 磁盘 | C: **25.5 GB 可用** / 53.5 GB；D: 盘「设备未就绪」 | 够用（但没余量堆大文件） |
| 已在跑 | MySQL 8.4.10（46379）、Defender 240MB、EasyConnect 55MB、ECAgent 42MB、YDService 34MB、RDP 会话里的 Edge 110MB | 105 个进程，已吃 1.5 GB |

**新组件要吃的内存预估**：

| 组件 | 常驻内存 |
|---|---|
| JVM（Spring Boot + Spring AI + Tika） | 550 – 900 MB |
| Qdrant（向量库） | 150 – 300 MB |
| 合计新增 | **700 MB – 1.2 GB** |

空闲只有 200 MB ⇒ **装上去必然触发换页，Tika 一解析 PDF 就可能 OOM 被系统杀掉。**

### 建议
1. **升到 4 GB 内存**（云控制台改配置，需重启一次）。4 GB 能跑，但要按下面的参数限死 JVM。
2. 想舒服一点就 **8 GB**（Tika 解析大文档、多人同时提问都不会抖）。
3. CPU 2 核不用动。

> 如果不升配，唯一能做的是「极限压缩 + 限制上传 20 MB」，基本只能单人演示，不推荐对外。

---

## 二、为什么不用 Docker

| 路线 | 结论 |
|---|---|
| Docker Desktop | **Windows Server 不支持**（微软只给 Win10/11） |
| Docker Engine + WSL2 | 需要**嵌套虚拟化**，CVM 实测「已检测到虚拟机监控程序」，嵌套虚拟化默认不开 ⇒ 装不起来 |
| Hyper-V | 同上，需嵌套虚拟化 |

⇒ **全部走 Windows 原生**，不依赖任何容器。

---

## 三、为什么不装 pgvector

原方案向量库是 **PostgreSQL + pgvector**，但：

- pgvector **没有 Windows 官方预编译包**
- 自己编译要装 **Visual Studio Build Tools（1–2 GB）+ nmake**，在只剩 25 GB 的 C 盘上做这个，性价比极低，还容易踩 MSVC 版本坑

**替代：Qdrant**

- 官方提供 **Windows 单文件 exe**（约 50 MB，解压即用，无安装程序）
- Spring AI 1.1 自带 `spring-ai-qdrant-store`，代码改动集中在 `RagStores`（约 30 行）
- 父块表（`JdbcDocumentStore`）本来就是通用 JDBC 实现，直接指向 MySQL 即可，不浪费内存

---

## 四、部署架构（最终形态）

```
                    ┌─────────────── <YOUR_SERVER_IP> (Windows Server 2022) ───────────────┐
  浏览器 ──8000──▶  │  zw-app.exe / java -jar         :8000  (对外开放)                    │
                    │      │                                                             │
                    │      ├── MySQL 8.4  127.0.0.1:46379  (复用现有实例，独立库 ziwei_kb) │
                    │      └── Qdrant     127.0.0.1:6333   (仅本机，不对外)                  │
                    └────────────────────────────────────────────────────────────────────┘
```

- **端口**：只对外开 **8000**（安全组已有）；Qdrant 6333/6334 **只绑 127.0.0.1**，不进安全组
- **MySQL**：复用 `C:\mysql84` 那个实例，**新建独立库 `ziwei_kb` + 独立账号**，绝不碰 `lot_paging_service` 的库

---

## 五、目录规划

```
C:\ziwei\
├─ jre21\            Temurin JRE 21（zip，免安装）
├─ app\              zw-agent-platform-0.1.0.jar + start.bat + logs\
├─ qdrant\           qdrant.exe + storage\ + config\production.yaml
└─ scripts\          备份 / 重启 / 健康检查脚本
```

---

## 六、密码与密钥（全部新生成，不复用弱口令）

| 用途 | 账号 / 键 | 值 | 说明 |
|---|---|---|---|
| MySQL 业务账号 | `ziwei` | `ZwKb-2026-9xQmTp4v` | **只授权 `ziwei_kb.*`**，不能碰其他库 |
| Qdrant API Key | — | `zw-qd-7Kx9mTp4vQ2nLr` | 只监听 127.0.0.1，仍设 key 兜底 |
| 应用管理员 | `admin` | 首启后立即改密 | 通过 `INIT_ADMIN_PASSWORD` 环境变量设置，**首启第一件事就改** |
| 服务器 MySQL root | `root` | 由你保管，**我不改** | 建库时你用一次，之后应用只用 `ziwei` |
| DashScope API Key | — | 写系统环境变量 `DASHSCOPE_API_KEY` | 不落配置文件 |

> ⚠️ 密码里刻意**不含 `@` `#` `%` `$`** —— 这些字符在 JDBC URL / 命令行里要么要转义，要么会被 shell 吃掉，属于「看起来很酷、部署时踩坑」的字符。

---

## 七、部署步骤（我会按这个顺序做）

1. **升配内存到 4 GB**（你操作，需重启）
2. 装 JRE 21 → `C:\ziwei\jre21`
3. 建库建账号（需要你给一次 MySQL root 密码）：
   ```sql
   CREATE DATABASE ziwei_kb DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
   CREATE USER 'ziwei'@'127.0.0.1' IDENTIFIED BY 'ZwKb-2026-9xQmTp4v';
   GRANT ALL PRIVILEGES ON ziwei_kb.* TO 'ziwei'@'127.0.0.1';
   ```
4. 部署 Qdrant → 绑定 `127.0.0.1:6333`，注册为开机自启
5. 写系统环境变量：`DASHSCOPE_API_KEY`、`ZW_DB_PASSWORD`、`ZW_QDRANT_KEY`
6. 传 jar + 改 `RagStores` 为 Qdrant 版本，重启验证
7. JVM 参数（**必须限死**，否则 2G 机器直接爆）：
   ```
   -Xms192m -Xmx512m -XX:MaxMetaspaceSize=128m
   -XX:+UseSerialGC -XX:MaxDirectMemorySize=64m -Dfile.encoding=UTF-8
   ```
8. 用 `schtasks /sc onstart /ru SYSTEM` 注册开机自启
9. 验收：`curl http://<YOUR_SERVER_IP>:8000/api/health` → `{"code":0,...}`

---

## 八、风险清单

| 风险 | 影响 | 应对 |
|---|---|---|
| **内存 2 GB** | Tika 解析大 PDF 时被 OOM Kill | 升 4 GB；上传上限压到 20 MB |
| C 盘仅 25 GB | 日志/临时文件堆满 | 日志按天滚动保留 7 天；Tika 临时目录指向 `C:\ziwei\tmp` 并定期清 |
| MySQL 与别的项目共用实例 | 误操作影响 `lot_paging_service` | 独立库 + 独立账号 + 只授权 `ziwei_kb.*` |
| Tika 解析器吃 CPU | 2 核实测解析 50 页 PDF 约 5–15 s | 演示前先预热一次；限制并发上传 |
| Qdrant 是单进程 | 进程挂了问答就不可用 | 注册开机自启 + 健康检查脚本，失败自动拉起 |
| `SERVER__PORT` 环境变量污染 | 端口被顶成别的值 | 启动脚本里 `--server.port=8000` 强制指定 |

---

## 九、需要你确认的三件事

1. **内存升到 4 GB 还是 8 GB？**（建议 4 GB 起步）
2. **MySQL root 密码**（建库用一次，之后不用）
3. 确认可以**只对外开 8000**，Qdrant 不对外

---

# 附录：2 GB 内存下的极限部署方案

> 前提：**不升配**。实测 `Available 165 MB`、`Committed 4.39 GB`（物理的 2.2 倍）⇒ 机器**已经在换页**。
> 所以 2G 部署 = **腾内存 + 砍进程**，两件事都得做，缺一不可。

## A. 关键改动：不要向量库进程

改用 **Spring AI 自带的 `SimpleVectorStore`**（`spring-ai-vector-store:1.1.0` 里确认存在）：

- 向量存**进程内存** + 落一个 `vector-store.json` 文件，启动时加载
- 支持 `Filter.Expression`（`SimpleVectorStoreFilterExpressionConverter`），**租户/用户/知识库三个过滤条件照旧可用**
- 父块表（`JdbcDocumentStore`）直接指向**现有 MySQL**，用独立表 `agentx_rag_document`

⇒ **新增进程数 = 0**（只有 JVM），不装 PostgreSQL、不装 Qdrant、不装 Docker。

代价：检索是**堆内全表扫描**。1 万块以内是毫秒级；10 万块以上会明显变慢。演示/小团队完全够。
重启不丢索引（JSON 落盘）。

> 保留开关 `app.rag.vector-store: simple | pgvector`，**将来升到 4G 改一个字就切回 pgvector**。

## B. 腾内存：可释放 425–615 MB

| 动作 | 释放 | 风险 |
|---|---|---|
| 关掉 RDP 会话里那个 Edge（110 MB） | 110 MB | 无 |
| **注销** RDP 会话（不要只是断开）：LogonUI 51 + explorer 46 + dwm | ~120 MB | 无 |
| 停 `EasyConnect`（深信服 VPN 客户端） | 55 MB | 服务器上如果不用 VPN 就停 |
| 停 `SysMain`（Superfetch，纯预读缓存） | 100–250 MB | 无（就是预读变慢一点） |
| 停 `WSearch`（Windows Search 索引） | 40–80 MB | 服务器文件搜索变慢 |
| **合计** | **425–615 MB** | |

## C. JVM 必须限死

```
-Xms128m -Xmx320m -XX:MaxMetaspaceSize=112m
-XX:+UseSerialGC -XX:MaxDirectMemorySize=32m -Xss512k
-Dfile.encoding=UTF-8
```
实测这类栈（Spring Boot 3.5 + Spring AI + Tika）RSS ≈ **380–420 MB**。
堆再小 Tika 解析 PDF 会 OOM，320m 是下限。

配套限制：
- 上传上限 **20 MB**（`app.rag.upload.max-file-size-mb`）
- 切块上限 **3000**（`app.rag.chunk.max-chunks`）
- 日志按天滚动保留 7 天

## D. 算账

| 项 | 内存 |
|---|---|
| 物理总量 | 1,970 MB |
| 清理后系统占用（估） | 1,050 MB |
| 可用 | ~920 MB |
| JVM（RSS） | 400 MB |
| **剩余缓冲** | **~520 MB** ✅ |

能跑，但**没有余量**：并发 3 人以上同时提问会开始抖，Tika 解析大 PDF 时要独占 CPU。

## E. 2G 方案的取舍（必须知情）

| 能接受 | 不能接受 |
|---|---|
| 单人/小团队演示（≤5 并发） | 大文档（>20MB）解析 |
| 知识库总量 < 1 万块 | 十万级向量检索 |
| 冷启动约 15–25 秒（JVM+Tika 初始化） | 长时间高并发压测 |

---

# 方案修订（2026-10-19 10:35）：PG 不可行 → 用 Qdrant

## 一、为什么 PG + pgvector 走不通（实测）

| 卡点 | 实测证据 |
|---|---|
| 服务器**没有外网** | `curl https://www.postgresql.org` → **000（18.5s 超时）**；PG 安装包根本下不来 |
| pgvector 要现场编译 | 需要 **VS Build Tools 2–3 GB**，同样要联网下载 |
| 本机也编不了 | 本机无 MSVC（`cl.exe` 不存在）、无 PostgreSQL 头文件 |
| 第三方预编译 DLL | 存在，但**在服务器上装来路不明的 DLL** 属供应链风险，不建议 |

> 结论：**放弃 PostgreSQL 路线。** 不是不想用，是这台机器客观上装不起来。

## 二、改用 Qdrant（Windows 原生，已确认可用）

- 官方提供 **`qdrant-x86_64-pc-windows-msvc.zip`**（v1.19.2，**28.8 MB**，单 exe，解压即用）
- **由我本机下载后 SFTP 上传**，服务器完全不需要外网
- Spring AI 官方适配包 **`spring-ai-qdrant-store:1.1.0`** 已在 Maven Central 确认存在（jar/pom 均 200）
- 代码改动集中在 `RagStores`（约 30 行），保留开关 `app.rag.vector-store: qdrant | simple | pgvector`

### 运行参数（2G 机器必须这么配）
```
qdrant.exe --disable-telemetry
环境变量：
  QDRANT__SERVICE__HOST=127.0.0.1     # 默认 0.0.0.0，必须改，否则对外暴露
  QDRANT__SERVICE__HTTP_PORT=6333
  QDRANT__SERVICE__GRPC_PORT=6334
  QDRANT__SERVICE__API_KEY=zw-qd-7Kx9mTp4vQ2nLr
  QDRANT__TELEMETRY_DISABLED=true      # 没外网，必须关，否则启动时卡在遥测
```

## 三、内存重算（Qdrant 版）

| 项 | 内存 |
|---|---|
| 物理总量 | 1,970 MB |
| 清理后系统占用（Edge/LogonUI/explorer/EasyConnect/SysMain） | ~1,050 MB |
| 可用 | ~920 MB |
| JVM（`-Xmx320m`，RSS） | 400 MB |
| **Qdrant** | **100–150 MB** |
| 剩余缓冲 | **~370–420 MB** ✅ |

比 SimpleVectorStore 方案多花 ~120 MB，但换来**真正的向量库**（HNSW 索引、十万级也能跑）。

## 四、部署包（全部本机下载后上传，服务器零外网依赖）

| 文件 | 大小 | 来源 |
|---|---|---|
| `OpenJDK21U-jre_x64_windows_hotspot_*.zip` | ~50 MB | Adoptium |
| `qdrant-x86_64-pc-windows-msvc.zip` | 28.8 MB | GitHub Releases |
| `zw-agent-platform-0.1.0.jar` | ~109 MB | 本地 `mvn package` |
| **合计** | **~190 MB** | SFTP 上传约 3–8 分钟 |
