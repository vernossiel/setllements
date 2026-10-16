"""
Several days in the life of a village, on a server nobody plays on: does it live by itself and grow?

Every minute the village is looked at (/settlements dump and status): people and their trades, what is built, the
stores (food, wool, wood, stone), hunger, who stands about. At the end a day-by-day summary, and the troubles found:
a village going hungry, a day with nothing built, workers standing about, nobody growing in number.
Usage: python3 scripts/growthtest.py <days> (the server must be running with RCON on 25575, password "test").
"""
import collections
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import daytest  # the console connection

AT = "-144 70 -832"
FOOD = {"bread", "baked_potato", "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_rabbit",
        "cooked_cod", "cooked_salmon", "carrot", "potato", "apple", "sweet_berries", "beetroot", "mushroom_stew",
        "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon", "melon_slice", "pumpkin_pie", "cookie"}


def stores(status):
    m = re.search(r"Stores: \{(.*)\}", status)
    out = {}
    if m:
        for part in m.group(1).split(", "):
            if "=" in part:
                k, v = part.split("=", 1)
                try:
                    out[k] = int(v)
                except ValueError:
                    pass
    return out


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    s = daytest.connect()
    daytest.run(s, "forceload add -240 -928 -48 -736")
    daytest.run(s, "setworldspawn " + AT)
    daytest.run(s, "time set 0")
    rows = []
    start = time.time()
    while True:
        dump = daytest.run(s, f"execute positioned {AT} run settlements dump")
        status = daytest.run(s, f"execute positioned {AT} run settlements status")
        tm = re.search(r"time (\d+) day (\d+)", dump)
        if not tm:
            time.sleep(60)
            continue
        t, day = int(tm.group(1)), int(tm.group(2))
        people = re.findall(r"\] (\S+) \[([a-z_]+)\] .*? h=(\d+) .*?stage=(\w+) :: (.*)", dump)
        pop = re.search(r"HAMLET|VILLAGE|TOWN|CITY", status)
        res = re.search(r"(\d+)/(\d+) residents", status)
        built = re.search(r"Built: (.*)", status)
        project = re.search(r"Project: (.*)", status)
        st = stores(status)
        row = {
            "day": day, "t": t,
            "residents": int(res.group(1)) if res else 0,
            "beds": int(res.group(2)) if res else 0,
            "level": pop.group(0) if pop else "?",
            "trades": collections.Counter(p[1] for p in people),
            "hunger": sum(int(p[2]) for p in people if p[1] != "child") / max(1, sum(1 for p in people if p[1] != "child")),
            "idle": sum(1 for p in people if p[3] == "custom" and "not doing anything" in p[4]),
            "working": sum(1 for p in people if p[3] == "custom"),
            "food": sum(v for k, v in st.items() if k in FOOD),
            "wool": sum(v for k, v in st.items() if k.endswith("_wool")),
            "planks": int(re.search(r"planks\+logs\*4=(\d+)", status).group(1)) if "planks+logs" in status else 0,
            "cobble": int(re.search(r"cobble=(\d+)", status).group(1)) if "cobble=" in status else 0,
            "built": built.group(1) if built else "",
            "project": project.group(1) if project else "",
        }
        rows.append(row)
        print(f"day {day} t={t} res={row['residents']}/{row['beds']} {row['level']} hunger={row['hunger']:.0f} "
              f"working={row['working']} idle={row['idle']} food={row['food']} wool={row['wool']} planks={row['planks']} "
              f"cobble={row['cobble']} project={row['project']} trades={dict(row['trades'])}", flush=True)
        if day >= days:
            break
        time.sleep(60)
    print("\n=== by day")
    troubles = []
    by_day = collections.OrderedDict()
    for r in rows:
        by_day.setdefault(r["day"], []).append(r)
    projects_seen = set()
    for d, rs in by_day.items():
        last = rs[-1]
        hungry = sum(1 for r in rs if r["hunger"] >= 850)
        idle = sum(r["idle"] for r in rs)
        work = sum(r["working"] for r in rs)
        projs = {r["project"].split(" at ")[0] for r in rs if r["project"] and r["project"] != "none"}
        new = projs - projects_seen
        projects_seen |= projs
        print(f"day {d}: residents {last['residents']}/{last['beds']}, {last['level']}, food {last['food']}, wool {last['wool']}, "
              f"planks {last['planks']}, cobble {last['cobble']}, hungry looks {hungry}/{len(rs)}, idle {idle}/{work}, "
              f"projects {sorted(projs)}, built: {last['built']}")
        if hungry > len(rs) * 0.4:
            troubles.append(f"day {d}: the village went hungry")
        if work and idle > work * 0.3:
            troubles.append(f"day {d}: workers stood about ({idle}/{work})")
        if not new and d > min(by_day):
            troubles.append(f"day {d}: nothing new started")
    if rows and rows[-1]["residents"] <= rows[0]["residents"]:
        troubles.append(f"no growth: {rows[0]['residents']} -> {rows[-1]['residents']} residents")
    print("\n=== troubles\n" + ("\n".join(troubles) if troubles else "none"))
    print(f"\n({(time.time() - start) / 60:.0f} minutes)")


if __name__ == "__main__":
    main()
