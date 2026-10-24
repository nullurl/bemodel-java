# ForgePilot 工业管理集成验证

## 干净分支验证

基线：`78b4be93b03dbabca044ac06aad2b85557377873`。所有 Java 源码与测试源码均重新编译，未使用本机部署 JAR 的类作为构建输入。

- Maven 20 项通过：IndustrialBoundaryFilterTest（4）、RelationQueryServiceTest（4）、ReleaseClosureGoldenTest（4）、ConnectorFactoryServiceTest（8）。
- `mvn package -DskipTests` 成功生成可执行 JAR，测试已在前一步执行。
- 工业前端 `npm ci` 和 `npm run build:forgepilot` 成功。
- 本体图 Node 测试4项通过，迁移与目录 Python 测试5项通过。
- 当前本体目录的四份跨仓库副本 SHA-256 一致；模型生成与源字段、单位和引用完整性测试通过。

运行命令：

```sh
cd bemodel-server
mvn test -Dtest=IndustrialBoundaryFilterTest,RelationQueryServiceTest,ReleaseClosureGoldenTest,ConnectorFactoryServiceTest
mvn package -DskipTests
cd ../bemodel-web
npm ci
npm run build:forgepilot
node --test tests/*.test.mjs
cd ..
python3 -m unittest discover -s forgepilot/tests -v
```

## 本机部署接口验证

此前在保留的工业数据库中验证：11份初始连接器、413条映射契约（126条现有演示路径、287条生产字段待确认），重复初始化不覆盖；旧版本写入拒绝，保存使旧校验失效；匿名401，VIEWER/REVIEWER写入403；明文凭据与单位不一致拒绝；重启保留版本与校验结果；模板创建、重复编码拦截、字段编辑持久化及留痕通过。临时测试连接器已清理。

样本验证采用现有 ForgePilot 只读批次快照：当前样本通过、重复身份和布尔数量被拒绝，空身份及移出必填校验被拒绝。样本原文未入库，原业务快照修订号和内容摘要未变化。

本体管理验证：45节点/58关系的总图、概念邻域、Lot出向闭包10项与反向18项可查；未适配医疗路由及医疗MCP在工业配置中拒绝；实例页等待当前概念目录后选取存在的概念，不再使用FEE_DETAIL。

## 范围

当前版本维护本体及只读接入契约草稿，不连接生产ERP/MES/财务，不自动同步，不将Java草稿发布到ForgePilot运行器，不发送链上交易。HTTP/MySQL/JSON协议在工厂中为契约选项；已有真实MySQL扫描仍使用原JDBC工作台。

本机运行库、密钥、数据库备份、编译产物与绝对路径测试日志均未纳入提交。
