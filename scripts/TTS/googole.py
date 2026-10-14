from dotenv import load_dotenv
from google.cloud import texttospeech

load_dotenv()

# Instantiates a client
client = texttospeech.TextToSpeechClient()

whitelist = [
    "en-AU-Neural2-B",
    "en-AU-Neural2-D",
    "en-GB-Neural2-B",
    "en-GB-Neural2-F",
    "en-US-Neural2-D",
    "en-US-Neural2-H",
    "en-US-Neural2-I",
    "en-US-Neural2-J",
    "en-US-Studio-M",
    "en-US-Studio-O",
]

languages = [lang for lang in client.list_voices().voices if lang.name in whitelist]


def translate_googogle(text, path):
    # Set the text input to be synthesized
    synthesis_input = texttospeech.SynthesisInput(text=text)

    for lang in languages:
        # Build the voice request, select the language code ("en-US") and the ssml
        # voice gender ("neutral")
        voice = texttospeech.VoiceSelectionParams(
            {"name": lang.name, "language_code": lang.language_codes[0]}
        )

        # Select the type of audio file you want returned
        audio_config = texttospeech.AudioConfig(
            {"audio_encoding": texttospeech.AudioEncoding.LINEAR16}
        )

        # Perform the text-to-speech request on the text input with the selected
        # voice parameters and audio file type
        response = client.synthesize_speech(
            input=synthesis_input, voice=voice, audio_config=audio_config
        )

        # The response's audio_content is binary.
        with open(f"{path}/{lang.name}.wav", "wb") as out:
            # Write the response to the output file.
            out.write(response.audio_content)
