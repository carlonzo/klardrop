// sqlite3_macos_stubs.c
//
// macOS system libsqlite3 does not export sqlite3_enable_load_extension or
// sqlite3_load_extension (they are compiled out of the system library on
// macOS for security reasons). The SQLDelight/SQLiter cinterop bundled inside
// the :presentation static framework generates wrapper symbols that call
// these functions, so we provide no-op stubs so the linker resolves them.
//
// These stubs are compiled only into the KlardropMac target (this file is not
// in the iosApp target membership). Extension loading is simply unavailable
// on this platform, which is acceptable — the app uses SQLite for persistence
// only and never loads dynamic SQLite extensions.

#include <stddef.h>

// Opaque sqlite3 handle (forward-declared; matches sqlite3.h layout)
typedef struct sqlite3 sqlite3;

// Return value matching SQLITE_ERROR (= 1)
#define SQLITE_ERROR 1

int sqlite3_enable_load_extension(sqlite3 *db, int onoff) {
    (void)db;
    (void)onoff;
    return SQLITE_ERROR;
}

int sqlite3_load_extension(
    sqlite3 *db,
    const char *zFile,
    const char *zProc,
    char **pzErrMsg
) {
    (void)db;
    (void)zFile;
    (void)zProc;
    if (pzErrMsg) {
        *pzErrMsg = NULL;
    }
    return SQLITE_ERROR;
}
