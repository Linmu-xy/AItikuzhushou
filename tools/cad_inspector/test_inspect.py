import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("inspect.py")
SPEC = importlib.util.spec_from_file_location("cad_probe", MODULE_PATH)
CAD_PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(CAD_PROBE)


class CadInspectorTests(unittest.TestCase):
    def test_step_extracts_entities_and_points(self):
        content = """ISO-10303-21; HEADER; FILE_SCHEMA(('AUTOMOTIVE_DESIGN_CC2')); ENDSEC; DATA; #1=CARTESIAN_POINT('',(1.,2.,3.)); #2=CARTESIAN_POINT('',(-1.,4.,0.)); #3=CYLINDRICAL_SURFACE('',#1,5.); ENDSEC; END-ISO-10303-21;"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.step"
            path.write_text(content, encoding="latin-1")
            result = CAD_PROBE.inspect_step(path)
        self.assertEqual(result["status"], "PARSED_PARTIAL")
        self.assertEqual(result["summary"]["cartesianPointCount"], 2)
        self.assertEqual(result["summary"]["commonEntities"]["CARTESIAN_POINT"], 2)
        self.assertEqual(result["summary"]["approximateBounds"]["size"], [2.0, 2.0, 3.0])

    def test_binary_stl_extracts_mesh_metrics(self):
        header = b"probe".ljust(80, b" ")
        triangle = struct.pack("<12fH", 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.stl"
            path.write_bytes(header + struct.pack("<I", 1) + triangle)
            result = CAD_PROBE.inspect_stl(path)
        self.assertEqual(result["status"], "PARSED")
        self.assertEqual(result["summary"]["triangleCount"], 1)
        self.assertEqual(result["summary"]["approximateBounds"]["size"], [1.0, 1.0, 0.0])

    def test_proprietary_formats_are_not_claimed_as_parsed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.prt"
            path.write_bytes(b"SPLMSSTR\x06")
            result = CAD_PROBE.inspect_signature(path, ".prt")
        self.assertEqual(result["status"], "ADAPTER_REQUIRED")
        self.assertFalse(result["facts"][0]["usableForGeneration"])
