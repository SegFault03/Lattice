"""Provision inputs for the PowerShell UI entry points, then run and clean up."""
import argparse
import sys
import json
import os
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import processes as subprocess
from dependencies import Dependencies, temporary_environment
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('configuration', type=Path)
    args = parser.parse_args()
    config = json.loads(args.configuration.read_text(encoding='utf-8-sig'))
    parameters = config['parameters']
    options = argparse.Namespace(
        download_dir=Path(parameters['DownloadDir']) if parameters.get('DownloadDir') else None,
        binaries_dir=Path(parameters['BinariesDir']) if parameters.get('BinariesDir') else None,
        gradle_user_home=Path(parameters['GradleUserHome']) if parameters.get('GradleUserHome') else None,
        cleanup=bool(parameters.get('Cleanup', False)) and not parameters.get('NoCleanup', False))
    with Dependencies(options) as deps:
        java = deps.java(parameters.get('JavaHome'))
        ide = deps.ide(parameters.get('IdeHome'))
        environment = deps.environment(java)
        info = json.loads((ide / 'product-info.json').read_text(encoding='utf-8-sig'))
        baseline = ide if info.get('version') == '2025.1' and not parameters.get('BuildIdeHome') else deps.ide(parameters.get('BuildIdeHome'))
        environment['LATTICE_BUILD_IDE'] = str(baseline)
        if not parameters.get('FixtureCache') and os.environ.get('LATTICE_UI_FIXTURE_CACHE'):
            parameters['FixtureCache'] = os.environ['LATTICE_UI_FIXTURE_CACHE']
        if parameters.get('FixtureCache') and not Path(parameters['FixtureCache']).is_dir():
            raise ValueError('-FixtureCache must contain mysql/ and hsqldb/ fixture folders')
        if not parameters.get('FixtureCache') and options.binaries_dir:
            parameters['FixtureCache'] = str(options.binaries_dir / 'jdbc')
        repository = parameters.get('MavenRepository') or str(deps.workspace() / 'maven-repository')
        environment['LATTICE_UI_MAVEN_REPOSITORY'] = repository
        artifacts = [
            'com/mysql/mysql-connector-j/9.0.0/mysql-connector-j-9.0.0.jar',
            'com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar',
            'com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar',
            'org/hsqldb/hsqldb/2.7.2/hsqldb-2.7.2.jar',
            'org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar',
            'org/hsqldb/hsqldb/2.6.1/hsqldb-2.6.1-jdk8.jar',
            'org/hsqldb/hsqldb/2.4.1/hsqldb-2.4.1.jar',
        ]
        for artifact in artifacts:
            destination = Path(repository).expanduser().resolve() / artifact
            if destination.is_file():
                continue
            kind = 'mysql' if artifact.startswith('com/mysql/') else 'hsqldb'
            supplied = Path(parameters['FixtureCache']) / kind / Path(artifact).name if parameters.get('FixtureCache') else None
            supplied = supplied if supplied and supplied.is_file() else None
            jar = deps.file(f'jdbc/{kind}/{Path(artifact).name}', 'https://repo.maven.apache.org/maven2/' + artifact, explicit=supplied)
            if not zipfile.is_zipfile(jar):
                raise ValueError('Invalid JDBC fixture: ' + str(jar))
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(jar, destination)
        parameters.update(JavaHome=str(java), IdeHome=str(ide), DependenciesReady=True)
        for name in ('DownloadDir', 'BinariesDir', 'GradleUserHome', 'Cleanup', 'NoCleanup', 'Python', 'BuildIdeHome'):
            parameters.pop(name, None)
        parameters['Python'] = sys.executable
        if config['script'] == 'capture-intellij-ui.ps1':
            parameters.pop('FixtureCache', None)
            parameters.pop('MavenRepository', None)
        else:
            parameters['MavenRepository'] = repository
        # JSON avoids native PowerShell's array and quoted-path argument ambiguity.
        prepared = deps.workspace() / 'powershell-parameters.json'
        prepared.write_text(json.dumps(parameters), encoding='utf-8')
        script = Path(__file__).parent / config['script']
        if script.name not in ('capture-intellij-ui.ps1', 'review-intellij-ui.ps1'):
            raise ValueError('Unsupported PowerShell entry point')
        environment.update(LATTICE_PS_PARAMETERS=str(prepared), LATTICE_PS_SCRIPT=str(script))
        command = "$values = Get-Content -LiteralPath $env:LATTICE_PS_PARAMETERS -Raw | ConvertFrom-Json; $params = @{}; $values.PSObject.Properties | ForEach-Object { $params[$_.Name] = $_.Value }; & $env:LATTICE_PS_SCRIPT @params"
        with temporary_environment(environment):
            subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                            '-Command', command], check=True)


if __name__ == '__main__':
    main()
