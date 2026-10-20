#!/usr/bin/env bash
# Builds 86box-catalog against an already-built 86Box tree (Linux).
#
#   tools/catalog/build.sh <86Box source dir> <86Box build dir> <output binary>
#
# The 86Box build must have been configured with the same options as the
# release build (at least -D AUTOMATION=ON) so the device tables match.
set -euo pipefail

SRC=$(realpath "$1")
BUILD=$(realpath "$2")
OUT=$3
HERE=$(dirname "$(realpath "$0")")
TMP=$(mktemp -d)

CFLAGS="-O1 -Wall -I$BUILD/src/include -I$SRC/src/include -I$SRC/src/cpu -I$SRC/src/codegen \
        -DCMAKE -DUSE_AUTOMATION -DUSE_DYNAREC -D_FILE_OFFSET_BITS=64"

gcc $CFLAGS -c "$HERE/catalog.c" -o "$TMP/catalog.o"
gcc -O1 -c "$HERE/stubs.c" -o "$TMP/stubs.o"

# Every emulator object, without the Qt/SDL front ends (which provide main()).
find "$BUILD/src" -name '*.o' | grep -v -e '/qt/' -e '/unix/' -e 'ui_autogen' > "$TMP/objs.txt"
LIBS=$(find "$BUILD/src" -name '*.a' | grep -v -e 'libui.a' -e 'libplat.a' | tr '\n' ' ')

# Front-end functions stay unresolved: the catalog never reaches them.
g++ -o "$OUT" "$TMP/catalog.o" "$TMP/stubs.o" @"$TMP/objs.txt" $LIBS \
    -Wl,--unresolved-symbols=ignore-all \
    $(pkg-config --libs libpng zlib freetype2 openal slirp rtmidi sndfile libserialport libzstd sdl2 2>/dev/null) \
    -lpthread -lm -ldl

rm -rf "$TMP"
echo "built $OUT"
