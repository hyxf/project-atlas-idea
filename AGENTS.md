# Repository Guidelines

## 适用范围与工作区安全

本文件适用于仓库根目录及全部子目录。开始修改前先运行 `git status --short`。工作区可能包含用户尚未提交的改动：不得覆盖、删除、回退、暂存或提交与当前任务无关的文件；若目标文件已有改动，应先阅读 diff 并在其基础上修改。涉及数据丢失、Git 历史、远端状态、发布渠道或凭据时必须先确认。

## 项目定位与技术栈

本仓库是 Project Atlas IntelliJ IDEA 插件（仓库：`https://github.com/hyxf/project-atlas-idea`），提供 AI 代码上下文、Git/Terminal 常用操作与本地项目管理能力。AI 上下文功能维护项目根目录下的 `.aicode.json`、按上下文组管理文件，并导出 Markdown；项目管理数据由 `ProjectJsonStore` 保存至用户级的 `~/.project-manager/project.json`。项目使用 Kotlin 2.2.20、Java 17、Gradle Kotlin DSL、IntelliJ Platform Gradle Plugin 2.11.0，开发基线为 IntelliJ IDEA Community 2023.2（build 232）。

## 目录与模块职责

- `src/main/kotlin/com/aicode/feature/*/action/`：Project View 和编辑器菜单动作；只负责参数校验与流程编排。
- `service/AICodeFileService.kt`：配置读写、分组操作、路径缓存与变更通知的唯一入口。
- `model/AICodeConfig.kt`：`.aicode.json` 的数据模型；修改字段时必须考虑旧版数组格式迁移。
- `listener/`：监听 VFS 创建、删除、移动、重命名和内容变化。
- `provider/`：文件图标及编辑器顶部通知。
- `ui/`：Tool Window、分组选择和文件树交互。
- `util/`：Markdown 构建、语言识别与剪贴板访问；优先保持无 UI 依赖，便于单元测试。
- `src/main/kotlin/com/aicode/feature/projectmanager/feature/project/`：项目模型、仓储接口与核心服务。
- `src/main/kotlin/com/aicode/feature/projectmanager/feature/action/`、`ui/`、`recent/`、`welcome/`：项目管理菜单动作、Tool Window、最近访问和欢迎页入口。
- `src/main/kotlin/com/aicode/feature/projectmanager/infrastructure/`：项目 JSON 存储、持久化仓储、路径规范化、目录扫描、复制与删除。
- `src/main/kotlin/com/aicode/feature/projectmanager/settings/`：项目管理的应用级设置与 Settings UI。
- `src/main/resources/META-INF/plugin.xml`：服务、扩展点、监听器、动作和快捷键注册。
- `src/main/resources/icons/`：插件 SVG 资源。

`update_aicode_plugin.py` 是一次性维护脚本，不属于插件运行链路；修改前先确认它仍有用途，不要把它当作构建或发布入口。

## 核心架构与变更原则

典型数据流为：IDE Action/UI → `AICodeFileService` → `.aicode.json` → `AICODE_TOPIC` → Tool Window/Editor Notification 刷新。新增功能应复用该链路，避免在 Action 或 Panel 中直接读写 JSON。

项目管理的数据流为：Action/Tool Window → `ProjectManagerService` → `ProjectRepository` → 持久化实现 → `ProjectJsonStore`。标签保存在 `ProjectItem` 中。UI 只负责交互与展示，路径规范化、去重、搜索、排序和状态变更应优先放在服务或基础设施层。

- 所有 VFS 写入必须通过 IntelliJ Write Action/`WriteCommandAction`，并尽量保留 Undo/Redo 语义。
- Swing 组件只能在 EDT 更新；耗时文件遍历或内容拼装不得阻塞 EDT。
- 路径统一保存为相对项目根目录的 `/` 分隔形式，禁止写入机器相关的绝对路径。
- 配置写入后必须使缓存失效并发布变更通知；同时检查多分组和活动分组是否仍一致。
- 严格区分“Remove from Project Atlas”和“Delete Project”：前者只移除记录，不得删除磁盘目录；后者必须保留明确确认并展示目标绝对路径，禁止删除当前已打开项目和文件系统根目录。
- 项目复制、删除、扫描、导入和 `project.json` 读写必须在后台任务或池化线程执行，完成后再切回 EDT 更新 Swing UI。
- 项目路径比较前统一进行绝对路径规范化；新增、迁移和重定位都必须防止重复路径。
- 修改项目管理持久化结构时保留 `schemaVersion` 迁移入口并兼容既有数据。写回时继续合并未知顶层、设置和项目字段；解析失败时保留最后一次有效数据并阻止写入，不得覆盖损坏文件。
- 新增 Action、Service、Provider、Listener 或 Tool Window 时，同步更新 `plugin.xml`。
- 不要静默吞掉新异常。向用户展示可操作的错误信息，并在适合的位置记录日志；不得记录文件正文、密钥或剪贴板内容。

## 构建与本地开发

统一使用仓库内 Gradle Wrapper：

```bash
GRADLE_USER_HOME=/Users/seven/.gradle
```

```bash
./gradlew --offline clean build       # 编译、检查、测试并打包
./gradlew runIde            # 启动安装了当前插件的沙箱 IDEA
./gradlew --offline test              # 运行全部自动化测试
./gradlew verifyPlugin      # 检查 IntelliJ API/二进制兼容性
./gradlew buildPlugin       # 输出 build/distributions/*.zip
```

日常调试优先使用 `runIde`，覆盖添加/移除文件、分组切换、Markdown 导出，以及文件重命名、移动、删除后的同步。构建依赖下载失败时先检查 JDK 17、代理与 Gradle 缓存，不要提交本机环境配置。

## 编码规范

Kotlin/Java 使用 4 空格缩进；类名使用 PascalCase，方法和变量使用 camelCase，常量使用 UPPER_SNAKE_CASE。包名保持在 `com.aicode` 下，并按职责归档。Kotlin 优先使用空安全；Java 公开边界遵循 IntelliJ SDK 的 `@NotNull`/`@Nullable` 约定。Action 应实现 `DumbAware`（确实可在索引期间运行时）并声明合适的 `ActionUpdateThread`。保持 import 显式、方法短小、注释解释设计原因、线程约束或兼容性背景，而非复述代码。

仓库尚未配置 Spotless、Checkstyle 或其他自动格式化工具；提交前使用 IntelliJ 的 Reformat Code 和 Optimize Imports，并避免夹带无关格式化。

## 测试要求

当前仓库没有覆盖率门槛。新增可测试逻辑时，在 `src/test/kotlin/com/aicode/` 下建立与生产代码一致的包结构，测试类命名为 `*Test`，测试方法描述行为与结果。纯逻辑优先覆盖 `AICodeConfig`、`MarkdownBuilder` 和 `CodeLanguageResolver`；涉及 Project、VirtualFile、Action 或 Tool Window 的行为应使用 IntelliJ Platform test fixture，而不是模拟 SDK 内部实现。

每次功能变更至少验证：正常路径、空分组、重复文件、缺失文件、旧配置迁移、非项目文件、二进制/忽略文件，以及 VFS 重命名或移动。若引入测试框架，显式添加 `testImplementation` 依赖并在 PR 中说明。

项目管理模块应按风险补充验证：

- 服务、搜索、排序或路径逻辑：覆盖空输入、路径规范化、重复路径和缺失项目。
- 持久化或 schema 变化：覆盖读写往返、旧版本迁移、损坏/缺失数据、未知字段保留和身份稳定性。
- 目录扫描、复制或删除：覆盖识别标记、扫描深度与忽略目录、重名目标、符号链接、部分失败清理、根目录保护和缺失目录。
- UI 或 Action：使用 `runIde` 冒烟验证保存、添加、批量导入、编辑、复制、重定位、仅移除、移至废纸篓/永久删除、收藏、标签、搜索、欢迎页和当前/新窗口打开。
- `plugin.xml`、依赖或兼容范围变化：执行完整构建和 `verifyPlugin`。

## 提交与 Pull Request

历史提交以简短主题为主，并混用 `feat:`、`docs:`、`style:`、`modify:`。新提交统一建议使用 `<type>: <动词开头的说明>`，常用类型为 `feat`、`fix`、`refactor`、`test`、`docs`、`build`，例如：`fix: 同步目录移动后的上下文路径`。一个提交只处理一个逻辑变更。

PR 必须包含变更目的、关键实现、影响范围和实际执行的验证命令；关联 Issue（如有）。涉及 Tool Window、菜单、图标或通知时附截图/录屏；涉及 `.aicode.json` 时给出前后示例并说明兼容性；涉及支持版本、依赖、快捷键或扩展点时明确标注。合并前确保 `./gradlew build` 通过，且不提交 `.idea/`、`build/`、沙箱数据、证书或发布令牌。

## 发版规范

用户提出“发版”时，默认含义是：检查并提交当前工作区的未提交改动，将提交推送到远端当前分支，再基于最新版本创建并推送新的 Git 标签。除非用户明确要求，否则不要手动上传 JetBrains Marketplace、创建 GitHub Release 或修改其他发布渠道；推送 `v*` 标签后，现有 CI 会自动构建插件、创建 GitHub Release 并发布 GitHub Pages 更新源。

发版属于会修改 Git 历史和远端状态的操作。执行前必须先检查工作区、当前分支、远端跟踪分支和现有标签，向用户汇报将要提交的文件、建议的提交信息以及建议的新版本，并征询用户确认。不得擅自提交来源不明的改动，也不得在用户未确认版本级别时自行选择版本。

版本号和 Git 标签遵循语义化版本，标签格式为 `vX.Y.Z`。根据现有最新标签计算候选版本，并询问用户选择以下一种发版类型：

- `PATCH`：向后兼容的问题修复，例如 `1.5.3` → `1.5.4`。
- `MINOR`：向后兼容的新功能，例如 `1.5.3` → `1.6.0`。
- `MAJOR`：配置格式、交互或 API 存在不兼容变化，例如 `1.5.3` → `2.0.0`。

如果用户已经明确给出完整版本号，则使用该版本，但仍需检查标签是否已存在。若没有任何历史标签，应向用户确认初始版本，不得自行假设为 `v1.0.0`。如果当前改动的性质与用户选择的版本级别明显不一致，应说明原因并再次确认，不要静默更改选择。

### 标准发版流程

1. 执行只读检查：`git status --short`、`git diff --stat`、`git diff`、`git branch --show-current`、`git remote -v`、`git tag --sort=-version:refname`。必要时执行 `git fetch --tags` 获取远端最新标签。
2. 判断所有未提交改动是否属于本次发版。发现无关改动、敏感文件、构建产物或无法确认来源的文件时，暂停并请用户决定；不要擅自丢弃、暂存或提交。
3. 根据实际 diff 拟定符合本仓库规范的提交信息，例如 `fix: 修复文件移动后的上下文路径`。在执行提交前，向用户明确展示：

   - 本次准备提交的文件和变更摘要；
   - 完整提交信息；
   - 当前最新标签；
   - `MAJOR`、`MINOR`、`PATCH` 各自对应的新标签，并请用户选择，推荐项必须说明依据。

4. 获得用户确认后，运行与改动风险相匹配的测试。正式发版至少执行：

   ```bash
   ./gradlew clean build
   ```

   修改 `sinceBuild`/`untilBuild` 时额外执行 `./gradlew verifyPlugin`，并记录最低支持版本和目标高版本 IDEA 的冒烟测试结果。测试失败时停止发版，不得提交、推送或打标签。
5. 只暂存本次已确认的文件，提交后核对提交内容和提交信息：

   ```bash
   git add <confirmed-files>
   git commit -m "<type>: <动词开头的说明>"
   git show --stat --oneline HEAD
   ```

6. 获取当前分支名称，并将该提交明确推送到远端同名分支：

   ```bash
   git branch --show-current
   git push origin <current-branch>
   ```

   例如当前分支为 `main`，则执行 `git push origin main`。必须核对推送目标确实是当前分支；若当前分支没有 upstream、远端包含新提交或推送被拒绝，应停止并向用户说明，不得强制推送，也不得擅自 rebase、merge 或改推其他分支。
7. 确认当前分支已经成功推送到远端后，在刚推送的提交上创建附注标签，再将该标签推送到同一远端：

   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   ```

8. 最后核验远端分支和标签都指向本次提交，并向用户报告提交哈希、提交信息、分支、标签、测试结果和推送结果。

### 失败处理与安全约束

- 提交失败：保留工作区状态，说明错误原因；不得绕过 Git hooks，除非用户明确授权。
- 分支推送失败：不要创建标签，先解决远端分歧或权限问题。
- 标签创建或推送失败：不要移动、覆盖或复用已存在标签；检查冲突后请用户决定新的版本号。
- 分支已经推送但标签失败：明确告知用户当前处于“代码已发布、标签未发布”的中间状态，并在问题解决后只补做标签步骤。
- 禁止使用 `git push --force`、移动既有标签或删除远端标签来完成常规发版。
- 插件签名及 Marketplace 发布使用 `CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`、`PUBLISH_TOKEN`；只有用户明确要求上传 Marketplace 时才执行，并且必须另行确认发布版本与 channel。凭据只能通过本地环境或 CI Secret 注入，禁止写入代码、`gradle.properties`、命令行参数、日志和 PR。
