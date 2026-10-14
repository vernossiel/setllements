import json
import os

from countries import COUNTRIES
from unicodes import UNICODES

genders = {
    "M": ["male"],
    "1M": ["male"],
    "?M": ["male"],
    "F": ["female"],
    "1F": ["female"],
    "?F": ["female"],
    "?": ["male", "female"],
}

export = "../../common/src/main/resources/data/mca/names/"


def main():
    names = {}
    for country in COUNTRIES:
        names[country] = {
            "male": {},
            "female": {},
        }

    with open("raw_names.txt", "r") as f:
        for line in f.readlines():
            if len(line) > 0 and not line.startswith("#"):
                gender = line[0:3].strip()
                name = line[3:29].strip().replace("+", "-")
                countries = line[30 : 30 + 55]

                for c, u in UNICODES.items():
                    name = name.replace(c, chr(u))

                if gender in genders:
                    for i in range(len(COUNTRIES)):
                        if countries[i] != " ":
                            for g in genders[gender]:
                                names[COUNTRIES[i]][g][name] = (
                                    int("0x" + countries[i], 16) ** 2 * 100
                                )

    total = 0
    for country in COUNTRIES:
        total += len(names[country]["female"])
        total += len(names[country]["male"])
        print(country, len(names[country]["female"]), len(names[country]["male"]))

        c = country.lower().replace(" ", "")

        os.makedirs(export + c, exist_ok=True)
        with open(export + c + "/female.json", "w") as f:
            json.dump(names[country]["female"], f, indent=4)

        os.makedirs(export + c, exist_ok=True)
        with open(export + c + "/male.json", "w") as f:
            json.dump(names[country]["male"], f, indent=4)
    print(total)


if __name__ == "__main__":
    main()
