#!/usr/bin/env bash
# Build official, pinned WebRTC Java/JNI artifacts before invoking Soong.
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${DUMBER_WEBRTC_CHECKOUT:=/tmp/dumber-call-webrtc}"
: "${DUMBER_DEPOT_TOOLS:=/tmp/dumber-call-depot-tools}"
revision=5e07d59caf8106fcc1ecd8cfb3ce7af0a1c3316a
export PATH="$DUMBER_DEPOT_TOOLS:$PATH"
[[ -x "$DUMBER_DEPOT_TOOLS/gclient" ]] || { echo 'Install official depot_tools and set DUMBER_DEPOT_TOOLS' >&2; exit 1; }
mkdir -p "$DUMBER_WEBRTC_CHECKOUT"
cd "$DUMBER_WEBRTC_CHECKOUT"
if [[ ! -f .gclient ]]; then
    gclient config --name=src https://webrtc.googlesource.com/src.git
    printf '\ntarget_os = ["android"]\n' >> .gclient
fi
# A completed checkout contains our deliberate Java-level/JNI packaging patches.
# Do not ask gclient to overwrite them on an incremental rebuild.
if [[ "${DUMBER_WEBRTC_SKIP_SYNC:-0}" != 1 ]] && [[ ! -f .dumber-synced-revision || "$(cat .dumber-synced-revision)" != "$revision" ]]; then
    gclient sync --no-history --revision "src@$revision"
fi
[[ "$(git -C src rev-parse HEAD)" == "$revision" ]] || { echo 'Unexpected WebRTC revision' >&2; exit 1; }
printf '%s\n' "$revision" > .dumber-synced-revision
cd src
# Android 14's Soong/R8 toolchain consumes Java 17 bytecode. Upstream currently
# emits Java 25; lower the build tools' language level, never rewrite class headers.
python3 - <<'PATCH'
from pathlib import Path
for name in ('compile_java.py', 'compile_kt.py', 'turbine.py'):
    p=Path('build/android/gyp')/name
    s=p.read_text()
    # These pinned scripts each have a single quoted Java release literal.
    import re
    s,n=re.subn(r"(['\"])25\1", lambda m: m[1]+'17'+m[1], s)
    if n==0 and not re.search(r"(['\"])17\1",s): raise RuntimeError('Unexpected Java release in '+name)
    p.write_text(s)
# Upstream's dist_jar lists only direct Java dependencies and omits the new
# JNI Zero wrappers/registration. Include the closure and compile its final
# registration srcjar against the exact native target built below.
p=Path('sdk/android/BUILD.gn');s=p.read_text()
s=s.replace('    direct_deps_only = true', '    direct_deps_only = false', 1)
marker='  dist_jar("libwebrtc") {'
if 'rtc_android_library("dumber_jni_registration_java")' not in s:
    s=s.replace(marker, '''  rtc_android_library("dumber_jni_registration_java") {
    srcjar_deps = [ ":libjingle_peerconnection_so__jni_registration" ]
    deps = [ "//third_party/jni_zero:jni_zero_java" ]
  }

'''+marker,1)
    start=s.index(marker);before=s[:start];after=s[start:]
    after=after.replace('    deps = [', '    deps = [\n      ":dumber_jni_registration_java",',1)
    s=before+after
s=s.replace('    direct_deps_only = false\n', '    direct_deps_only = false\n    jar_excluded_patterns = [ \"META-INF/*\" ]\n',1) if '    jar_excluded_patterns = [ \"META-INF/*\" ]' not in s else s
p.write_text(s)
PATCH
python3 tools_webrtc/android/build_aar.py --build-dir out/dumber-audio --output out/dumber-webrtc.aar \
    --arch armeabi-v7a arm64-v8a --extra-gn-args 'is_debug=false' 'rtc_include_tests=false' 'rtc_build_examples=false' 'rtc_use_h264=false' 'use_remoteexec=false'
python3 - "$app_dir" "$revision" <<'PY'
import hashlib,io,json,sys,zipfile
from pathlib import Path
root=Path(sys.argv[1])/'prebuilts/webrtc';root.mkdir(parents=True,exist_ok=True)
checksums={}
with zipfile.ZipFile('out/dumber-webrtc.aar') as archive:
    for member,dest in [('classes.jar','classes.jar')]+[(f'jni/{abi}/libjingle_peerconnection_so.so',f'{abi}/libjingle_peerconnection_so.so') for abi in ('armeabi-v7a','arm64-v8a')]:
        data=archive.read(member)
        if member=='classes.jar':
            # Avoid repackaging AndroidX/Kotlin already provided by the host.
            output=io.BytesIO()
            with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
                for name in sorted(source.namelist()):
                    if name.endswith('.class') and name.startswith(('org/webrtc/','org/jni_zero/','J/')):
                        info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                        target.writestr(info,source.read(name))
                # Chromium normally inlines this forwarding class while building
                # its APK. A distributable AAR must keep the real final version,
                # never one of the per-target compilation placeholders.
                with zipfile.ZipFile('out/dumber-audio/armeabi-v7a/obj/sdk/android/dumber_jni_registration_java.compliment.jar') as registration:
                    name='org/jni_zero/GEN_JNI.class'
                    info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                    target.writestr(info,registration.read(name))
                required={'org/webrtc/PeerConnectionFactoryJni.class','J/N.class','org/jni_zero/GEN_JNI.class','org/jni_zero/JniZeroJni.class'}
                if not required.issubset(target.namelist()): raise RuntimeError('Missing WebRTC JNI registration')
            data=output.getvalue()
        p=root/dest;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data);checksums[dest]=hashlib.sha256(data).hexdigest()
(root/'manifest.json').write_text(json.dumps({'revision':sys.argv[2],'sha256':checksums},indent=2)+'\n')
(root/'LICENSE').write_bytes(Path('LICENSE').read_bytes())
# Upstream emits all third-party notices next to the AAR, not inside it.
(root/'THIRD_PARTY_LICENSES.md').write_bytes(Path('out/LICENSE.md').read_bytes())
PY
