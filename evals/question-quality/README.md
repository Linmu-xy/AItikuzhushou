# 出题质量回归

这是离线工程回归，不是教师金标准题集，也不是现场调用模型的质量分数。

## 运行

本仓库的精简 Promptfoo 安装面向 Windows x64，需 JDK 21、Maven、Node >=22.22 和 PowerShell 7；Maven 不在 PATH 时设置 `TIKU_MAVEN_CMD`。其他平台可直接运行 Java 回归；使用 Promptfoo 需替换成本平台的 libsql 原生包。

```powershell
cd evals/question-quality
npm ci --ignore-scripts --omit=optional
npm test
npm run eval
```

`npm run eval` 先编译当前 Java、执行固定案例，再由 Promptfoo 回放实际 Java 结果，生成 `results/latest.json` 和 `results/latest.html`。不会把预期结果当作模型输出，不会消费 DeepSeek token，不加载根目录 `.env`，关闭缓存、遥测、更新检查和分享。结果目录已忽略，不自动上传。

- 27 个审查契约案例：通过、实质失败、审查不完整/矛盾分别处理。
- 10 个题型策略案例：只加载当前题型策略，包括判断题与实操题。
- 4 个计算案例：舍入、中间值错误和禁止执行代码。
- 其余盲解隔离、联网搜索隐私/预算、失败重试、评分总分、人工修改保护在后端完整测试中验证。

运行 `mvn test` 可直接执行 Java 回归，不依赖 Node/Promptfoo。

本地 `.npmrc` 默认不装可选的浏览器、Agent SDK、云服务和嵌入模型依赖，离线回放不需要它们；安装不执行第三方脚本。显式固定启动必需的 `hono` 和 Windows x64 `libsql` 组件，避免上游可选依赖分类导致启动失败。

## 质量与成本怎么比较

线上生成继续走原有鉴权、额度、搜索预算和人工审核，不由 Promptfoo 绕过后端直接调用模型。
使用 `scripts/Test-AssessmentOptimization.ps1` 的新 Revision，复用同一冻结计划；调用前报告预算，禁止自动重复付费实验。
按实际 usage 汇总输入、输出、推理、失败恢复和重写；reasoning 是 completion 的子集，不能再次相加。
缓存回放不是新请求，不能用它的零 token/延迟声称线上节省。模型审查通过不等于教师验收通过。

人工评审独立标注：答案正确、条件充分、目标匹配、真实考核价值、干扰项质量、评分公平、组卷多样性。
用 `human-review-template.json` 记录未评/通过/需改/不适用；未知不得当作通过。每个学科先收集至少30道专家标注样本，另留未参与调参的测试集。不能靠平均分掩盖答案错误。

当前没有教师金标准，因此不启动 GEPA 自动提示词优化，也不宣称质量提升百分比。

## 采用与未采用的外部方案

- [Context Engineering Skills](https://github.com/muratcankoylan/Agent-Skills-for-Context-Engineering)：仅安装 `context-optimization`、`evaluation`，固定提交 `6dbe1a1d868eab51a3bc9011b0f55e2891513e40`。采用稳定提示前缀、题型按需加载、实测和分维度回归，不照搬其节省比例/阈值。
- [Quiz Generator](https://github.com/SkillMedev/skills/tree/main/skills/quiz-generator)、[Rubric Builder](https://github.com/SkillMedev/skills/tree/main/skills/rubric-builder)：借鉴能力目标、典型误解、可观察评分方法；未安装、不复制其固定认知比例/题型禁令。
- [Promptfoo](https://github.com/promptfoo/promptfoo)：独立开发依赖，固定 0.123.1，不进入生产 Java 或前端包。当前只用自定义本地回放和确定性断言。
- GEPA/DSPy、LLMLingua 暂不接入：缺少专家标签，不追加大量评测调用，也不压缩掉关键单位/条件。

安装开发技能不会自动改变 DeepSeek 的运行时行为。本项目实际生效的是 `AssessmentAuthorStrategy`、`AssessmentReviewGate` 及 `OpenAssessmentService` 的接线。
