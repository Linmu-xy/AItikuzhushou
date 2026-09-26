# 资料智能组卷模式：CAD / 工程图第 0 阶段验证报告

日期：2026-09-21

## 阶段目标

在不改动现有题库、命题、导出和生产配置的前提下，读取真实样题目录，确认三维模型和工程图解析的格式边界，并建立后续 CAD Worker 的结构化事实协议。

## 本阶段新增

- `tools/cad_inspector/inspect.py`：只读格式探测与基础事实提取器。
- `tools/cad_inspector/test_inspect.py`：STEP、STL 和专有格式边界测试。
- `tools/cad_inspector/README.md`：使用方式和解析边界说明。

## 实测结果

对 `D:\Documents\题库助手单\新加功能样题` 扫描了 62 个相关文件：

| 状态 | 数量 | 含义 |
| --- | ---: | --- |
| `PARSED_PARTIAL` | 34 | PDF 或 STEP 已实际读取，但还不是生产级完整几何解析 |
| `ADAPTER_REQUIRED` | 28 | PRT、DWG、X_T 已识别，但当前环境没有对应 CAD 解析运行时 |

### STEP/STP

已实际读取到：

- STEP schema；
- 实体总数；
- 笛卡尔点数量；
- 平面、圆柱面、圆锥面、球面、环面；
- B-Rep 相关实体数量；
- 单位信息；
- 基于笛卡尔点的近似包围盒。

例如当前样题中的 STEP 模型能够读出数万级笛卡尔点、数千个曲面实体和明确的毫米单位。

当前包围盒只用于能力验证，不能直接作为正式题目事实，因为它还没有经过 B-Rep 精确几何计算和人工确认。

### PRT

样题中的 PRT 文件签名已识别为 Siemens/NX 类专有格式，但当前环境没有 NX/Siemens 解析接口，因此报告为 `ADAPTER_REQUIRED`，不会假装已读取模型内容。

### DWG

样题中的 DWG 文件版本签名已识别，但 DWG 几何和标注读取需要 ODA/AutoCAD 等专用适配器。

### X_T

样题中的 X_T 文件已识别为 Parasolid 文本格式，但完整几何读取需要 Open Cascade 或 Parasolid 运行时。

### PDF

已实际读取：

- 页数；
- 文本内容；
- 页面级尺寸 token；
- 文件哈希。

工程图中的矢量尺寸、箭头、形位公差、图框和视图关系仍需工程图专用解析器。

## 当前结论

第 0 阶段确认了三件事：

1. 资料目录中的 STEP/STP 和 PDF 可以进入后续解析流程；
2. PRT、DWG、X_T 不能靠现有 Java/PDF 依赖完成，需要独立 CAD 适配器；
3. 生产环境不能把“文件识别”当作“模型已解析”，所有未完成的格式都会被明确阻断或标记为待处理。

## 进入第 1 阶段的前置条件

下一阶段将建设独立 CAD Worker 和模型预览接口。正式接入前需要确定至少一种生产级 CAD 解析方案：

- Open Cascade/FreeCAD headless，用于 STEP/STP/X_T/IGES；
- ODA/AutoCAD 适配器，用于 DWG；
- NX/Siemens 接口或 STEP 转换服务，用于 PRT。

当前代码没有修改现有业务类、数据库迁移、前端页面或生产配置。
