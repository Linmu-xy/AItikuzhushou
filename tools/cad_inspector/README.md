# CAD / 工程图解析能力验证工具

这是资料智能组卷模式第 0 阶段的隔离验证工具。它只读取用户指定的资料目录，输出结构化的解析能力报告，不接入现有题库业务，也不会修改现有数据库、前端或生产配置。

## 用法

```powershell
$python = "C:\Users\lxy17\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $python tools/cad_inspector/inspect.py `
  --root "D:\Documents\题库助手单\新加功能样题" `
  --output "tmp/cad-inspection-report.json"
```

也可以限制扫描文件数量，先验证小样本：

```powershell
& $python tools/cad_inspector/inspect.py `
  --root "D:\Documents\题库助手单\新加功能样题" `
  --extensions .step .stp .x_t .prt .dwg .pdf `
  --max-files 30
```

## 当前阶段支持边界

| 格式 | 当前处理 | 说明 |
| --- | --- | --- |
| STEP/STP | 实际读取文本实体、单位、笛卡尔点、几何实体统计和近似包围盒 | 包围盒为点集近似，不冒充 B-Rep 精确结果 |
| STL | 实际读取 ASCII/二进制三角网格、包围盒、面积和体积 | 依赖文件单位说明；未声明单位时不猜测 |
| OBJ | 实际读取顶点、面和包围盒 | 仅使用文件自身几何，不推断单位 |
| PDF | 实际读取页数、文本、页级尺寸 token | 工程图完整标注识别仍需专用 CAD/PDF 适配器 |
| X_T | 读取 Parasolid 文件签名和头部信息 | 几何解析需要 Open Cascade/Parasolid 运行时 |
| PRT | 读取文件签名和基础文件信息 | 需要 NX/Siemens 适配器或转换服务 |
| DWG | 读取版本签名和基础文件信息 | 需要 ODA/AutoCAD 等专用适配器 |

报告 schema 为 `cad-fact-probe.v1`，后续正式 CAD Worker 将兼容该事实结构，但不会把本阶段的近似事实直接作为生产出题依据。
