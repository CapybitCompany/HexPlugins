# HexNameTags — struktura DB

Plugin korzysta z `HexCore` przez `HexApi` i `DatabaseService`.

Finalna nazwa tabeli to:

```text
<HexCore Db.tablePrefix()> + config.database.table-name
```

Domyślnie, bez prefixu: `hex_nametags`.
Przy prefixie `smp_`: `smp_hex_nametags`.

## Tabela

```sql
CREATE TABLE IF NOT EXISTS <prefix>hex_nametags (
  target_uuid VARCHAR(36) PRIMARY KEY,
  target_type VARCHAR(16) NOT NULL,
  lines_data TEXT NOT NULL,
  style_key VARCHAR(64) NOT NULL,
  enabled INTEGER NOT NULL,
  updated_at BIGINT NOT NULL
);
```

## Kolumny

| Kolumna | Typ | Znaczenie |
| --- | --- | --- |
| `target_uuid` | `VARCHAR(36)` | UUID gracza, klucz główny. |
| `target_type` | `VARCHAR(16)` | Obecnie `PLAYER`; zostawione pod przyszłe stabilne entity/custom targety. |
| `lines_data` | `TEXT` | Linie tagu jako Base64-encoded MiniMessage, jedna fizyczna linia DB = jedna linia nametaga. |
| `style_key` | `VARCHAR(64)` | Obecnie `default`; przygotowane pod wiele stylów z configu. |
| `enabled` | `INTEGER` | `1` = wpis aktywny, `0` = zarezerwowane pod czasowe wyłączenie bez kasowania. |
| `updated_at` | `BIGINT` | `System.currentTimeMillis()` ostatniej zmiany. |

## Cache

Odczyty z DB są cachowane per UUID gracza przez `database.cache-ttl-seconds`.
Domyślnie: `10` sekund.
`0` wyłącza cache odczytu.

Zapis `/hexnametag set` aktualizuje cache optymistycznie.
`/hexnametag clear` usuwa wpis z cache i DB.
