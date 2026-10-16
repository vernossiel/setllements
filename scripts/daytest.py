"""
A working day in a real village, on a server nobody plays on: the test the build runs.

The world seed is fixed (12345); its nearest village is at -144 -832. The script waits for the server's console
(RCON), keeps the village loaded, starts the day and looks at the village every 20 seconds for a stretch of the day,
with /settlements dump and status. It fails when the village did nothing: the builder never got a project going
or laid nothing, nobody brought wood to the stores, or the workers spent most of the working time standing about.
"""
import re
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "test"
AT = "-144 70 -832"
LOOKS = int(sys.argv[1]) if len(sys.argv) > 1 else 24  # every 20 s: 24 looks = 8 minutes, two fifths of a day


def connect(timeout=900):
    end = time.time() + timeout
    while time.time() < end:
        try:
            s = socket.create_connection((HOST, PORT), timeout=60)
            s.sendall(packet(1, 3, PASSWORD))
            read(s)
            return s
        except OSError:
            time.sleep(5)
    sys.exit("the server never opened its console")


def packet(i, t, body):
    b = body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<iii", len(b) + 8, i, t) + b


def read(s):
    n = struct.unpack("<i", s.recv(4))[0]
    d = b""
    while len(d) < n:
        d += s.recv(n - len(d))
    return d[8:-2].decode("utf-8", "replace")


def run(s, cmd):
    s.sendall(packet(2, 2, cmd))
    return read(s)


def main():
    s = connect()
    run(s, "forceload add -208 -896 -80 -768")
    run(s, "setworldspawn " + AT)
    run(s, "time set 1000")
    time.sleep(30)
    idle = busy = 0
    planks = []
    steps = []
    projects = set()
    for i in range(LOOKS):
        dump = run(s, f"execute positioned {AT} run settlements dump")
        status = run(s, f"execute positioned {AT} run settlements status")
        print(f"--- look {i + 1}\n{dump}\n{status}", flush=True)
        for line in dump.split("[Settlements]"):
            m = re.search(r"\[(cartographer|farmer|fletcher|toolsmith|mason)\].*stage=(\w+) :: (.*)", line)
            if not m or m.group(2) != "custom":
                continue  # only the working hours at the trade count
            if "not doing anything" in m.group(3):
                idle += 1
            else:
                busy += 1
        p = re.search(r"planks\+logs\*4=(\d+)", status)
        if p:
            planks.append(int(p.group(1)))
        pr = re.search(r"Project: (\S+) at [^,]+, [^,]+, [^,]+, step (\d+)", status)
        if pr:
            projects.add(pr.group(1))
            steps.append(int(pr.group(2)))
        time.sleep(20)
    run(s, "stop")
    print(f"\nworking looks: {busy} busy, {idle} idle; projects: {sorted(projects)}; steps seen: {steps[-5:]}; wood: {planks[:1]} -> {planks[-1:]}")
    failures = []
    if not projects:
        failures.append("the builder never started anything")
    elif max(steps or [0]) == 0 and len(projects) < 2:
        failures.append("the builder started but laid nothing")
    if busy + idle > 0 and idle > (busy + idle) * 0.35:
        failures.append(f"the workers stood about {idle} of {busy + idle} looks")
    rises = sum(1 for a, b in zip(planks, planks[1:]) if b > a)
    if rises == 0:
        failures.append("nobody brought wood to the stores")
    if failures:
        sys.exit("DAY TEST FAILED: " + "; ".join(failures))
    print("day test passed")


if __name__ == "__main__":
    main()
