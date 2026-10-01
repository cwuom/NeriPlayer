from pathlib import Path
import tempfile
import unittest

from module_scope import select_scope


class ModuleScopeTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "player/nested").mkdir(parents=True)
        (self.root / "player/A.kt").write_text("class A")
        (self.root / "player/nested/B.kt").write_text("class B")

    def test_keeps_recursive_patterns_for_current_and_future_sources(self):
        scope = {"source_patterns": ["player/**/*.kt", "other/**/*.kt"]}
        self.assertEqual(["player/**/*.kt"], select_scope(scope, self.root)["source_patterns"])

    def test_preserves_method_rules_and_signatures(self):
        rule = {"source": "player/A.kt", "class": "A", "methods": ["run"], "descriptor": "()V"}
        selected = select_scope({"method_scopes": [rule, {"source": "other/A.kt"}]}, self.root)
        self.assertEqual([rule], selected["method_scopes"])

    def test_rejects_empty_scope_instead_of_succeeding_without_checks(self):
        with self.assertRaises(ValueError):
            select_scope({"source_patterns": ["other/**/*.kt"]}, self.root)

    def test_directory_match_is_not_a_production_source(self):
        with self.assertRaises(ValueError):
            select_scope({"source_patterns": ["player/nested"]}, self.root)


if __name__ == "__main__":
    unittest.main()
