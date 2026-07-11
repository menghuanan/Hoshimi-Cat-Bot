# 仓库根目录文件维护约束

## 文档定位

本文约束仓库根目录中不属于源码包、构建脚本或 `docs` 分区的说明文件、元数据文件和开发工具配置。构建、Docker、CI 和发布 workflow 仍以 [`build-ci-release.md`](build-ci-release.md) 为主入口。

## 文件入口

- `README.md`
- `README-MIRAI-ORIGINAL.md`
- `LICENSE`
- `.editorconfig`
- `.gitattributes`
- `.gitignore`
- `.env.example`
- `.dockerignore`
- 其他新增根目录文件

## 主要职责

- 说明项目面向用户和维护者的基础信息。
- 约束编辑器、换行、忽略规则、示例环境变量和授权信息。
- 防止根目录成为绕过模块、构建或文档维护入口的临时堆放区。

## 禁止事项

- 禁止把长期模块约束只写入 `README.md`。原因：模块约束必须能从 `docs/AGENTS.md` 和对应维护文档追踪。
- 禁止在 `.env.example`、README 或示例命令中写入真实 cookie、token、管理员账号或私有地址。原因：根目录文件容易被公开分发。
- 禁止新增根目录临时报告、截图、诊断输出或本地缓存。原因：这类内容应进入 `docs/plans`、`logs`、`temp` 或本地忽略路径。
- 禁止修改 `.gitignore`、`.gitattributes` 或 `.dockerignore` 后不检查构建、发布和源码编码影响。原因：这些文件会改变提交与发行包边界。

## 关键流程

根目录文件变更先判断归属：用户说明和兼容注意写入 README；构建、Docker、CI、发布流程写入 [`build-ci-release.md`](build-ci-release.md) 或相关脚本；长期开发约束写入 `docs/development`、`docs/modules`、`docs/architecture`、`docs/domain` 或 `docs/operations`；一次性计划或报告写入 `docs/plans`。

## 资源与生命周期

根目录文件通常不拥有运行期资源。若新增文件会进入发行包、Docker build context 或源码分发包，必须说明它的维护者、更新时机、是否可自动生成，以及是否需要加入忽略或打包规则。

## 配置与数据

`.env.example` 只能保存无敏感值的示例变量和安全默认值；真实运行配置仍归属 `config` 模块和部署文档。修改忽略规则或属性规则时，必须确认不会隐藏应提交的源码、文档、资源或测试 fixture。

`.gitignore` 当前显式放行 `docs/AGENTS.md`、`docs/bugs.md`、`architecture`、`context`、`development`、`domain`、`modules`、`operations` 和 `release`。新增当前维护文档必须能被 Git 发现；`docs/plans`、`docs/规则` 与 `docs/过期文档` 仍保持忽略，不能把取消忽略当作改变其权威级别的捷径。

## 测试与验证

- README 或示例命令变更后，至少检查命令、路径和版本描述是否与构建、部署文档一致。
- `.dockerignore` 变更后，检查 Docker build context 是否仍包含运行必需资源。
- `.gitattributes`、`.editorconfig` 变更后，检查 UTF-8、换行和脚本可执行性约束是否仍符合根目录 `AGENTS.md`。
- `.gitignore` 变更后，检查新增忽略规则不会屏蔽源码、测试、构建脚本或当前维护文档。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象确实是根目录文件，而不是模块文档、构建脚本或 release 文档？
- [ ] 是否区分用户说明、开发约束、构建规则、发布追溯和历史背景？
- [ ] 是否避免把 README 中的过时描述当作当前模块约束？

## 变更 checklist

- [ ] 变更是否会影响用户安装、部署、升级或故障排查路径？
- [ ] 是否需要同步更新 `docs/AGENTS.md`、模块文档、构建文档或运维文档？
- [ ] 是否确认没有引入真实密钥、私有地址、临时诊断输出或本地产物？
- [ ] 是否保留既有授权、来源说明和兼容提示？

## 新建 checklist

- [ ] 新根目录文件是否无法归入已有源码、资源、docs、logs、temp 或配置目录？
- [ ] 新文件是否有明确维护者、用途、提交理由和更新时机？
- [ ] 新文件是否需要同步更新 `.gitignore`、`.dockerignore`、构建脚本或文档入口？
- [ ] 新文件是否避免成为绕过模块约束、release 所有者维护边界或当前文档体系的入口？
