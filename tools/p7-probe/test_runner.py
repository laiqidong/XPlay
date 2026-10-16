import importlib.util
import io
import tempfile
import shlex
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('p7_probe_runner', ROOT / 'scripts/p7_vehicle_probe.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class RunnerTests(unittest.TestCase):
    def test_default_only_builds_without_adb(self):
        with patch.object(sys, 'argv', ['p7_vehicle_probe.py']), patch.object(runner, 'build', return_value=Path('/tmp/probe.jar')), patch.object(runner, 'run') as run:
            runner.main()
            run.assert_not_called()

    def test_run_without_explicit_device_is_rejected_before_build(self):
        with patch.object(sys, 'argv', ['p7_vehicle_probe.py', '--run']), patch.object(runner, 'build') as build:
            with self.assertRaises(SystemExit) as error:
                runner.main()
            self.assertEqual(2, error.exception.code)
            build.assert_not_called()

    def test_runtime_commands_include_working_directory_before_entry_class(self):
        seen = []
        def fake_command(args, **kwargs):
            seen.append(args)
            if args[-1] == 'get-state':
                return 0, 'device\n'
            if args[-2] == 'shell' and 'P7Probe surface' in args[-1]:
                return 0, 'FRAMEWORK available\n'
            return 0, ''
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, 'command', side_effect=fake_command), patch('sys.stdout', new_callable=io.StringIO):
            runner.run('adb', 'test-device', Path('/tmp/probe.jar'), Path(folder))
        launches = [shlex.split(args[-1]) for args in seen if 'P7Probe' in str(args[-1])]
        self.assertEqual(11, len(launches))
        for tokens in launches:
            entry = tokens.index('com.laiqidong.xplay.tools.P7Probe')
            self.assertEqual('/system/bin', tokens[entry - 1])
            self.assertTrue(tokens[entry - 2].startswith('/system/bin/app_process'))

    def test_dump_filter_excludes_unrelated_identifiers_and_subscriptions(self):
        target = 'lastEvent:Property=VCU_BMS_SOCDISP(557847042),int32Values: [49]'
        text = target + '\nlastEvent:Property=MCU_VIN(1),string: secret\nsubscribed Property=VCU_BMS_SOCDISP(557847042)'
        self.assertEqual([target], runner.cached_fields(text))

if __name__ == '__main__':
    unittest.main()
