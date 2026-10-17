hello_world_16k.wav is a self-generated clip: the phrase "Hello world." synthesized with
eSpeak NG 1.51 (GPL-3.0-or-later; the synthesized audio is not a derivative work of the program)
and converted with ffmpeg to 16 kHz mono 16-bit PCM:

    espeak-ng -v en-us -s 140 -p 45 "Hello world." -w raw.wav
    ffmpeg -i raw.wav -ar 16000 -ac 1 -c:a pcm_s16le -af "apad=pad_dur=0.3,areverse,apad=pad_dur=0.3,areverse" hello_world_16k.wav

Verified to transcribe as "Hello world!" with the BASE model (sherpa-onnx 1.13.8, Python bindings).
