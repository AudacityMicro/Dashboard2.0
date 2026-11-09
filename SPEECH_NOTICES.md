# Offline speech components

Dashboard bundles Vosk Android 0.3.75, Java Native Access 5.18.1 and the
`vosk-model-small-en-us-0.15` English model. Vosk and this model are Apache-2.0;
JNA is used under its Apache-2.0 option. License texts are bundled in the APK's
`speech/` assets (`VOSK-LICENSE.txt` and `JNA-LICENSE.txt`). Copyright and notice
information remain in those files and the original dependency distributions.

- Vosk source and license: https://github.com/alphacep/vosk-api
- Official Android dependency versions: https://github.com/alphacep/vosk-android-demo
- Model, size and license: https://alphacephei.com/vosk/models
- JNA license: https://github.com/java-native-access/jna/blob/5.18.1/LICENSE

At build time, `:data:dashboard:prepareSpeechModel` downloads this public archive:
`https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`.
SHA-256: `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
It verifies the checksum before adding the ZIP as a generated asset. Gradle caches
the result under `data/dashboard/build/`; no model binary, credential or downloaded
dependency belongs in Git. A clean build needs internet access to fetch dependencies
and the model. The resulting app needs no internet to transcribe. First use extracts
the model into private, backup-excluded storage. Budget about 300 MB working memory
for recognition and additional disk space for the model. Bundling all native ABIs
and the English model makes the debug APK about 120 MiB.

The instrumentation fixture `app/app/src/androidTest/assets/vosk-test.wav` is the
Vosk project's Apache-2.0 sample from `python/example/test.wav` at
https://github.com/alphacep/vosk-api. SHA-256:
`dcfea5712c43a43ba7ae8083afb39d36993e5a69c46e88b68aaa72b65cb615bb`.
It contains spoken digits and is used to check the actual bundled engine and Android
audio decoder, rather than a mocked transcription result.
