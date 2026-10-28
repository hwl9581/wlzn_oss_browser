# WLZN OSS Browser

在 IntelliJ IDEA 内管理 **阿里云 OSS / Amazon S3** 的独立插件，项目名 `wlzn_oss_browser`。
采用 IDEA 原生 Swing 界面与两家云厂商的官方 SDK，不需要额外启动桌面客户端。

1.0.1 支持同账号多区域自动路由，修复配置校验后 OK 无法恢复，以及新上传空分片列表缺省下一页游标导致的空指针；沿用 WLZN 系列插件已有固定图标。传输意外异常的安全诊断可在 IDEA 日志中按 `WLZN OSS transfer` 检索，不记录异常原文中的密钥、Token 或签名链接。

## 安装与配置

1. 在 IDEA 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 `build/distributions/wlzn_oss_browser-1.0.1.zip`，重启 IDEA。
2. 打开 **Settings → Tools → WLZN OSS Browser**，新增连接。
3. 选择阿里云 OSS 或 Amazon S3，填写连接名称、AccessKey ID、Secret；临时凭据同时填写 STS / Session Token。**Region 和 Endpoint 均可留空**。
4. **Endpoint 留空时自动按 Bucket 路由**，同一套密钥可以访问多个区域，无需重复配置。OSS 使用 Bucket 列表的区域信息或位置查询，S3 使用 HeadBucket 的区域响应头；浏览、上传下载、续传、分片管理、临时链接都使用 Bucket 的实际区域。
5. 可以指定 Bucket，直接连接该 Bucket，减少列举全部 Bucket 的权限需求。起始目录只控制初始定位，访问范围由云端权限决定。
6. 打开右侧 **OSS Browser** 工具窗口，选择连接并点击“连接”。使用“文件浏览”和“传输任务”两个页签。

Region 在自动连接中仅作为初始访问区域，留空默认 OSS `cn-hangzhou`、S3 `us-east-1`，不限制 Bucket 所在区域。AWS 中国区/GovCloud 等独立分区请填写该分区的初始 Region。

内网、专线或兼容服务需要固定端点时再填写 Endpoint；**显式 Endpoint 保持手动模式，不会切换到其他公网端点**。Region 可从标准 OSS/S3 区域域名推断，自定义域名请填写服务要求的签名 Region。原有显式端点配置继续按手动模式工作；希望同一账号自动访问多个区域时，清空 Endpoint 后重新连接即可。

“路径风格访问”适用于明确支持此模式的兼容端点及本地测试；正常云端连接保持关闭。新建 Bucket 时需要单独指定其所属区域，不影响账号访问已有的其他区域。点击“连接”会刷新区域缓存。

## 已实现功能

| 功能 | OSS | S3 |
| --- | --- | --- |
| 多连接、同账号多区域自动路由、指定 Bucket、临时会话凭据、安全存储 | ✓ | ✓ |
| Bucket 列举、新建、删除空 Bucket | ✓ | ✓ |
| 目录浏览、路径跳转、名称前缀搜索、分页 | ✓ | ✓ |
| 文件和文件夹上传、拖放上传、保留目录层级和空目录 | ✓ | ✓ |
| 多文件/目录下载、分片上传、分段下载、暂停/继续/取消 | ✓ | ✓ |
| 重启 IDEA 后从本地断点恢复、失败任务重试 | ✓ | ✓ |
| 新建目录、批量删除、复制、移动、重命名 | ✓ | ✓ |
| UTF-8 文本预览/编辑、图片预览 | ✓ | ✓ |
| 复制对象路径、生成临时签名下载链接 | ✓ | ✓ |
| 查看/修改对象 ACL、HTTP 元数据、自定义元数据 | ✓ | ✓ |
| 归档对象发起解冻、查看/清理未完成分片 | ✓ | ✓ |

文件操作也可通过右键菜单和“更多”菜单执行。移动先完成所有复制再删除来源；中途失败保留已复制的目标，便于核对。删除、移动、覆盖、公开 ACL 等操作需要界面确认。

## 凭据与任务

- AccessKey ID、Secret、Session Token 保存到 IDEA **PasswordSafe**，不写入源码、项目配置、普通状态 XML 或断点文件。其实际存储方式由 IDEA 的 Passwords 设置决定。
- 普通状态只保存连接参数、任务路径、状态；禁止漫游。断点位于 IDEA system 目录的 `wlzn-oss-browser/checkpoints`，只保存分片信息、文件摘要及账号指纹。含临时访问权限的签名链接只按用户操作复制到剪贴板。
- 编辑连接时，凭据留空保留原值，已保存密钥不回显。更换长期密钥时需同时填 ID 和 Secret；只填 Token 可刷新同账号临时会话。
- 有未完成任务时，暂停后可更新 Token；删除连接、更换长期密钥或修改服务/区域/端点，需要先取消并清理任务，避免把断点用于另一个账号。
- 同时执行 2 个文件任务；单次规划最多 100000 个对象，待处理任务最多 10000 个，保存最近 500 条完成记录。上传分片至少 8 MiB，下载按 8 MiB 请求并校验 Range 和长度。
- 上传续传会校验本地文件 SHA-256、账号指纹及云端已确认分片；文件或云端对象变化时停止续传。取消会清理插件断点及对应未完成上传；清理失败会保留失败任务供重试。

## 权限准备

按实际使用功能为账号授予相应权限，不需要所有功能时无需授予全部权限。

| 能力 | 阿里云 OSS 主要权限 | Amazon S3 主要权限 |
| --- | --- | --- |
| 浏览 | `oss:ListBuckets`（不指定 Bucket 时）、`oss:ListObjects`、`oss:GetObject` | `s3:ListAllMyBuckets`（不指定 Bucket 时）、`s3:ListBucket`、`s3:GetObject` |
| 自动定位区域 | 桶列表含位置信息时直接复用；指定 Bucket 等未缓存场景可能需要 `oss:GetBucketLocation` | 使用 HeadBucket 的区域响应头，不额外要求 `s3:GetBucketLocation` |
| 上传/分片/清理 | `oss:PutObject`、`oss:ListParts`、`oss:AbortMultipartUpload`、`oss:ListMultipartUploads` | `s3:PutObject`、`s3:ListMultipartUploadParts`、`s3:AbortMultipartUpload`、`s3:ListBucketMultipartUploads` |
| 禁止覆盖上传 | 还需要 `oss:GetBucketVersioning` | 使用条件写入 |
| 删除/建桶 | `oss:DeleteObject`、`oss:PutBucket`、`oss:DeleteBucket` | `s3:DeleteObject`、`s3:CreateBucket`、`s3:DeleteBucket` |
| ACL/保留权限复制 | `oss:GetObjectAcl`、`oss:PutObjectAcl`；跨桶继承 ACL 还需 `oss:GetBucketAcl` | `s3:GetObjectAcl`、`s3:PutObjectAcl` |
| 解冻 | `oss:RestoreObject` | `s3:RestoreObject` |

复制、文本编辑、元数据修改会读取和保留原对象的权限、常用 HTTP 元数据、加密设置及存储类型，因此还需要这些属性对应的读取/写入权限。SSE-KMS 对象需要相应 KMS 权限。Bucket Policy、公共访问阻止、Object Ownership、保留策略等服务端规则仍生效，权限不足会显示云端错误码和 Request ID。

只有单个 Bucket 的访问权限时可以填写“指定 Bucket”，仍不要求列举全部桶。OSS 自动位置查询受权限限制时不会猜测区域并写入；无法取得位置时，请补充位置查询权限，或手动填写实际 Region 与 Endpoint。

## 操作边界

- 复制/移动/重命名限定同一连接、同一区域内，支持跨 Bucket，单对象最大 5 GiB。跨区域目标会在写入前拒绝，不删除来源。目标存在性在写入前检查；云端复制 API 无法对目标提供同等的原子禁止覆盖保证，检查后发生的并发写入仍可能被覆盖。移动删除来源前再次校验 ETag，但不具备跨对象事务或条件删除保证。对有其他写入方的目录，请先停止并发写入并核对结果。
- S3 上传/文本保存使用条件写入。OSS 新对象上传使用 `x-oss-forbid-overwrite`，且会先确认 Bucket 从未开启版本控制；开启或暂停版本控制的 Bucket 无法提供相同保证，此时会拒绝“跳过已存在”模式，需要明确选择覆盖。
- OSS 文本原地保存只能先检查 ETag，再执行覆盖写入；其 PutObject 不支持 S3 的 `If-Match` 条件，界面会说明并要求确认。云端元数据编辑使用带来源 ETag 条件的自复制。
- 文本/图片预览最多 5 MiB，文本要求合法 UTF-8，图片最多 2000 万像素。归档对象要先解冻。解冻默认采用厂商标准方式，S3 保留 1 天；解冻和请求等费用由云服务计费。
- 下载拒绝路径越界、符号链接、无法安全映射的对象名。默认禁止覆盖的文件通过同目录临时文件和硬链接安全提交，需要目标文件系统支持硬链接（常见本地 APFS/NTFS/ext4 支持）；不支持时保留完整断点并报错。明确允许覆盖时会再次检查本地目标后替换，仍应避免其他程序同时修改该文件。
- 链接有效期 1 秒到 7 天；使用临时凭据时，不会超过凭据本身的有效期。
- 首版覆盖常用对象管理流程；不包含云控制台的 IAM/账号管理、扫码登录、生命周期策略编辑、版本历史管理、跨云迁移或大对象分片复制。S3 的公共读写 ACL、BucketOwnerEnforced 下的 ACL 修改等不支持或受服务端限制。

## 开发与验证

使用 JDK 21、Gradle Wrapper、Kotlin 2.2.20、IntelliJ Platform Gradle Plugin 2.10.5；官方 SDK 为 Aliyun OSS 3.18.5、AWS SDK v2 2.57.5。
声明兼容 IDEA 2025.3～2026.1（253～261.*），实际平台测试和 Plugin Verifier 结果见 [验证记录](docs/验证记录.md)。未逐个验证的 IDE 版本需安装试用确认。

```bash
./gradlew test buildPlugin verifyPluginStructure
./gradlew verifyPlugin
./gradlew runIde
```

本机可指定已安装的 IDE，避免重新下载平台：

```bash
./gradlew test buildPlugin verifyPluginStructure \
  -PplatformLocalPath='/Applications/IntelliJ IDEA.app/Contents'
./gradlew verifyPlugin --offline \
  -PplatformLocalPath='/Applications/IntelliJ IDEA.app/Contents'
```

测试使用内存存储和本机 HTTP 模拟服务，不含真实密钥、不读公司云端对象。真实云端联网验收仍需在设置中填写专用测试连接，验证上传→预览→下载校验→暂停续传→复制/移动→清理；不同区域、IAM、KMS、版本控制和 ACL 策略需分别检查。

参考：[阿里云 OSS Browser](https://help.aliyun.com/zh/oss/user-guide/ossbrowser)、[IDEA 安全存储](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)、[S3 条件写入](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)、[OSS PutObject 条件限制](https://help.aliyun.com/zh/oss/user-guide/0017-00000245)。
