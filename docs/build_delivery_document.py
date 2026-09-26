from __future__ import annotations

from datetime import date
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(r"D:\code\tikuzhushou")
OUT_DIR = ROOT / "docs" / "delivery"
QA_DIR = OUT_DIR / "qa"
OUT_PATH = OUT_DIR / "题库助手项目交付与部署运维手册_V1.0.docx"
ARCH_PATH = QA_DIR / "system-architecture.png"

BLUE = "2563EB"
NAVY = "17365D"
INK = "172033"
MUTED = "68768E"
LIGHT_BLUE = "E8EEF5"
LIGHT_GRAY = "F2F4F7"
PALE_BLUE = "EEF5FF"
PALE_GOLD = "FFF7E6"
GREEN = "15803D"
RED = "B91C1C"
WHITE = "FFFFFF"
DXA_TOTAL = 9360


def set_run_font(run, ascii_name="Calibri", east_asia="Microsoft YaHei", size=None,
                 color=INK, bold=None, italic=None):
    run.font.name = ascii_name
    run._element.get_or_add_rPr().rFonts.set(qn("w:ascii"), ascii_name)
    run._element.get_or_add_rPr().rFonts.set(qn("w:hAnsi"), ascii_name)
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), east_asia)
    if size is not None:
        run.font.size = Pt(size)
    if color:
        run.font.color.rgb = RGBColor.from_string(color)
    if bold is not None:
        run.bold = bold
    if italic is not None:
        run.italic = italic


def shade_cell(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=80, start=120, bottom=80, end=120):
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for tag, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{tag}"))
        if node is None:
            node = OxmlElement(f"w:{tag}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_repeat_table_header(row):
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def prevent_row_split(row):
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    tr_pr.append(cant_split)


def set_table_geometry(table, widths_dxa):
    table.autofit = False
    table.alignment = WD_TABLE_ALIGNMENT.LEFT
    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(sum(widths_dxa)))
    tbl_w.set(qn("w:type"), "dxa")
    tbl_ind = tbl_pr.find(qn("w:tblInd"))
    if tbl_ind is None:
        tbl_ind = OxmlElement("w:tblInd")
        tbl_pr.append(tbl_ind)
    tbl_ind.set(qn("w:w"), "120")
    tbl_ind.set(qn("w:type"), "dxa")
    grid = table._tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for width in widths_dxa:
        col = OxmlElement("w:gridCol")
        col.set(qn("w:w"), str(width))
        grid.append(col)
    for row in table.rows:
        for idx, cell in enumerate(row.cells):
            width = widths_dxa[min(idx, len(widths_dxa) - 1)]
            tc_pr = cell._tc.get_or_add_tcPr()
            tc_w = tc_pr.find(qn("w:tcW"))
            if tc_w is None:
                tc_w = OxmlElement("w:tcW")
                tc_pr.append(tc_w)
            tc_w.set(qn("w:w"), str(width))
            tc_w.set(qn("w:type"), "dxa")
            set_cell_margins(cell)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER


def style_table_text(table, header=True, font_size=9.5):
    for r_idx, row in enumerate(table.rows):
        prevent_row_split(row)
        if r_idx == 0 and header:
            set_repeat_table_header(row)
        for cell in row.cells:
            if r_idx == 0 and header:
                shade_cell(cell, LIGHT_BLUE)
            for p in cell.paragraphs:
                p.paragraph_format.space_before = Pt(0)
                p.paragraph_format.space_after = Pt(2)
                p.paragraph_format.line_spacing = 1.08
                for run in p.runs:
                    set_run_font(run, size=font_size, bold=(r_idx == 0 and header))


def add_table(doc, headers, rows, widths_dxa, font_size=9.5):
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    for idx, value in enumerate(headers):
        table.rows[0].cells[idx].text = str(value)
    for row in rows:
        cells = table.add_row().cells
        for idx, value in enumerate(row):
            cells[idx].text = str(value)
    set_table_geometry(table, widths_dxa)
    style_table_text(table, header=True, font_size=font_size)
    doc.add_paragraph().paragraph_format.space_after = Pt(0)
    return table


def add_paragraph(doc, text="", bold_prefix=None, style=None, color=INK, italic=False,
                  align=None, keep=False):
    p = doc.add_paragraph(style=style)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(6)
    p.paragraph_format.line_spacing = 1.25
    if align is not None:
        p.alignment = align
    if keep:
        p.paragraph_format.keep_with_next = True
    if bold_prefix and text.startswith(bold_prefix):
        first = p.add_run(bold_prefix)
        set_run_font(first, size=11, color=color, bold=True)
        rest = p.add_run(text[len(bold_prefix):])
        set_run_font(rest, size=11, color=color, italic=italic)
    else:
        run = p.add_run(text)
        set_run_font(run, size=11, color=color, italic=italic)
    return p


def add_bullets(doc, items, level=0):
    for item in items:
        p = doc.add_paragraph(style="List Bullet" if level == 0 else "List Bullet 2")
        p.paragraph_format.left_indent = Inches(0.375 + 0.25 * level)
        p.paragraph_format.first_line_indent = Inches(-0.188)
        p.paragraph_format.space_after = Pt(4)
        p.paragraph_format.line_spacing = 1.25
        run = p.add_run(item)
        set_run_font(run, size=11)


def add_numbered(doc, items):
    numbering = doc.part.numbering_part.element
    style_num_pr = doc.styles["List Number"].element.pPr.numPr
    base_num_id = str(style_num_pr.numId.val)
    base_abstract_id = None
    existing_ids = []
    for num in numbering.findall(qn("w:num")):
        num_id = num.get(qn("w:numId"))
        if num_id is not None:
            existing_ids.append(int(num_id))
        if num_id == base_num_id:
            abstract = num.find(qn("w:abstractNumId"))
            if abstract is not None:
                base_abstract_id = abstract.get(qn("w:val"))
    if base_abstract_id is None:
        raise RuntimeError("Unable to resolve the List Number abstract numbering definition")

    num_id = max(existing_ids, default=0) + 1
    num = OxmlElement("w:num")
    num.set(qn("w:numId"), str(num_id))
    abstract = OxmlElement("w:abstractNumId")
    abstract.set(qn("w:val"), base_abstract_id)
    num.append(abstract)
    override = OxmlElement("w:lvlOverride")
    override.set(qn("w:ilvl"), "0")
    start = OxmlElement("w:startOverride")
    start.set(qn("w:val"), "1")
    override.append(start)
    num.append(override)
    numbering.append(num)

    for item in items:
        p = doc.add_paragraph(style="List Number")
        num_pr = p._p.get_or_add_pPr().get_or_add_numPr()
        num_pr.get_or_add_ilvl().val = 0
        num_pr.get_or_add_numId().val = num_id
        p.paragraph_format.left_indent = Inches(0.375)
        p.paragraph_format.first_line_indent = Inches(-0.188)
        p.paragraph_format.space_after = Pt(5)
        p.paragraph_format.line_spacing = 1.25
        run = p.add_run(item)
        set_run_font(run, size=11)


def add_code(doc, lines):
    for line in lines:
        p = doc.add_paragraph(style="Code Block")
        p.paragraph_format.space_after = Pt(0)
        run = p.add_run(line)
        set_run_font(run, ascii_name="Consolas", east_asia="Microsoft YaHei", size=9.2, color=NAVY)


def add_callout(doc, title, body, fill=PALE_BLUE, accent=BLUE):
    table = doc.add_table(rows=1, cols=1)
    table.style = "Table Grid"
    cell = table.cell(0, 0)
    shade_cell(cell, fill)
    p = cell.paragraphs[0]
    p.paragraph_format.space_after = Pt(4)
    r = p.add_run(title)
    set_run_font(r, size=10.5, color=accent, bold=True)
    p2 = cell.add_paragraph()
    p2.paragraph_format.space_after = Pt(0)
    p2.paragraph_format.line_spacing = 1.2
    r2 = p2.add_run(body)
    set_run_font(r2, size=10.5, color=INK)
    set_table_geometry(table, [DXA_TOTAL])
    prevent_row_split(table.rows[0])
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def add_heading(doc, text, level=1):
    p = doc.add_paragraph(style=f"Heading {level}")
    p.paragraph_format.keep_with_next = True
    run = p.add_run(text)
    return p


def add_page_number(paragraph):
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run("第 ")
    set_run_font(run, size=9, color=MUTED)
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = " PAGE "
    fld_sep = OxmlElement("w:fldChar")
    fld_sep.set(qn("w:fldCharType"), "separate")
    text = OxmlElement("w:t")
    text.text = "1"
    fld_end = OxmlElement("w:fldChar")
    fld_end.set(qn("w:fldCharType"), "end")
    run._r.extend([fld_begin, instr, fld_sep, text, fld_end])
    tail = paragraph.add_run(" 页")
    set_run_font(tail, size=9, color=MUTED)


def make_architecture(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (1600, 900), "#F7F9FC")
    draw = ImageDraw.Draw(image)
    font_path = r"C:\Windows\Fonts\msyh.ttc"
    bold_path = r"C:\Windows\Fonts\msyhbd.ttc"
    title_font = ImageFont.truetype(bold_path, 42)
    box_title = ImageFont.truetype(bold_path, 28)
    body_font = ImageFont.truetype(font_path, 22)
    small_font = ImageFont.truetype(font_path, 19)

    draw.text((60, 38), "题库助手生产架构（Windows 原生部署）", fill="#17365D", font=title_font)

    def box(x, y, w, h, title, lines, fill="#FFFFFF", border="#9FB5D3"):
        draw.rounded_rectangle((x, y, x + w, y + h), radius=18, fill=fill, outline=border, width=4)
        draw.text((x + 24, y + 20), title, fill="#17365D", font=box_title)
        yy = y + 70
        for line in lines:
            draw.text((x + 24, yy), line, fill="#42526B", font=body_font)
            yy += 36

    def arrow(x1, y1, x2, y2, label=""):
        draw.line((x1, y1, x2, y2), fill="#2563EB", width=5)
        if x2 >= x1:
            pts = [(x2, y2), (x2 - 18, y2 - 11), (x2 - 18, y2 + 11)]
        else:
            pts = [(x2, y2), (x2 + 18, y2 - 11), (x2 + 18, y2 + 11)]
        draw.polygon(pts, fill="#2563EB")
        if label:
            tx = (x1 + x2) // 2 - 45
            ty = min(y1, y2) - 32
            draw.text((tx, ty), label, fill="#2563EB", font=small_font)

    box(70, 185, 300, 190, "用户访问层", ["浏览器", "HTTPS / 统一域名", "管理员 / 编辑 / 查看"], "#EEF5FF")
    box(510, 155, 340, 250, "应用层", ["React + TypeScript", "Spring Boot 3 / Java 21", "权限、任务、审计、配额"], "#FFFFFF")
    box(1010, 90, 500, 170, "PostgreSQL 17 + pgvector", ["业务数据、版本、任务题位", "向量列 + HNSW 检索索引"], "#F0FDF4", "#86C69A")
    box(1010, 310, 500, 150, "Redis", ["待处理任务标识", "恢复、重试与并发协调"], "#FFF7ED", "#E9B47B")
    box(1010, 510, 500, 150, "MinIO", ["源文件与 Excel 制品", "对象存储、配额和备份"], "#FFF1F2", "#D99AA0")
    box(510, 570, 340, 170, "AI 服务", ["DeepSeek 文本模型", "DeepSeek Vision OCR", "后端保存 API 密钥"], "#F5F3FF", "#B4A6DA")

    arrow(370, 280, 510, 280, "HTTP(S)")
    arrow(850, 230, 1010, 175, "JDBC")
    arrow(850, 300, 1010, 385, "队列")
    arrow(850, 350, 1010, 585, "S3")
    arrow(680, 405, 680, 570, "API")

    draw.rounded_rectangle((70, 790, 1440, 58 + 790), radius=12, fill="#E8EEF5")
    draw.text((95, 807), "恢复原则：PostgreSQL 保存最终业务状态；Redis 只保存待执行任务标识；MinIO 保存文件与导出制品。",
              fill="#17365D", font=body_font)
    image.save(path, quality=95)


def configure_document(doc):
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(1.0)
    section.bottom_margin = Inches(1.0)
    section.left_margin = Inches(1.0)
    section.right_margin = Inches(1.0)
    section.header_distance = Inches(0.492)
    section.footer_distance = Inches(0.492)
    section.different_first_page_header_footer = True

    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)
    normal._element.rPr.rFonts.set(qn("w:ascii"), "Calibri")
    normal._element.rPr.rFonts.set(qn("w:hAnsi"), "Calibri")
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.color.rgb = RGBColor.from_string(INK)
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(6)
    normal.paragraph_format.line_spacing = 1.25

    heading_tokens = {
        1: (16, BLUE, 18, 10),
        2: (13, BLUE, 14, 7),
        3: (12, NAVY, 10, 5),
    }
    for level, (size, color, before, after) in heading_tokens.items():
        style = styles[f"Heading {level}"]
        style.font.name = "Calibri"
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(color)
        style._element.rPr.rFonts.set(qn("w:ascii"), "Calibri")
        style._element.rPr.rFonts.set(qn("w:hAnsi"), "Calibri")
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True

    for name in ("List Bullet", "List Bullet 2", "List Number"):
        style = styles[name]
        style.font.name = "Calibri"
        style.font.size = Pt(11)
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")

    if "Code Block" not in styles:
        styles.add_style("Code Block", 1)
    code = styles["Code Block"]
    code.font.name = "Consolas"
    code.font.size = Pt(9.2)
    code._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    code.paragraph_format.left_indent = Inches(0.18)
    code.paragraph_format.right_indent = Inches(0.18)
    code.paragraph_format.space_before = Pt(0)
    code.paragraph_format.space_after = Pt(0)

    header = section.header
    hp = header.paragraphs[0]
    hp.alignment = WD_ALIGN_PARAGRAPH.LEFT
    hr = hp.add_run("题库助手  |  项目交付与部署运维手册")
    set_run_font(hr, size=9, color=MUTED, bold=True)
    footer = section.footer
    fp = footer.paragraphs[0]
    add_page_number(fp)


def add_cover(doc):
    for _ in range(3):
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(4)
    kicker = doc.add_paragraph()
    kicker.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = kicker.add_run("DELIVERY & OPERATIONS MANUAL")
    set_run_font(r, size=10, color=BLUE, bold=True)
    kicker.paragraph_format.space_after = Pt(18)

    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_after = Pt(10)
    r = title.add_run("题库助手")
    set_run_font(r, size=30, color=NAVY, bold=True)

    subtitle = doc.add_paragraph()
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle.paragraph_format.space_after = Pt(24)
    r = subtitle.add_run("项目交付与部署运维手册")
    set_run_font(r, size=18, color=NAVY, bold=True)

    lead = doc.add_paragraph()
    lead.alignment = WD_ALIGN_PARAGRAPH.CENTER
    lead.paragraph_format.space_after = Pt(42)
    r = lead.add_run("Windows 原生部署 · Java 21 · React · PostgreSQL/pgvector · Redis · MinIO · DeepSeek")
    set_run_font(r, size=10.5, color=MUTED)

    rows = [
        ("文档版本", "V1.0"),
        ("交付状态", "生产候选版（正式验收待甲方指定样本）"),
        ("编制日期", "2026-08-29"),
        ("甲方", "[待填写]"),
        ("乙方", "[待填写]"),
        ("保密级别", "内部 / 项目交付"),
    ]
    table = doc.add_table(rows=0, cols=2)
    table.style = "Table Grid"
    for label, value in rows:
        cells = table.add_row().cells
        cells[0].text = label
        cells[1].text = value
        shade_cell(cells[0], LIGHT_BLUE)
        for run in cells[0].paragraphs[0].runs:
            set_run_font(run, size=10, color=NAVY, bold=True)
        for run in cells[1].paragraphs[0].runs:
            set_run_font(run, size=10, color=INK)
    set_table_geometry(table, [2300, 7060])
    for row in table.rows:
        prevent_row_split(row)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(24)
    r = p.add_run("本文件不包含 API 密钥、数据库密码或管理员密码；敏感凭据应通过独立安全渠道交接。")
    set_run_font(r, size=9, color=RED, italic=True)
    doc.add_page_break()


def build_document():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    QA_DIR.mkdir(parents=True, exist_ok=True)
    make_architecture(ARCH_PATH)
    doc = Document()
    configure_document(doc)
    add_cover(doc)

    add_heading(doc, "文档控制", 1)
    add_table(doc, ["项目", "内容"], [
        ("文档用途", "指导题库助手项目的软件包交接、Windows 服务器部署、业务验收、日常运维、备份恢复和升级。"),
        ("适用人员", "甲方项目负责人、验收人员、系统管理员、运维工程师、业务管理员和乙方交付人员。"),
        ("交付边界", "当前交付为生产候选版；正式 OCR 准确率、100 页性能和甲方评分基线需在指定样本及目标服务器到位后补测。"),
        ("源代码目录", r"D:\code\tikuzhushou（交付时应复制为独立版本目录或压缩包）"),
    ], [2200, 7160])

    add_heading(doc, "修订记录", 2)
    add_table(doc, ["版本", "日期", "状态", "说明"], [
        ("V1.0", "2026-08-29", "生产候选", "形成完整交付、部署、验收和运维说明；记录当前本机生产链路验收结果。"),
    ], [1200, 1700, 1700, 4760])

    add_heading(doc, "文档导航", 2)
    add_bullets(doc, [
        "第 1-4 章：了解交付范围、交付物、架构和部署前检查。",
        "第 5-8 章：完成 Windows 原生部署、配置、启停和首次初始化。",
        "第 9-12 章：执行业务演示、功能验收、性能验收和安全验收。",
        "第 13-17 章：执行备份恢复、监控、故障排查、升级回滚和正式移交。",
        "附录：端口、环境变量、状态码、目录结构和签字页。",
    ])

    add_heading(doc, "1. 交付概述", 1)
    add_paragraph(doc, "题库助手面向职业技能等级认定业务，覆盖资料入库、文档解析/OCR、职业标准结构化、细目表生成、RAG 检索、DeepSeek AI 命题、逐题质量校验、补题、质量预览、Excel 导入导出、用户权限、操作日志和模型调用预算。")
    add_callout(doc, "推荐交付结论", "采用“软件包交接 + 服务器部署 + 公开样本预验收 + 甲方样本正式验收 + 签字移交”的五步方式。不要只复制源码或只演示页面；数据库、对象文件、环境配置、运维责任和验收证据必须一起闭环。")
    add_heading(doc, "1.1 交付原则", 2)
    add_bullets(doc, [
        "不使用 Docker；正式环境采用 Windows 原生服务。",
        "密钥、数据库密码和管理员密码不进入源码、前端构建目录或交付文档。",
        "生产数据以 PostgreSQL 为主记录，MinIO 保存源文件和导出制品，Redis 仅承担待执行任务标识。",
        "所有部署变更先备份、后升级；验收结果必须能通过脚本、日志或制品复现。",
        "公开样本验收只能证明链路可用，不能替代甲方指定样本的正式准确率结论。",
    ])

    add_heading(doc, "2. 推荐交付流程", 1)
    add_numbered(doc, [
        "冻结交付版本：确定源代码、数据库迁移版本、前端构建产物、依赖清单和文档版本，生成校验值并禁止临时改动。",
        "准备目标服务器：确认 Windows 版本、CPU/内存/磁盘、端口、域名、TLS 证书、服务账号、备份盘和出网策略。",
        "部署基础组件：安装 JDK、PostgreSQL/pgvector、Redis、MinIO，并按最小权限配置服务账号。",
        "部署应用：配置环境变量，构建前后端，注册 Windows 服务，配置 IIS/Nginx 反向代理与 HTTPS。",
        "执行技术预验收：运行健康检查、数据库迁移、权限隔离、上传、任务恢复、导入导出和安全配置检查。",
        "执行业务验收：以公开样本跑通全链路，再使用甲方指定文件、期望结果和评分表形成正式准确率报告。",
        "签署移交：交接源码、制品、凭据、备份、管理员账号、运维权限、问题清单和双方签字件。",
    ])

    add_heading(doc, "3. 交付物清单", 1)
    add_table(doc, ["编号", "交付物", "建议形式", "验收要点"], [
        ("D01", "完整源代码", "版本化压缩包或受控 Git 仓库", "包含 backend、frontend、scripts、docs、infra；不包含真实密钥。"),
        ("D02", "后端制品", "tiku-api-0.1.0.jar", "Java 21 可启动，健康接口返回 UP。"),
        ("D03", "前端制品", "frontend/dist", "部署到 IIS/Nginx 后可访问，/api 反向代理正常。"),
        ("D04", "数据库迁移", "Flyway V1-V11", "空库可自动迁移；既有库可升级；迁移前有备份。"),
        ("D05", "环境变量模板", ".env.example", "不含真实密码；变量名称、用途和责任人明确。"),
        ("D06", "启动与健康脚本", "Start-Tiku.ps1 / Test-Tiku.ps1", "目标服务器路径已适配，五项健康检查全部通过。"),
        ("D07", "部署与运维文档", "本手册及 docs 目录", "覆盖部署、启停、备份、恢复、排障、升级和回滚。"),
        ("D08", "样本与验收记录", "samples + 验收报告", "公开样本预验收与甲方样本正式验收分开记录。"),
        ("D09", "数据库与文件备份", "pg_dump + MinIO 数据备份", "可在隔离环境完成恢复演练。"),
        ("D10", "安全交接包", "独立加密渠道", "管理员密码、数据库密码、MinIO 密码、DeepSeek API 密钥不进入普通文档。"),
    ], [700, 1900, 2700, 4060], font_size=8.8)

    add_heading(doc, "4. 系统架构", 1)
    add_paragraph(doc, "系统采用前后端分离和异步任务架构。浏览器访问 React 前端，后端 Spring Boot 提供权限、业务和任务接口；PostgreSQL/pgvector 保存结构化业务数据与向量，Redis 承载队列标识，MinIO 保存文件，DeepSeek 提供文本与视觉模型能力。")
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    shape = run.add_picture(str(ARCH_PATH), width=Inches(6.45))
    shape._inline.docPr.set("title", "题库助手生产架构")
    shape._inline.docPr.set(
        "descr",
        "Windows 原生部署架构：用户经 React 与 Spring Boot 访问 PostgreSQL/pgvector、Redis、MinIO 和 DeepSeek AI 服务。",
    )
    p.paragraph_format.space_after = Pt(4)
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.space_after = Pt(8)
    r = cap.add_run("图 1  题库助手生产架构")
    set_run_font(r, size=9, color=MUTED, italic=True)

    add_heading(doc, "4.1 技术组件", 2)
    add_table(doc, ["组件", "技术/版本", "职责", "默认端口"], [
        ("前端", "React + TypeScript + Vite", "工作台、任务反馈、质量预览、管理页面", "4173（开发）；生产建议 443"),
        ("后端", "Spring Boot 3 / Java 21", "API、权限、业务编排、Worker、审计、配额", "8080"),
        ("数据库", "PostgreSQL 17", "用户、知识库、文档、任务、题库、版本、日志", "5432"),
        ("向量检索", "pgvector 0.8.6 / HNSW", "文档向量列和余弦相似度检索", "随 PostgreSQL"),
        ("任务队列", "Redis 8.10.1 for Windows", "文档、命题和业务任务队列标识", "6379"),
        ("对象存储", "MinIO", "源文件、解析输入、Excel 导出制品", "9000；控制台 9001"),
        ("模型", "DeepSeek 文本/视觉接口", "标准抽取、AI 命题、OCR", "HTTPS 出网 443"),
    ], [1500, 2600, 3760, 1500], font_size=8.8)

    add_heading(doc, "4.2 数据恢复边界", 2)
    add_bullets(doc, [
        "PostgreSQL 是业务状态事实来源，必须优先备份与恢复。",
        "Redis 可在任务恢复机制下重建，但 AOF 数据仍建议随服务备份。",
        "MinIO 与 PostgreSQL 必须使用一致恢复点，否则可能出现记录存在但对象缺失。",
        "DeepSeek API 不保存本项目业务状态；模型输出已写入数据库后方视为完成。",
    ])

    add_heading(doc, "5. 目标服务器准备", 1)
    add_heading(doc, "5.1 建议配置", 2)
    add_table(doc, ["场景", "CPU", "内存", "磁盘", "说明"], [
        ("演示/试运行", "8 核", "16 GB", "SSD 200 GB", "适合少量用户和公开样本联调。"),
        ("正式生产起步", "16 核", "32 GB", "SSD 500 GB 起", "数据库、MinIO 和应用同机时的建议起点。"),
        ("高并发/大批量", "按压测扩容", "64 GB 起", "数据盘独立", "应拆分数据库、对象存储和应用节点，并验证多实例额度原子性。"),
    ], [1900, 1200, 1300, 1900, 3060])
    add_callout(doc, "容量说明", "最终配置必须以目标服务器上的 100 页向量化、检索响应和 1,000 道题生成压测结果为准。默认单知识库配额为 10 GB，但磁盘还需预留数据库索引、MinIO 版本、日志和备份空间。", PALE_GOLD, "7A5A00")

    add_heading(doc, "5.2 前置软件", 2)
    add_bullets(doc, [
        "Windows Server 2019/2022 或经项目确认的 Windows 10/11；系统时区设为 Asia/Shanghai。",
        "Eclipse Temurin/OpenJDK 21；Node.js 20+ 仅用于构建前端，生产静态托管后可不常驻。",
        "PostgreSQL 17 x64，pgvector 与 PostgreSQL 主版本和 x64 ABI 匹配。",
        "Redis Windows 兼容版、MinIO Server、IIS 或 Nginx、有效 TLS 证书。",
        "目标服务器可访问 DeepSeek API；如通过代理出网，应在服务账号环境中配置。",
    ])

    add_heading(doc, "5.3 端口与网络", 2)
    add_table(doc, ["端口", "服务", "开放范围", "生产建议"], [
        ("443", "前端与 /api", "业务用户", "唯一对外业务入口，启用 TLS。"),
        ("8080", "Spring Boot", "仅反向代理/运维网", "禁止直接暴露互联网。"),
        ("5432", "PostgreSQL", "仅后端与 DBA", "限制来源 IP，禁止公网。"),
        ("6379", "Redis", "仅后端", "绑定内网/本机，设置 ACL 或防火墙。"),
        ("9000", "MinIO API", "仅后端", "禁止公网；通过后端下载制品。"),
        ("9001", "MinIO Console", "仅运维网", "启用强密码并限制访问。"),
    ], [1100, 1900, 2400, 3960])

    add_heading(doc, "6. Windows 原生部署步骤", 1)
    add_heading(doc, "6.1 准备交付目录", 2)
    add_numbered(doc, [
        r"在目标服务器创建版本化目录，例如 D:\apps\tikuzhushou\releases\1.0.0。",
        "复制源码或交付制品，核对文件数量、版本号和 SHA-256 校验值。",
        r"创建独立数据目录，例如 D:\data\tikuzhushou\minio、redis、backup、logs。",
        "为 PostgreSQL、MinIO、Redis 和应用创建受限 Windows 服务账号，不使用个人管理员账号长期运行。",
    ])

    add_heading(doc, "6.2 安装 PostgreSQL 与 pgvector", 2)
    add_numbered(doc, [
        "安装 PostgreSQL 17 x64，设置强密码并记录到安全密码库。",
        "按 docs/pgvector-windows.md 编译或安装经过审核的 pgvector 0.8.6，必须与 PostgreSQL 17 x64 匹配。",
        "创建数据库和最小权限应用用户，启用 vector 扩展。",
        "启动后端时由 Flyway 自动执行 V1-V11；升级既有库前必须先执行 pg_dump。",
    ])
    add_code(doc, [
        "CREATE DATABASE tikuzhushou;",
        "CREATE EXTENSION IF NOT EXISTS vector;",
        "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';",
    ])

    add_heading(doc, "6.3 安装 Redis 与 MinIO", 2)
    add_bullets(doc, [
        "Redis 绑定 127.0.0.1 或业务内网地址，启用 AOF；生产环境建议配置访问控制。",
        "MinIO 数据目录放在独立磁盘，设置专用 root 用户和强密码；创建 tikuzhushou bucket。",
        "将 Redis 与 MinIO 注册为 Windows 服务，设置自动启动、失败重启和服务日志。",
        "不要把 MinIO 控制台和 Redis 端口暴露到公网。",
    ])

    add_heading(doc, "6.4 配置环境变量", 2)
    add_paragraph(doc, "以 .env.example 为模板创建服务器配置。正式值应写入受 ACL 保护的环境文件或 Windows 服务环境，不得复制到前端、Git 或普通工单。")
    add_code(doc, [
        "SPRING_PROFILES_ACTIVE=prod",
        "JDBC_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/tikuzhushou",
        "POSTGRES_USER=<应用数据库用户>",
        "POSTGRES_PASSWORD=<安全渠道提供>",
        "APP_STORAGE_PROVIDER=minio",
        "MINIO_ENDPOINT=http://127.0.0.1:9000",
        "MINIO_ROOT_USER=<MinIO 用户>",
        "MINIO_ROOT_PASSWORD=<安全渠道提供>",
        "REDIS_HOST=127.0.0.1",
        "REDIS_PORT=6379",
        "APP_QUEUE_PROVIDER=redis",
        "DEEPSEEK_API_KEY=<安全渠道提供>",
        "APP_CORS_ORIGINS=https://<正式域名>",
    ])

    add_heading(doc, "6.5 构建与启动", 2)
    add_callout(doc, "部署前必须处理", "当前 Start-Tiku.ps1 中的 Java、Maven、Node、MinIO、Redis 和 psql 路径来自开发机。正式交付前必须改为目标服务器实际安装路径，或将路径参数化后再注册为服务。")
    add_code(doc, [
        "Set-Location <安装目录>",
        r".\scripts\Start-Tiku.ps1 -Build",
        r".\scripts\Test-Tiku.ps1",
    ])
    add_paragraph(doc, "Test-Tiku.ps1 应显示 PostgreSQL、MinIO、Redis、Backend health、Frontend 五项均为 True。前端正式发布时应部署 frontend/dist 到 IIS/Nginx，由 443 提供静态文件并将 /api 反向代理到 127.0.0.1:8080。")

    add_heading(doc, "6.6 注册 Windows 服务", 2)
    add_bullets(doc, [
        "后端 JAR、Redis、MinIO 均设置为自动启动和失败后重启；服务工作目录固定到版本目录。",
        "服务账号只拥有运行目录、日志目录、数据目录和必要网络权限。",
        "把真实环境变量配置在服务级环境中，避免依赖某个交互式 PowerShell 会话。",
        "IIS/Nginx 负责 HTTPS、静态资源缓存、请求大小和反向代理超时；上传上限应不低于后端 64 MB。",
    ])

    add_heading(doc, "7. 配置基线", 1)
    add_table(doc, ["配置项", "建议值/原则", "说明"], [
        ("SPRING_PROFILES_ACTIVE", "prod", "启用 PostgreSQL、MinIO、Redis 生产配置。"),
        ("APP_OCR_ENABLED", "true", "允许低质量页面进入视觉 OCR。"),
        ("APP_OCR_MAX_PAGES", "120（按额度调整）", "超过上限应拆分文档或经审批提高。"),
        ("APP_AI_STANDARD_EXTRACTION_ENABLED", "true", "职业标准结构化抽取。"),
        ("APP_AI_QUESTION_REFINEMENT_ENABLED", "true", "AI 证据化命题与质量修复。"),
        ("APP_AI_QUESTION_BATCH_SIZE", "3", "平衡模型输出稳定性、额度和吞吐。"),
        ("APP_CORS_ORIGINS", "仅正式前端完整来源", "禁止使用 *；协议、域名、端口必须准确。"),
        ("知识库配额", "默认 10 GB", "可由管理员按业务调整。"),
        ("操作日志", "保留 30 天", "应配合数据库备份与运维审计。"),
        ("模型额度", "默认每用户 1,000 次/日", "系统内部调用预算，不是 DeepSeek 官方余额。"),
    ], [2600, 2600, 4160], font_size=9)

    add_heading(doc, "8. 首次初始化与安全加固", 1)
    add_numbered(doc, [
        "在首次启动前设置 APP_BOOTSTRAP_ADMIN_PASSWORD，禁止使用默认或项目群中公开的密码。",
        "使用 admin 登录后验证权限；当前身份验证采用 HTTP Basic，必须通过 HTTPS 反向代理对外提供。",
        "创建业务用户并分配 ADMIN、EDITOR 或 VIEWER；用户创建可通过 /api/admin/users 接口完成。",
        "将 DeepSeek、数据库和 MinIO 凭据录入安全密码库，并指定凭据保管人和轮换周期。",
        "限制 Swagger、MinIO Console、数据库和 Redis 的访问来源；正式环境建议由网关进一步限制 API 文档。",
        "执行一次备份与恢复演练，确认数据库记录和 MinIO 对象可在隔离环境共同恢复。",
    ])
    add_callout(doc, "安全差距说明", "当前前端“用户角色”页面主要展示角色矩阵，模型额度也只有个人查询页面；用户管理和额度调整的完整可视化后台仍属于后续增强项。正式交付时应明确采用 API/数据库运维流程，或将该增强项列入变更范围。", PALE_GOLD, "7A5A00")

    add_heading(doc, "9. 完整业务演示与验收链路", 1)
    add_numbered(doc, [
        "登录：通过正式 HTTPS 地址登录，确认首页显示服务正常、DeepSeek 已配置和当前模型预算。",
        "创建知识库：填写名称和说明，验证默认 10 GB 配额与用户数据隔离。",
        "上传资料：上传职业标准 PDF、教材或知识点文档，观察上传进度、任务编号和服务端完整性校验。",
        "解析/OCR：提交解析任务，观察文字提取、OCR、章节识别、分块、Embedding 和质量检查阶段。",
        "原文检查：在文档预览中抽查片段、章节和页码，必要时人工编辑片段。",
        "标准抽取：提交职业标准解析，核对职业名称、编码、等级、考点和字段-原文对应关系。",
        "人工确认：保存修改版本并执行确认；未确认标准不得进入细目表生成。",
        "生成细目表：输入计划题目数量，生成后确认“已生成细目表”数量与输入一致；参数改变时必须重新生成。",
        "AI 命题：提交题库任务，观察计划、通过、拒绝和耗尽数；计划数必须继承细目表题位数。",
        "继续补题：数量不足时状态为 QUESTION_COUNT_MISMATCH；用户可不限轮数继续补题，每轮单题位最多尝试 5 次。",
        "题目预览：从质量验收列表进入独立详情页，抽查题干、选项、答案、解析、考点和原文定位。",
        "导出交付：完整任务生成 DOWNLOAD_READY；数量不足时允许 PARTIAL_EXPORT_READY，Excel 交付说明页必须记录计划数与实际数。",
    ])

    add_heading(doc, "10. 功能验收清单", 1)
    add_table(doc, ["编号", "验收项", "验收方法", "通过标准", "结果"], [
        ("F01", "登录与权限", "使用三种角色访问受限接口", "未授权返回 401/403；数据按用户隔离", "□"),
        ("F02", "知识库 CRUD", "新建、查询、归档知识库", "状态和配额正确，操作有审计", "□"),
        ("F03", "文件上传", "上传支持格式和超限文件", "合法文件成功；超限/非法格式被拒绝", "□"),
        ("F04", "MinIO 存储", "核对对象与数据库记录", "对象可读，权限受控，不直接公开", "□"),
        ("F05", "文档解析/OCR", "数字版与扫描件分别解析", "状态完整，正文、页码、章节可检查", "□"),
        ("F06", "分块与检索", "按考点检索并查看来源", "返回原文片段、定位和相关度", "□"),
        ("F07", "职业标准", "抽取、编辑、确认、查看版本", "Schema 完整，未确认版本不能命题", "□"),
        ("F08", "细目表数量", "输入 31 并生成/提交", "细目表与题库任务计划数均为 31", "□"),
        ("F09", "九题型 AI 命题", "各题型至少一题", "题型结构合法且有 RAG 来源", "□"),
        ("F10", "补题与部分交付", "制造校验失败并继续补题", "合格题保留；可不限轮继续；部分导出有说明", "□"),
        ("F11", "任务恢复", "运行中重启后端", "任务恢复或明确失败，不重复交付", "□"),
        ("F12", "Excel 往返", "导出后重新导入", "题型、数量、答案和考点保持一致", "□"),
        ("F13", "日志与额度", "执行操作并查询", "日志可查；模型调用真实计入内部预算", "□"),
        ("F14", "10 GB 配额", "模拟达到知识库配额", "超限上传被拒绝且已有数据不受影响", "□"),
    ], [650, 1600, 2650, 3760, 700], font_size=8.2)

    add_heading(doc, "11. 性能与稳定性验收", 1)
    add_table(doc, ["指标", "测试方法", "记录内容", "正式结论"], [
        ("100 页解析/向量化", "使用甲方代表性 100 页文档", "服务器规格、文件特征、总耗时、OCR 页数、失败页", "待目标服务器实测"),
        ("检索响应", "固定查询集执行冷/热检索", "P50/P95/P99、召回片段、相关度", "待目标服务器实测"),
        ("1,000 道题生成", "按细目表提交异步任务", "耗时、模型请求数、通过率、补题轮数、恢复次数", "旧规则队列基线通过；新质量规则待正式压测"),
        ("任务中断恢复", "生成/OCR 中重启后端", "恢复阶段、重复数、最终状态", "本机已验证"),
        ("30 天日志查询", "按日期、用户、操作筛选", "响应时间和记录准确性", "待目标数据量验证"),
    ], [1850, 3000, 3110, 1400], font_size=8.6)
    add_callout(doc, "不可提前签署的指标", "甲方未提供指定扫描件、期望结果或评分表时，不得签署 OCR 字符准确率、职业标准字段准确率和题目质量评分的正式达标结论。应先使用公开样本确认链路，再在资料到位后补充基线。", PALE_GOLD, "7A5A00")

    add_heading(doc, "12. 当前本机预验收结果", 1)
    add_table(doc, ["项目", "结果", "当前证据"], [
        ("基础服务", "通过", "PostgreSQL、MinIO、Redis、后端和前端健康检查均为 True。"),
        ("数据库迁移", "通过", "Flyway V1-V11 在 H2 与真实 PostgreSQL 执行成功。"),
        ("pgvector", "通过", "vector 0.8.6、向量列和 HNSW 索引已启用。"),
        ("职业标准", "通过", "公共营养师公开样本完成异步抽取与人工确认链路。"),
        ("九题型命题", "通过", "真实 DeepSeek 任务计划 9、通过 9、拒绝 0、耗尽 0。"),
        ("完整导出", "通过", "异步任务达到 DOWNLOAD_READY，制品下载 HTTP 200。"),
        ("数量不足交付", "通过", "QUESTION_COUNT_MISMATCH 可继续补题并允许部分导出。"),
        ("自动化构建", "通过", "后端测试 7/7，前端生产构建通过。"),
    ], [2300, 1300, 5760])

    add_heading(doc, "13. 备份与恢复", 1)
    add_heading(doc, "13.1 备份策略", 2)
    add_bullets(doc, [
        "PostgreSQL：每日逻辑备份，升级前额外备份；至少保留 7 个日备、4 个周备和 3 个月备。",
        "MinIO：备份对象数据目录或使用镜像工具同步到独立备份存储；与数据库保持同一恢复时间点。",
        "Redis：启用 AOF 并备份配置；Redis 丢失时以 PostgreSQL 任务状态执行恢复审计。",
        "环境配置：加密备份 .env/服务环境、反向代理配置和证书；密钥备份仅进入安全密码库。",
        "源代码与制品：保存 Git 标签、JAR、frontend/dist、迁移脚本和 SHA-256 清单。",
    ])
    add_heading(doc, "13.2 恢复演练", 2)
    add_numbered(doc, [
        "在隔离环境停止应用写入，记录备份时间点和版本。",
        "恢复 PostgreSQL，再恢复同一时间点的 MinIO 对象；检查数据库与对象数量。",
        "恢复环境变量和服务配置，启动 PostgreSQL、MinIO、Redis、后端、前端。",
        "运行 Test-Tiku.ps1，并抽查知识库、文档、任务、题库和导出制品。",
        "形成恢复耗时、数据缺口和问题记录；未完成恢复演练不得认为备份有效。",
    ])

    add_heading(doc, "14. 日常运维与监控", 1)
    add_table(doc, ["频率", "检查项", "处理要求"], [
        ("实时/5 分钟", "五项健康、磁盘、进程、端口、HTTP 5xx", "异常告警，确认是否影响上传、任务或下载。"),
        ("每日", "失败任务、模型额度、MinIO 容量、数据库备份", "处理失败原因，验证备份文件可读。"),
        ("每周", "慢查询、向量索引、Redis AOF、日志增长", "分析趋势，清理无效临时文件。"),
        ("每月", "权限账号、密钥轮换计划、恢复抽查、依赖漏洞", "禁用离职账号，更新风险清单。"),
        ("升级前", "完整备份、迁移检查、回滚包、维护通知", "满足回滚条件后才允许升级。"),
    ], [1400, 3400, 4560])
    add_paragraph(doc, "操作日志默认保留 30 天。应用日志应输出到受控目录并接入 Windows Event Log 或集中日志平台；不要在日志中打印 API 密钥、Authorization 头或文件敏感正文。")

    add_heading(doc, "15. 常见故障排查", 1)
    add_table(doc, ["现象/状态码", "优先检查", "处理建议"], [
        ("UPLOAD_FAILED / HTTP 403 CORS", "APP_CORS_ORIGINS 与实际来源", "填写完整协议、域名/IP、端口；重启后端并重新预检。"),
        ("MODEL_QUOTA_EXCEEDED", "用户当日 request_count", "确认内部预算；调整数据库额度或等待次日，不代表 DeepSeek 官方余额。"),
        ("MODEL_UNAVAILABLE", "API 密钥、模型名、出网、供应商响应", "禁止切换为模板题凑数；恢复模型后重试缺失题位。"),
        ("QUESTION_COUNT_MISMATCH", "耗尽题位及失败原因", "继续补题不限轮数；也可导出带说明的不完整题库。"),
        ("BLUEPRINT_COUNT_CHANGED", "页面数量与细目表数量", "按新数量重新生成细目表，旧细目表不得提交。"),
        ("MinIO Access Key 错误", "后端与 MinIO 服务凭据", "统一服务环境并重启两端；不得直接修改已有对象。"),
        ("pgvector 不可用", "扩展、vector 列、HNSW 索引", "按安装手册修复；系统可回退 JSON embedding，但性能不同。"),
        ("任务停在 RUNNING", "后端、Redis、数据库任务状态", "重启 Worker；核对恢复队列和最终状态，避免重复提交。"),
        ("前端仍显示旧样式", "浏览器缓存与 dist 版本", "确认部署最新 dist，清缓存或使用版本化静态资源。"),
    ], [2300, 2900, 4160], font_size=8.6)

    add_heading(doc, "16. 升级与回滚", 1)
    add_heading(doc, "16.1 升级流程", 2)
    add_numbered(doc, [
        "冻结新任务并等待运行中任务结束或记录可恢复状态。",
        "备份 PostgreSQL、MinIO、环境配置和当前 JAR/dist。",
        "在预发布环境运行 Flyway、后端测试、前端构建和关键业务回归。",
        "发布新版本目录，切换 Windows 服务和前端静态目录，不覆盖旧版本目录。",
        "执行健康检查和最小业务链路；观察错误率、失败任务和数据库迁移状态。",
        "达到观察窗口后恢复业务；记录版本、人员、时间和验证结果。",
    ])
    add_heading(doc, "16.2 回滚原则", 2)
    add_bullets(doc, [
        "应用回滚：停止新版本，恢复旧 JAR/dist 和旧环境配置。",
        "数据库回滚：Flyway 迁移默认不自动回退；涉及不可逆结构变更时应恢复升级前备份。",
        "MinIO 回滚：避免单独回滚对象存储；应与数据库恢复点一致。",
        "回滚后必须运行健康检查、权限检查、文档读取和题库导出验证。",
    ])

    add_heading(doc, "17. 已知限制与正式交付待办", 1)
    add_table(doc, ["事项", "当前状态", "正式交付处理"], [
        ("甲方指定样本与评分表", "未提供", "到位后补做 OCR、标准字段和题目质量正式基线。"),
        ("目标服务器信息", "未提供", "确认服务器规格、域名、证书、网络、备份和运维账号。"),
        ("前端生产托管", "当前本机由 Vite 4173 提供", "部署 frontend/dist 到 IIS/Nginx，统一 HTTPS 和 /api。"),
        ("启动脚本路径", "包含开发机绝对路径", "在目标服务器参数化或修改为实际路径后注册服务。"),
        ("身份认证", "HTTP Basic + 数据隔离", "必须置于 HTTPS 后；如甲方要求 SSO/JWT，需列入增强范围。"),
        ("模型额度", "系统内部请求次数预算", "不等同 DeepSeek 余额；多后端实例前改为数据库/Redis 原子扣减。"),
        ("管理界面", "角色矩阵与个人额度展示已具备", "完整用户管理、额度调整 UI 可作为后续增强。"),
        ("实验模型稳定性", "使用指定 DeepSeek 模型", "上线前确认供应商模型可用性、版本策略和降级方案。"),
    ], [2400, 2800, 4160], font_size=8.8)

    add_heading(doc, "18. 最终移交清单", 1)
    add_table(doc, ["检查项", "甲方确认", "乙方确认", "备注"], [
        ("源代码、JAR、dist、迁移脚本和版本校验值已交接", "□", "□", ""),
        ("目标服务器五项健康检查全部通过", "□", "□", ""),
        ("管理员、数据库、MinIO、DeepSeek 凭据已通过安全渠道交接", "□", "□", ""),
        ("数据库与 MinIO 备份已完成恢复演练", "□", "□", ""),
        ("公开样本完整操作链路已演示", "□", "□", ""),
        ("甲方指定样本正式验收已完成或形成待办", "□", "□", ""),
        ("已知限制、遗留问题和责任边界已确认", "□", "□", ""),
        ("运维联系人、告警渠道、响应时间和升级窗口已确认", "□", "□", ""),
    ], [5320, 1100, 1100, 1840], font_size=8.8)

    add_heading(doc, "附录 A：环境变量说明", 1)
    add_table(doc, ["变量", "是否必填", "用途", "保密要求"], [
        ("DEEPSEEK_API_KEY", "是", "DeepSeek 服务认证", "高敏；仅后端服务环境"),
        ("DEEPSEEK_TEXT_MODEL", "是", "文本/JSON 模型名称", "普通配置"),
        ("DEEPSEEK_VISION_MODEL", "OCR 启用时", "视觉 OCR 模型名称", "普通配置"),
        ("APP_BOOTSTRAP_ADMIN_PASSWORD", "首次启动", "初始化管理员密码", "高敏；上线后轮换"),
        ("JDBC_DATABASE_URL", "是", "PostgreSQL JDBC 地址", "内部配置"),
        ("POSTGRES_USER/PASSWORD", "是", "数据库应用账号", "高敏"),
        ("MINIO_ENDPOINT", "是", "对象存储 API 地址", "内部配置"),
        ("MINIO_ROOT_USER/PASSWORD", "是", "MinIO 访问凭据", "高敏"),
        ("REDIS_HOST/PORT", "是", "任务队列地址", "内部配置"),
        ("APP_CORS_ORIGINS", "是", "允许访问 API 的前端来源", "严禁通配符"),
    ], [2800, 1500, 3260, 1800], font_size=8.6)

    add_heading(doc, "附录 B：关键任务状态码", 1)
    add_table(doc, ["环节", "主要状态码", "含义"], [
        ("上传", "UPLOADING / UPLOAD_VERIFYING / SUCCEEDED", "客户端上传、服务端校验、完成。"),
        ("文档", "TEXT_EXTRACTING / OCR_PROCESSING / CHUNKING / EMBEDDING", "提取、OCR、分块、向量化。"),
        ("标准", "STANDARD_AI_EXTRACTING / SCHEMA_VALIDATING / SOURCE_MAPPING", "AI 抽取、Schema 校验、原文映射。"),
        ("命题", "SOURCE_RETRIEVING / AI_GENERATING / QUESTION_REFILLING", "RAG 召回、生成、补题。"),
        ("数量不足", "QUESTION_COUNT_MISMATCH", "补题后仍少于计划数；保留合格题并可部分导出。"),
        ("导出", "EXCEL_WRITING / FILE_STORING / DOWNLOAD_READY", "生成、存储、完整文件可下载。"),
        ("部分导出", "PARTIAL_EXPORT_READY", "数量不足文件可下载，交付说明记录差异。"),
        ("通用失败", "MODEL_UNAVAILABLE / MODEL_QUOTA_EXCEEDED / ACCESS_DENIED", "模型不可用、内部额度不足、权限拒绝。"),
    ], [1900, 4100, 3360], font_size=8.5)

    appendix_c = add_heading(doc, "附录 C：项目目录", 1)
    appendix_c.paragraph_format.page_break_before = True
    add_code(doc, [
        "tikuzhushou/",
        "  backend/                 Spring Boot 后端、Flyway、测试",
        "  frontend/                React/TypeScript 前端与 dist",
        "  scripts/                 Windows 启动和健康检查",
        "  docs/                    部署、验收、AI 链路与本交付手册",
        "  samples/                 公开验收样本",
        "  infra/                   参考基础设施配置（本项目生产不使用 Docker）",
        "  .env.example             无敏感值的环境变量模板",
        "  README.md                项目入口说明",
    ])

    add_heading(doc, "附录 D：验收签字页", 1)
    add_paragraph(doc, "双方确认已按本手册完成约定范围的交付、演示、验收和问题记录。未完成项目应在备注中明确责任人、计划日期和是否影响上线。")
    sign = doc.add_table(rows=6, cols=4)
    sign.style = "Table Grid"
    values = [
        ("甲方单位", "", "乙方单位", ""),
        ("甲方代表", "", "乙方代表", ""),
        ("验收结论", "□ 通过  □ 有条件通过  □ 不通过", "交付结论", "□ 已完成  □ 待整改"),
        ("遗留问题", "", "计划完成日期", ""),
        ("签字/盖章", "", "签字/盖章", ""),
        ("日期", "____年__月__日", "日期", "____年__月__日"),
    ]
    for r_idx, row in enumerate(values):
        for c_idx, value in enumerate(row):
            sign.rows[r_idx].cells[c_idx].text = value
            if c_idx % 2 == 0:
                shade_cell(sign.rows[r_idx].cells[c_idx], LIGHT_BLUE)
    set_table_geometry(sign, [1500, 3180, 1500, 3180])
    style_table_text(sign, header=False, font_size=9.2)

    doc.core_properties.title = "题库助手项目交付与部署运维手册"
    doc.core_properties.subject = "Windows 原生部署、验收、运维与正式移交"
    doc.core_properties.author = "题库助手项目组"
    doc.core_properties.keywords = "题库助手, 交付, Windows, PostgreSQL, pgvector, Redis, MinIO, DeepSeek"
    doc.core_properties.comments = "不包含密钥与密码。正式验收待甲方指定样本。"
    doc.save(OUT_PATH)
    print(OUT_PATH)


if __name__ == "__main__":
    build_document()
