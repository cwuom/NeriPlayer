import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from domain_dependencies import check_bridge_members, check_class_dependencies, load_domains, verify


class DomainDependenciesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.classes = self.root / "classes"
        self.classes.mkdir()
        self.domain = {
            "name": "calculation", "package": "sample.domain.",
            "descriptor_only": [],
            "allowed_classes": ["java.lang.*", "java.util.function.*"],
            "bridges": {"sample.bridge.Identity": ["Methodref key:()Ljava/lang/String;"]}
        }
        self.config = self.root / "domains.json"
        self.config.write_text(json.dumps([self.domain]))
        self.sources = {
            "sample/runtime/PlayerManager.java": (
                "package sample.runtime; public class PlayerManager {"
                "public static String read() { return \"runtime\"; }}"
            ),
            "sample/bridge/Identity.java": (
                "package sample.bridge; public class Identity {"
                "public static String key() { return \"key\"; }"
                "public static String load() { return sample.runtime.PlayerManager.read(); }"
                "public static String clean(String text, sample.runtime.Context context) { return text; }}"
            ),
            "sample/runtime/Context.java": "package sample.runtime; public class Context {}"
        }

    def compile(self, body, extra=None):
        sources = dict(self.sources)
        sources["sample/domain/Calculation.java"] = "package sample.domain; public class Calculation {" + body + "}"
        sources.update(extra or {})
        files = []
        for name, source in sources.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        subprocess.run(["javac", "--release", "17", "-d", str(self.classes), *files],
                       capture_output=True, text=True, check=True)

    def check(self):
        return verify([self.classes], load_domains(self.config))

    def test_allows_precise_identity_bridge_and_nested_computation(self):
        self.compile("public String key() { return sample.bridge.Identity.key(); }"
                     "static class Nested { int sum(int a, int b) { return a + b; }}")
        report = self.check()
        self.assertEqual([], report["errors"])
        self.assertEqual(2, report["domains"]["calculation"])
        self.assertEqual(1, report["bridge_references"])

    def test_rejects_fully_qualified_runtime_call(self):
        self.compile("public String load() { return sample.runtime.PlayerManager.read(); }")
        self.assertTrue(any("forbidden class sample.runtime.PlayerManager" in x for x in self.check()["errors"]))

    def test_rejects_same_package_helper_that_calls_runtime(self):
        self.compile("public String load() { return Neighbor.load(); }", {
            "sample/domain/Neighbor.java": "package sample.domain; class Neighbor {"
            "static String load() { return sample.runtime.PlayerManager.read(); }}"
        })
        errors = self.check()["errors"]
        self.assertTrue(any("sample.domain.Neighbor -> forbidden class" in x for x in errors))

    def test_rejects_runtime_calls_in_lambda_and_nested_class(self):
        self.compile("public java.util.function.Supplier<String> load() {"
                     "return () -> sample.runtime.PlayerManager.read(); }"
                     "static class Nested { String load() { return sample.runtime.PlayerManager.read(); }}")
        errors = self.check()["errors"]
        self.assertTrue(any("Calculation -> forbidden class" in x for x in errors))
        self.assertTrue(any("Calculation$Nested -> forbidden class" in x for x in errors))

    def test_new_domain_classes_are_checked_even_without_existing_callers(self):
        self.compile("", {
            "sample/domain/NewPolicy.java": "package sample.domain; class NewPolicy {"
            "sample.runtime.PlayerManager runtime; }"
        })
        self.assertTrue(any("sample.domain.NewPolicy -> forbidden class" in x for x in self.check()["errors"]))

    def test_allowed_bridge_class_cannot_expose_another_method(self):
        self.compile("public String load() { return sample.bridge.Identity.load(); }")
        errors = self.check()["errors"]
        self.assertTrue(any("forbidden bridge member sample.bridge.Identity#Methodref load:" in x for x in errors))

    def test_bridge_types_can_be_allowed_without_permitting_construction(self):
        self.compile("public sample.bridge.Identity identity = new sample.bridge.Identity();")
        self.assertTrue(any("Methodref <init>:()V" in x for x in self.check()["errors"]))

    def test_another_domain_is_not_implicitly_allowed(self):
        self.compile("public String load() { return sample.bridge.Identity.key(); }")
        second = {"name": "bridge", "package": "sample.bridge.", "allowed_classes": ["java.lang.*"],
                  "bridges": {}, "descriptor_only": []}
        first = dict(self.domain, bridges={})
        errors = verify([self.classes], [first, second])["errors"]
        self.assertTrue(any("calculation: sample.domain.Calculation -> forbidden class sample.bridge.Identity" in x
                            for x in errors))

    def test_reads_jars_and_rejects_duplicate_classes(self):
        self.compile("public String key() { return sample.bridge.Identity.key(); }")
        jar = self.root / "classes.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for source in self.classes.rglob("*.class"):
                archive.write(source, source.relative_to(self.classes).as_posix())
        self.assertEqual([], verify([jar], [self.domain])["errors"])
        with self.assertRaisesRegex(ValueError, "Duplicate compiled class"):
            verify([jar, self.classes], [self.domain])

    def test_empty_missing_and_unmatched_inputs_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "No compiled classes"):
            self.check()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            verify([self.root / "missing.jar"], [self.domain])
        self.compile("")
        with self.assertRaisesRegex(ValueError, "no compiled classes in protected package"):
            verify([self.classes], [dict(self.domain, package="missing.domain.")])

    def test_tool_failures_and_omitted_classes_fail_closed(self):
        self.compile("")
        with self.assertRaises(OSError):
            verify([self.classes], [self.domain], jdeps=str(self.root / "missing-tool"))
        selected = {"sample.domain.Calculation": self.domain}
        with self.assertRaisesRegex(ValueError, "jdeps omitted"):
            check_class_dependencies("unexpected output", selected)
        with self.assertRaisesRegex(ValueError, "javap omitted"):
            check_bridge_members("unexpected output", selected)

    def test_cli_exit_codes_distinguish_violations_from_invalid_inputs(self):
        self.compile("public String load() { return sample.runtime.PlayerManager.read(); }")
        output = self.root / "report.json"
        command = [sys.executable, str(Path(__file__).with_name("domain_dependencies.py")),
                   "--config", str(self.config), "--input", str(self.classes), "--output", str(output)]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertTrue(json.loads(output.read_text())["errors"])
        self.config.write_text("[]")
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(2, result.returncode, result.stderr)

    def test_invalid_or_overlapping_configuration_fails_closed(self):
        self.config.write_text(json.dumps([dict(self.domain, package="sample.domain")]))
        with self.assertRaisesRegex(ValueError, "dotted prefix"):
            load_domains(self.config)
        self.compile("")
        with self.assertRaisesRegex(ValueError, "Overlapping domains"):
            verify([self.classes], [self.domain, dict(self.domain, name="duplicate")])

    def descriptor_domain(self):
        self.domain["descriptor_only"] = ["sample.runtime.Context"]
        self.domain["bridges"]["sample.bridge.Identity"].append(
            "Methodref clean:(Ljava/lang/String;Lsample/runtime/Context;)Ljava/lang/String;"
        )
        self.config.write_text(json.dumps([self.domain]))

    def test_allows_descriptor_only_type_in_the_precise_bridge_signature(self):
        self.descriptor_domain()
        self.compile('public String value() { return sample.bridge.Identity.clean("value", null); }')
        self.assertEqual([], self.check()["errors"])

    def test_descriptor_only_type_cannot_be_used_directly_even_when_the_bridge_is_called(self):
        self.descriptor_domain()
        bodies = [
            "public Class<?> type() { return sample.runtime.Context.class; }",
            "public sample.runtime.Context field;",
            "public java.util.List<sample.runtime.Context> genericField;",
            "public void accept(sample.runtime.Context context) {}",
            "public Object cast(Object value) { return (sample.runtime.Context) value; }",
            "public Object array() { return new sample.runtime.Context[1]; }"
        ]
        for body in bodies:
            with self.subTest(body=body):
                self.compile(body + 'public String value() { return sample.bridge.Identity.clean("value", null); }')
                self.assertTrue(any("descriptor-only type sample.runtime.Context" in x for x in self.check()["errors"]))

    def test_an_own_method_cannot_reuse_an_allowed_bridge_descriptor(self):
        self.descriptor_domain()
        self.compile('public String value(String text, sample.runtime.Context context) {'
                     'return sample.bridge.Identity.clean(text, context); }')
        self.assertTrue(any("declared on the computational class" in x for x in self.check()["errors"]))

    def test_malformed_member_output_is_not_silently_ignored(self):
        output = "this_class: #2 // sample/domain/Calculation\n #5 = Methodref unexpected-format"
        with self.assertRaisesRegex(ValueError, "Unrecognized javap member"):
            check_bridge_members(output, {"sample.domain.Calculation": self.domain})


if __name__ == "__main__":
    unittest.main()
