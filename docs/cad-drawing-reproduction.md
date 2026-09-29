# 工程图复现 MVP

当前首版支持把 CAD 导出的 PDF 工程图按固定高清 DPI 渲染成可预览页面，并将页面作为带页码的 CAD 预览资产保存。它保留原 PDF 的版式，不把 PDF 中不可提取的矢量文字伪装成已识别尺寸。

## 支持范围

- PDF：分析时按 `APP_CAD_DRAWING_DPI` 渲染页面，默认 300 DPI；
- 页面数：默认最多渲染 40 页，可由 `APP_CAD_DRAWING_MAX_PAGES` 调整；
- STEP/STP：继续使用现有 OpenCascade 事实解析和 OBJ 预览；
- DWG/PRT/X_T：仍需专用适配器，不会被标记为已完成图纸解析。

## 页面接口

```text
GET /api/cad-materials/{materialId}/analysis
GET /api/cad-materials/{materialId}/drawing-pages/{page}/image
```

分析响应的 `previewAssets` 中，`assetType=DRAWING_PAGE` 的记录对应高清复现页。页面在人工确认前只作为视觉依据，不能直接成为正式出题事实。

## 下一步

后续再在页面资产之上增加视图、剖面和尺寸标注的结构化识别，并使用 DXF/STEP 做交叉校验。只有通过人工确认的标注，才允许进入参数化变式或图示题生成。
