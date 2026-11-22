# Build the UES watch app (BB + voice notes) .prg with the API key baked in, for sideloading (needs Connect IQ SDK + device files; see watch/README.md)
watch-prg *devices:
    fnox exec -- watch/build.sh {{devices}}
