# Google Play graphic assets

The assets in this directory correspond to `../google-play.md`:

- `play-icon.png`: 512×512, no alpha channel
- `feature-graphic.png`: 1024×500, no alpha channel
- `zh/*.png`, `en/*.png`: 1080×1920, no alpha channel
- `feature-background.png`: promotional image background generated with OpenAI's built-in
  ImageGen, with the final image overlaying the project's existing terminal mark and
  deterministic text on top of it
- `play-icon.svg`: store icon source file rebuilt proportionally from the Android adaptive
  icon vector paths

The store screenshots in `en/` and `zh/` are the final assets for publishing. `docs/screenshots/`
keeps only the current iOS device showcase images used by the README, so that intermediate
screenshots not used by the documentation are not left in the repository.

Feature graphic background generation prompt summary: dark, minimalist, secure remote connection
and data flow, emerald/teal lines; no text, logos, phone mockups, watermarks, or hacker figures.
