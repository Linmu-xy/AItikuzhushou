# CAD Worker 部署说明

资料智能组卷模式的 CAD 解析运行在独立 Worker 中，不与现有题库 JVM 共用原生 CAD 库。

## 依赖

```powershell
python -m pip install -r tools/cad_worker/requirements.txt
```

当前依赖包含：

- Open Cascade Python bindings：STEP/STP B-Rep 读取和网格预览；
- ezdxf：DXF 图元和标注实体读取；
- pypdf：PDF 文本和页面读取。

## 后端环境变量

```text
APP_CAD_WORKER_COMMAND=C:\path\to\python.exe
APP_CAD_WORKER_SCRIPT=..\tools\cad_worker\worker.py
APP_CAD_WORKER_PYTHONPATH=C:\path\to\cad-worker-runtime
APP_CAD_WORK_ROOT=..\cad-work
APP_CAD_WORKER_TIMEOUT_SECONDS=300
APP_CAD_PREVIEW_DEFLECTION=0.5
APP_CAD_PREVIEW_MAX_TRIANGLES=250000
APP_CAD_MAX_FILE_SIZE=67108864
```

生产环境必须使用固定的 Python 运行时路径，不能依赖 Windows Store 的 `python` 别名。Worker 进程应配置独立的临时目录、超时、内存和三角面数量限制。

## 格式适配边界

- STEP/STP：当前可实际解析 B-Rep 和生成 OBJ 预览；
- PDF：当前读取文本、页数和可识别的尺寸 token；
- DXF：当前读取图元和标注实体；
- X_T：需要 Open Cascade/Parasolid 适配器后再开放几何事实；
- PRT：需要 NX/Siemens 接口或 STEP 转换服务；
- DWG：需要 ODA/AutoCAD 适配器。

Worker 输出的事实均默认 `verified=false`、`usableForGeneration=false`，只有老师确认后才能进入后续命题流程。
