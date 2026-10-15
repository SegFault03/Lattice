"""Preview cache discovery and shareable gallery packaging, without launching Java."""
import importlib.util
import re
import struct
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('ui_preview', SCRIPTS / 'ui-preview.py')
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class UiPreviewTests(unittest.TestCase):
    def test_readme_images_meet_marketplace_resolution_with_original_display_widths(self):
        root = SCRIPTS.parent
        readme = (root / 'README.md').read_text(encoding='utf-8')
        widths = {'side-panel.png': 340, 'connection-dialog.png': 740,
                  'table-view.png': 1100, 'table-editing.png': 1100, 'sql-console.png': 1100}
        for name, display_width in widths.items():
            with self.subTest(name=name):
                data = (root / 'screenshots' / name).read_bytes()
                self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                width, height = struct.unpack('>II', data[16:24])
                self.assertGreaterEqual(width, 1200)
                self.assertGreaterEqual(height, 760)
                self.assertEqual(width % display_width, 0)
                self.assertGreaterEqual(width // display_width, 2)
                tag = re.search(r'<img\b[^>]*src="screenshots/' + re.escape(name) + r'"[^>]*>', readme)
                self.assertIsNotNone(tag)
                self.assertIn(f'width="{display_width}"', tag.group())
                self.assertNotIn('height=', tag.group())

    def test_gradle_wrapper_command_matches_host(self):
        with patch.object(preview.os, 'name', 'nt'):
            self.assertEqual(r'.\gradlew.bat', preview.gradle_wrapper())
        with patch.object(preview.os, 'name', 'posix'):
            self.assertEqual('./gradlew', preview.gradle_wrapper())

    def test_cached_sdk_supports_macos_app_and_linux_windows_layouts(self):
        for suffix in ('', 'Contents', 'IntelliJ IDEA CE.app/Contents', 'idea-IC-251'):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as temporary:
                cache = Path(temporary)
                home = cache / 'caches/8.14/transforms/hash/transformed/ideaIC-2025.1' / suffix
                (home / 'lib').mkdir(parents=True)
                (home / 'lib/app-client.jar').touch()
                self.assertEqual(preview.cached_sdk(cache), home.resolve())

    def test_cached_test_runtime_jars_requires_junit_and_hamcrest(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            junit = cache / 'caches/modules-2/files-2.1/junit/junit/4.13.2/hash/junit-4.13.2.jar'
            hamcrest = cache / 'caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/hash/hamcrest-core-1.3.jar'
            junit.parent.mkdir(parents=True)
            hamcrest.parent.mkdir(parents=True)
            junit.touch()
            self.assertIsNone(preview.cached_test_runtime_jars(cache))
            hamcrest.touch()
            self.assertEqual(preview.cached_test_runtime_jars(cache), [junit, hamcrest])

    def test_gallery_zip_uses_current_manifest_and_matching_baseline_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'current'
            baseline = root / 'baseline'
            output.mkdir()
            baseline.mkdir()
            for theme in ('light', 'dark'):
                filename = f'{theme}-table-1100.png'
                (output / filename).write_bytes(b'current screenshot')
                (baseline / filename).write_bytes(b'baseline screenshot')
                (output / f'{theme}-manifest.txt').write_text(filename + '\n')
            (output / 'light-stale.png').touch()
            (output / 'preview-classes').mkdir()
            (output / 'preview-classes/UiPreview.class').touch()
            preview.render_gallery(output, baseline)
            page = (output / 'index.html').read_text()
            self.assertIn('before/light-table-1100.png', page)
            self.assertNotIn('@FIGURES@', page)
            self.assertNotIn('@COMPARISON_HIDDEN@', page)
            with zipfile.ZipFile(output / 'lattice-ui-previews.zip') as archive:
                self.assertEqual(set(archive.namelist()), {
                    'index.html', 'README.md', 'light-table-1100.png', 'dark-table-1100.png',
                    'before/light-table-1100.png', 'before/dark-table-1100.png',
                })
                self.assertEqual(archive.read('before/dark-table-1100.png'), b'baseline screenshot')

    def test_gallery_rejects_mismatched_theme_sets(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / 'light-manifest.txt').write_text('light-table-1100.png\n')
            (output / 'dark-manifest.txt').write_text('dark-table-760.png\n')
            with self.assertRaisesRegex(ValueError, 'snapshot sets differ'):
                preview.render_gallery(output)

    def test_search_gallery_keeps_both_themes_and_drawer_widths(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            names = []
            for theme in ('light', 'dark'):
                images = [f'{theme}-explorer-search-{variant}.png'
                          for variant in ('all-340', 'focused-340', 'focused-1100')]
                (output / f'{theme}-manifest.txt').write_text('\n'.join(images))
                for name in images:
                    (output / name).write_bytes(b'search preview')
                names.extend(images)
            preview.render_gallery(output)
            page = (output / 'index.html').read_text()
            for name in names:
                if name.startswith('light-'):
                    self.assertIn(name, page)
            self.assertIn('<option value="dark">dark</option>', page)
            self.assertIn('data-screen="side-panel"', page)
            with zipfile.ZipFile(output / 'lattice-ui-previews.zip') as archive:
                self.assertTrue(set(names).issubset(archive.namelist()))

    def test_featured_gallery_supports_light_only_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            name = 'light-side-panel-340.png'
            (output / name).write_bytes(b'featured screenshot')
            (output / 'light-manifest.txt').write_text(name + '\n')
            (output / 'dark-manifest.txt').write_text('')
            preview.render_gallery(output)
            page = (output / 'index.html').read_text()
            self.assertIn('id="theme"', page)
            self.assertIn('<label hidden>Theme', page)
            self.assertNotIn('@DARK_THEME_OPTION@', page)
            self.assertNotIn('@FIGURES@', page)

    def test_publish_keeps_only_five_stable_readme_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'custom-gallery'
            screenshots = root / 'screenshots'
            output.mkdir()
            screenshots.mkdir()
            (screenshots / 'side-panel.png').write_bytes(b'previous render')
            (screenshots / 'dark-old-variant.png').write_bytes(b'old render')
            for theme in ('light', 'dark'):
                names = [name.replace('light-', theme + '-', 1) for name in preview.FEATURED_SCREENSHOTS.values()]
                (output / f'{theme}-manifest.txt').write_text('\n'.join(names))
                for name in names:
                    (output / name).write_bytes(name.encode())
            (output / 'light-stale.png').touch()
            preview.publish_screenshots(output, screenshots)
            for alias, source in preview.FEATURED_SCREENSHOTS.items():
                self.assertEqual((screenshots / alias).read_bytes(), source.encode())
            self.assertEqual({path.name for path in screenshots.glob('*.png')}, set(preview.FEATURED_SCREENSHOTS))
            self.assertFalse((screenshots / 'light-stale.png').exists())

    def test_missing_featured_variant_leaves_previous_readme_images_intact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'current'
            screenshots = root / 'screenshots'
            output.mkdir()
            screenshots.mkdir()
            (screenshots / 'side-panel.png').write_bytes(b'previous render')
            for theme in ('light', 'dark'):
                name = f'{theme}-table-1100.png'
                (output / f'{theme}-manifest.txt').write_text(name + '\n')
                (output / name).touch()
            with self.assertRaisesRegex(ValueError, 'Missing required screenshot'):
                preview.publish_screenshots(output, screenshots)
            self.assertEqual((screenshots / 'side-panel.png').read_bytes(), b'previous render')


if __name__ == '__main__':
    unittest.main()
