import subprocess
import sys
from contextlib import closing

from boto3 import Session
from botocore.exceptions import BotoCoreError, ClientError

# Section of the AWS credentials file (~/.aws/credentials).
session = Session(profile_name="default")
polly = session.client("polly")


# https://docs.aws.amazon.com/polly/latest/dg/voicelist.html
def translate_polly(text, path):
    for name in [
        "Olivia",
        "Amy",
        "Emma",
        # "Brian",  # very slow speaker
        "Arthur",
        "Kajal",
        "Aria",
        "Ayanda",
        # "Salli", # bad quality
        # "Kimberly",
        # "Kendra",
        # "Joanna", # low quality
        # "Ivy", # child
        "Ruth",
        # "Kevin", # child
        "Matthew",
        # "Justin", # teen
        # "Joey", # low quality
        "Stephen",
    ]:
        try:
            # Request speech synthesis
            response = polly.synthesize_speech(
                Engine="neural",
                Text=text,
                OutputFormat="pcm",
                VoiceId=name,
            )
        except (BotoCoreError, ClientError) as error:
            # The service returned an error, exit gracefully
            print(error)
            sys.exit(-1)

        # Access the audio stream from the response
        if "AudioStream" in response:
            # Note: Closing the stream is important because the service throttles on the
            # number of parallel connections. Here we are using contextlib.closing to
            # ensure the close method of the stream object will be called automatically
            # at the end of the with statement's scope.
            with closing(response["AudioStream"]) as stream:
                try:
                    # Open a file for writing the output as a binary stream
                    with open("temp.pcm", "wb") as file:
                        file.write(stream.read())
                    subprocess.check_call(
                        f'ffmpeg -f s16le -ar 16000 -ac 1 -i "temp.pcm" "{path}/{name}.wav"',
                        shell=True,
                        stderr=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                    )
                except IOError as error:
                    # Could not write to file, exit gracefully
                    print(error)
                    sys.exit(-1)

        else:
            # The response didn't contain audio data, exit gracefully
            print("Could not stream audio")
            sys.exit(-1)
