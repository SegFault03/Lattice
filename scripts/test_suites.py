"""Shared CI/local suite catalog and workflow contract (standard library only)."""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'scripts/test-suites.json'
PLATFORMS = {'linux', 'windows', 'macos'}
RUNNERS = {'linux': 'ubuntu-24.04', 'windows': 'windows-2025', 'macos': 'macos-15'}
KINDS = {'build', 'command', 'gradle', 'live', 'ui', 'postgres-ui', 'h2-ui', 'verifier', 'database'}
REQUIREMENTS = {'java21', 'java8', 'ide', 'mysql', 'jdbc-cache', 'desktop', 'ui-fixtures', 'docker'}


def load_catalog(path=CATALOG):
    catalog = json.loads(Path(path).read_text(encoding='utf-8'))
    if catalog.get('schema') != 1 or not catalog.get('suites'):
        raise ValueError('Unsupported or empty test-suite catalog')
    seen = set()
    for suite in catalog['suites']:
        name = suite['id']
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]*', name) or name in seen:
            raise ValueError(f'Invalid or duplicate suite ID: {name}')
        seen.add(name)
        platforms = set(suite['platforms'])
        if not platforms or not platforms <= PLATFORMS or not suite['ci_hosts'] or not set(suite['ci_hosts']) <= platforms:
            raise ValueError(f'{name}: invalid supported platforms or CI hosts')
        unsupported = suite.get('unsupported', {})
        if set(unsupported) != PLATFORMS - platforms or not all(str(reason).strip() for reason in unsupported.values()):
            raise ValueError(f'{name}: every unsupported platform needs an explicit reason')
        if suite['kind'] not in KINDS or suite['phase'] not in {'base', 'ui', 'ide', 'database'}:
            raise ValueError(f'{name}: unknown execution kind or phase')
        phase = {'ui-tests': 'ui', 'ide-compatibility': 'ide', 'database-compatibility': 'database'}.get(suite['group'], 'base')
        if suite['phase'] != phase:
            raise ValueError(f'{name}: execution phase differs from its CI group')
        if not set(suite['requires']) <= REQUIREMENTS:
            raise ValueError(f'{name}: unknown dependency requirement')
        required = set()
        if suite['kind'] != 'command':
            required.add('java21')
        if suite['kind'] == 'database':
            required |= {'java8', 'jdbc-cache'}
        if suite['kind'] in {'ui', 'postgres-ui', 'h2-ui'}:
            required |= {'ide', 'desktop'}
        if not required <= set(suite['requires']):
            raise ValueError(f'{name}: missing dependencies required by its execution adapter')
        if suite['kind'] in {'build', 'gradle', 'live'} and not suite.get('tasks'):
            raise ValueError(f'{name}: missing Gradle selectors')
        if suite['kind'] == 'build' and not suite.get('package_tasks'):
            raise ValueError(f'{name}: missing packaging selectors')
        if suite['kind'] == 'command' and not suite.get('command'):
            raise ValueError(f'{name}: missing command')
        if suite['kind'] in {'ui', 'postgres-ui', 'h2-ui'} and not suite.get('theme'):
            raise ValueError(f'{name}: missing UI theme')
        if suite['kind'] in {'postgres-ui', 'h2-ui'} and platforms != {'linux'}:
            raise ValueError(f'{name}: this owned UI wrapper supports Linux only')
        if suite['kind'] == 'ui' and 'macos' in platforms:
            raise ValueError(f'{name}: no macOS native UI adapter')
        if suite['kind'] == 'live' and set(suite.get('fixtures', {})) != platforms:
            raise ValueError(f'{name}: fixture selection required for every supported platform')
        if suite['kind'] == 'live' and any(not set(fixtures) <= {'mysql', 'hsqldb', 'postgres'} for fixtures in suite['fixtures'].values()):
            raise ValueError(f'{name}: unknown owned fixture adapter')
        if suite['kind'] == 'live' and any('mysql' in fixtures for fixtures in suite['fixtures'].values()):
            if 'mysql' not in suite['requires'] or not suite.get('mysql_native_version') or not suite.get('mysql_docker_image'):
                raise ValueError(f'{name}: declare native and Docker MySQL fixture versions')
        if suite['kind'] == 'verifier' and not suite.get('version'):
            raise ValueError(f'{name}: missing IDE version')
        if suite['kind'] == 'database' and suite.get('mode') not in {'mysql', 'hsqldb', 'h2', 'postgres'}:
            raise ValueError(f'{name}: unknown database matrix adapter')
        if suite['kind'] == 'database' and suite['mode'] == 'postgres' and platforms != {'linux'}:
            raise ValueError(f'{name}: the PostgreSQL matrix adapter supports Linux only')
    return catalog


def host_platform():
    return {'win32': 'windows', 'darwin': 'macos'}.get(sys.platform, sys.platform)


def suite_by_id(name, catalog=None):
    for suite in (catalog or load_catalog())['suites']:
        if suite['id'] == name:
            return suite
    raise ValueError(f'Unknown test suite: {name}')


def select_suites(platform, *, phases=None, ids=None, catalog=None):
    """All supported entries are selected automatically; no separate local allowlist."""
    if platform not in PLATFORMS:
        raise ValueError(f'Unsupported platform: {platform}')
    catalog = catalog or load_catalog()
    if ids:
        selected = [suite_by_id(name, catalog) for name in dict.fromkeys(ids)]
        for suite in selected:
            if platform not in suite['platforms']:
                raise ValueError(f"{suite['id']}: {suite['unsupported'][platform]}")
        return selected
    return [suite for suite in catalog['suites']
            if platform in suite['platforms'] and (phases is None or suite['phase'] in phases)]


def gradle_tasks(name, *, packaging=False):
    suite = suite_by_id(name)
    return list(suite['package_tasks'] if packaging else suite['tasks'])


def github_matrices(catalog=None):
    suites = (catalog or load_catalog())['suites']
    return {
        name: {'include': [dict(suite=suite['id'], os=RUNNERS[host], **{('ide' if field == 'version' else field): suite[field]})
                           for suite in suites if suite['group'] == group for host in suite['ci_hosts']]}
        for name, group, field in [('ui', 'ui-tests', 'theme'), ('ide', 'ide-compatibility', 'version'),
                                   ('database', 'database-compatibility', 'mode')]
    }


def workflow_jobs(workflow):
    return dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                           workflow.split('jobs:\n', 1)[1], re.M | re.S))


def check_workflow(workflow, catalog=None):
    """Fail closed on unregistered suites, direct test commands and host-selection drift.

    Only the repository's small workflow shape is accepted. This is a policy
    check, not a general YAML parser; actionlint separately validates YAML/Actions.
    """
    catalog = catalog or load_catalog()
    suites = catalog['suites']
    jobs = workflow_jobs(workflow)
    groups = {suite['group'] for suite in suites}
    if set(jobs) != groups | {'suite-plan', 'full-validation'}:
        raise ValueError('Every CI test job must have a catalog group')
    plan = jobs['suite-plan']
    if re.search(r'^    if:', plan, re.M) or '--check-workflow --github-matrices' not in plan:
        raise ValueError('The suite parity guard must run on every event, including staging')
    for group in groups:
        body = jobs[group]
        if 'continue-on-error:' in body:
            raise ValueError(f'{group}: required suite failures cannot be ignored')
        if len(re.findall(r'scripts/one-shot-test.py .*--in-place', body)) != 1:
            raise ValueError(f'{group}: dispatch its shared selection exactly once')
        if group in {'ui-tests', 'ide-compatibility', 'database-compatibility'}:
            output = {'ui-tests': 'ui', 'ide-compatibility': 'ide', 'database-compatibility': 'database'}[group]
            if f'matrix: ${{{{ fromJSON(needs.suite-plan.outputs.{output}) }}}}' not in body:
                raise ValueError(f'{group}: matrix must come from the shared catalog')
            if 'SUITE_ID: ${{ matrix.suite }}' not in body or '--suite "$SUITE_ID" --in-place' not in body:
                raise ValueError(f'{group}: execute the catalog matrix suite ID')
            if 'runs-on: ${{ matrix.os }}' not in body:
                raise ValueError(f'{group}: runner hosts must come from the catalog matrix')
        else:
            calls = re.findall(r'scripts/one-shot-test.py --ci-group ([a-z0-9.-]+) --in-place', body)
            if calls != [group]:
                raise ValueError(f'{group}: execute its shared CI group exactly once')
            if 'os: [ubuntu-24.04, windows-2025, macos-15]' not in body or 'runs-on: ${{ matrix.os }}' not in body:
                raise ValueError(f'{group}: all supported CI hosts must be routed through the shared group')
        for step in re.split(r'(?=^      - )', body, flags=re.M):
            if 'scripts/one-shot-test.py ' in step and re.search(r'^        if:', step, re.M):
                raise ValueError(f'{group}: suite selection belongs to the catalog, not a step condition')
        # Preparation is allowed; executable test selection belongs to the runner.
        for run in re.findall(r'^        run: (.*?)(?=^      - |\Z)', body, re.M | re.S):
            for line in run.splitlines():
                line = line.strip()
                if not line or line in {'|', '>-'}:
                    continue
                if (line.startswith(('python scripts/one-shot-test.py ', 'python3 scripts/one-shot-test.py '))
                        and '--in-place' in line):
                    if re.search(r'\|\||&&|[;<>]', line):
                        raise ValueError(f'{group}: execute the shared runner without shell overrides')
                    continue
                if group == 'ui-tests' and (line == './gradlew writeUiIdeHome --no-daemon'
                                           or line in {'sudo apt-get update', 'sudo apt-get install -y xvfb xfwm4 x11-utils'}):
                    continue
                if group == 'ide-compatibility' and line in {
                    'archives=(build/distributions/*.zip)',
                    '[[ ${#archives[@]} -eq 1 && -f "${archives[0]}" ]]',
                }:
                    continue
                raise ValueError(f'{group}: unregistered CI execution: {line}')
    return select_suites('windows', catalog=catalog)
