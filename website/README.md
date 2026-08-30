# Twinotify marketing site

The site is intentionally framework-free and self-contained.

Preview only the site on the loopback interface:

```bash
cd website
python3 -m http.server 4173 --bind 127.0.0.1
```

Then open `http://127.0.0.1:4173/`.

Run its static contract with:

```bash
node website/verify-site.mjs
```

The download call to action points to GitHub Releases because this repository does not currently prove a public Play Store listing or a stable direct APK URL.
