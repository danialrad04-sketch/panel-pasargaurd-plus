"""Read version and architecture from the Android package being published."""
import re
from struct import error as StructError, unpack_from
from zipfile import BadZipFile, ZipFile


def manifest_identity(payload):
    strings = []
    offset = 8
    while offset + 8 <= len(payload):
        kind, header, length = unpack_from("<HHI", payload, offset)
        end = offset + length
        if length < header or header < 8 or end > len(payload):
            raise ValueError("Malformed Android manifest")
        if kind == 1:
            count, _, flags, strings_start, _ = unpack_from("<IIIII", payload, offset + 8)
            if count > (length - header) // 4:
                raise ValueError("Malformed Android string pool")
            for index in range(count):
                start = offset + strings_start + unpack_from("<I", payload, offset + header + index * 4)[0]
                if not offset <= start < end:
                    raise ValueError("Android string offset outside container")
                if flags & 0x100:
                    start += 2 if payload[start] & 128 else 1
                    size = payload[start]
                    start += 1
                    if size & 128:
                        size = ((size & 127) << 8) | payload[start]
                        start += 1
                    if start + size > end:
                        raise ValueError("Truncated Android string")
                    strings.append(payload[start:start + size].decode("utf-8"))
                else:
                    size = unpack_from("<H", payload, start)[0]
                    start += 2
                    if size & 0x8000:
                        size = ((size & 0x7fff) << 16) | unpack_from("<H", payload, start)[0]
                        start += 2
                    if start + size * 2 > end:
                        raise ValueError("Truncated Android string")
                    strings.append(payload[start:start + size * 2].decode("utf-16le"))
        elif kind == 0x102:
            _, name, start, stride, count = unpack_from("<IIHHH", payload, offset + 16)
            if strings[name] == "manifest":
                if stride < 20 or offset + 16 + start + stride * count > end:
                    raise ValueError("Malformed Android attributes")
                values = {}
                for index in range(count):
                    address = offset + 16 + start + index * stride
                    key = strings[unpack_from("<I", payload, address + 4)[0]]
                    if key not in ("package", "versionName", "versionCode"):
                        continue
                    value = unpack_from("<I", payload, address + 16)[0]
                    values[key] = strings[value] if payload[address + 15] == 3 else value
                return values
        offset = end
    raise ValueError("Missing Android manifest element")


def inspect_apk(source):
    try:
        with ZipFile(source) as archive:
            manifest = archive.getinfo("AndroidManifest.xml")
            if not 1 <= manifest.file_size <= 2 * 1024 * 1024:
                raise ValueError("Invalid manifest size")
            values = manifest_identity(archive.read(manifest))
            native_abis = {name.split("/")[1] for name in archive.namelist()
                           if name.startswith("lib/") and name.count("/") >= 2 and name.endswith(".so")}
        package, version, build = (values.get(key) for key in ("package", "versionName", "versionCode"))
        if not isinstance(package, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+", package):
            raise ValueError("Invalid Android package")
        if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", version):
            raise ValueError("Invalid Android version")
        if not isinstance(build, int) or not 1 <= build <= 2_100_000_000:
            raise ValueError("Invalid Android build number")
        supported = {"arm64-v8a", "armeabi-v7a", "x86_64"}
        if not native_abis or supported <= native_abis:
            abi = "universal"
        elif len(native_abis) == 1 and native_abis <= supported:
            abi = next(iter(native_abis))
        else:
            raise ValueError("Unsupported APK ABI combination")
        return {"package_name": package, "latest_version": version, "latest_version_code": build, "abi": abi}
    except (BadZipFile, KeyError, ValueError, IndexError, StructError, RuntimeError) as error:
        raise ValueError("Invalid APK metadata") from error
