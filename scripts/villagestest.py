"""
Several villages of one world side by side, in different lands: which of them live and grow, and which go under.

The vanilla villages nearest to the world's origin, one of each kind (plains, desert, savanna, taiga, snowy), are found
with /locate, kept loaded, and each gets a stand-in player (crops grow only near a player). Every few seconds each
village is looked at (/settlements villages, status and dump at its center); deaths are read from the server log.
At the end a table by village: residents at the start and at the end, the most there ever were, deaths and their
causes, food per head, hunger, idle workers, what was built, and the troubles.
Usage: python3 scripts/villagestest.py <days> [seconds between looks, default 5] [kinds, default all, e.g. plains,taiga]
Run with /settlements warp on, the server up with RCON on 25575, password "test"; the log read is forge/run/logs/latest.log.
"""
import collections
import os
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import daytest  # the console connection

KINDS = ["plains", "desert", "savanna", "taiga", "snowy"]
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "forge", "run", "logs", "latest.log")
FOOD = {"bread", "baked_potato", "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_rabbit",
        "cooked_cod", "cooked_salmon", "carrot", "potato", "apple", "sweet_berries", "beetroot", "mushroom_stew",
        "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon", "melon_slice", "pumpkin_pie", "cookie"}


def locate(s, kind):
    out = daytest.run(s, f"execute positioned 0 80 0 run locate structure minecraft:village_{kind}")
    m = re.search(r"\[(-?\d+), [^,]+, (-?\d+)\]", out)
    return (int(m.group(1)), int(m.group(2))) if m else None


def stores(status):
    m = re.search(r"Stores: \{(.*?)\}", status)
    out = {}
    if m:
        for part in m.group(1).split(", "):
            k, _, v = part.partition("=")
            if v.isdigit():
                out[k] = int(v)
    return out


def look(s, x, z):
    at = f"execute positioned {x} 0 {z} positioned over world_surface run settlements"
    status = daytest.run(s, at + " status")
    dump = daytest.run(s, at + " dump")
    people = re.findall(r"\] (\S+) \[([a-z_]+)\] .*? h=(\d+) .*?stage=(\w+) :: (.*)", dump)
    res = re.search(r"(\d+)/(\d+) residents", status)
    level = re.search(r"HAMLET|VILLAGE|TOWN|CITY", status)
    built = re.search(r"Built: (.*)", status)
    project = re.search(r"Project: (.*)", status)
    st = stores(status)
    grown = [p for p in people if p[1] != "child"]
    doing = collections.Counter(" ".join(re.sub(r"[^a-z ]", " ", p[4].lower()).split()[:3]) for p in grown)  # the first words of what each is doing
    return {
        "doing": doing, "dump": dump,
        "residents": int(res.group(1)) if res else 0,
        "beds": int(res.group(2)) if res else 0,
        "level": level.group(0) if level else "-",
        "children": sum(1 for p in people if p[1] == "child"),
        "trades": collections.Counter(p[1] for p in people),
        "hunger": sum(int(p[2]) for p in grown) / max(1, len(grown)),
        "working": sum(1 for p in people if p[3] == "custom"),
        "idle": sum(1 for p in people if p[3] == "custom" and "not doing anything" in p[4]),
        "food": sum(v for k, v in st.items() if k in FOOD),
        "planks": int(re.search(r"planks\+logs\*4=(\d+)", status).group(1)) if "planks+logs" in status else 0,
        "cobble": int(re.search(r"cobble=(\d+)", status).group(1)) if "cobble=" in status else 0,
        "built": built.group(1) if built else "",
        "project": (project.group(1) if project else "none").split(" at ")[0],
    }


def deaths(text, places):
    """Deaths in the server log, each given to the nearest village under test."""
    out = collections.defaultdict(list)
    for m in re.finditer(r"\[Settlements\] (\S+) died at (-?\d+), (-?\d+), (-?\d+) \(([^)]*)\): (.*)", text):
        x, z = int(m.group(2)), int(m.group(4))
        near = min(places, key=lambda k: (places[k][0] - x) ** 2 + (places[k][1] - z) ** 2)
        out[near].append(f"{m.group(1)} ({m.group(6).strip()}; {m.group(5)})")
    nights = collections.Counter()
    for m in re.finditer(r"\[Settlements\] \S+ not in bed after \d+s at (-?\d+), (-?\d+), (-?\d+)", text):
        x, z = int(m.group(1)), int(m.group(3))
        nights[min(places, key=lambda k: (places[k][0] - x) ** 2 + (places[k][1] - z) ** 2)] += 1
    return out, nights


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    every = float(sys.argv[2]) if len(sys.argv) > 2 else 5
    kinds = sys.argv[3].split(",") if len(sys.argv) > 3 else KINDS
    s = daytest.connect()
    log_start = os.path.getsize(LOG) if os.path.exists(LOG) else 0
    places = {}
    for k in kinds:
        p = locate(s, k)
        if p:
            places[k] = p
            print(f"{k}: village at {p[0]} {p[1]}", flush=True)
        else:
            print(f"{k}: none found", flush=True)
    for k, (x, z) in places.items():
        daytest.run(s, f"forceload add {x - 96} {z - 96} {x + 96} {z + 96}")
    time.sleep(20)  # the chunks load, MCA finds the bells and beds
    for k, (x, z) in places.items():
        print(daytest.run(s, f"execute positioned {x} 0 {z} positioned over world_surface run settlements fake add T_{k}").strip(), flush=True)
        daytest.run(s, f"gamemode creative T_{k}")
    daytest.run(s, "time set 0")
    rows = collections.defaultdict(list)
    os.makedirs("villages", exist_ok=True)
    journals = {k: open(os.path.join("villages", f"{k}.log"), "w", encoding="utf-8") for k in places}
    start = time.time()
    while True:
        tm = re.search(r"day (\d+)", daytest.run(s, "settlements dump"))
        day = int(tm.group(1)) if tm else 0
        for k, (x, z) in places.items():
            r = look(s, x, z)
            r["day"] = day
            rows[k].append(r)
            # everyone, what he is doing and why: the journal to read afterwards
            journals[k].write(f"=== day {day}\n" + r.pop("dump") + "\n")
            journals[k].flush()
        line = " | ".join(f"{k} {rows[k][-1]['residents']}/{rows[k][-1]['beds']} f{rows[k][-1]['food']} h{rows[k][-1]['hunger']:.0f} "
                          f"{rows[k][-1]['project']}" for k in places)
        print(f"day {day}: {line}", flush=True)
        if day >= days:
            break
        time.sleep(every)
    print("\n=== villages side by side")
    print(daytest.run(s, "settlements villages"))
    text = ""
    if os.path.exists(LOG):
        with open(LOG, encoding="utf-8", errors="replace") as f:
            f.seek(log_start)
            text = f.read()
    died, nights = deaths(text, places)
    print("\n=== by village")
    for k, rs in rows.items():
        first, last = rs[0], rs[-1]
        projects = sorted({r["project"] for r in rs if r["project"] != "none"})
        hungry = sum(1 for r in rs if r["hunger"] >= 850)
        idle, work = sum(r["idle"] for r in rs), sum(r["working"] for r in rs)
        food_head = last["food"] / max(1, last["residents"])
        print(f"\n{k} @{places[k][0]} {places[k][1]}: residents {first['residents']} -> {last['residents']} (most {max(r['residents'] for r in rs)}), "
              f"beds {last['beds']}, {last['level']}, children {last['children']}")
        print(f"  food {first['food']} -> {last['food']} ({food_head:.1f}/head), hungry looks {hungry}/{len(rs)}, idle {idle}/{work}, "
              f"planks {last['planks']}, cobble {last['cobble']}")
        print(f"  trades {dict(last['trades'])}")
        doing = collections.Counter()
        for r in rs:
            doing.update(r["doing"])
        total = max(1, sum(doing.values()))
        print("  time spent: " + ", ".join(f"{d} {100 * n // total}%" for d, n in doing.most_common(8)))
        print(f"  projects {projects}")
        print(f"  built: {last['built']}")
        print(f"  deaths {len(died[k])}, nights out of bed {nights[k]}")
        for d in died[k]:
            print(f"    {d}")
        troubles = []
        if last["residents"] < first["residents"]:
            troubles.append("shrank")
        elif last["residents"] == first["residents"]:
            troubles.append("did not grow")
        if hungry > len(rs) * 0.4:
            troubles.append("went hungry")
        if work and idle > work * 0.3:
            troubles.append(f"workers stood about ({idle}/{work})")
        if not projects:
            troubles.append("built nothing")
        if len(died[k]) >= 2:
            troubles.append(f"{len(died[k])} dead")
        print(f"  troubles: {', '.join(troubles) if troubles else 'none'}")
    print(f"\n({(time.time() - start) / 60:.0f} minutes)")


if __name__ == "__main__":
    main()
