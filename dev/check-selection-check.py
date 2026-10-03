#!/usr/bin/env python3
"""Check suite selection without executing a gate or a Scala test body."""
import ast
import contextlib
import io
import sys
import tempfile
from pathlib import Path
import runpy
import unittest

module = runpy.run_path(str(Path(__file__).with_name('check')))

class SelectionChecks(unittest.TestCase):
    def test_fast_never_selects_test_suites_by_pattern(self):
        tree = ast.parse(Path(__file__).with_name('check').read_text())
        commands = [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant)
                    and isinstance(node.value, str) and 'testOnly ' in node.value]
        self.assertFalse([command for command in commands if '*' in command], commands)

    def test_failure_output_keeps_the_assertion_before_a_large_log_tail(self):
        with tempfile.TemporaryDirectory(prefix='cq-check-output-') as temporary:
            checks = module['Checks'](Path(temporary), {})
            stderr = io.StringIO()
            script = "print('[info] - retained evidence *** FAILED ***'); print('[info] assertion: actual 2 expected 1'); print('noise' * 20000); raise SystemExit(1)"
            with contextlib.redirect_stderr(stderr), self.assertRaises(RuntimeError):
                checks.run([sys.executable, '-c', script], 'failed-fixture', 10)
            self.assertIn('assertion: actual 2 expected 1', stderr.getvalue())
            self.assertIn('noise', (Path(temporary) / 'failed-fixture.log').read_text())

    def test_selected_suites_are_run_by_separate_exact_commands(self):
        checks = module['Checks'](Path('/unused'), {})
        commands = []
        def sbt(selected, name):
            commands.extend(selected)
            if selected == ['show server/Test/definedTestNames']:
                return '[info] * cq.server.FirstDummy\n[info] * cq.server.SecondLocal\n[info] * cq.server.OtherPostgres\n'
            return '\n'.join('Tests: succeeded 1, failed 0' for _ in selected)
        checks.sbt = sbt
        checks.contracts_of('Dummy|Local', 'focused')
        self.assertEqual(commands, ['show server/Test/definedTestNames',
                                   'server/testOnly cq.server.FirstDummy', 'server/testOnly cq.server.SecondLocal'])

if __name__ == '__main__':
    unittest.main()
