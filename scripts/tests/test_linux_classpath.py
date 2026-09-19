"""Manifest serialization and packaging safety for the Linux launcher fix."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from urllib.parse import unquote
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location(
    "compact_linux_classpath", Path(__file__).resolve().parents[1] / "compact-linux-classpath.py")
compact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compact)


class LinuxClasspathTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.image = Path(self.temp.name) / "CC Pocket"
        self.app = self.image / "lib" / "app"
        self.app.mkdir(parents=True)
        self.cfg = self.app / "CC Pocket.cfg"

    def prepare(self, names):
        for name in names:
            (self.app / name).write_bytes(b"original JAR contents")
        self.cfg.write_text("[Application]\napp.mainclass=example.Main\n" + "".join(
            f"app.classpath=$APPDIR/{name}\n" for name in names) +
            "\n[JavaOptions]\njava-options=-Dresources=$APPDIR/resources\n", encoding="utf-8")

    def test_order_uri_encoding_wrapping_and_unchanged_options(self):
        names = ["main.jar", "with space #%.jar", "中文.jar"] + [f"dependency-{i:03d}.jar" for i in range(120)]
        self.prepare(names)
        self.assertEqual(compact.compact(self.image), len(names))
        with ZipFile(self.app / compact.CLASSPATH_JAR) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF")
        self.assertTrue(manifest.endswith(b"\r\n\r\n"))
        self.assertTrue(all(len(line) <= 70 for line in manifest.split(b"\r\n")))
        unfolded = manifest.decode("ascii").replace("\r\n ", "")
        value = unfolded.split("Class-Path: ", 1)[1].split("\r\n", 1)[0]
        self.assertEqual([unquote(name) for name in value.split()], names)
        self.assertIn("app.mainclass=example.Main", self.cfg.read_text())
        self.assertIn("java-options=-Dresources=$APPDIR/resources", self.cfg.read_text())
        self.assertEqual(self.cfg.read_text().count("app.classpath="), 1)
        for name in names:
            self.assertEqual((self.app / name).read_bytes(), b"original JAR contents")

    def test_repeated_build_is_identical_and_checks_missing_dependency(self):
        self.prepare(["main.jar", "dependency.jar"])
        compact.compact(self.image)
        before = (self.cfg.read_bytes(), (self.app / compact.CLASSPATH_JAR).read_bytes())
        compact.compact(self.image)
        self.assertEqual(before, (self.cfg.read_bytes(), (self.app / compact.CLASSPATH_JAR).read_bytes()))
        (self.app / "dependency.jar").unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            compact.compact(self.image)

    def test_missing_dependency_does_not_modify_config(self):
        self.prepare(["main.jar"])
        (self.app / "main.jar").unlink()
        before = self.cfg.read_bytes()
        with self.assertRaisesRegex(ValueError, "Missing"):
            compact.compact(self.image)
        self.assertEqual(self.cfg.read_bytes(), before)
        self.assertFalse((self.app / compact.CLASSPATH_JAR).exists())

    def test_external_or_empty_classpath_is_rejected(self):
        for value in ("", "app.classpath=/tmp/other.jar\n", "app.classpath=$APPDIR/../other.jar\n"):
            with self.subTest(value=value):
                self.cfg.write_text("[Application]\n" + value)
                before = self.cfg.read_bytes()
                with self.assertRaises(ValueError):
                    compact.compact(self.image)
                self.assertEqual(self.cfg.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
