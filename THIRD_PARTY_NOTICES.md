# Third-Party Notices

This project bundles third-party data files, listed here with their attributions and licenses.

## EFF Long Wordlist (diceware)

- **File:** `src/main/resources/com/sparrowwallet/sparrow/control/eff-large.txt`
- **Source:** Electronic Frontier Foundation — <https://www.eff.org/dice>
- **Used by:** the diceware passphrase helper (`DicewareWordList`, `DicewareDialog`)
- **License:** Creative Commons Attribution 3.0 United States (CC BY 3.0 US) —
  <https://creativecommons.org/licenses/by/3.0/us/>
- **Attribution:** "EFF Long Wordlist" by the Electronic Frontier Foundation, used under CC BY 3.0 US.

The wordlist is included verbatim. Its integrity is verified at load time against a bundled SHA-256
digest.

## Inter (reading typeface)

- **Files:** `src/main/resources/font/Inter-Regular.ttf`, `src/main/resources/font/Inter-SemiBold.ttf`, `src/main/resources/font/Inter-read.txt`
- **Used by:** every sentence in the GUI: warnings, help text and wizard copy (`.ag-read` in `ashigaru.css`)
- **Copyright:** `Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter)`
- **License:** SIL Open Font License, Version 1.1 (https://openfontlicense.org)

## Nikkyou Sans (splash typeface)

- **Files:** `src/main/resources/font/NikkyouSans.ttf`, `src/main/resources/font/NikkyouSans-read.txt`
- **Used by:** the splash screen title and subtitle (`.splash-title`, `.splash-subtitle`)
- **Copyright:** `Copyright (c) 2017, Dare-Demo Iie, Japan` (read from the font's name table)
- **Author:** daredemotypo, released 2017
- **License:** none stated. The archive carries no license file, and the font embeds no License
  Description (name ID 13) or License URL (name ID 14).

The author distributes the font freely, ships it with the note *"公序良俗に反しない程度にご利用ください"*
("please use it to the extent that it does not violate public order and morals" — reproduced in full
in `NikkyouSans-read.txt`), and set the OpenType embedding permission field to the most permissive
value available (`OS/2 fsType = 0x0000`, Installable Embedding), which places no restriction on
embedding or installing the font.

It is bundled on that basis: an inference of permissive intent from how the author distributes and
flags the font, **not** an explicit grant of redistribution rights. Third-party font sites describe
it as free for commercial use with redistribution permitted, but that wording does not originate
with the author. Anyone redistributing this project should be aware of that distinction, and it
would be worth obtaining explicit permission or an OFL release from the author.

## Roboto Mono

- **Files:** `src/main/resources/font/RobotoMono-Regular.ttf`, `src/main/resources/font/RobotoMono-Italic.ttf`
- **Used by:** monospaced text throughout the interface, loaded in `AshigaruGui`
- **License:** Apache License 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>

## Font Awesome Free

- **Files:** `src/main/resources/font/fa-solid-900.ttf`, `src/main/resources/font/fa-brands-400.ttf`
- **Used by:** interface icons via `FontAwesome5` and `FontAwesome5Brands`
- **Source:** <https://fontawesome.com>
- **License:** the icon fonts are licensed under SIL OFL 1.1 —
  <https://scripts.sil.org/OFL>. Font Awesome Free requires attribution; see
  <https://fontawesome.com/license/free>.
