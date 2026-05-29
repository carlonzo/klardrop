# Klardrop APT repository

A self-hosted, GPG-signed APT repository that distributes the Klardrop desktop
`.deb`. It is built with [`reprepro`](https://wiki.debian.org/DebianRepository/SetupWithReprepro)
in CI and served from GitHub Pages at:

    https://carlonzo.github.io/klardrop/apt

## For users: install Klardrop via APT

These steps use the modern `signed-by` keyring approach (Debian/Ubuntu's
replacement for the deprecated `apt-key add`).

```sh
# 1. Create the keyrings dir (already present on Debian 12+/Ubuntu 22.04+).
sudo install -d -m 0755 /etc/apt/keyrings

# 2. Fetch the repository's public signing key into that dir.
sudo curl -fsSL -o /etc/apt/keyrings/klardrop-archive-keyring.gpg \
  https://carlonzo.github.io/klardrop/apt/klardrop-archive-keyring.gpg

# 3. Register the repository, pinned to that key.
echo "deb [signed-by=/etc/apt/keyrings/klardrop-archive-keyring.gpg] https://carlonzo.github.io/klardrop/apt stable main" \
  | sudo tee /etc/apt/sources.list.d/klardrop.list

# 4. Install.
sudo apt update
sudo apt install klardrop
```

Future Klardrop releases are picked up by a normal `sudo apt update && sudo apt
upgrade`.

### deb822 alternative

If you prefer the newer `.sources` format, instead of step 3 write
`/etc/apt/sources.list.d/klardrop.sources`:

```
Types: deb
URIs: https://carlonzo.github.io/klardrop/apt
Suites: stable
Components: main
Signed-By: /etc/apt/keyrings/klardrop-archive-keyring.gpg
```

## For the maintainer: one-time GPG key setup

The repository metadata is signed by a dedicated GPG key. Generate it once and
store the private half as a CI secret.

```sh
# 1. Generate a key (no expiry or a long one; RSA 4096 or Ed25519 both fine).
#    Use a real name/email and SET A PASSPHRASE - CI expects a protected key.
gpg --full-generate-key
#    Suggested UID: Klardrop APT signing key <carlo.marinangeli@gmail.com>

# 2. Find the key id / fingerprint.
gpg --list-secret-keys --keyid-format=long

# 3. Export the ASCII-armored PRIVATE key (this is the secret CI imports).
gpg --armor --export-secret-keys <KEY_ID> > klardrop-apt-private.asc
```

Add two GitHub Actions secrets:

- `APT_GPG_PRIVATE_KEY` - the full contents of `klardrop-apt-private.asc`
  (the entire `-----BEGIN PGP PRIVATE KEY BLOCK-----` ... block).
- `APT_GPG_PASSPHRASE` - the passphrase chosen in step 1.

Then delete the local export (`shred -u klardrop-apt-private.asc`); the key now
lives only in your offline backup and the CI secret.

The CI release job imports this key into an isolated keyring and runs
`packaging/apt/publish.sh` to add the new `.deb` and re-export the public key.
See `packaging/apt/publish.sh` for details.
