#!/usr/bin/env python3
"""Read-only CAD and engineering-drawing capability probe.

The probe deliberately reports uncertainty. It is not the production CAD
parser; it establishes the adapter contract and gives us evidence about the
real sample files before any business feature is wired into the application.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = "cad-fact-probe.v1"
SUPPORTED_EXTENSIONS = {
    ".step",
    ".stp",
    ".stl",
    ".obj",
    ".pdf",
    ".x_t",
    ".prt",
    ".dwg",
}

NUMBER = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?"
POINT_RE = re.compile(
    rf"CARTESIAN_POINT\s*\(\s*'[^']*'\s*,\s*\(\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*\)\s*\)",
    re.IGNORECASE,
)
ENTITY_RE = re.compile(r"#\d+\s*=\s*([A-Z0-9_]+)\s*\(", re.IGNORECASE)
DIMENSION_RE = re.compile(
    rf"(?<![\w.])({NUMBER})\s*(mm|毫米|cm|厘米|m|米|°|度)\b",
    re.IGNORECASE,
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bounds(points: Iterable[tuple[float, float, float]]) -> dict[str, Any] | None:
    values = list(points)
    if not values:
        return None
    xs, ys, zs = zip(*values)
    minimum = [min(xs), min(ys), min(zs)]
    maximum = [max(xs), max(ys), max(zs)]
    return {
        "min": minimum,
        "max": maximum,
        "size": [maximum[i] - minimum[i] for i in range(3)],
    }


def round_values(values: Iterable[float]) -> list[float]:
    return [round(value, 8) for value in values]


def fact(name: str, value: Any, source: str, confidence: float, verified: bool = False) -> dict[str, Any]:
    return {
        "name": name,
        "value": value,
        "source": source,
        "confidence": confidence,
        "verified": verified,
        "usableForGeneration": verified,
    }


def inspect_step(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="latin-1", errors="replace")
    points = [tuple(float(part) for part in match.groups()) for match in POINT_RE.finditer(text)]
    entities: dict[str, int] = {}
    for entity in ENTITY_RE.findall(text):
        key = entity.upper()
        entities[key] = entities.get(key, 0) + 1
    common_entities = dict(sorted(entities.items(), key=lambda item: (-item[1], item[0]))[:40])
    schema = re.findall(r"FILE_SCHEMA\s*\(\s*\(('([^']+)'|\"([^\"]+)\")", text, re.IGNORECASE)
    units = sorted(set(re.findall(r"(?:SI_UNIT|CONVERSION_BASED_UNIT|LENGTH_UNIT|NAMED_UNIT)[^;]{0,180}", text, re.IGNORECASE)))
    model_bounds = bounds(points)
    facts: list[dict[str, Any]] = [
        fact("cartesian_point_count", len(points), "STEP/CARTESIAN_POINT", 0.99),
        fact("entity_count", sum(entities.values()), "STEP entity records", 0.99),
    ]
    if model_bounds:
        facts.append(
            fact(
                "approximate_cartesian_point_bounds",
                {key: round_values(value) if isinstance(value, list) else value for key, value in model_bounds.items()},
                "STEP/CARTESIAN_POINT",
                0.62,
            )
        )
    warnings = [
        "当前仅根据 STEP 笛卡尔点计算近似包围盒，不能替代 B-Rep 精确几何解析。",
        "未声明或无法确认单位时，不将数值直接用于正式命题。",
    ]
    return {
        "parser": "step-text-probe",
        "status": "PARSED_PARTIAL",
        "summary": {
            "format": "STEP",
            "schema": [item[1] or item[2] for item in schema],
            "cartesianPointCount": len(points),
            "entityCount": sum(entities.values()),
            "commonEntities": common_entities,
            "units": units[:20],
            "approximateBounds": model_bounds,
        },
        "facts": facts,
        "warnings": warnings,
    }


def triangle_metrics(triangles: list[tuple[tuple[float, float, float], ...]]) -> dict[str, Any]:
    vertices = [vertex for triangle in triangles for vertex in triangle]
    model_bounds = bounds(vertices)
    area = 0.0
    signed_volume = 0.0
    for a, b, c in triangles:
        ab = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        ac = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
        cross = (
            ab[1] * ac[2] - ab[2] * ac[1],
            ab[2] * ac[0] - ab[0] * ac[2],
            ab[0] * ac[1] - ab[1] * ac[0],
        )
        area += 0.5 * math.sqrt(sum(value * value for value in cross))
        signed_volume += (
            a[0] * (b[1] * c[2] - b[2] * c[1])
            - a[1] * (b[0] * c[2] - b[2] * c[0])
            + a[2] * (b[0] * c[1] - b[1] * c[0])
        ) / 6.0
    return {
        "triangleCount": len(triangles),
        "approximateBounds": model_bounds,
        "surfaceArea": round(area, 8),
        "signedVolume": round(signed_volume, 8),
        "absoluteVolume": round(abs(signed_volume), 8),
    }


def inspect_stl(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    triangles: list[tuple[tuple[float, float, float], ...]] = []
    is_binary = len(data) >= 84 and 84 + struct.unpack_from("<I", data, 80)[0] * 50 == len(data)
    if is_binary:
        count = struct.unpack_from("<I", data, 80)[0]
        offset = 84
        for _ in range(count):
            values = struct.unpack_from("<12f", data, offset)
            triangles.append((tuple(values[3:6]), tuple(values[6:9]), tuple(values[9:12])))
            offset += 50
    else:
        vertices = [tuple(float(value) for value in match) for match in re.findall(r"vertex\s+(%s)\s+(%s)\s+(%s)" % (NUMBER, NUMBER, NUMBER), data.decode("utf-8", errors="ignore"), re.IGNORECASE)]
        triangles = [tuple(vertices[index:index + 3]) for index in range(0, len(vertices) - 2, 3)]
    metrics = triangle_metrics(triangles)
    return {
        "parser": "stl-mesh-probe",
        "status": "PARSED",
        "summary": {"format": "STL", "encoding": "binary" if is_binary else "ascii", **metrics},
        "facts": [
            fact("triangle_count", metrics["triangleCount"], "STL triangles", 0.99),
            fact("approximate_bounds", metrics["approximateBounds"], "STL vertices", 0.95),
            fact("surface_area", metrics["surfaceArea"], "STL mesh", 0.90),
            fact("absolute_volume", metrics["absoluteVolume"], "STL mesh", 0.82),
        ],
        "warnings": ["STL 通常不携带可靠单位；报告中的长度、面积和体积必须由资料或老师确认单位。"],
    }


def inspect_obj(path: Path) -> dict[str, Any]:
    vertices: list[tuple[float, float, float]] = []
    faces = 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("v "):
            parts = line.split()
            if len(parts) >= 4:
                vertices.append(tuple(float(value) for value in parts[1:4]))
        elif line.startswith("f "):
            faces += max(0, len(line.split()) - 3)
    model_bounds = bounds(vertices)
    return {
        "parser": "obj-mesh-probe",
        "status": "PARSED",
        "summary": {"format": "OBJ", "vertexCount": len(vertices), "triangleCount": faces, "approximateBounds": model_bounds},
        "facts": [
            fact("vertex_count", len(vertices), "OBJ vertex records", 0.99),
            fact("triangle_count", faces, "OBJ face records", 0.96),
            fact("approximate_bounds", model_bounds, "OBJ vertices", 0.95),
        ],
        "warnings": ["OBJ 通常不携带可靠单位；不能仅凭文件内容推断毫米或厘米。"],
    }


def inspect_pdf(path: Path) -> dict[str, Any]:
    # This file is intentionally named inspect.py for the first probe draft,
    # but pypdf imports Python's stdlib module with the same basename. Remove
    # the probe directory only while importing pypdf so the local file cannot
    # shadow stdlib inspect in a source checkout.
    probe_dir = str(Path(__file__).resolve().parent)
    removed_probe_paths: list[tuple[int, str]] = []
    for index in range(len(sys.path) - 1, -1, -1):
        if Path(sys.path[index]).resolve() == Path(probe_dir):
            removed_probe_paths.append((index, sys.path.pop(index)))
    try:
        from pypdf import PdfReader
    except ImportError:
        return {"parser": "pdf-text-probe", "status": "UNAVAILABLE", "summary": {}, "facts": [], "warnings": ["当前 Python 环境缺少 pypdf。"]}
    finally:
        for index, value in sorted(removed_probe_paths):
            sys.path.insert(index, value)
    reader = PdfReader(str(path))
    pages: list[dict[str, Any]] = []
    total_chars = 0
    total_dimensions = 0
    for index, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        dimensions = [{"value": match.group(1), "unit": match.group(2)} for match in DIMENSION_RE.finditer(text)]
        total_chars += len(text)
        total_dimensions += len(dimensions)
        pages.append({"page": index, "textChars": len(text), "dimensionTokens": dimensions[:100]})
    return {
        "parser": "pdf-text-probe",
        "status": "PARSED_PARTIAL",
        "summary": {"format": "PDF", "pageCount": len(reader.pages), "textChars": total_chars, "dimensionTokenCount": total_dimensions, "pages": pages},
        "facts": [
            fact("page_count", len(reader.pages), "PDF page tree", 0.99),
            fact("text_character_count", total_chars, "PDF extracted text", 0.96),
            fact("dimension_token_count", total_dimensions, "PDF text regex", 0.74),
        ],
        "warnings": [
            "当前只提取 PDF 文本和显式单位 token；工程图中的矢量尺寸、箭头、形位公差和图框仍需专用工程图解析器。",
            "扫描图或字体编码异常时，必须使用 OCR/视觉解析并人工确认。",
        ],
    }


def inspect_signature(path: Path, extension: str) -> dict[str, Any]:
    head = path.read_bytes()[:64]
    signature = head.decode("latin-1", errors="replace").replace("\x00", "\\0")
    adapter = {
        ".x_t": "Open Cascade/Parasolid adapter",
        ".prt": "NX/Siemens adapter or STEP conversion service",
        ".dwg": "ODA/AutoCAD adapter",
    }[extension]
    return {
        "parser": "format-signature-probe",
        "status": "ADAPTER_REQUIRED",
        "summary": {"format": extension.lstrip("."), "signature": signature[:64], "requiredAdapter": adapter},
        "facts": [fact("format_signature", signature[:64], "file header", 0.99)],
        "warnings": [f"{extension} 文件已识别，但当前环境没有可用的 {adapter}。不能将其几何内容用于正式出题。"],
    }


def inspect_file(path: Path, root: Path) -> dict[str, Any]:
    extension = path.suffix.lower()
    relative = str(path.relative_to(root))
    result: dict[str, Any] = {
        "path": relative,
        "extension": extension,
        "sizeBytes": path.stat().st_size,
        "sha256": sha256(path),
    }
    try:
        if extension in {".step", ".stp"}:
            details = inspect_step(path)
        elif extension == ".stl":
            details = inspect_stl(path)
        elif extension == ".obj":
            details = inspect_obj(path)
        elif extension == ".pdf":
            details = inspect_pdf(path)
        elif extension in {".x_t", ".prt", ".dwg"}:
            details = inspect_signature(path, extension)
        else:
            details = {"parser": "none", "status": "SKIPPED", "summary": {}, "facts": [], "warnings": []}
        result.update(details)
    except Exception as error:  # probe must report a bad file, not abort all evidence
        result.update({"parser": "error", "status": "FAILED", "summary": {}, "facts": [], "warnings": [f"解析失败：{type(error).__name__}: {error}"]})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only CAD and engineering-drawing capability probe")
    parser.add_argument("--root", required=True, type=Path, help="资料根目录")
    parser.add_argument("--output", type=Path, help="可选 JSON 输出路径")
    parser.add_argument("--extensions", nargs="*", default=sorted(SUPPORTED_EXTENSIONS), help="限制扩展名，例如 .step .pdf")
    parser.add_argument("--max-files", type=int, default=0, help="最多处理文件数，0 表示全部")
    args = parser.parse_args()
    root = args.root.resolve()
    extensions = {value.lower() if value.startswith(".") else f".{value.lower()}" for value in args.extensions}
    paths = sorted(path for path in root.rglob("*") if path.is_file() and path.suffix.lower() in extensions)
    if args.max_files > 0:
        paths = paths[:args.max_files]
    files = [inspect_file(path, root) for path in paths]
    status_counts: dict[str, int] = {}
    for item in files:
        status = item.get("status", "UNKNOWN")
        status_counts[status] = status_counts.get(status, 0) + 1
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "fileCount": len(files),
        "statusCounts": status_counts,
        "files": files,
    }
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
