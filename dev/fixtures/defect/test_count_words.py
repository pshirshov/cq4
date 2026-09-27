import subprocess
import sys
import unittest
from count_words import counts


class CountingTest(unittest.TestCase):
    def test_count_sort_and_case(self):
        self.assertEqual(counts("Zebra apple APPLE"), [("apple", 2), ("zebra", 1)])

    def test_empty(self):
        self.assertEqual(counts(""), [])

    def test_ascii_delimiters(self):
        self.assertEqual(counts("a_b 12 café"), [("a", 1), ("b", 1), ("caf", 1)])

    def test_cli(self):
        result = subprocess.run([sys.executable, "count_words.py"], input="Z z a", text=True, capture_output=True)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "a\t1\nz\t2\n", ""))


if __name__ == "__main__":
    unittest.main()
