# ibykus Metabase Fork — Änderungsprotokoll

Dieses Repository ist ein Fork von [Metabase](https://github.com/metabase/metabase),
lizenziert unter der [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE.txt).

Gemäß AGPL-3.0 werden alle Änderungen gegenüber dem Upstream hier dokumentiert.

## Basis — und wie man unsere Änderungen selbst nachrechnet

**Basis ist `metabase/metabase` auf `master`, Commit
[`dd6c1e01`](https://github.com/metabase/metabase/commit/dd6c1e01cba86231ccdae3e6ae8d84c077e7198b)
vom 23.04.2026.** Verankert als Tag **`upstream-base-2026-04-23`**. Der vollständige
Änderungssatz ist damit bildbar:

```
git diff upstream-base-2026-04-23 master
```

Ohne Klonen geht es auch im Browser, weil dieses Repository ein GitHub-Fork von
`metabase/metabase` ist und beide denselben Objektspeicher teilen:
<https://github.com/metabase/metabase/compare/dd6c1e01cba86231ccdae3e6ae8d84c077e7198b...fwibyk:master>

Der Anker-Commit trägt **absichtlich keinen Vorgänger**; er enthält ausschließlich den
unveränderten Upstream-Quellbaum. Dass dabei kein veränderter Baum untergeschoben wurde, ist ohne
Vertrauen in uns prüfbar — Git-Bäume sind inhaltsadressiert:

```
git rev-parse upstream-base-2026-04-23^{tree}   # 103d8f2e74a311e891d86434c98524c6a82eb658
```

Dieselbe Kennung liefert der Baum von `dd6c1e01` im Upstream-Repository.

**Korrektur einer früheren Falschangabe (04.08.2026):** Bis hierher stand in diesem Dokument
und im Wurzel-Commit `4c02fe37` („import Metabase v0.59.5.1 upstream source"), die Basis sei
**v0.59.5.1**. Das ist falsch, und zwar um eine ganze Minor-Version: `v0.59.5.1` wurde upstream
am 30.03.2026 getaggt und enthält `src/metabase/agent_lib`/`agent_api` **nicht**, unser Baum
enthält beides; `v0.60.1` wurde am 15.04. getaggt, `v0.60.2` am 21.04. Unsere Basis liegt also
im 0.60er-Entwicklungszweig **nach** `v0.60.2`. Sie entspricht keinem Release-Tag, weil es ein
`master`-Stand ist — deshalb wird sie hier über den Commit benannt und nicht über eine
Versionsnummer.

Ermittelt wurde sie durch Blob-für-Blob-Vergleich unseres Import-Baums gegen die
Upstream-Historie: **18231 von 18263 Blobs identisch (99,82 %)**, das Maximum über alle
geprüften Stände. Die 32 verbleibenden Abweichungen sind genau drei Gruppen:

1. **unsere Änderungen** — die unten aufgeführten Dateien;
2. **drei Dateien, die sich nur in den Zeilenenden unterscheiden** (`e2e/support/assets/dog_breeds.csv`,
   `e2e/support/assets/pokedex.tsv`, `resources/frontend_client/app/fonts/Merriweather/OFL.txt`) —
   Artefakt eines Windows-Checkouts, inhaltlich identisch (nachgeprüft nach Entfernen der CR);
3. **Dateien, die unsere eigene `.gitignore` ausschließt** (`.vscode/*`, `.babel_cache/.gitkeep`,
   `modules/drivers/mongo/.clj-kondo/.lock`) — im Fork nicht vorhanden, nicht geändert.

Zusätzlich melden **57 Dateien eine reine Rechteänderung** (verlorenes Ausführbar-Bit an
Shell-Skripten, ebenfalls Windows-Checkout). `git diff --numstat` zeigt sie mit `0` Zeilen.

**Warum der Wurzel-Commit dafür nicht genügt:** `4c02fe37` heißt „import upstream source",
enthält aber `src/metabase/sso/local.clj` — also unsere Modifikation — schon im ersten Commit.
Ein Empfänger konnte daraus nie ablesen, was von uns stammt. Genau diese Lücke schließt der
Basis-Tag.

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
- **Übergabe per Cookie, nicht über die URL** (BSI CON.10.A7): der einbettende Host setzt den Token
  als `HttpOnly`-Cookie mit `Path=/auth/sso-local`; beim Einlösen wird es gelöscht, die Übergabe ist
  damit einmalig. Vorher stand der Token als `?token=…` in der iframe-Adresse und damit im
  Zugriffsprotokoll des Reverse-Proxys.
- **Zwei Cookie-Namen, präfixierter zuerst** *(ab `v0.59.5.7-ibykus`, ASVS V3.3.1)*: gelesen werden
  `__Secure-mb-sso-token` **und** `mb-sso-token`. Der einbettende Host wählt den Namen nach
  Protokoll — über HTTPS mit Präfix, bei lokaler Entwicklung ohne, weil der Browser ein
  `__Secure-`-Cookie ohne HTTPS verwirft. Der präfixierte Name hat **Vorrang**; sonst könnte ein
  über HTTP gesetztes `mb-sso-token` das über HTTPS gesetzte aushebeln, und der Schutz wäre
  umgehbar, indem man ihn wegläßt. Gelöscht wird unter **beiden** Namen, damit nach der Umstellung
  kein Cookie unter dem alten Namen liegenbleibt. `__Host-` wäre hier falsch: dieser Präfix verlangt
  `Path=/` und würde die Pfadbindung aufheben, die verhindert, dass der Token an jede
  Metabase-Anfrage mitgeht.
- **Anzeigesprache aus dem Token nachziehen** *(ab `v0.59.5.10-ibykus`)*: der Claim `locale` wird
  bei jeder Anmeldung nach `core_user.locale` geschrieben. Nötig, weil Metabase im eingebetteten
  Notebook keine Sprachumschaltung anbietet und `MB_SITE_LOCALE` nur die Vorgabe für **neue** Konten
  ist — ein bestehendes Konto bliebe deutsch, während die einbettende Anwendung schon englisch ist.
  Bei jeder Anmeldung, weil das der einzige Zeitpunkt ist, an dem beide Seiten zusammenkommen; das
  ist die Umsetzung der Zusage „ein Sprachwechsel greift beim nächsten Anmelden". Geschrieben wird
  nur bei Abweichung, sonst fiele je Anmeldung ein wirkungsloser Schreibvorgang auf `core_user` an.
  **Allowlist `#{"de" "en"}`:** der Wert kommt zwar aus einem signierten Token, stammt aber
  ursprünglich aus einem Keycloak-Attribut, das anders belegt sein kann — und Metabase prüft beim
  Schreiben nicht nach.

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
- **Änderung**: Ein Kommentar und zwei Leerzeilen entfernt — das Verhalten ist unverändert
  (`git diff` zeigt `+0/-3`)

#### `frontend/src/metabase/querying/notebook/components/NotebookDataPicker/NotebookDataPicker.tsx`
- **Änderung**: Der Embedding-Picker (`EmbeddingDataPicker` → `PLUGIN_EMBEDDING.*`) wird nicht mehr allein wegen iframe-Kontext erzwungen; die Bedingung ist auf `isTenantUser` verengt
- **Grund**: `PLUGIN_EMBEDDING.SimpleDataPicker`/`DataSourceSelector` sind in der OSS-Edition `null`; im iframe blieb der Datenquellen-Picker dadurch leer. Full-App-Embed nutzt jetzt den normalen `ModernDataPicker`

#### `frontend/src/metabase/querying/notebook/components/DataStep/DataStep.tsx`
- **Änderung**: Das erzwungene Rendern des `NotebookDataPicker` im iframe-Kontext (`|| isEmbed`) wurde entfernt
- **Grund**: Im Ruhezustand (Tabelle gewählt, Schritt geschlossen) rendert der `ModernDataPicker` keine sichtbare Pille → die „Daten"-Leiste blieb leer. Ohne den Zwang nutzt das Embedding dieselbe Ruhe-Pille (`DataPickerTarget`) wie der Normalbetrieb; ein Klick öffnet den Picker

#### Weitere Dateien des Embed-Freischaltens *(nachgetragen 04.08.2026)*

Diese Dateien standen bisher **nicht** in diesem Protokoll, obwohl das Dokument oben
Vollständigkeit behauptet. Aufgefallen ist das erst, als der Basis-Tag den Diff bildbar machte —
vorher gab es nichts, wogegen man die Liste prüfen konnte. Alle folgen demselben Muster: Metabase
sperrt im Full-App-Embedding Funktionen für **alle** Nutzer; da unsere Instanz *immer* eingebettet
läuft, wäre sie damit dauerhaft eingeschränkt. Die Sperre entfällt, die **Rollenprüfung bleibt**.

| Datei | Änderung |
|---|---|
| `frontend/src/metabase/route-guards.tsx` | `AvailableInEmbedding`-Wächter entfernt (sperrte Data Studio im iframe pauschal) |
| `frontend/src/metabase/data-studio/selectors.ts` | `canAccessDataStudio` gibt im iframe nicht mehr `false` zurück; bleibt auf Admin/Analyst begrenzt |
| `frontend/src/metabase/data-studio/selectors.unit.spec.ts` | die Tests zur entfernten iframe-Sperre entfernt |
| `frontend/src/metabase/browse/models/BrowseModels.tsx`, `browse/metrics/BrowseMetrics.tsx` | „Modell/Metrik anlegen" auch im iframe |
| `frontend/src/metabase/nav/containers/MainNavbar/MainNavbarContainer/BrowseNavSection.tsx` | Browse-Einträge in der Navigation auch im iframe |
| `frontend/src/metabase/nav/components/search/SearchBar/SearchBar.tsx` | Suchleiste auch im iframe |
| `frontend/src/metabase/palette/components/Palette.tsx` | Befehlspalette auch im iframe |
| `frontend/src/metabase/visualizations/click-actions/actions/NativeQueryClickFallback.tsx` | Hinweis „als Frage speichern" auch im iframe |

#### `frontend/src/metabase/AppColorSchemeProvider.tsx`
- **Änderung**: Im iframe wird ein vom Host gesetztes Cookie `mb-embed-color-scheme` (`light`/`dark`)
  berücksichtigt; ohne Cookie bleibt es beim bisherigen `light`
- **Grund**: Der einbettende Host (Analytics Studio) schaltet hell/dunkel zentral um, ohne die
  persönliche Einstellung eines Nutzers zu überschreiben

#### `Dockerfile`
- **Änderung**: fehlende Zeilenfortsetzung (`\`) in der Installationszeile ergänzt — eine Zeile
- **Grund**: Bauen des Images aus dem Quellbaum

## Neue Dateien

| Datei | Beschreibung |
|-------|-------------|
| `src/metabase/sso/local.clj` | Custom-SSO-Endpunkt für Keycloak-Integration |
| `test/metabase/sso/local_test.clj` | Tests dazu (Signatur, Ablauf, `safe-return-to`, Cookie-Vorrang, Gruppen-Abgleich) — fehlte in dieser Liste bis 04.08.2026 |
| `IBYKUS_CHANGES.md` | Dieses Dokument |

## Umgebungsvariablen

| Variable | Beschreibung |
|----------|-------------|
| `MB_SSO_LOCAL_SECRET` | Shared Secret für JWT-Signatur (HS256) |
| `MB_EMBEDDING_APP_ORIGIN` | Erlaubter Origin für iframe-Einbettung (z.B. `*`) |

## Build

**Reihenfolge einhalten: erst das Git-Tag, dann bauen — mit genau diesem Namen.**

```bash
git tag v0.59.5.5-ibykus                      # 1. Tag auf den zu bauenden Commit
docker build -t metabase-ibykus:v0.59.5.5-ibykus \
  --build-arg VERSION=v0.59.5.5-ibykus .      # 2. derselbe Name als Build-Argument
```

Die Version kommt **ausschließlich** aus `--build-arg VERSION` und wird nicht aus Git abgeleitet.
Sie kann deshalb von dem abweichen, was tatsächlich im Image steckt — und genau das ist passiert:

> **Stolperstein (aufgefallen am 30.07.).** Die laufende Instanz meldete unter
> `/api/session/properties` den Tag `v0.59.5.4-ibykus` und den Hash `197a0b2`. In Git gab es
> weder diesen Tag noch enthielt der Commit `197a0b2` den damals schon committeten
> Open-Redirect-Fix. Der Schluss „der Fix ist nicht im Image" war naheliegend — und **falsch**:
> ein Funktionstest am laufenden Stand zeigte, dass `//evil.example` bereits abgewiesen wurde.
> Gebaut worden war also ein neuerer Stand, nur trugen Tag und Hash im Image das nicht.
>
> **Lehre:** Die Versionsangabe im Image ist ein *Vorsatz*, kein Beweis. Für die Frage „steckt
> Änderung X drin?" gilt der Funktionstest gegen die laufende Instanz — nicht die
> Selbstauskunft. Und damit die Angabe künftig doch trägt: Tag zuerst, Name identisch,
> nichts Uncommittetes im Baum.

## Lizenz

Dieses Werk ist lizenziert unter der GNU Affero General Public License v3.0.
Siehe [LICENSE-AGPL.txt](LICENSE-AGPL.txt) für den vollständigen Lizenztext.

Der Quellcode dieser modifizierten Version ist verfügbar unter:
`https://github.com/fwibyk/metabase`
