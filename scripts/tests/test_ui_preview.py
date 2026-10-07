"""Preview cache discovery and shareable gallery packaging, without launching Java."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('ui_preview', SCRIPTS / 'ui-preview.py')
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class UiPreviewTests(unittest.TestCase):
    def test_cached_sdk_supports_macos_app_and_linux_windows_layouts(self):
        for suffix in ('', 'Contents', 'IntelliJ IDEA CE.app/Contents', 'idea-IC-251'):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as temporary:
                cache = Path(temporary)
                home = cache / 'caches/8.14/transforms/hash/transformed/ideaIC-2025.1' / suffix
                (home / 'lib').mkdir(parents=True)
                (home / 'lib/app-client.jar').touch()
                self.assertEqual(preview.cached_sdk(cache), home.resolve())

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
