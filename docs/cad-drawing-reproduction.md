# 工程图复现 MVP

当前首版支持把 CAD 导出的 PDF 工程图按固定高清 DPI 渲染成可预览页面，并将页面作为带页码的 CAD 预览资产保存。它保留原 PDF 的版式，不把 PDF 中不可提取的矢量文字伪装成已识别尺寸。

## 支持范围

- PDF：分析时按 `APP_CAD_DRAWING_DPI` 渲染页面，默认 300 DPI；
- 页面数：默认最多渲染 40 页，可由 `APP_CAD_DRAWING_MAX_PAGES` 调整；
- STEP/STP：继续使用现有 OpenCascade 事实解析和 OBJ 预览；
- DWG/PRT/X_T：仍需专用适配器，不会被标记为已完成图纸解析。

## 使用入口和解析边界

工程图 PDF 应通过 CAD 模型/图纸资料入口上传。普通“知识库资料”入口面向文字资料和职业标准，不会把 PDF 的矢量线段、尺寸箭头、剖面关系自动还原成 CAD 语义。

如果 CAD 导出的 PDF 被误传到普通资料入口，系统会根据 CAXA、AutoCAD、SolidWorks、CATIA、NX、Creo、Inventor 等 PDF 元数据跳过普通表格 OCR，保留原页并标记为待复核，避免把图框和尺寸线误识别成空白表格。

高清页面资产只表示页面视觉复现，不表示尺寸、形位公差或视图关系已经结构化识别。要进入自动出题，仍需后续完成 DXF/STEP 交叉校验和人工确认。

## 页面接口

```text
GET /api/cad-materials/{materialId}/analysis
GET /api/cad-materials/{materialId}/drawing-pages/{page}/image
```

分析响应的 `previewAssets` 中，`assetType=DRAWING_PAGE` 的记录对应高清复现页。页面在人工确认前只作为视觉依据，不能直接成为正式出题事实。

## 下一步

后续再在页面资产之上增加视图、剖面和尺寸标注的结构化识别，并使用 DXF/STEP 做交叉校验。只有通过人工确认的标注，才允许进入参数化变式或图示题生成。
