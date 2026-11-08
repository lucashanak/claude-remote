# Pravidla „nehlas“

Tenhle soubor patří Lukášovi. Jarvis ho jen čte a pravidla smí nejvýš navrhnout. Instalace ho založí jednou a pak
ho nikdy nepřepíše.

Od F3 platí pravidla „nehlas“ ze souboru `~/.claude-remote/jarvis/rules`, který píše jen `cr-rules`
(`cr-rules add --session <jméno> --kind question|task|watch|all [--days N] "<věta>"`, `cr-rules list`,
`cr-rules rm n-…`). Pravidlo platí nejvýš 7 dní a **nikdy neumlčí povolení, dotazy, dialogy, incidenty ani
připomínky**. Jarvis ho zapíše jen tehdy, když to Lukáš sám řekne („nehlas“, „nechci“, „přestaň hlásit“).

Sem si Lukáš může psát vlastní poznámky pro Jarvise; `jarvis-d` tenhle soubor nečte.
