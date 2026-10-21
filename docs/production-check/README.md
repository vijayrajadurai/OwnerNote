# Production check — probe files

`probes/*.kt.txt` are the Kai probes behind `../PRODUCTION_CHECK.md` section 7 (words never fed to Kai, typed and mic).
They are kept as `.txt` so they don't run in CI (some lines fail on purpose — they are the findings K1–K6).
To run one: copy it to `app/src/test/java/com/shopai/app/brain/chat/` as `.kt` and run that class; it writes its result file
(`-Dprobe.out=...`). `kai-*-results.txt` are the results of 20 Oct 2026.
