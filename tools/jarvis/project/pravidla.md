# Pravidla „nehlas“

Tenhle soubor patří Lukášovi. Jarvis ho jen čte a pravidla smí nejvýš navrhnout. Instalace ho založí jednou a pak
ho nikdy nepřepíše.

Stav: **kostra pro F3.** V F1 pravidla ještě nic nefiltruje. Formát níže je návrh, se kterým bude počítat `jarvis-d`.

## Formát
Jedno pravidlo = jeden řádek začínající `- nehlas`, pole `klíč=hodnota` oddělená mezerou, za `#` poznámka:

```
- nehlas session=<vzor> druh=<druh> do=<RRRR-MM-DD>  # původní věta, kdy vzniklo
```

- `session`: vzor jména sessions (krátké jméno nebo tmux jméno), `*` = cokoli. Například `music-seeker*`.
- `druh`: `done` (doběhl), `question` (odpověď končí otázkou), `dead`, `missing`, `closed`, `new`, `back`
  nebo `*`. **`perm`, `ask` a `waiting` pravidlo nikdy neumlčí**, i kdyby tu byly napsané.
- `do`: poslední den platnosti, nejvýš 7 dní od vzniku. Prošlé pravidlo neplatí a při úklidu se smaže.
- Co se nevejde do formátu, zůstane jen poznámka a nic nefiltruje.
- Řádky uvnitř bloků ``` jsou jen ukázky a nečtou se. Platí jen řádky pod nadpisem „Pravidla“.

Příklad (zakomentovaný, neplatí):

```
- nehlas session=music-seeker* druh=done do=2026-11-14  # „Stopy z music-seeker mi nehlas.“ 2026-11-07
```

## Pravidla

