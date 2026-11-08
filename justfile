# Build the watch .prg with the UES API key baked in, for sideloading (needs Connect IQ SDK + device files; see watch/README.md)
watch-prg *devices:
    fnox exec -- watch/build.sh {{devices}}

# Build the UES Note voice-note watch app (watch-note/) with the API key baked in, for sideloading
note-prg *devices:
    APP_DIR=watch-note fnox exec -- watch/build.sh {{devices}}
