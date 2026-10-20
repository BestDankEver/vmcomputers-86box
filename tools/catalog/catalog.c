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
#include <86box/vid_8514a_device.h>
#include <86box/vid_xga_device.h>
#include <86box/vid_ps55da2.h>
#include <86box/sound.h>
#include <86box/network.h>
#include <86box/scsi.h>
#include <86box/hdd.h>
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

static void device_options(const device_t *dev);

/* A device's settings section name and options, for anything with a Configure dialog. */
static void
device_settings(const device_t *dev)
{
    if ((dev == NULL) || (dev->config == NULL))
        return;
    fputs(", \"section\": ", out);
    jstr(dev->name);
    fputs(", \"options\": ", out);
    device_options(dev);
}

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
        device_settings(dev);
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

/* ---- Graphics accelerators ------------------------------------------------------
 *
 * The Voodoo, 8514/A, XGA and Display Adapter II are not in 86Box's video card
 * list: they are add-ons that sit next to the main card, switched on by their
 * own key in the [Video] section. 86Box has no table of them (the Display
 * settings page checks for each one by hand), so this mirrors those checks in
 * qt_settingsdisplay.cpp: which boards can take each one, and which main cards
 * rule it out.
 */
typedef struct accel_t {
    const char     *id;      /* catalog id */
    const char     *key;     /* [Video] key that switches it on */
    const device_t *dev;     /* the device whose config section holds the options */
    uintptr_t       bus;     /* machine bus it needs */
    uintptr_t       not_bus; /* ...unless the machine also has this one (0 = no exception) */
    int             conflict_type; /* VIDEO_FLAG_TYPE_* of main cards that already have it, -1 = none */
    int             needs_available; /* the device has its own ROM check */
} accel_t;

static const accel_t accels[] = {
    { "voodoo",   "voodoo", &voodoo_device,      MACHINE_BUS_PCI,   0,               -1,                   0 },
    { "8514_isa", "8514a",  &gen8514_isa_device, MACHINE_BUS_ISA16, MACHINE_BUS_MCA, VIDEO_FLAG_TYPE_8514, 0 },
    { "8514_mca", "8514a",  &ibm8514_mca_device, MACHINE_BUS_MCA,   0,               VIDEO_FLAG_TYPE_8514, 0 },
    { "xga_mca",  "xga",    &xga_device,         MACHINE_BUS_MCA,   0,               VIDEO_FLAG_TYPE_XGA,  1 },
    { "ps55da2",  "da2",    &ps55da2_device,     MACHINE_BUS_MCA,   0,               VIDEO_FLAG_TYPE_DA2,  1 },
};

static int
accel_fits(const accel_t *a, int m)
{
    if (!machine_has_bus(m, a->bus))
        return 0;
    if (a->not_bus && machine_has_bus(m, a->not_bus))
        return 0;
    return 1;
}

static void
accel_machine_bitset(const accel_t *a)
{
    char *hex = calloc((size_t) (navail / 4) + 2, 1);
    for (int i = 0; i < navail; i += 4) {
        int nib = 0;
        for (int b = 0; b < 4; b++) {
            nib <<= 1;
            if (((i + b) < navail) && accel_fits(a, avail[i + b]))
                nib |= 1;
        }
        hex[i / 4] = "0123456789abcdef"[nib];
    }
    jstr(hex);
    free(hex);
}

/* The settings page greys out the add-on Voodoo whenever the main card's name looks like a 3dfx card. */
static int
looks_like_3dfx(const char *s)
{
    char low[600];
    int  i;
    for (i = 0; s[i] && (i < (int) sizeof(low) - 1); i++)
        low[i] = (s[i] >= 'A' && s[i] <= 'Z') ? (s[i] + 32) : s[i];
    low[i] = '\0';
    return strstr(low, "3dfx") || strstr(low, "voodoo") || strstr(low, "banshee") || strstr(low, "raven");
}

static void
accel_conflicts(const accel_t *a)
{
    char name[600];
    int  n = 0;

    fputs("[", out);
    for (int c = 0;; c++) {
        const char     *in  = video_get_internal_name(c);
        const device_t *dev = video_card_getdevice(c);
        if ((in == NULL) || (in[0] == '\0'))
            break;
        if ((dev == NULL) || (dev->init == NULL))
            continue;
        int clash;
        if (a->conflict_type < 0) {
            device_display_name(dev, name);
            clash = looks_like_3dfx(name);
        } else
            clash = (video_card_get_flags(c) == a->conflict_type);
        if (clash) {
            fputs(n++ ? ", " : "", out);
            jstr(in);
        }
    }
    fputs("]", out);
}

static int
bios_roms_present(const device_config_bios_t *b)
{
    int p = 0;
    if (b->files_no < 0)
        return 1;
    for (int d = 0; d < b->files_no; d++)
        p += !!rom_present(b->files[d]);
    return p == b->files_no;
}

/*
 * A device's settings, as its Configure dialog shows them. Only kinds the mod
 * can offer as buttons are written: variants (CONFIG_BIOS), on/off switches,
 * lists of choices and spinners. Values are strings exactly as 86Box stores
 * them in its config file. Anything else keeps 86Box's default.
 */
static void
device_options(const device_t *dev)
{
    char buf[64];
    int  n = 0;

    fputs("[", out);
    for (const device_config_t *c = dev->config; (c != NULL) && (c->type != CONFIG_END); c++) {
        const int type = c->type & CONFIG_TYPE_MASK;
        if ((c->name == NULL) || (c->name[0] == '\0'))
            continue;
        switch (type) {
            case CONFIG_BIOS:
            case CONFIG_BINARY:
            case CONFIG_SELECTION:
            case CONFIG_HEX16:
            case CONFIG_HEX20:
            case CONFIG_SPINNER:
                break;
            default:
                continue;
        }
        fprintf(out, "%s\n        {\"name\": ", n++ ? "," : "");
        jstr(c->name);
        fputs(", \"label\": ", out);
        jstr(c->description);

        if (type == CONFIG_BIOS) {
            fputs(", \"kind\": \"variant\", \"default\": ", out);
            jstr(c->default_string);
            fputs(", \"choices\": [", out);
            int k = 0;
            for (const device_config_bios_t *b = c->bios; (b->name != NULL) && (b->internal_name != NULL) &&
                                                         (b->name[0] != '\0') && (b->files_no != 0); b++) {
                if (!bios_roms_present(b))
                    continue;
                fprintf(out, "%s{\"value\": ", k++ ? ", " : "");
                jstr(b->internal_name);
                fputs(", \"label\": ", out);
                jstr(b->name);
                /* Memory limits of this variant, applied to the "memory" (or
                   "framebuffer_memory") and "texture_memory" options. */
                if (b->flags & BIOS_LIMIT_MIN_MEMORY)
                    fprintf(out, ", \"mem_min\": %d", (int) (b->flags & 0xff));
                if (b->flags & BIOS_LIMIT_MAX_MEMORY)
                    fprintf(out, ", \"mem_max\": %d", (int) ((b->flags >> 8) & 0xff));
                if (b->flags & BIOS_LIMIT_MIN_MEMORY_2)
                    fprintf(out, ", \"mem2_min\": %d", (int) ((b->flags >> 16) & 0xff));
                if (b->flags & BIOS_LIMIT_MAX_MEMORY_2)
                    fprintf(out, ", \"mem2_max\": %d", (int) ((b->flags >> 24) & 0xff));
                if (b->flags & BIOS_FIXED_MEMORY)
                    fputs(", \"mem_fixed\": true", out);
                fputs("}", out);
            }
            fputs("]}", out);
            continue;
        }

        if (type == CONFIG_BINARY) {
            fprintf(out, ", \"kind\": \"toggle\", \"default\": \"%d\"}", c->default_int ? 1 : 0);
            continue;
        }

        if (type == CONFIG_SPINNER) {
            fprintf(out, ", \"kind\": \"spinner\", \"default\": \"%d\", \"min\": %d, \"max\": %d, \"step\": %d}",
                    c->default_int, c->spinner.min, c->spinner.max, c->spinner.step ? c->spinner.step : 1);
            continue;
        }

        /* Selections; hex ones are stored in hex. */
        const char *fmt = (type == CONFIG_HEX16) ? "%04X" : (type == CONFIG_HEX20) ? "%05X" : "%d";
        snprintf(buf, sizeof(buf), fmt, c->default_int);
        fputs(", \"kind\": \"select\", \"default\": ", out);
        jstr(buf);
        fputs(", \"choices\": [", out);
        int k = 0;
        for (const device_config_selection_t *s = c->selection; (s->description != NULL) && (s->description[0] != '\0'); s++) {
            snprintf(buf, sizeof(buf), fmt, s->value);
            fprintf(out, "%s{\"value\": ", k++ ? ", " : "");
            jstr(buf);
            fputs(", \"label\": ", out);
            jstr(s->description);
            fputs("}", out);
        }
        fputs("]}", out);
    }
    fputs(n ? "\n      ]" : "]", out);
}

static void
accel_list(int *first)
{
    char name[600];
    int  n = 0;

    fprintf(out, "%s\n  ", *first ? "" : ",");
    *first = 0;
    jstr("accel");
    fputs(": [", out);
    for (size_t i = 0; i < sizeof(accels) / sizeof(accels[0]); i++) {
        const accel_t *a = &accels[i];
        if (a->needs_available && !device_available(a->dev))
            continue;
        device_display_name(a->dev, name);
        fprintf(out, "%s\n    {\"id\": ", n ? "," : "");
        jstr(a->id);
        fputs(", \"name\": ", out);
        jstr(name);
        fprintf(out, ", \"flags\": %" PRIu32 ", \"machines\": ", (uint32_t) a->dev->flags);
        accel_machine_bitset(a);
        fputs(",\n      \"key\": ", out);
        jstr(a->key);
        fputs(", \"conflicts\": ", out);
        accel_conflicts(a);
        /* 86Box keeps a device's settings in a section named after the device. */
        device_settings(a->dev);
        fputs("}", out);
        n++;
    }
    fputs("\n  ]", out);
    fprintf(stderr, "accel: %d\n", n);
}

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
            fprintf(out, ", \"hz\": %" PRIu32 ", \"multi\": %.17g", fam->cpus[c].rspeed, fam->cpus[c].multi);
            /* Optional math coprocessors (the [Machine] fpu_type key), when there's a choice. */
            const FPU *fpus = fam->cpus[c].fpus;
            int        nfpu = 0;
            while ((fpus != NULL) && (fpus[nfpu].name != NULL))
                nfpu++;
            if (nfpu > 1) {
                fputs(", \"fpus\": [", out);
                for (int k = 0; k < nfpu; k++) {
                    fputs(k ? ", [" : "[", out);
                    jstr(fpus[k].internal_name);
                    fputs(", ", out);
                    jstr(fpus[k].name);
                    fputs("]", out);
                }
                fputs("]", out);
            }
            fputs("}", out);
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

        /* Settings: the board's own Configure dialog (usually its BIOS version), then
           those of its built-in video, sound and network chips. Keys in the mod are
           prefixed so one board can carry all of them. */
        {
            struct {
                const char     *prefix;
                const char     *label;
                const device_t *dev;
            } groups[] = {
                { "",       "Motherboard",       machine_get_device(m) },
                { "video/", "On-board video",    machine_has_flags(m, MACHINE_VIDEO) ? machine_get_vid_device(m) : NULL },
                { "sound/", "On-board sound",    machine_has_flags(m, MACHINE_SOUND) ? machine_get_snd_device(m) : NULL },
                { "net/",   "On-board network",  machine_has_flags(m, MACHINE_NIC_PRI) ? machine_get_net_device(m) : NULL },
            };
            int ng = 0;
            for (size_t g = 0; g < sizeof(groups) / sizeof(groups[0]); g++) {
                const device_t *d = groups[g].dev;
                if ((d == NULL) || (d->config == NULL) || (d->config[0].type == CONFIG_END))
                    continue;
                fputs(ng++ ? ",\n      {\"prefix\": " : ", \"settings\": [\n      {\"prefix\": ", out);
                jstr(groups[g].prefix);
                fputs(", \"label\": ", out);
                jstr(groups[g].label);
                device_settings(d);
                fputs("}", out);
            }
            if (ng)
                fputs("]", out);
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

    /* Hard disk models (the hdd_NN_speed key): generic speed classes and real drives. */
    fputs("  \"hdd_models\": [", out);
    for (int i = 0; i < hdd_preset_get_num(); i++) {
        fprintf(out, "%s\n    [", i ? "," : "");
        jstr(hdd_preset_get_internal_name(i));
        fputs(", ", out);
        jstr(hdd_preset_getname(i));
        fputs("]", out);
    }
    fputs("\n  ],\n", out);

    /* Standard drive types [cylinders, heads, sectors], as in the New Hard Disk dialog. */
    fputs("  \"hdd_types\": [", out);
    for (int i = 0; (i < 127) && hdd_table[i][0]; i++)
        fprintf(out, "%s[%u, %u, %u]", i ? ", " : "", hdd_table[i][0], hdd_table[i][1], hdd_table[i][2]);
    fputs("],\n", out);

    /* Expansion cards, each with the machines it fits. */
    fputs("  \"cards\": {", out);
    int first = 1;
    device_list("video", v_dev, v_name, v_avail, &first);
    device_list("sound", s_dev, s_name, s_avail, &first);
    device_list("network", n_dev, n_name, n_avail, &first);
    device_list("scsi", sc_dev, sc_name, sc_avail, &first);
    device_list("hdc", h_dev, h_name, h_avail, &first);
    device_list("fdc", f_dev, f_name, f_avail, &first);
    accel_list(&first);
    fputs("\n  }\n}\n", out);

    fclose(out);
    return 0;
}
