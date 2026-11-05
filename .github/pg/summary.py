import sys, os, xml.etree.ElementTree as ET

# usage: summary.py <classes file> <reports dir>; fails if any listed class failed or left no report
classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
reports = sys.argv[2]
bad = False
for c in classes:
    name = "peergos.server.tests." + c.replace("/", ".")
    path = os.path.join(reports, "TEST-" + name + ".xml")
    if not os.path.exists(path):
        print(f"{c}: NO REPORT (crashed or timed out)")
        bad = True
        continue
    suite = ET.parse(path).getroot()
    tests, failures, errors, skipped = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    print(f"{c}: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped, {float(suite.get('time', 0)):.0f} s")
    for case in suite.iter("testcase"):
        for kind in ("failure", "error"):
            for f in case.iter(kind):
                bad = True
                message = (f.get("message") or f.text or "").strip().splitlines()
                print(f"    {kind}: {case.get('name')}: {message[0][:300] if message else ''}")
sys.exit(1 if bad else 0)
