import glob
import json
import os.path
import pathlib
import re
import shutil
import subprocess

import tqdm
from tqdm.contrib.concurrent import process_map

import googole
import polly


def to_text(path, text):
    if not os.path.exists(path):
        os.makedirs(path)
        polly.translate_polly(text, path)
        googole.translate_googogle(text, path)


def wavegain(original):
    files = glob.glob(f"working/*/{original}.wav")
    subprocess.check_call(
        "wavegain -a -y --force " + " ".join(files),
        shell=True,
        stderr=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
    )


def convert(args):
    file, out_file = args
    pathlib.Path(out_file).parent.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(
        f"ffmpeg -i {file} -y -c:a libvorbis -b:a 16k '{out_file}'",
        shell=True,
        stderr=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
    )


def main():
    with open(
        "../../common/src/main/resources/assets/mca_dialogue/lang/en_us.json"
    ) as f:
        translations = json.load(f)
        total = 0

        # remove variables and *sounds*
        processed = {}
        for key, text in translations.items():
            p = text
            p = re.sub("\*.*\*", "", p)
            p = p.replace("%supporter%", "someone")
            p = p.replace("%Supporter%", "someone")
            p = p.replace("some %2$s", "something")
            p = p.replace("at %2$s", "somewhere here")
            p = p.replace("At %2$s", "Somewhere here")
            p = p.replace(" to %2$s", " to here")
            p = p.replace(", %1$s.", ".")
            p = p.replace(", %1$s!", "!")
            p = p.replace(" %1$s!", "!")
            p = p.replace(", %1$s.", ".")
            p = p.replace("%1$s!", " ")
            p = p.replace("%1$s, ", " ")
            p = p.replace("%1$s", " ")
            p = p.replace("avoid %2$s", "avoid that location")
            p = p.replace(" Should be around %2$s.", "")
            p = p.replace("  ", " ")
            p = p.replace(" ,", ",")
            p = p.replace("Bahaha! ", "")
            p = p.replace("Run awaaaaaay! ", "Run!")
            p = p.replace("Aaaaaaaahhh! ", "")
            p = p.replace("Aaaaaaahhh! ", "")
            p = p.replace("Aaaaaaaaaaahhh! ", "")
            p = p.replace("AAAAAAAAAAAAAAAAAAAHHHHHH!!!!!! ", "")
            p = p.strip()

            if p.startswith("!"):
                p = p[1:]
            if p.startswith("?"):
                p = p[1:]

            p = p.strip()

            if "%" in p:
                print("Failed: " + p)

            processed[key] = p
            total += len(p)

        # convert everything to text
        with tqdm.tqdm(total=total, desc="TTS") as t:
            for key, text in processed.items():
                escaped_key = key.replace("/", "_slash_")
                check_file_path = "output/" + escaped_key + ".text"

                try:
                    if len(text) == 0:
                        shutil.rmtree("output/" + escaped_key, ignore_errors=True)
                        os.remove(check_file_path)
                    with open(check_file_path, "r") as text_file:
                        if text_file.read() != text:
                            shutil.rmtree("output/" + escaped_key, ignore_errors=True)
                            os.remove(check_file_path)
                except FileNotFoundError:
                    pass

                if len(text) > 0:
                    t.update(len(text))
                    with open(check_file_path, "w") as text_file:
                        text_file.write(text)

                    to_text("output/" + escaped_key, text)

        # copy all files to keep originals clean
        shutil.rmtree("working", ignore_errors=True)
        shutil.copytree("output", "working")

        # names and gender
        name = {
            "Olivia": "female_0",
            "Amy": "female_1",
            "Emma": "female_2",
            "Arthur": "male_0",
            "Kajal": "female_3",
            "Aria": "female_4",
            "Ayanda": "female_5",
            "Ruth": "female_6",
            "Matthew": "male_1",
            "Stephen": "male_2",
            "en-AU-Neural2-B": "male_3",
            "en-AU-Neural2-D": "male_4",
            "en-GB-Neural2-B": "male_5",
            "en-GB-Neural2-F": "female_7",
            "en-US-Neural2-D": "male_6",
            "en-US-Neural2-H": "female_8",
            "en-US-Neural2-I": "male_7",
            "en-US-Neural2-J": "male_8",
            "en-US-Studio-M": "male_9",
            "en-US-Studio-O": "female_9",
        }

        # copy all files to keep originals clean
        process_map(
            wavegain, [original for (original, to) in name.items()], max_workers=10
        )

        # convert to ogg
        conversions = []
        all_files = {}
        for original, to in tqdm.tqdm(name.items(), desc="Build library"):
            files = glob.glob(f"working/*/{original}.wav")
            for file in files:
                phrase = file[len("working/") : -len(original) - 5]

                # insert sound into library
                real_phrase = phrase.replace("_slash_", "/")
                all_files[real_phrase.lower() + "/" + to] = {
                    "subtitle": real_phrase,
                    "stream": True,
                    "sounds": [f"mca_voices:{phrase.lower()}/{to}"],
                }

                out_file = f"../../common/src/main/resources/assets/mca_voices/sounds/{phrase.lower()}/{to}.ogg"
                conversions.append((file, out_file))

        # convert
        process_map(convert, conversions, max_workers=12, chunksize=16)

        # write sound library
        with open(
            "../../common/src/main/resources/assets/mca_voices/sounds.json", "w"
        ) as dict_file:
            dict_file.write(json.dumps(all_files))


if __name__ == "__main__":
    main()
