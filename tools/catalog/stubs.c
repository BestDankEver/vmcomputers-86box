/*
 * Minimal platform functions for 86box-catalog.
 *
 * The catalog tool links 86Box's emulator objects without a front end, so the
 * plat_* layer (normally provided by the Qt or SDL UI) is missing. Only the
 * functions reached while checking ROM availability are implemented here;
 * everything else is left unresolved and is never called.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

FILE *
plat_fopen(const char *path, const char *mode)
{
    return fopen(path, mode);
}

FILE *
plat_fopen64(const char *path, const char *mode)
{
    return fopen(path, mode);
}

int
plat_file_check(const char *path)
{
    struct stat st;
    return (stat(path, &st) == 0) && S_ISREG(st.st_mode);
}

int
plat_dir_check(char *path)
{
    struct stat st;
    return (stat(path, &st) == 0) && S_ISDIR(st.st_mode);
}

void
plat_getcwd(char *bufp, int max)
{
    if (!getcwd(bufp, max))
        bufp[0] = '\0';
}

int
plat_path_abs(char *path)
{
    return path[0] == '/';
}

void
path_slash(char *path)
{
    size_t len = strlen(path);
    if (len && (path[len - 1] != '/')) {
        path[len]     = '/';
        path[len + 1] = '\0';
    }
}

void
path_append_filename(char *dest, const char *s1, const char *s2)
{
    strcpy(dest, s1);
    path_slash(dest);
    strcat(dest, s2);
}

/* pclog and fatal come from 86box.c. */
