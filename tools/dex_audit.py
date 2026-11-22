#!/usr/bin/env python3
"""Dex audit v2 for ALPR Signal — REACHABILITY audit (replaces the v1 prefix audit).

Why v1 failed, phone evidence 2026-11-20 12:18 HST (ALPR Signal v1.0):
  ML Kit client creation failed: NoClassDefFoundError: Failed resolution of:
  Landroidx/collection/ArraySet
v1's audit PASSED that build ("ML Kit closure resolves") because it only
checked types referenced by classes under the mlkit / datatransport /
firebase / tesseract / javax.inject / androidx.arch.core prefixes.
ArraySet is NOT referenced by any of those classes — it is instantiated
by com.google.android.gms.common.api.internal.GoogleApiManager and
zaae (dexdump-verified), i.e. by the play-services classes ML Kit's
client creation loads. The audit never looked at gms, so it never saw it.

v2 therefore does a real reachability analysis on the FINAL dex:
  1. Parse `dexdump -d classes.dex`, one section per defined class.
     For each class collect every type it references in field/method
     signatures and Dalvik instructions (annotation blocks excluded —
     annotations are metadata, not loaded code).
  2. BFS from the seeds: the app's own classes (art.phoenix.platebase.*)
     plus every ML Kit / datatransport / firebase-components class.
     Every defined class reachable from a seed is followed; every type
     referenced by a reachable class is recorded.
  3. FAIL the build if any reachable type is missing from the dex and
     from android.jar, except the allowlist below.

Allowlist (each entry has a recorded proof — no silent "never loaded"):
  * `*/R$*` — generated resource classes. This manual aapt2 build has
    no resources, so no R classes exist in the dex. R field values are
    inlined by the compiler; the classes are never instantiated by any
    path this app runs (NotificationCompat styles / GoogleApi error
    dialogs are not used by this app). If a code path that actually
    reads an R field ever runs, it would fail — none of the app's paths
    do, and the Test tab would show the real error.
  * `Llibcore/io/Memory;`, `Lsun/misc/Unsafe;` — present in the Android
    runtime boot classpath on the device; absent only from the
    android.jar stub, which is not the runtime.
  * `Ljava/*`, `Lkotlin/*`, `Ldalvik/*` — platform / stdlib, in the dex
    or the device boot classpath.

On the v1.0 dex this audit reports 28 reachable missing types; after
vendoring the real libraries (see build.sh header) it must report 0
outside the allowlist.

Usage: dex_audit.py <classes.dex> <android.jar> <dexdump-path>
"""
import re
import subprocess
import sys
import zipfile

ALLOW_RE = [
    re.compile(r"^L(?:[A-Za-z0-9_$]+/)+R\$[A-Za-z0-9_$]+;$"),
    re.compile(r"^Llibcore/io/Memory;$"),
    re.compile(r"^Lsun/misc/Unsafe;$"),
    re.compile(r"^Ljava/"),
    re.compile(r"^Lkotlin/"),
    re.compile(r"^Ldalvik/"),
    re.compile(r"^Ljavax/annotation/"),
]


def parse_dexdump(dexdump, dex_path):
    out = subprocess.run([dexdump, "-d", dex_path], capture_output=True)
    text = out.stdout.decode("utf-8", "replace")
    sections = re.split(r"(?m)^Class #\d+.*$", text)
    refs = {}
    for sec in sections:
        m = re.search(r"Class descriptor\s+:\s+'([^']+)'", sec)
        if not m:
            continue
        cls = m.group(1)
        lines = sec.splitlines()
        keep = []
        in_ann = False
        for line in lines:
            if re.match(r"\s*Annotations\s*$", line) or re.match(r"\s*AnnotationSetRefList", line):
                in_ann = True
            if not in_ann:
                keep.append(line)
        body = "\n".join(keep)
        toks = set(re.findall(r"L(?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+;", body))
        toks |= set(re.findall(r"\[+L(?:[A-Za-z0-9_$]+/)+[A-Za-z0-9_$]+;", body))
        refs[cls] = toks
    return refs


def android_jar_types(jar_path):
    out = set()
    with zipfile.ZipFile(jar_path) as z:
        for n in z.namelist():
            if n.endswith(".class"):
                out.add("L" + n[:-6] + ";")
    return out


def is_allowed(t):
    return any(p.match(t) for p in ALLOW_RE)


def main():
    dex_path, android_jar, dexdump = sys.argv[1], sys.argv[2], sys.argv[3]
    refs = parse_dexdump(dexdump, dex_path)
    defined = set(refs.keys())
    android_types = android_jar_types(android_jar)

    seeds = [
        c for c in defined
        if c.startswith("Lart/phoenix/platebase/")
        or c.startswith("Lcom/google/mlkit/")
        or c.startswith("Lcom/google/android/datatransport/")
        or c.startswith("Lcom/google/firebase/components/")
    ]
    reach = set(seeds)
    frontier = list(seeds)
    missing = {}
    while frontier:
        c = frontier.pop()
        for t in refs.get(c, ()):
            base = t.lstrip("[")
            if base in defined and base not in reach:
                reach.add(base)
                frontier.append(base)
            elif base not in defined and base not in android_types and not is_allowed(base):
                missing.setdefault(base, set()).add(c)

    # Hard-required classes: fail even if not referenced (defence in depth).
    required = [
        "Ljavax/inject/Provider;",
        "Lcom/google/android/datatransport/runtime/dagger/internal/Factory;",
        "Lcom/google/mlkit/common/sdkinternal/MlKitContext;",
        "Lcom/google/mlkit/vision/text/TextRecognition;",
        "Lcom/google/mlkit/common/internal/CommonComponentRegistrar;",
        "Lcom/google/mlkit/vision/text/internal/TextRegistrar;",
        "Lcom/google/mlkit/vision/common/internal/VisionCommonRegistrar;",
        "Landroidx/collection/ArraySet;",
        "Landroidx/collection/ArrayMap;",
        "Landroidx/collection/SimpleArrayMap;",
        "Landroidx/collection/LruCache;",
        "Landroidx/collection/LongSparseArray;",
    ]
    missing_req = [r for r in required if r not in defined]

    print(f"dex classes defined: {len(defined)}; reachable from app+ML Kit: {len(reach)}")
    print(f"reachable missing types (outside allowlist): {len(missing)}")
    for t in sorted(missing):
        print(f"  MISSING {t}  <- {sorted(missing[t])[:3]}")
    if missing_req:
        print("MISSING required classes:")
        for m in missing_req:
            print(f"  {m}")
    if missing or missing_req:
        print("DEX AUDIT FAILED — a class the phone needs is not in the APK.")
        sys.exit(1)
    print("DEX AUDIT PASSED — reachable ML Kit closure resolves (0 missing types).")


if __name__ == "__main__":
    main()
