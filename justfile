# Build the watch .prg with the UES API key baked in, for sideloading (needs Connect IQ SDK + device files; see watch/README.md)
watch-prg *devices:
    fnox exec -- watch/build.sh {{devices}}
