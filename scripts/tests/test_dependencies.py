"""Dependency lifetimes, offline overrides and bootstrap failure boundaries."""
from argparse import Namespace
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dependencies import Dependencies, download, extract
from windows import native_mysql


class DependencyTests(unittest.TestCase):
    def options(self, root, cleanup=True, binaries=None):
        return Namespace(download_dir=root, cleanup=cleanup, binaries_dir=binaries, gradle_user_home=None)

    def test_cleanup_on_failure_removes_only_owned_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            supplied = root / 'supplied/tool.jar'
            supplied.parent.mkdir()
            supplied.write_bytes(b'keep')
            with self.assertRaisesRegex(RuntimeError, 'fixture failed'):
                with Dependencies(self.options(root)) as deps:
                    owned = deps.workspace()
                    deps.file('tool.jar', 'unused', explicit=supplied)
                    (owned / 'download.jar').write_bytes(b'owned')
                    raise RuntimeError('fixture failed')
            self.assertFalse(owned.exists())
            self.assertEqual(b'keep', supplied.read_bytes())
            self.assertTrue(root.is_dir())

    def test_cleanup_handles_readonly_windows_archive_entries(self):
        import stat
        with tempfile.TemporaryDirectory() as directory:
            with Dependencies(self.options(Path(directory))) as deps:
                owned = deps.workspace()
                item = owned / 'readonly.dll'
                item.write_bytes(b'runtime')
                item.chmod(stat.S_IREAD)
            self.assertFalse(owned.exists())

    def test_retained_download_is_reused_offline_and_not_deleted_by_next_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with Dependencies(self.options(root, cleanup=False)) as first:
                retained = first.workspace() / 'tools/tool.jar'
                retained.parent.mkdir()
                retained.write_bytes(b'tool')
            with patch('dependencies.urllib.request.urlopen') as network:
                with Dependencies(self.options(root)) as second:
                    self.assertEqual(retained, second.file('tools/tool.jar', 'unused'))
                network.assert_not_called()
            self.assertTrue(retained.exists())

    def test_explicit_invalid_java_never_downloads_a_replacement(self):
        with tempfile.TemporaryDirectory() as directory, patch('dependencies.urllib.request.urlopen') as network:
            with Dependencies(self.options(Path(directory))) as deps:
                with self.assertRaisesRegex(ValueError, 'JDK 21'):
                    deps.java(Path(directory) / 'missing')
            network.assert_not_called()

    def test_missing_file_downloads_and_corrupt_download_is_not_promoted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = b'verified asset'
            with Dependencies(self.options(root)) as deps, patch('dependencies.urllib.request.urlopen', return_value=io.BytesIO(content)):
                asset = deps.file('tools/file.jar', 'https://example.invalid/file', hashlib.sha256(content).hexdigest())
                self.assertEqual(content, asset.read_bytes())
            self.assertFalse(asset.exists())
            with patch('dependencies.urllib.request.urlopen', return_value=io.BytesIO(b'corrupt')):
                with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                    download('https://example.invalid/file', root / 'bad.jar', hashlib.sha256(content).hexdigest())
            self.assertFalse((root / 'bad.jar').exists())
            self.assertFalse((root / 'bad.jar.part').exists())

    def test_zip_extractor_rejects_windows_and_posix_traversal(self):
        for name in ('../outside', '..\\outside', 'C:/outside', '/outside'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'bad.zip'
                with zipfile.ZipFile(archive, 'w') as bundle:
                    bundle.writestr(name, b'escape')
                with self.assertRaisesRegex(ValueError, 'Unsafe ZIP'):
                    extract(archive, root / 'destination')
                self.assertFalse((root / 'outside').exists())

    def test_java_bootstrap_selects_host_archive_and_marks_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def unpack(archive, destination):
                binary = destination / 'jdk/bin' / ('java.exe' if os.name == 'nt' else 'java')
                binary.parent.mkdir(parents=True)
                binary.touch()
            package = b'{"binary":{"package":{"link":"https://example.invalid/jdk.zip","name":"jdk.zip","checksum":"abc"}}}'
            with Dependencies(self.options(root)) as deps, patch('dependencies.java_version', side_effect=lambda home, *args: (home / 'bin').is_dir()), \
                 patch('dependencies.shutil.which', return_value=None), patch.dict(os.environ, {}, clear=True), \
                 patch('dependencies.platform.system', return_value='Windows'), patch('dependencies.platform.machine', return_value='AMD64'), \
                 patch('dependencies.urllib.request.urlopen', return_value=io.BytesIO(b'[' + package + b']')) as metadata, \
                 patch('dependencies.download', return_value=root / 'jdk.zip') as fetch, patch('dependencies.extract', side_effect=unpack):
                home = deps.java()
                self.assertTrue(home.is_dir())
                self.assertTrue((home.parent / '.complete').exists())
                self.assertIn('os=windows', metadata.call_args.args[0].full_url)
                fetch.assert_called_once()

    def test_native_mysql_refuses_occupied_port_before_provisioning(self):
        with patch('test.require_free_port', side_effect=OSError('occupied')), patch.object(native_mysql, 'mysql_home') as resolve:
            with self.assertRaisesRegex(OSError, 'occupied'):
                with native_mysql.mysql_fixture(None):
                    self.fail('Occupied port was taken over')
            resolve.assert_not_called()

    def test_gradle_cache_respects_environment_and_explicit_override(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            with Dependencies(self.options(root)) as deps:
                self.assertEqual(deps.workspace() / 'gradle-home', Path(deps.environment(root)['GRADLE_USER_HOME']))
            with patch.dict(os.environ, {'GRADLE_USER_HOME': str(root / 'shared')}):
                with Dependencies(self.options(root)) as deps:
                    self.assertEqual(root / 'shared', Path(deps.environment(root)['GRADLE_USER_HOME']))
                options = self.options(root)
                options.gradle_user_home = root / 'explicit'
                with Dependencies(options) as deps:
                    self.assertEqual(root / 'explicit', Path(deps.environment(root)['GRADLE_USER_HOME']))

    def test_unresolved_cache_variables_fail_before_creating_directories(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            for option in ('download_dir', 'gradle_user_home'):
                with self.subTest(option=option):
                    args = self.options(root)
                    setattr(args, option, root / '%LATTICE_UNDEFINED_PROFILE%' / '.gradle')
                    with self.assertRaisesRegex(ValueError, 'unresolved environment variable'):
                        Dependencies(args)
                    self.assertEqual([], list(root.iterdir()))

    @unittest.skipUnless(os.name == 'nt', 'Windows CMD environment variables')
    def test_literal_cmd_cache_variable_expands_outside_checkout(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'LATTICE_TEST_PROFILE': directory}):
            root = Path(directory)
            args = self.options(root)
            args.gradle_user_home = Path('%LATTICE_TEST_PROFILE%/.gradle')
            with Dependencies(args) as deps:
                self.assertEqual(root / '.gradle', Path(deps.environment(root)['GRADLE_USER_HOME']))

    @unittest.skipUnless(os.name == 'nt', 'Windows wrapper socket defaults')
    def test_windows_wrapper_uses_ipv4_and_preserves_java_options(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for supplied, expected in (('', '-Djava.net.preferIPv4Stack=true'),
                                       ('-Xmx512m', '-Xmx512m -Djava.net.preferIPv4Stack=true'),
                                       ('-Djava.net.preferIPv4Stack=false', '-Djava.net.preferIPv4Stack=false')):
                with self.subTest(supplied=supplied), patch.dict(os.environ, {'JAVA_TOOL_OPTIONS': supplied}), \
                     Dependencies(self.options(root)) as deps:
                    self.assertEqual(expected, deps.environment(root)['JAVA_TOOL_OPTIONS'])

    def test_ide_download_uses_portable_windows_zip_and_validates_checksum(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def unpack(archive, destination):
                destination.mkdir(parents=True)
                (destination / 'product-info.json').write_text('{}')
            release = {'IIC': [{'version': '2025.1', 'downloads': {
                'windows': {'link': 'https://example.invalid/installer.exe'},
                'windowsZip': {'link': 'https://example.invalid/ide.win.zip', 'checksumLink': 'https://example.invalid/ide.win.zip.sha256'},
            }}]}
            with Dependencies(self.options(root)) as deps, patch('dependencies.platform.system', return_value='Windows'), \
                 patch('dependencies.platform.machine', return_value='AMD64'), \
                 patch('dependencies.urllib.request.urlopen', side_effect=[io.BytesIO(json.dumps(release).encode()), io.BytesIO(b'abcd  ide.win.zip')]), \
                 patch('dependencies.download', return_value=root / 'ide.win.zip') as fetch, patch('dependencies.extract', side_effect=unpack):
                home = deps.ide()
                self.assertTrue((home / '.complete').is_file())
                self.assertEqual('https://example.invalid/ide.win.zip', fetch.call_args.args[0])
                self.assertEqual('abcd', fetch.call_args.args[2])

    def test_runtime_extraction_reads_all_embedded_cabinets(self):
        import struct
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def cabinet(payload):
                return b'MSCF' + b'\0' * 4 + struct.pack('<I', 36 + len(payload)) + b'\0' * 24 + payload
            package = root / 'runtime.exe'
            package.write_bytes(b'executable prefix' + cabinet(b'first') + b'padding' + cabinet(b'second'))
            tool = root / '7za.exe'
            tool.touch()
            with patch.object(native_mysql.subprocess, 'run') as unpack:
                native_mysql.extract_runtime_package(package, root / 'output', tool)
            self.assertEqual(2, unpack.call_count)
            self.assertEqual(2, len(list((root / 'output/cabinets').glob('*.cab'))))


if __name__ == '__main__':
    unittest.main()
