#!/usr/bin/env bash
# Capture production UI flows in IDEA's bundled themes (optional installed IDE).
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${LATTICE_REVIEW_SUPERVISED:-}" != 1 ]]; then
    exec python3 "$repo_root/scripts/processes.py" --command -- env LATTICE_REVIEW_SUPERVISED=1 bash "${BASH_SOURCE[0]}" "$@"
fi
cd "$repo_root"
themes=("$@")
if [[ ${#themes[@]} -eq 0 ]]; then
    themes=(ExperimentalDark ExperimentalLight ExperimentalLightWithLightHeader JetBrainsHighContrastTheme Darcula IntelliJ JetBrainsLightTheme)
fi
for theme in "${themes[@]}"; do
    case "$theme" in
        ExperimentalDark|ExperimentalLight|ExperimentalLightWithLightHeader|JetBrainsHighContrastTheme|Darcula|IntelliJ|JetBrainsLightTheme|"Islands Dark"|"Islands Light"|"Islands Darcula") ;;
        *) echo "Unsupported theme: $theme" >&2; exit 2 ;;
    esac
done
export LATTICE_UI_MAVEN_REPOSITORY="$repo_root/build/ui-test-maven/repository"

fetch_jar() {
    local artifact_path="$1" destination="$LATTICE_UI_MAVEN_REPOSITORY/$1"
    if [[ ! -s "$destination" ]]; then
        mkdir -p "$(dirname "$destination")"
        if ! curl --fail --location --retry 3 "https://repo.maven.apache.org/maven2/$artifact_path" -o "$destination.part"; then
            rm -f "$destination.part"
            return 1
        fi
        mv "$destination.part" "$destination"
    fi
}
for version in 9.0.0 8.4.0 8.0.33; do
    fetch_jar "com/mysql/mysql-connector-j/$version/mysql-connector-j-$version.jar"
done
fetch_jar org/hsqldb/hsqldb/2.7.2/hsqldb-2.7.2.jar
fetch_jar org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar
fetch_jar org/hsqldb/hsqldb/2.6.1/hsqldb-2.6.1-jdk8.jar
fetch_jar org/hsqldb/hsqldb/2.4.1/hsqldb-2.4.1.jar

result_root="${LATTICE_UI_REVIEW_OUTPUT:-$repo_root/build/ui-review}"
mkdir -p "$result_root"
result_root="$(cd "$result_root" && pwd)"
for theme in "${themes[@]}"; do
    result_dir="$result_root/$theme"
    mkdir -p "$result_dir"
    # Each theme uses the same isolated test project and a fresh IDE system sandbox.
    # Preserve the log outside the directory cleared by the screenshot test.
    version_args=()
    if [[ -n "${LATTICE_UI_PLUGIN_VERSION:-}" ]]; then
        version_args+=("-PreleaseVersion=$LATTICE_UI_PLUGIN_VERSION")
    fi
    if [[ -n "${LATTICE_UI_RELEASE_NOTES_FILE:-}" ]]; then
        version_args+=("-PreleaseNotesFile=$LATTICE_UI_RELEASE_NOTES_FILE")
    fi
    if [[ "${LATTICE_UI_INPUTS_ONLY:-false}" == true ]]; then
        version_args+=("-Plattice.ui.inputsOnly=true")
    fi
    if [[ "${LATTICE_UI_STYLE_ONLY:-false}" == true ]]; then
        version_args+=("-Plattice.ui.styleOnly=true")
    fi
    ./scripts/linux/capture-intellij-ui.sh "${version_args[@]}" -Plattice.ui.review=true -Plattice.ui.theme="$theme" \
        -Plattice.ui.output="$result_dir" 2>&1 | tee "$result_root/$theme.log"
    cp build/test-results/uiScreenshotTest/TEST-com.segfault03.ideadb.ui.IntellijUiScreenshotTest.xml "$result_dir/test-result.xml"
done
python3 - "$result_root" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
lines = ['# Real IntelliJ UI captures', '', 'Every image is a full virtual-desktop capture of the running IDE.', '']
for directory in sorted(p for p in root.iterdir() if p.is_dir()):
    lines += [f'## {directory.name}', '']
    for png in sorted(directory.glob('*.png')):
        lines.append(f'- [{png.stem}](<{directory.name}/{png.name}>)')
    lines += [f'- [Runtime evidence](<{directory.name}/runtime-evidence.txt>)', f'- [JUnit result](<{directory.name}/test-result.xml>)', '']
(root / 'index.md').write_text('\n'.join(lines) + '\n')
PY
echo "Screenshots and live-runtime evidence: $result_root"
