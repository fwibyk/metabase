# ibykus Metabase Fork — Änderungsprotokoll

Dieses Repository ist ein Fork von [Metabase](https://github.com/metabase/metabase) v0.59.5.1,
lizenziert unter der [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE.txt).

Gemäß AGPL-3.0 werden alle Änderungen gegenüber dem Upstream hier dokumentiert.

## Geänderte Dateien

### Backend (Clojure)

#### `src/metabase/server/auth_wrapper.clj`
- **Änderung**: Einbindung des neuen `/auth/sso-local` Endpunkts in die Route-Konfiguration
- **Grund**: Ermöglicht Custom-SSO ohne Enterprise-Lizenz

#### `src/metabase/sso/local.clj` *(neu)*
- **Änderung**: Neuer Endpunkt `/auth/sso-local` für Custom-SSO via Keycloak
- **Funktionalität**:
  - Validiert einen signierten HS256-JWT (shared secret: `MB_SSO_LOCAL_SECRET`)
  - Legt Metabase-User automatisch an (per E-Mail aus JWT)
  - Synchronisiert Keycloak-Rollen → Metabase-Gruppen
  - Setzt Session-Cookie und leitet weiter

#### `src/metabase/server/middleware/security.clj`
- **Änderung**: `X-Frame-Options`-Header wird unterdrückt wenn `MB_EMBEDDING_APP_ORIGIN` gesetzt ist
- **Grund**: Ermöglicht iframe-Einbettung der vollständigen Metabase-UI in die Webapp

#### `src/metabase/sync/sync_metadata/tables.clj`
- **Änderung**: `effective-schema`-Funktion ergänzt; normalisiert leeres Schema auf den `dbname` der Verbindung
- **Grund**: Doris-JDBC-Treiber liefert leere Strings statt dem Datenbanknamen als Schema-Bezeichner; ohne diesen Fix zeigt Metabase keine Tabellen an

### Frontend (TypeScript/React)

#### `frontend/src/metabase/selectors/embed.ts`
- **Änderung**: `getEmbedOptions` gibt im iframe-Kontext vollständige UI-Optionen zurück
- **Grund**: Im iframe-Modus blendet Metabase standardmäßig Buttons aus; für den ibykus-Use-Case wird die vollständige UI benötigt

#### `frontend/src/metabase/selectors/app.ts`
- **Änderung**: `getIsAppSwitcherVisible` prüft `user.is_superuser` statt `embedOptions.new_button`
- **Grund**: Das Admin-Menü (Settings, Admin-Bereich) soll nur für Metabase-Superuser sichtbar sein

#### `frontend/src/metabase/utils/iframe.ts`
- **Änderung**: Keine inhaltliche Änderung gegenüber Upstream (Kommentar angepasst)

#### `frontend/src/metabase/querying/notebook/components/NotebookDataPicker/NotebookDataPicker.tsx`
- **Änderung**: Der Embedding-Picker (`EmbeddingDataPicker` → `PLUGIN_EMBEDDING.*`) wird nicht mehr allein wegen iframe-Kontext erzwungen; die Bedingung ist auf `isTenantUser` verengt
- **Grund**: `PLUGIN_EMBEDDING.SimpleDataPicker`/`DataSourceSelector` sind in der OSS-Edition `null`; im iframe blieb der Datenquellen-Picker dadurch leer. Full-App-Embed nutzt jetzt den normalen `ModernDataPicker`

#### `frontend/src/metabase/querying/notebook/components/DataStep/DataStep.tsx`
- **Änderung**: Das erzwungene Rendern des `NotebookDataPicker` im iframe-Kontext (`|| isEmbed`) wurde entfernt
- **Grund**: Im Ruhezustand (Tabelle gewählt, Schritt geschlossen) rendert der `ModernDataPicker` keine sichtbare Pille → die „Daten"-Leiste blieb leer. Ohne den Zwang nutzt das Embedding dieselbe Ruhe-Pille (`DataPickerTarget`) wie der Normalbetrieb; ein Klick öffnet den Picker

## Neue Dateien

| Datei | Beschreibung |
|-------|-------------|
| `src/metabase/sso/local.clj` | Custom-SSO-Endpunkt für Keycloak-Integration |
| `IBYKUS_CHANGES.md` | Dieses Dokument |

## Umgebungsvariablen

| Variable | Beschreibung |
|----------|-------------|
| `MB_SSO_LOCAL_SECRET` | Shared Secret für JWT-Signatur (HS256) |
| `MB_EMBEDDING_APP_ORIGIN` | Erlaubter Origin für iframe-Einbettung (z.B. `*`) |

## Build

```bash
docker build -t metabase-ibykus:latest --build-arg VERSION=v0.59.5.3-ibykus .
```

## Lizenz

Dieses Werk ist lizenziert unter der GNU Affero General Public License v3.0.
Siehe [LICENSE-AGPL.txt](LICENSE-AGPL.txt) für den vollständigen Lizenztext.

Der Quellcode dieser modifizierten Version ist verfügbar unter:
`https://github.com/fwibyk/metabase`
