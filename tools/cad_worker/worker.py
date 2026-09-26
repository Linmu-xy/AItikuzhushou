#!/usr/bin/env python3
"""Isolated CAD analysis worker for the material-exam package.

The worker is intentionally database-free. It produces a versioned JSON fact
document and an optional OBJ preview. Facts are unverified by default; the
application must require human confirmation before using them for generation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "cad-fact.v1"
NUMBER = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?"
DIMENSION_RE = re.compile(rf"(?<![\w.])({NUMBER})\s*(mm|毫米|cm|厘米|m|米|°|度)\b", re.IGNORECASE)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fact(name: str, value: Any, source: str, confidence: float, unit: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "name": name,
        "value": value,
        "source": source,
        "confidence": confidence,
        "verified": False,
        "usableForGeneration": False,
    }
    if unit:
        result["unit"] = unit
    return result


def empty_result(path: Path, parser: str, status: str, warnings: list[str]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": {"name": path.name, "extension": path.suffix.lower(), "sha256": sha256(path)},
        "parser": parser,
        "status": status,
        "facts": [],
        "annotations": [],
        "preview": None,
        "warnings": warnings,
    }


def shape_counts(shape: Any) -> dict[str, int]:
    from OCP.TopAbs import TopAbs_EDGE, TopAbs_FACE, TopAbs_SHELL, TopAbs_SOLID, TopAbs_VERTEX
    from OCP.TopExp import TopExp_Explorer

    result: dict[str, int] = {}
    for name, kind in (("solid", TopAbs_SOLID), ("shell", TopAbs_SHELL), ("face", TopAbs_FACE), ("edge", TopAbs_EDGE), ("vertex", TopAbs_VERTEX)):
        explorer = TopExp_Explorer(shape, kind)
        count = 0
        while explorer.More():
            count += 1
            explorer.Next()
        result[name] = count
    return result


def analyze_step(path: Path, preview: Path | None, deflection: float, max_triangles: int) -> dict[str, Any]:
    from OCP.BRep import BRep_Tool
    from OCP.BRepAdaptor import BRepAdaptor_Surface
    from OCP.BRepGProp import BRepGProp
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.Bnd import Bnd_Box
    from OCP.GeomAbs import GeomAbs_SurfaceType
    from OCP.GProp import GProp_GProps
    from OCP.IFSelect import IFSelect_ReturnStatus
    from OCP.STEPControl import STEPControl_Reader
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS

    reader = STEPControl_Reader()
    status = reader.ReadFile(str(path))
    if status != IFSelect_ReturnStatus.IFSelect_RetDone:
        return empty_result(path, "opencascade-step", "FAILED", [f"STEP 读取失败：{status}"])
    transferred = reader.TransferRoots()
    shape = reader.OneShape()
    if shape.IsNull() or transferred <= 0:
        return empty_result(path, "opencascade-step", "FAILED", ["STEP 没有可用的实体根。"])

    box = Bnd_Box()
    from OCP.BRepBndLib import BRepBndLib
    BRepBndLib.Add_s(shape, box)
    minimum = [box.GetXMin(), box.GetYMin(), box.GetZMin()]
    maximum = [box.GetXMax(), box.GetYMax(), box.GetZMax()]
    size = [maximum[index] - minimum[index] for index in range(3)]
    properties = GProp_GProps()
    BRepGProp.VolumeProperties_s(shape, properties)
    volume = properties.Mass()
    surface_properties = GProp_GProps()
    BRepGProp.SurfaceProperties_s(shape, surface_properties)
    area = surface_properties.Mass()
    counts = shape_counts(shape)

    surface_counts: Counter[str] = Counter()
    radii: list[float] = []
    explorer = TopExp_Explorer(shape, TopAbs_FACE)
    while explorer.More():
        face = TopoDS.Face(explorer.Current())
        adaptor = BRepAdaptor_Surface(face, True)
        surface_type = adaptor.GetType()
        name = str(surface_type).rsplit(".", 1)[-1].replace("GeomAbs_", "").lower()
        surface_counts[name] += 1
        if surface_type == GeomAbs_SurfaceType.GeomAbs_Cylinder:
            radii.append(round(adaptor.Cylinder().Radius(), 6))
        explorer.Next()

    facts = [
        fact("unit", "model-native; verify from STEP unit entity", "STEP unit declarations", 0.92),
        fact("bounding_box", {"min": minimum, "max": maximum, "size": size}, "Open Cascade Bnd_Box", 0.99),
        fact("volume", round(volume, 8), "Open Cascade BRepGProp.VolumeProperties", 0.99),
        fact("surface_area", round(area, 8), "Open Cascade BRepGProp.SurfaceProperties", 0.99),
        fact("topology_counts", counts, "Open Cascade TopExp_Explorer", 0.99),
        fact("surface_counts", dict(surface_counts), "Open Cascade BRepAdaptor_Surface", 0.96),
        fact("cylinder_radii", sorted(set(radii)), "Open Cascade cylindrical surface radii", 0.88),
    ]
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": {"name": path.name, "extension": path.suffix.lower(), "sha256": sha256(path)},
        "parser": "opencascade-step",
        "status": "PARSED",
        "facts": facts,
        "annotations": [],
        "preview": None,
        "warnings": ["模型事实默认未人工确认，不能直接用于正式出题。"],
    }
    if preview:
        result["preview"] = mesh_to_obj(shape, preview, deflection, max_triangles)
    return result


def mesh_to_obj(shape: Any, preview: Path, deflection: float, max_triangles: int) -> dict[str, Any]:
    from OCP.BRep import BRep_Tool
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS

    preview.parent.mkdir(parents=True, exist_ok=True)
    BRepMesh_IncrementalMesh(shape, deflection, False, deflection, True)
    vertices = 0
    triangles = 0
    with preview.open("w", encoding="utf-8") as stream:
        stream.write("# Generated by material-exam CAD Worker\n")
        explorer = TopExp_Explorer(shape, TopAbs_FACE)
        while explorer.More() and triangles < max_triangles:
            face = TopoDS.Face(explorer.Current())
            location = TopLoc_Location()
            triangulation = BRep_Tool.Triangulation_s(face, location)
            if triangulation is not None:
                nodes: dict[int, int] = {}
                for index in range(1, triangulation.NbNodes() + 1):
                    point = triangulation.Node(index).Transformed(location.Transformation())
                    stream.write(f"v {point.X():.9f} {point.Y():.9f} {point.Z():.9f}\n")
                    vertices += 1
                    nodes[index] = vertices
                for index in range(1, triangulation.NbTriangles() + 1):
                    if triangles >= max_triangles:
                        break
                    first, second, third = triangulation.Triangle(index).Get()
                    stream.write(f"f {nodes[first]} {nodes[second]} {nodes[third]}\n")
                    triangles += 1
            explorer.Next()
    return {"format": "OBJ", "path": str(preview), "vertexCount": vertices, "triangleCount": triangles, "truncated": triangles >= max_triangles}


def analyze_pdf(path: Path) -> dict[str, Any]:
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    annotations: list[dict[str, Any]] = []
    pages: list[dict[str, Any]] = []
    for page_number, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        dimensions = [{"value": match.group(1), "unit": match.group(2)} for match in DIMENSION_RE.finditer(text)]
        pages.append({"page": page_number, "textChars": len(text), "dimensionTokenCount": len(dimensions)})
        for dimension in dimensions[:200]:
            annotations.append({"kind": "DIMENSION_TOKEN", "page": page_number, **dimension, "confidence": 0.72, "verified": False})
    result = empty_result(path, "pypdf-engineering-drawing-text", "PARSED_PARTIAL", [
        "当前只读取 PDF 文本和显式单位 token；矢量尺寸、箭头、形位公差和视图关系需要进一步适配。",
        "工程图事实默认未人工确认，不能直接用于正式出题。",
    ])
    result["summary"] = {"pageCount": len(reader.pages), "pages": pages, "annotationCount": len(annotations)}
    result["annotations"] = annotations
    result["facts"] = [
        fact("page_count", len(reader.pages), "PDF page tree", 0.99),
        fact("dimension_token_count", len(annotations), "PDF text dimension regex", 0.72),
    ]
    return result


def analyze_dxf(path: Path) -> dict[str, Any]:
    import ezdxf

    drawing = ezdxf.readfile(path)
    modelspace = drawing.modelspace()
    counts = Counter(entity.dxftype() for entity in modelspace)
    annotations = []
    for entity in modelspace:
        if entity.dxftype() in {"DIMENSION", "TEXT", "MTEXT", "LEADER", "TOLERANCE"}:
            annotations.append({"kind": entity.dxftype(), "handle": entity.dxf.handle, "verified": False, "confidence": 0.86})
    result = empty_result(path, "ezdxf", "PARSED_PARTIAL", [
        "DXF 图元和标注实体已读取；尺寸语义、视图关系和单位仍需工程图规则确认。",
        "工程图事实默认未人工确认，不能直接用于正式出题。",
    ])
    result["summary"] = {"version": drawing.dxfversion, "entityCounts": dict(counts), "annotationCount": len(annotations)}
    result["annotations"] = annotations
    result["facts"] = [
        fact("entity_counts", dict(counts), "DXF modelspace entity types", 0.98),
        fact("annotation_count", len(annotations), "DXF annotation entities", 0.88),
    ]
    return result


def analyze(path: Path, preview: Path | None, deflection: float, max_triangles: int) -> dict[str, Any]:
    extension = path.suffix.lower()
    if extension in {".step", ".stp"}:
        return analyze_step(path, preview, deflection, max_triangles)
    if extension == ".pdf":
        return analyze_pdf(path)
    if extension == ".dxf":
        return analyze_dxf(path)
    if extension in {".prt", ".dwg", ".x_t"}:
        return empty_result(path, "format-signature-only", "ADAPTER_REQUIRED", [
            f"{extension} 需要专用 CAD 适配器，当前 Worker 不会伪造几何内容。"
        ])
    return empty_result(path, "none", "UNSUPPORTED", [f"暂不支持 {extension} 文件。"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--deflection", type=float, default=0.5)
    parser.add_argument("--max-triangles", type=int, default=250000)
    args = parser.parse_args()
    if not args.input.is_file():
        raise SystemExit(f"输入文件不存在：{args.input}")
    result = analyze(args.input, args.preview, max(0.001, args.deflection), max(1000, args.max_triangles))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["status"] not in {"FAILED", "UNSUPPORTED"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
