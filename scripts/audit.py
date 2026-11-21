#!/usr/bin/env python3
"""A static gate for the mistakes this project keeps re-learning.

javac already runs with `-Xlint:all` and the tree is clean apart from
deprecations, so this is not a linter. These are the specific shapes that have
cost real releases and that no compiler can see:

  * a toast or a bare logcat line where the user-facing sink should be -- the
    one place a report can see is `DebugSink`;
  * `String.format` without a Locale on a number that goes into JSON (a Turkish
    locale writes `1,5` and the engine refuses the delta);
  * wall-clock arithmetic on an interval -- NTP moves the clock and a drag
    frame decides the finger is still down;
  * plaintext traffic allowed app-wide for one loopback debug server;
  * Kotlin or backup files sitting beside the Java sources, which either break
    the build later or get edited by mistake.

Run it before committing:

    python3 scripts/audit.py          # exit 1 on a regression
    python3 scripts/audit.py --list   # print every offender

The counts below are the debt as of 1.6.16w. A rule fails when its count goes
*up*; paying debt down is always allowed, and lowering the number here is how
the improvement gets locked in.
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app/src/main/java")
CPP = os.path.join(ROOT, "app/src/main/cpp")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")

# 1.6.16w baseline.
BASELINE = {
    "toast": 0,
    "bare log": 0,
    # One left, on purpose: "%.1f MB" is a readout, and a German user expects
    # "1,5 MB". Every number that reaches JSON or a cache key is Locale.US.
    "format without Locale": 1,
    "wall clock in an interval": 0,
    "cleartext app-wide": 0,
    "kotlin beside the sources": 0,
    "backup file in the tree": 0,
    # The debt as of 1.6.16w: mostly deliberate (a diagnostic must not throw),
    # but it may not grow.
    "swallowed exception": 421,
}


def strip_comments(text):
    """Blank out comments but keep every newline, so line numbers stay true."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif text[i] in "\"'":
            q = text[i]
            j = i + 1
            while j < n and text[j] != q:
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j])
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def java_files():
    for d, _, fs in os.walk(JAVA):
        for f in sorted(fs):
            if f.endswith(".java"):
                yield os.path.join(d, f)


def offenders():
    hits = {k: [] for k in BASELINE}
    for p in java_files():
        raw = io.open(p, encoding="utf-8", errors="replace").read()
        code = strip_comments(raw)
        rel = os.path.relpath(p, ROOT)
        for i, line in enumerate(code.split("\n"), 1):
            if "Toast.makeText(" in line:
                hits["toast"].append("%s:%d" % (rel, i))
            if re.search(r"\bandroid\.util\.Log\.[dweiv]\(", line):
                hits["bare log"].append("%s:%d" % (rel, i))
            if re.search(r'String\.format\(\s*"', line):
                hits["format without Locale"].append("%s:%d" % (rel, i))
            # A *duration*: clock minus clock. A deadline (now + ms) is the same
            # bug but cannot be told apart from string concatenation, so review
            # catches those; this rule catches every one that is unambiguous.
            # `UpdateCheck` is exempt on purpose: its timestamp is persisted, so
            # it has to be wall clock to survive a reboot.
            if re.search(r"currentTimeMillis\(\)\s*-", line) and "UpdateCheck.java" not in rel:
                hits["wall clock in an interval"].append("%s:%d" % (rel, i))
        hits["swallowed exception"].extend(
            ["%s" % rel] * len(re.findall(r"catch\s*\([A-Za-z0-9_.<> ]*\bignored\b\)", code)))
    man = io.open(MANIFEST, encoding="utf-8").read() if os.path.exists(MANIFEST) else ""
    if "usesCleartextTraffic" in man:
        hits["cleartext app-wide"].append("app/src/main/AndroidManifest.xml")
    for d, _, fs in os.walk(JAVA):
        for f in fs:
            if f.endswith(".kt") or ".bak" in f:
                key = "kotlin beside the sources" if f.endswith(".kt") else "backup file in the tree"
                hits[key].append(os.path.relpath(os.path.join(d, f), ROOT))
    return hits


def main():
    hits = offenders()
    verbose = "--list" in sys.argv
    bad = 0
    print("%-28s %6s %10s" % ("rule", "now", "baseline"))
    for k, base in BASELINE.items():
        n = len(hits[k])
        flag = "  <-- FAIL" if n > base else ""
        print("%-28s %6d %10d%s" % (k, n, base, flag))
        if n > base:
            bad += 1
            for h in hits[k][:12]:
                print("      " + h)
        elif verbose:
            for h in hits[k][:12]:
                print("      " + h)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
