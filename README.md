# VM Computers (86Box edition)

Build real, working retro PCs in Minecraft. This is a fork of
[VM Computers](https://github.com/Delta2Force/MCVmComputers) by DeltaTwoForce,
with VirtualBox replaced by [86Box](https://86box.net), an emulator of IBM PCs
and compatibles from 1981 to the early 2000s.

- **Minecraft 1.16.5, Fabric**, same as the original mod.
- **Real hardware.** Hundreds of real motherboards, CPUs, video, sound and network
  cards, disk controllers, floppy and CD-ROM drives, all taken from 86Box itself.
  Parts only go where they'd really fit: a Pentium won't go in a 486 board and a PCI
  card won't go in an ISA-only machine.
- **Always the newest 86Box.** The mod downloads the newest 86Box development build
  by itself. No mod update is needed when 86Box updates.
- **Multiplayer.** Players near your PC see its screen without having 86Box; they
  only need the mod. Only the owner can type and click on it.

## How it fits together

```
 86Box master ──nightly GitHub Action──► release: 86Box-Windows-x64.zip
 (built with -D AUTOMATION=ON                    catalog.json  (parts + compatibility)
  + tools/86box-patches)                          manifest.json (what's newest)
                                                          │
 the mod ──reads releases/latest/download/manifest.json──┘
   • catalog.json     every game start, for the parts shop
   • 86Box + ROM set  the first time a PC is switched on, then kept up to date
   • runs 86Box hidden and talks to it over its automation socket
     (screen, sound, keyboard, mouse, media, reset, power)
```

**Why a separate build of 86Box?** 86Box has a built-in automation socket that lets
a program control a running machine, but it's a compile-time option that the
official builds leave off. The workflow in `.github/workflows/86box-nightly.yml`
builds the newest 86Box source every night, unchanged, with that one option on.
If the 86Box team ever turns it on in their own builds, the mod can switch to
those instead.

**Why a parts catalog?** `tools/catalog` is a small program linked against the same
86Box build. It asks 86Box's own code which machines exist, which CPUs each board
takes, how much RAM it supports, and which cards fit which slots, and writes the
answers to `catalog.json`. New hardware in 86Box shows up in the shop by itself,
and compatibility always matches what 86Box will actually run.

## Playing

1. Install Fabric Loader and Fabric API for Minecraft 1.20.1, then put the mod jar in `mods`.
2. Start the game and finish the short setup (folder and "leave the VM" keys).
3. Craft an **Ordering Tablet**. When the satellite passes over, open the store.
   **Parts catalog** opens a searchable shop with every motherboard, CPU and card.
   **Showing: fits my board** narrows it to parts that fit the board in your cart,
   in the PC you last opened, or in your inventory. Pay in iron ingots at the delivery chest.
   Graphics accelerators (3dfx Voodoo Graphics, Voodoo 2 and friends, the 8514/A, XGA and
   Display Adapter II) are at the top of the **Video** tab.
   **Setup** on any part opens the same settings as its 86Box Configure dialog: BIOS
   version, video memory, IRQ and address jumpers, SLI and so on. Motherboards also cover
   their built-in video, sound and network chips, and CPUs that took a separate math
   coprocessor (8087, 287, 387...) let you add one. **Add** buys a part with 86Box's defaults.
   Installed parts have a **Setup** button in the open case too (with the PC off).
4. Place a PC case and right-click it. **Open case** to install the motherboard, CPU,
   RAM, cards and drives. Right-click a **Hard Drive** item to create its disk first:
   like 86Box's New Hard Disk dialog, you pick the image format (raw, fixed-size VHD,
   dynamic-size VHD or a differencing VHD based on another disk), the size, a standard
   drive type or exact cylinders/heads/sectors, what controller it's for, and the drive
   model, which sets how fast it is.
5. Close the case and click **Turn on**. The first time, 86Box and its ROM set (about
   150 MB) download automatically.
6. Right-click a monitor to use the PC. The default key combo to leave is
   **Ctrl + Backspace**.

Put CD images (`.iso`, `.cue`, `.chd`...) in `vm_computers/isos` and floppy images
(`.img`, `.ima`, `.86f`...) in `vm_computers/floppies`. A folder in `vm_computers/isos`
works as a CD too: 86Box turns its files into a disc on the fly, so there's no need
to make an ISO. Insert them from the PC screen, even while it's running.

The PC's sound comes from the PC case: it fades with distance and pans as you turn,
and players nearby hear it too. Its volume follows the **Jukebox/Note Blocks** slider.

In the open case, **Keyboard and mouse** picks the keyboard (XT, AT, PS/2...) and the
mouse (serial, bus, InPort, PS/2) with their settings, such as the COM port or IRQ. Hard disks live in `vm_computers/vhds`, and each PC's BIOS
settings live in `vm_computers/machines/<pc id>`.

Settings are in `vm_computers/setup.json`:

| Setting | Default | What it does |
|---|---|---|
| `frameIntervalMs` | 50 | How often your screen refreshes from 86Box (50 = 20 fps). |
| `streamFps` | 5 | Frames per second sent to players watching your screen. |
| `romUpdateDays` | 7 | How often the ~100 MB ROM set is refreshed. A PC that needs newer ROMs triggers an update by itself. |
| `manifestUrl` | this repository | Where to look for nightly 86Box builds. |
| `custom86BoxExe`, `customRomDirectory` | empty | Use your own 86Box build and ROMs instead (it must be built with `-D AUTOMATION=ON`). |

## Setting up the repository (one time)

1. Put this repository on GitHub. In GitHub Desktop, use *File → Add local repository*,
   then *Publish repository*.
2. On GitHub, open the **Actions** tab and enable workflows if asked.
3. Run **86Box nightly** once by hand (*Run workflow*, tick *force*). It takes about
   30–45 minutes and publishes the first release. After that it runs every night and
   only builds when 86Box or its ROM set changed.
4. **Build mod** runs on every push. The mod jar is in that run's *Artifacts*. The jar
   automatically points at this repository's releases.

GitHub pauses scheduled workflows in repositories with no activity for 60 days. If
nightlies stop, re-enable the workflow on the Actions tab.

## Building locally

Needs Java 17 or newer to run Gradle (the mod itself is compiled for Java 8):

```
./gradlew build -Pnightly_repo=YOUR_GITHUB_NAME/YOUR_REPO
```

## What changed from the original

**Replaced:** VirtualBox and its Java bindings, vboxwebsrv, and the VirtualBox setup
pages. The old "32/64-bit motherboard", "1/N host CPU" and fixed RAM items are gone.

**Fixed along the way** (several are open issues on the original repository):
- Clicks registering as right-clicks (#142, #190, #284): the right-click that focused
  the monitor was forwarded to the guest.
- `NoSuchMethodError: DoubleBuffer.clear()` on Java 8 (#208): the mod is now compiled with `--release 8`.
- Crash in the PC screen, "Color parameter outside of expected range" (#198).
- "Duplicate id value" server crash (#39): the delivery chest registered its data
  under the flat screen's entity class.
- Off-hand placement crash (#68).
- Screens flickering for other players (#121), and screen textures being re-created
  (and leaked) every frame.
- Renamed hard drives losing their name after going in and out of a PC (#50).
- The tablet's render thread spinning a CPU core at 100%.
- The order list being changed while iterated (crash), and deliveries to players who logged out.
- Keyboard: `End` was sent as `Down`, and `=` as keypad `.`.
- Folder paths with spaces forcing the setup screen on every launch.
- Deleting a hard disk image with one click (now shift-click).
- Any player could sneak-click and pick up someone else's monitor.

**Known limits:**
- **Mouse wheel.** 86Box's automation socket has no mouse wheel command yet (#302).
- **Sound for other players** is compressed (about 12 KB/s per sound source) and
  arrives a little later than for the owner.
- **Platforms.** Nightly builds are made for 64-bit Windows. Other systems can point
  `custom86BoxExe` at their own 86Box built with `-D AUTOMATION=ON`.
- **One PC at a time.** Each player can run one PC at a time.
- **Tablet text.** The tablet's built-in screens are English only (#60).

## Possible upstream improvements to 86Box

- **Reply latency.** The automation socket sends each reply in two writes, the JSON
  and then a newline, which adds up to 40–200 ms per request because of Nagle's
  algorithm. The mod works around this, but setting `TCP_NODELAY`, or sending the
  newline together with the reply, would fix it in 86Box itself.
- **Missing commands.** A mouse-wheel command is the last gap.
- **Focus.** Starting 86Box without taking the keyboard focus from the program that
  drives it is a small patch in `tools/86box-patches` that could go upstream.
- **Official builds.** Turning `AUTOMATION` on in the official builds would let the
  mod use them directly.

## Credits and license

Original mod by DeltaTwoForce and contributors. 86Box by the 86Box team. This fork
is under the GNU GPL v3 or later, the same as the original (see `LICENSE` and `GPLv3`).
86Box is GPL v2 or later.
