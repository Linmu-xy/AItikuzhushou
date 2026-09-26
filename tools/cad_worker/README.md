# CAD Worker

第 1 阶段的隔离 CAD Worker。它负责把模型或工程图转换为结构化事实和可预览的 OBJ，不直接访问数据库，也不参与现有题库任务。

## 本地验证

先安装隔离依赖：

```powershell
$python = "C:\Users\lxy17\.codex\...\python.exe"
$env:PIP_TRUSTED_HOST = "pypi.org files.pythonhosted.org pypi.python.org"
& $python -m pip install -r tools/cad_worker/requirements.txt
```

运行 STEP：

```powershell
$env:PYTHONPATH = "C:\Users\lxy17\.cache\codex-cad-runtime"
& $python tools/cad_worker/worker.py `
  --input "D:\path\model.step" `
  --output "tmp/cad-worker/model.analysis.json" `
  --preview "tmp/cad-worker/model.obj"
```

当前 Worker：

- 使用 Open Cascade 读取 STEP/STP 的真实 B-Rep；
- 计算包围盒、体积、表面积、实体数量；
- 统计平面、圆柱面、圆锥面、球面、环面和 B-Rep 实体；
- 提取圆柱半径集合；
- 输出 OBJ 三角网格预览；
- 使用 `pypdf` 读取 PDF 文本和尺寸 token；
- 使用 `ezdxf` 读取 DXF 图元和尺寸实体；
- 对 PRT/DWG/X_T 明确返回适配器缺失，不输出虚假几何事实。

生产环境由后端通过受限子进程调用，输入文件和输出目录必须位于任务专属临时目录，且要配置超时、内存和三角面数量上限。
