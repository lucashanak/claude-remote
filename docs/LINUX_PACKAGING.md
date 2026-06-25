# Linux Packaging — Claude Remote

## Easiest path: install the prebuilt Manjaro package from Releases

Every push to `main` makes GitHub Actions build a ready-to-install pacman
package. On your Manjaro box just grab it from the latest release and install:

```bash
# from the GitHub Releases page, download claude-remote-<version>-1-x86_64.pkg.tar.zst
sudo pacman -U claude-remote-*-x86_64.pkg.tar.zst
claude-remote   # or launch "Claude Remote" from the app menu
```

The release also ships `claude-remote-linux.deb` (Debian/Ubuntu) and
`claude-remote-linux-x64.tar.gz` (portable app-image). The pacman package is
built by the `package-manjaro` CI job (`makepkg` inside an Arch container) from
the PKGBUILD below — so the local flow here is only needed for offline builds.

---

The rest of this document covers building a portable Linux app-image on the dev
box and installing it on Manjaro (or any Arch-based system) via the PKGBUILD.

## Prerequisites (dev box — Debian/Ubuntu/Proxmox)

- JDK 21 with `jpackage` — confirmed present at `~/jdks/jdk-21.0.5+11`
- Gradle wrapper (`./gradlew`) — no separate install needed

## 1. Build the tarball

```bash
bash scripts/build-linux.sh
```

This runs `:desktopApp:createDistributable` and produces:

```
dist/claude-remote-linux-x64.tar.gz
```

The tarball contains a self-contained JVM app-image (bundled runtime, no
system JVM required on the target machine).

## 2. Transfer to Manjaro

```bash
scp dist/claude-remote-linux-x64.tar.gz \
    packaging/manjaro/PKGBUILD \
    packaging/manjaro/claude-remote.desktop \
    desktopApp/src/main/resources/icon.png \
    user@manjaro-host:~/claude-remote-pkg/
```

## 3. Install on Manjaro

```bash
ssh user@manjaro-host
cd ~/claude-remote-pkg

# (Optional but recommended) verify the tarball checksum and update PKGBUILD:
sha256sum claude-remote-linux-x64.tar.gz
# paste the output into PKGBUILD's sha256sums=('...') line

makepkg -si
```

`makepkg -si` builds the package and installs it via `pacman`.

## 4. Run

```bash
claude-remote
```

Or launch *Claude Remote* from your application menu (Development category).

## Gradle tasks reference

| Task | Output | Use |
|------|--------|-----|
| `:desktopApp:createDistributable` | `desktopApp/build/compose/binaries/main/app/` | Portable app-image (used by build-linux.sh) |
| `:desktopApp:packageDeb` | `.deb` | Debian/Ubuntu installer |

## Notes

- The app-image bundles a JVM so no Java installation is needed on the target.
- `sha256sums=('SKIP')` in the PKGBUILD is fine for local/private use. Replace
  it with the real checksum for any distributed package.
- The launcher binary inside the app-image is named `"Claude Remote"` (with a
  space) because `packageName` in `build.gradle.kts` contains a space. The
  PKGBUILD symlinks it to `/usr/bin/claude-remote`.
