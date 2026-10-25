"""Fixture isolation, compatibility scope and historic-source integrity checks."""
import unittest
from unittest.mock import patch
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import postgres

class PostgresFixtureTests(unittest.TestCase):
    def test_matrix_uses_java8_drivers_with_explicit_legacy_server_boundary(self):
        self.assertEqual(len(postgres.POSTGRES_VERSIONS),7)
        self.assertEqual(postgres.POSTGRES_VERSIONS[0],'8.4.22')
        self.assertEqual(postgres.POSTGRES_VERSIONS[-1],'18.6')
        self.assertEqual(sum(len(postgres.matrix_drivers(v)) for v in postgres.POSTGRES_VERSIONS),14)
        for version in postgres.POSTGRES_VERSIONS:
            self.assertEqual(postgres.matrix_drivers(version),('42.2.27','42.7.3' if version in ('8.4.22','9.0.23') else '42.7.14'))
        for digest in postgres.LEGACY_SOURCES.values(): self.assertRegex(digest,r'^[0-9a-f]{64}$')

    def test_occupied_port_never_starts_or_removes_container(self):
        with patch('test.require_free_port',side_effect=ValueError('occupied')), patch.object(postgres.subprocess,'run') as run:
            with self.assertRaisesRegex(ValueError,'occupied'):
                with postgres.postgres_fixture(5432): self.fail('fixture started')
            run.assert_not_called()

    def test_modern_fixture_never_builds_a_source_image(self):
        with patch.object(postgres,'postgres_fixture') as fixture:
            fixture.return_value.__enter__.return_value='owned'
            with postgres.matrix_fixture(12345,'18.6',None,cleanup=False) as name:
                self.assertEqual(name,'owned')
            fixture.assert_called_once_with(12345,'18.6','docker',False)
