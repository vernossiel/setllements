import json
import os
import urllib.parse
import urllib.request
from tqdm.contrib.concurrent import thread_map


def translate(s):
    r = urllib.request.urlopen(
        "https://pirate.monkeyness.com/api/translate?english=" + urllib.parse.quote(s)
    )
    return urllib.parse.unquote(r.read().decode("utf-8"))


def load_json(path):
    if not os.path.exists(path):
        return {}
    file = open(path, "r")
    d = file.read()
    file.close()
    return json.loads(d)


def save_json(path, d):
    file = open(path, "w")
    file.write(json.dumps(d, indent=4))
    file.close()


def translate_all(path):
    phrases = load_json(path + "/lang/en_us.json")

    translated_phrases = {}
    translated = thread_map(translate, phrases.values())
    for i, key in enumerate(phrases):
        translated_phrases[key] = translated[i]

    os.makedirs("translated", exist_ok=True)
    save_json("translated/" + os.path.basename(path) + ".json", translated_phrases)


if __name__ == "__main__":
    assets_dir = "../common/src/main/resources/assets/"
    translate_all(assets_dir + "mca_books")
    translate_all(assets_dir + "mca_dialogue")
    translate_all(assets_dir + "mca")
