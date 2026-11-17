"""Build a self-contained Windows installer from the same verified bundle files."""
import os
from pathlib import Path, PurePosixPath
import subprocess

from build_installers import fetch, verify_windows_signature

INNO_VERSION = "7.1.1"
INNO_URL = "https://github.com/jrsoftware/issrc/releases/download/is-7_1_1/innosetup-7.1.1-x64.exe"


def compiler(cache):
    # A pinned, publisher-verified build tool is installed only into the build
    # cache, in portable mode. It is not a dependency on the recipient's PC.
    root = Path(cache).resolve() / ("inno-" + INNO_VERSION)
    installer = root / "compiler-setup.exe"
    executable = root / "compiler" / "ISCC.exe"
    root.mkdir(parents=True, exist_ok=True)
    if not installer.exists():
        fetch(INNO_URL, installer)
    verify_windows_signature(installer, r"Pyrsys B\.V\.")
    if not executable.exists():
        subprocess.run([str(installer), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART",
                        "/SP-", "/PORTABLE=1", "/NOICONS", "/DIR=" + str(executable.parent)],
                       check=True, timeout=180)
    if not executable.is_file():
        raise RuntimeError("Inno Setup compiler installation did not finish")
    verify_windows_signature(executable, r"Pyrsys B\.V\.")
    return executable


def quoted(value):
    # Literal file paths in an ISS entry: braces must not become Inno constants.
    value = str(value)
    if any(c in value for c in ('\r', '\n', '"')):
        raise ValueError("Unsupported installer path")
    return '"' + value.replace('{', '{{') + '"'


def build(folder, payload_paths, version, output, cache):
    if os.name != "nt":
        raise RuntimeError("Windows Setup must be built on Windows")
    iscc = compiler(cache)
    folder, output = Path(folder).resolve(), Path(output).resolve()
    source = Path(__file__).with_name("windows-setup.iss").read_text(encoding="utf-8")
    entries = []
    paths = {p.relative_to(folder).as_posix(): p for p in folder.rglob('*') if p.is_file()}
    paths.update(payload_paths)
    for relative, path in sorted(paths.items()):
        dest = PurePosixPath(relative)
        if dest.is_absolute() or '..' in dest.parts or '\\' in relative:
            raise ValueError("Unsafe installer destination: " + relative)
        directory = "{app}" + ("\\" + str(dest.parent).replace('/', '\\') if str(dest.parent) != '.' else '')
        # Images and vendor installers are already compressed. Store directly
        # to avoid slow recompression and large temporary extraction archives.
        flags = "ignoreversion" + (" nocompression" if path.suffix.lower() in ('.gz', '.msi', '.exe') else '')
        entries.append('Source: ' + quoted(path.resolve()) + '; DestDir: "' + directory
                       + '"; DestName: ' + quoted(dest.name) + '; Flags: ' + flags)
    script = folder.parent / "EL-Setup.iss"
    script.write_text(source.replace("@@VERSION@@", version).replace("@@OUTPUT_DIR@@", str(output.parent))
                      .replace("@@OUTPUT_NAME@@", output.stem) + '\n[Files]\n' + '\n'.join(entries) + '\n',
                      encoding="utf-8-sig")
    subprocess.run([str(iscc), "/Qp", str(script)], check=True)
    if not output.is_file():
        raise RuntimeError("Windows Setup output missing")
