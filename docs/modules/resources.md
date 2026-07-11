# Resources 模块

_最后复核：2026-07-12_

## 模块定位

Resources 模块约束 `src/main/resources` 下字体、图标、兜底图片和日志配置等随包资源。它不是业务逻辑层，但会直接影响绘图效果、部署包体积、运行内存和日志行为。

## 代码入口

- `src/main/resources/font/*`
- `src/main/resources/icon/*`
- `src/main/resources/image/*`
- `src/main/resources/webui/react/*`
- `src/main/resources/logback.xml`
- 相关加载代码：`FontUtils`、`ImageCache`、`draw/*`、`skia/*`、`webui/*`

当前随包资源包括 `FansCard.ttf`、`SourceHanSansSC-Regular.otf` 及字体授权，Bilibili/动态类型 SVG 图标，`admin_help.png`、`HELP.png`、`IMAGE_MISS.png`、`Blocked_BG_Day.png`，以及 WebUI 的 `index.html`、`assets/app.css`、`assets/app.js`。

## 主要职责

- 提供绘图所需字体和授权说明。
- 提供 SVG 图标和兜底图片资源。
- 提供 WebUI 打包后的静态 shell 资源。
- 提供默认日志配置。
- 保持资源路径与代码加载路径一致。

## 禁止事项

- 禁止替换字体或图片后不确认授权和包体影响。原因：资源会随发行包分发。
- 禁止修改资源文件名但不同步代码引用和测试。原因：运行时资源加载失败通常只在绘图路径暴露。
- 禁止提交临时输出、截图产物或调试缓存到 `src/main/resources`。原因：会污染发行包。
- 禁止手工编辑 `src/main/resources/webui/react` 的生成产物。原因：该目录由 `webui-frontend` 构建刷新，手改会和源前端脱节。
- 禁止在 `logback.xml` 中输出敏感 cookie、token 或完整授权头。原因：日志可能被用户直接上传排障。

## 关键流程

新增资源前先判断它是否属于运行时必需资源。绘图资源必须能由现有加载工具找到；日志配置变更必须兼容 Docker 和裸机运行；字体变更必须同时考虑 Skia 段落布局、fallback 和 native 内存。WebUI 静态文件由 `webui-frontend` 构建复制，只能从前端源代码重新生成。

## 资源与生命周期

资源文件本身随 classpath 存在。加载后的字体、图片、SVG、paragraph 或缓存对象必须由 utils、draw、skia 或 cleanup tasker 管理，不能因为资源是静态文件就跳过运行期释放策略。

## 配置与数据

Resources 不写配置。资源路径若暴露为配置项，必须在 config 文档中说明默认值、迁移策略和缺失时的降级行为。

## 测试与验证

- 修改字体、图标或兜底图片后，运行资源加载、绘图或打包相关检查。
- 修改 WebUI 前端后，运行 `webui-frontend` 的 test/build；需要浏览器流程保证时再运行 e2e，并用 `./gradlew processResources` 检查静态产物进入 JVM 资源目录。
- 修改 `logback.xml` 后，检查 Docker 和裸机日志路径、敏感信息屏蔽和错误日志策略。
- 新增随包资源后，检查包体积、授权来源、路径大小写和代码加载入口。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认资源当前由哪段代码加载？
- [ ] 是否核对资源路径大小写、文件名和 classpath 打包结果？
- [ ] 是否区分运行时资源和 docs 示例图片？

## 变更 checklist

- [ ] 是否保留或更新字体、图标、图片的授权说明？
- [ ] 是否同步修改所有代码引用、测试 fixture 和文档截图说明？
- [ ] 是否影响绘图尺寸、字体 fallback、缓存或 native 内存？若是，是否更新 draw/skia 文档？
- [ ] 是否运行资源加载、绘图或打包相关检查？

## 新建 checklist

- [ ] 新资源是否确实需要进入发行包，而不是 docs、test resources 或外部配置？
- [ ] 新资源是否有稳定命名、授权来源和加载入口？
- [ ] 新资源是否有缺失、损坏或平台不支持时的降级路径？
- [ ] 新资源是否会显著增加 Docker 镜像或发行包体积？
