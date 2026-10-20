/*
 * 86box-catalog: dumps the hardware 86Box can emulate as JSON, for the
 * VM Computers 86Box mod's in-game parts shop.
 *
 * It is linked against the object files of an 86Box build (everything except
 * the Qt/SDL front end), so every list and every compatibility answer comes
 * from 86Box's own code: machine_available(), cpu_is_eligible(),
 * device_is_valid(), the *_available() functions and so on. Nothing here
 * re-implements 86Box's rules, which is what lets the mod follow new 86Box
 * builds without being updated.
 *
 * Usage: 86box-catalog <rom directory> <output.json> [86Box git hash]
 */
#include <inttypes.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <86box/86box.h>
#include <86box/timer.h>
#include <86box/plat.h>
#include <86box/thread.h>
#include <86box/device.h>
#include <86box/machine.h>
#include "cpu.h"
#include <86box/video.h>
#include <86box/sound.h>
#include <86box/network.h>
#include <86box/scsi.h>
#include <86box/hdc.h>
#include <86box/fdd.h>
#include <86box/fdc.h>
#include <86box/fdc_ext.h>
#include <86box/cdrom.h>
#include <86box/mem.h>
#include <86box/rom.h>
#include <86box/version.h>

static FILE *out;

static void
jstr(const char *s)
{
    fputc('"', out);
    for (const unsigned char *p = (const unsigned char *) (s ? s : ""); *p; p++) {
        switch (*p) {
            case '"':
                fputs("\\\"", out);
                break;
            case '\\':
                fputs("\\\\", out);
                break;
            case '\n':
                fputs("\\n", out);
                break;
            case '\r':
                fputs("\\r", out);
                break;
            case '\t':
                fputs("\\t", out);
                break;
            default:
                if (*p < 0x20)
                    fprintf(out, "\\u%04x", *p);
                else
                    fputc(*p, out);
        }
    }
    fputc('"', out);
}

/* Indices (into machines[]) of the machines that can run with this ROM set,
   in output order. Everything else in the catalog refers to machines by their
   position in this list. */
static int *avail;
static int  navail;

static void
machine_bitset(const device_t *dev)
{
    /* Hex string, bit i set = valid on catalog machine i (bit 0 = MSB of char 0). */
    char *hex = calloc((size_t) (navail / 4) + 2, 1);
    for (int i = 0; i < navail; i += 4) {
        int nib = 0;
        for (int b = 0; b < 4; b++) {
            nib <<= 1;
            if (((i + b) < navail) && device_is_valid(dev, avail[i + b]))
                nib |= 1;
        }
        hex[i / 4] = "0123456789abcdef"[nib];
    }
    jstr(hex);
    free(hex);
}

static void
device_display_name(const device_t *dev, char *buf)
{
    /* Same as the Settings dialog: name with the bus in front, e.g. "[ISA16] Sound Blaster 16". */
    char        temp[512];
    const char *bus;

    device_get_name(dev, -1, temp);
    bus = device_get_bus_name(dev);
    if (bus != NULL)
        snprintf(buf, 600, "[%s] %s", bus, temp);
    else
        snprintf(buf, 600, "%s", temp);
}

typedef const device_t *(*getdev_fn)(int);
typedef const char *(*getname_fn)(int);
typedef int (*avail_fn)(int);

static void
device_list(const char *key, getdev_fn getdev, getname_fn getname, avail_fn available, int *first)
{
    char name[600];
    int  n = 0;

    fprintf(out, "%s\n  ", *first ? "" : ",");
    *first = 0;
    jstr(key);
    fputs(": [", out);
    for (int c = 0;; c++) {
        const char     *in  = getname(c);
        const device_t *dev = getdev(c);
        if ((in == NULL) || (in[0] == '\0'))
            break;
        /* "none", "internal", "external" and other placeholders (no init function)
           are not parts you can buy. */
        if (!strcmp(in, "none") || !strcmp(in, "internal") || (dev == NULL) || (dev->init == NULL))
            continue;
        if ((available != NULL) && !available(c))
            continue;
        device_display_name(dev, name);
        fprintf(out, "%s\n    {\"id\": ", n ? "," : "");
        jstr(in);
        fputs(", \"name\": ", out);
        jstr(name);
        fprintf(out, ", \"flags\": %" PRIu32 ", \"machines\": ", (uint32_t) dev->flags);
        machine_bitset(dev);
        fputs("}", out);
        n++;
    }
    fputs("\n  ]", out);
    fprintf(stderr, "%s: %d\n", key, n);
}

/* hdc_get_device and friends are not all the same shape; small adapters. */
static const device_t *v_dev(int c) { return video_card_getdevice(c); }
static const char     *v_name(int c) { return video_get_internal_name(c); }
static int             v_avail(int c) { return video_card_available(c); }
static const device_t *s_dev(int c) { return sound_card_getdevice(c); }
static const char     *s_name(int c) { return sound_card_get_internal_name(c); }
static int             s_avail(int c) { return sound_card_available(c); }
static const device_t *n_dev(int c) { return network_card_getdevice(c); }
static const char     *n_name(int c) { return network_card_get_internal_name(c); }
static int             n_avail(int c) { return network_card_available(c); }
static const device_t *sc_dev(int c) { return scsi_card_getdevice(c); }
static const char     *sc_name(int c) { return scsi_card_get_internal_name(c); }
static int             sc_avail(int c) { return scsi_card_available(c); }
static const device_t *h_dev(int c) { return hdc_get_device(c); }
static const char     *h_name(int c) { return hdc_get_internal_name(c); }
static int             h_avail(int c) { return hdc_available(c); }
static const device_t *f_dev(int c) { return fdc_card_getdevice(c); }
static const char     *f_name(int c) { return fdc_card_get_internal_name(c); }
static int             f_avail(int c) { return fdc_card_available(c); }

static const char *
onboard(const device_t *dev)
{
    return (dev != NULL) ? dev->name : NULL;
}

int
main(int argc, char **argv)
{
    char name[600];

    if (argc < 3) {
        fprintf(stderr, "usage: %s <rom dir> <out.json> [git hash]\n", argv[0]);
        return 1;
    }

    rom_add_path(argv[1]);
    out = fopen(argv[2], "w");
    if (!out) {
        perror(argv[2]);
        return 1;
    }

    /* Machines that can actually start with this ROM set. */
    avail = calloc(machine_count() + 1, sizeof(int));
    for (int m = 0; m < machine_count(); m++) {
        if (machine_get_internal_name_ex(m) == NULL)
            continue;
        if (machine_available(m))
            avail[navail++] = m;
    }
    fprintf(stderr, "machines: %d of %d available\n", navail, machine_count());

    fputs("{\n  \"format\": 1,\n  \"emulator_version\": ", out);
    jstr(EMU_VERSION);
    fputs(",\n  \"emulator_git_hash\": ", out);
    jstr((argc > 3) ? argv[3] : "");
    fprintf(out, ",\n  \"generated\": %lld,\n", (long long) time(NULL));

    /* Machine type groups, e.g. "[Socket 7] ...". */
    fputs("  \"machine_types\": [", out);
    /* The table has one entry per type and no terminator. */
    for (int t = 0; t < MACHINE_TYPE_MAX; t++) {
        fprintf(out, "%s\n    {\"id\": %d, \"name\": ", t ? "," : "", (int) machine_types[t].id);
        jstr(machine_types[t].name);
        fputs("}", out);
    }
    fputs("\n  ],\n", out);

    /* CPU families. */
    fputs("  \"cpu_families\": [", out);
    int nfam = 0;
    for (int f = 0; cpu_families[f].package != 0; f++, nfam++) {
        const cpu_family_t *fam = &cpu_families[f];
        fprintf(out, "%s\n    {\"id\": ", f ? "," : "");
        jstr(fam->internal_name);
        fputs(", \"vendor\": ", out);
        jstr(fam->manufacturer);
        fputs(", \"name\": ", out);
        jstr(fam->name);
        fputs(", \"cpus\": [", out);
        for (int c = 0; fam->cpus[c].cpu_type != 0; c++) {
            fprintf(out, "%s{\"name\": ", c ? ", " : "");
            jstr(fam->cpus[c].name);
            fprintf(out, ", \"hz\": %" PRIu32 ", \"multi\": %.17g}", fam->cpus[c].rspeed, fam->cpus[c].multi);
        }
        fputs("]}", out);
    }
    fputs("\n  ],\n", out);
    fprintf(stderr, "cpu families: %d\n", nfam);

    /* Machines. */
    fputs("  \"machines\": [", out);
    for (int i = 0; i < navail; i++) {
        const int      m = avail[i];
        const machine_t *mt = &machines[m];
        fprintf(out, "%s\n    {\"id\": ", i ? "," : "");
        jstr(mt->internal_name);
        fputs(", \"name\": ", out);
        jstr(mt->name);
        fprintf(out, ", \"type\": %d, \"bus\": %" PRIu64 ", \"flags\": \"%" PRIx64 "\"",
                (int) machine_get_type(m), (uint64_t) mt->bus_flags, (uint64_t) mt->flags);
        fprintf(out, ", \"ram\": {\"min\": %d, \"max\": %d, \"step\": %d}",
                machine_get_min_ram(m), machine_get_max_ram(m), machine_get_ram_granularity(m));

        /* Built-in devices, if any, by display name. */
        const char *ob;
        if ((ob = onboard(machine_get_vid_device(m))) && machine_has_flags(m, MACHINE_VIDEO)) {
            fputs(", \"onboard_video\": ", out);
            jstr(ob);
        }
        if (machine_has_flags(m, MACHINE_VIDEO_ONLY))
            fputs(", \"video_fixed\": true", out);
        if ((ob = onboard(machine_get_snd_device(m))) && machine_has_flags(m, MACHINE_SOUND)) {
            fputs(", \"onboard_sound\": ", out);
            jstr(ob);
        }
        if ((ob = onboard(machine_get_net_device(m))) && machine_has_flags(m, MACHINE_NIC_PRI)) {
            fputs(", \"onboard_network\": ", out);
            jstr(ob);
        }

        /* Eligible CPUs: [[family index, [cpu indices...]], ...] */
        fputs(", \"cpus\": [", out);
        int nf = 0;
        for (int f = 0; cpu_families[f].package != 0; f++) {
            const cpu_family_t *fam = &cpu_families[f];
            if (!cpu_family_is_eligible(fam, m))
                continue;
            fprintf(out, "%s[%d, [", nf ? ", " : "", f);
            int nc = 0;
            for (int c = 0; fam->cpus[c].cpu_type != 0; c++) {
                if (cpu_is_eligible(fam, c, m))
                    fprintf(out, "%s%d", nc++ ? ", " : "", c);
            }
            fputs("]]", out);
            nf++;
        }
        fputs("]}", out);
    }
    fputs("\n  ],\n", out);

    /* Floppy drive types. */
    fputs("  \"floppy_drives\": [", out);
    for (int t = 1;; t++) {
        const char *in = fdd_get_internal_name(t);
        if ((in == NULL) || (in[0] == '\0'))
            break;
        fprintf(out, "%s\n    {\"id\": ", (t > 1) ? "," : "");
        jstr(in);
        fputs(", \"name\": ", out);
        jstr(fdd_getname(t));
        fputs("}", out);
    }
    fputs("\n  ],\n", out);

    /* CD-ROM drive models. */
    fputs("  \"cdrom_drives\": [", out);
    for (int t = 0; cdrom_drive_types[t].internal_name != NULL; t++) {
        if (cdrom_drive_types[t].internal_name[0] == '\0')
            break;
        snprintf(name, sizeof(name), "%s %s %s", cdrom_drive_types[t].vendor, cdrom_drive_types[t].model,
                 cdrom_drive_types[t].revision);
        fprintf(out, "%s\n    {\"id\": ", t ? "," : "");
        jstr(cdrom_drive_types[t].internal_name);
        fputs(", \"name\": ", out);
        jstr(name);
        fprintf(out, ", \"bus\": %d, \"speed\": %d}", cdrom_drive_types[t].bus_type, cdrom_drive_types[t].speed);
    }
    fputs("\n  ],\n", out);

    /* Expansion cards, each with the machines it fits. */
    fputs("  \"cards\": {", out);
    int first = 1;
    device_list("video", v_dev, v_name, v_avail, &first);
    device_list("sound", s_dev, s_name, s_avail, &first);
    device_list("network", n_dev, n_name, n_avail, &first);
    device_list("scsi", sc_dev, sc_name, sc_avail, &first);
    device_list("hdc", h_dev, h_name, h_avail, &first);
    device_list("fdc", f_dev, f_name, f_avail, &first);
    fputs("\n  }\n}\n", out);

    fclose(out);
    return 0;
}
