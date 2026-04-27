# Minigames (etap 1)

Plugin buduje wspolny framework instancji + lobby dla wielu trybow gry (np. skywars, spleef).

## Co jest zaimplementowane
- framework instancji w `hex.minigames.framework`
- lifecycle instancji: `LOBBY -> COUNTDOWN -> INGAME -> END -> RESET`
- `InstanceManager` (rejestracja gameType, join/leave, auto-create instancji)
- komendy: `/join`, `/leave`, `/arena`, `/minigames reload`
- listener `PlayerQuit -> leave`
- prosty `MapProvider` (single-world)
- status publishing co 2s przez adapter refleksyjny do HexCore `status()` (jesli dostepny)
- przykładowe zachowania gier: SkyWars i Spleef

## Uwaga o mapach
`SingleWorldMapProvider` nie klonuje swiatow. Uzywa istniejacych nazw map z `minigames.yml`.
`TemplateCloneMapProvider` jest szkieletem pod etap 2.

## Quick start
1. Skonfiguruj mapy i spawn w `src/main/resources/minigames.yml`
2. Dodaj template keys z `src/main/resources/ui-templates-example.yml` do HexCore `ui.yml`
3. Uruchom plugin i testuj:
   - `/join skywars`
   - `/leave`
   - `/arena list skywars`

