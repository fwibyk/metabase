(ns metabase.sso.local
  "Custom OSS SSO endpoint for iframe embedding via Keycloak.
   Validates a signed JWT and creates a Metabase session with cookie."
  (:require
   [clojure.string :as str]
   [compojure.core :refer [GET]]
   [java-time.api :as t]
   [environ.core :as env]
   [metabase.permissions.core :as perms]
   [metabase.request.cookies :as cookies]
   [metabase.session.models.session :as session.model]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [ring.util.response :as response]
   [toucan2.core :as t2])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Base64)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

;;; ---- JWT validation -------------------------------------------------------

(defn- base64url-decode ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) ^String s))

(defn- hmac-sha256-b64url ^String [^String secret ^String data]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256")))]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                     (.doFinal mac (.getBytes data StandardCharsets/UTF_8)))))

(defn- validate-jwt
  "Validates a signed HS256 JWT. Returns parsed claims map (string keys) or nil."
  [token secret]
  (when (and (string? token) (string? secret) (not (str/blank? secret)))
    (let [parts (str/split token #"\." 3)]
      (when (= 3 (count parts))
        (let [[header payload sig] parts
              expected (hmac-sha256-b64url secret (str header "." payload))]
          (when (= sig expected)
            (try
              (let [payload-json (String. (base64url-decode payload) StandardCharsets/UTF_8)
                    claims       (json/decode payload-json)]
                (when-let [exp (get claims "exp")]
                  (when (> (long exp) (quot (System/currentTimeMillis) 1000))
                    claims)))
              (catch Exception e
                (log/warn e "Failed to parse SSO JWT payload")
                nil))))))))

;;; ---- User & group management ----------------------------------------------

;; Schlüssel in `login_attributes`, unter dem die unveränderliche Keycloak-Kennung liegt.
;;
;; Warum dort und nicht in einer eigenen Spalte: `core_user` gehört Metabase, seine Schemaänderungen
;; laufen über Liquibase. Eine Fork-eigene Spalte wäre bei jedem Upgrade im Weg. `login_attributes`
;; ist ein für genau solche Zusatzmerkmale gedachtes Feld und überlebt Upgrades.
(def ^:private kc-sub-attr "keycloak_sub")

(defn- user-mit-sub [sub]
  (when-not (str/blank? sub)
    (first (filter #(= sub (get (:login_attributes %) kc-sub-attr))
                   (t2/select [:model/User :id :is_active :email :first_name :last_name :login_attributes]
                              :sso_source "sso")))))

(defn- sub-schreiben! [user-id sub]
  (let [vorhanden (or (:login_attributes (t2/select-one [:model/User :login_attributes] :id user-id)) {})]
    (t2/update! :model/User user-id {:login_attributes (assoc vorhanden kc-sub-attr sub)})))

(defn- zuordnung
  "Die **Entscheidung** über die Kontozuordnung — ohne Datenbankzugriff, damit sie prüfbar ist.

  Diese Funktion ist absichtlich von `find-or-create-user!` getrennt: die Regel ist der
  sicherheitsrelevante Teil, und ein Test dafür soll keine Datenbank brauchen. Alles, was hier
  hereingegeben wird, hat der Aufrufer vorher nachgesehen.

  Mögliche Antworten: `:vorhanden`, `:neu`, `:altkonto`, `:fremde-kennung`, `:unbestaetigt`."
  [sub email-verified? konto-per-sub konto-per-mail]
  (let [kennung-am-konto (get (:login_attributes konto-per-mail) kc-sub-attr)]
    (cond
      (some? konto-per-sub)                                   :vorhanden
      (nil? konto-per-mail)                                   :neu
      (and (some? kennung-am-konto)
           (not= kennung-am-konto sub))                       :fremde-kennung
      (not email-verified?)                                   :unbestaetigt
      :else                                                   :altkonto)))

(defn- find-or-create-user!
  "Ordnet dem Aufrufer sein Metabase-Konto zu — **über die unveränderliche Keycloak-Kennung**, nicht
  über die E-Mail-Adresse. Die Regel selbst steht in [[zuordnung]].

  Gibt `{:user … }` zurück oder `{:fehler \"…\"}`, wenn die Zuordnung abgelehnt wird.

  **Warum diese Umstellung (04.08.2026).** Vorher entschied allein die Adresse. Das war übernehmbar,
  und es ist am laufenden System belegt worden: Kollege A meldet sich an und bekommt Konto 12; A
  scheidet aus, sein Keycloak-Konto wird gelöscht, das Metabase-Konto bleibt (Metabase löscht keine
  Nutzer); B setzt die eigene Adresse auf die freigewordene — was jeder selbst darf — und landet beim
  nächsten Aufruf auf Konto 12, mitsamt persönlicher Sammlung. Dieselbe Lehre wie im Juli bei
  `datasets.owner_sub`: die Identität muss an dem hängen, was sich nicht ändern lässt.

  **Die drei Fälle:**

  1. Konto mit dieser Kennung vorhanden → das ist es. Eine abweichende Adresse wird nachgezogen (der
     Nutzer hat sie rechtmäßig geändert).
  2. Keine Kennung, aber ein Konto mit dieser Adresse → **Altkonto**. Es wird übernommen und die
     Kennung nachgetragen, aber **nur bei bestätigter Adresse**. Keycloak setzt `emailVerified` auf
     `false`, sobald jemand seine Adresse selbst ändert (gemessen) — genau daran scheitert der
     Übernahmeversuch, während der Bestandsnutzer mit seiner gewachsenen Adresse durchgeht. Die
     Umstellung heilt den Bestand also durch Benutzung, ohne Datenmigration.
  3. Nichts vorhanden → neues Konto mit Kennung.

  Ein Konto, dessen Kennung **abweicht**, wird nie übernommen — dann kollidieren zwei Identitäten auf
  einer Adresse, und Weitergeben wäre die falsche Antwort."
  [sub email first-name last-name email-verified?]
  (let [per-sub  (user-mit-sub sub)
        per-mail (when (nil? per-sub)
                   (t2/select-one [:model/User :id :is_active :email :login_attributes]
                                  :%lower.email (str/lower-case email)))]
    (case (zuordnung sub email-verified? per-sub per-mail)
      :vorhanden
      ;; Adresse UND Namen nachziehen. Das Nachziehen der Namen lag bis zum 04.08.2026 in der
      ;; Webapp (`ensureMetabaseUser`), die dafuer per Admin-API nach der Adresse suchte und ein
      ;; Konto anlegte, wenn sie keins fand -- ohne Kennung. Damit haette sie genau die Konten
      ;; erzeugt, die unten nur mit bestaetigter Adresse uebernommen werden duerfen. Zustaendig ist
      ;; jetzt diese Stelle, an der die Identitaet ohnehin entschieden wird.
      (let [neu (cond-> {}
                  (not= (str/lower-case (or (:email per-sub) "")) (str/lower-case email))
                  (assoc :email email)
                  (not= (or (:first_name per-sub) "") (or first-name ""))
                  (assoc :first_name (or first-name ""))
                  (not= (or (:last_name per-sub) "") (or last-name ""))
                  (assoc :last_name (or last-name "")))]
        (when (seq neu)
          (log/infof "SSO local: Konto %s wird nachgezogen (%s)" (:id per-sub) (keys neu))
          (t2/update! :model/User (:id per-sub) neu))
        {:user per-sub})

      :fremde-kennung
      (do (log/warnf "SSO local: abgelehnt — Konto %s gehoert einer anderen Keycloak-Kennung"
                     (:id per-mail))
          {:fehler "Dieses Konto gehoert zu einer anderen Anmeldung."})

      :unbestaetigt
      (do (log/warnf (str "SSO local: abgelehnt — Altkonto %s soll uebernommen werden, die "
                          "Adresse ist aber nicht bestaetigt") (:id per-mail))
          {:fehler (str "Ihre E-Mail-Adresse ist nicht bestaetigt. Ein bestehendes Konto mit "
                        "dieser Adresse kann deshalb nicht zugeordnet werden.")})

      :altkonto
      (do (log/infof "SSO local: Altkonto %s wird der Keycloak-Kennung zugeordnet" (:id per-mail))
          (sub-schreiben! (:id per-mail) sub)
          {:user per-mail})

      :neu
      (do
        (log/infof "SSO local: creating new Metabase user for %s" email)
        {:user (t2/insert-returning-instance!
                [:model/User :id :is_active]
                {:email            email
                 :first_name       (or first-name "")
                 :last_name        (or last-name "")
                 :sso_source       "sso"
                 :login_attributes {kc-sub-attr sub}
                 :is_active        true})}))))

(defn- ensure-group! [group-name]
  (or (t2/select-one :permissions_group :name group-name)
      (t2/insert-returning-instance! :permissions_group {:name group-name})))

;; Gruppen, die aus einem Token NIE ansprechbar sind (Audit 04.08.2026).
;;
;; `ensure-group!` sucht per Namen — bei "Administrators" fand es die EINGEBAUTE Gruppe, und die
;; Mitgliedschaft wurde angelegt. Diese Gruppe haelt die Berechtigung "/" (Voll-Lese/Schreibzugriff
;; auf alle Inhalte, auch ohne `is_superuser`). Gleichzeitig schuetzt der Abgleich unten genau diese
;; Gruppe vor dem Entfernen — eine einmal so entstandene Mitgliedschaft blieb also dauerhaft, auch
;; nachdem die Rolle in Keycloak entzogen war.
;;
;; Ein Nutzer kann sich die Rolle nicht selbst geben (der Claim ist signiert und stammt aus
;; Keycloak). Aber eine Keycloak-Rolle, die zufaellig so heisst, oder eine einmalige Fehlzuweisung
;; haette dauerhaft gewirkt. Rechte auf dieser Ebene gehoeren nicht an einen Rollennamen.
(def ^:private gesperrte-gruppennamen #{"administrators" "all users"})

(defn- sync-groups!
  "Bringt die Metabase-Gruppen des Nutzers auf die Rollen aus dem Token.

  UEBER DIE MODELL-FUNKTIONEN, nicht ueber die Rohtabelle (Audit 04.08.2026). Vorher schrieb dies
  direkt auf `:permissions_group_membership` — genau das verbietet der Upstream ausdruecklich
  (siehe `permissions_group_membership.clj`), weil dort die Synchronisation der Kennzeichen haengt:
  `is_superuser` und `is_data_analyst` werden beim Anlegen/Entfernen einer Mitgliedschaft
  mitgefuehrt. Folge des Rohzugriffs: ein Nutzer, der regulaer in \"Data Analysts\" war, verlor beim
  naechsten SSO-Login die Mitgliedschaft, behielt aber das Kennzeichen `true` — ein erhoehtes Recht
  ohne sichtbare Gruppe, ueber die Oberflaeche nicht mehr entziehbar."
  [user-id groups]
  (let [erlaubte   (remove #(contains? gesperrte-gruppennamen (str/lower-case (str %))) groups)
        magic-ids  (t2/select-fn-set :id :permissions_group
                                     :name [:in ["All Users" "Administrators"]])
        ist-jetzt  (t2/select-fn-set :group_id :model/PermissionsGroupMembership :user_id user-id)
        soll-ids   (into #{} (keep (fn [name] (:id (ensure-group! name))) erlaubte))
        zu-viel    (remove #(contains? (or magic-ids #{}) %) (remove soll-ids ist-jetzt))
        fehlend    (remove ist-jetzt soll-ids)]
    (when-let [abgelehnt (seq (remove (set erlaubte) groups))]
      (log/warnf "SSO local: Gruppen aus dem Token abgelehnt (nicht ueber Rollen setzbar): %s"
                 (pr-str abgelehnt)))
    (doseq [gid zu-viel]
      (perms/remove-user-from-group! user-id gid))
    (when (seq fehlend)
      (perms/add-users-to-groups! (map (fn [gid] {:user user-id :group gid}) fehlend)))))

;;; ---- Color scheme cookie (embedded light/dark) ----------------------------
;; Der Einbettungs-Host (Analytics Studio) steuert Hell/Dunkel zentral ueber den
;; &scheme=-Param. Wir persistieren bewusst NICHT das user-local color-scheme (das
;; wuerde die persoenliche Standalone-Praeferenz ueberschreiben), sondern setzen ein
;; von JS lesbares Cookie, das der eingebettete Client (AppColorSchemeProvider)
;; auswertet, um im iframe nicht hart auf hell zu erzwingen.
(def ^:private embed-scheme-cookie "mb-embed-color-scheme")
(def ^:private allowed-schemes #{"light" "dark"})

;;; ---- Token-Uebergabe per Cookie -------------------------------------------
;;
;; Bis zum 30.07.2026 kam der SSO-Token als Query-Parameter (?token=...). Fuer ein Geheimnis
;; ist die URL der falsche Ort: der Reverse-Proxy protokolliert die vollstaendige Request-Zeile
;; (nginx-Standardformat "combined"), damit stand ein gueltiger Sitzungs-Erzeuger im
;; Zugriffsprotokoll. Wer es lesen konnte, konnte sich innerhalb der Gueltigkeit eine
;; Metabase-Sitzung IM NAMEN DIESES NUTZERS ausstellen — ohne dessen Passwort und ohne Keycloak
;; (BSI CON.10.A7: keine vertraulichen Daten in URLs).
;;
;; Stattdessen setzt der einbettende Host (Analytics Studio) den Token als HttpOnly-Cookie und
;; laedt den iframe OHNE Parameter. Das geht, weil Cookies den PORT nicht unterscheiden: die
;; Webapp auf :443 und Metabase auf :8443 sind derselbe Host, also dieselbe Cookie-Domain.
;; `Path=/auth/sso-local` sorgt dafuer, dass das Cookie ausschliesslich an diesen Endpunkt geht
;; und nicht an jede Metabase-Anfrage.
;;
;; VORAUSSETZUNG, die bewusst nicht abgefedert wird: Metabase muss unter demselben Hostnamen
;; erreichbar sein wie die einbettende Anwendung. Ist das nicht so, kommt kein Cookie an und der
;; Login schlaegt mit 401 fehl — laut und sofort. Ein Rueckfall auf den Parameter waere die
;; schlechtere Wahl: er wuerde die Luecke stillschweigend wieder oeffnen.
;; ZWEI NAMEN, EINE UEBERGABE — `__Secure-`-Praefix ueber HTTPS (ASVS V3.3.1, 03.08.2026).
;;
;; Der Praefix ist keine Namenskonvention, sondern eine Regel, die der BROWSER durchsetzt: ein
;; Cookie mit diesem Namen wird nur angenommen, wenn es `Secure` traegt und ueber eine sichere
;; Verbindung gesetzt wurde. Das schliesst eine Luecke, die `Secure` allein offen laesst — ein
;; Angreifer in der Leitung kann ueber eine UNVERSCHLUESSELTE Verbindung zum selben Host ein
;; Cookie SETZEN (Cookies unterscheiden das Protokoll nicht) und damit die Uebergabe ueberschreiben.
;;
;; `__Host-` waere hier falsch: dieser Praefix verlangt `Path=/` und wuerde die Pfadbindung auf
;; /auth/sso-local unmoeglich machen — also genau die Eigenschaft, die verhindert, dass der Token
;; an jede Metabase-Anfrage mitgeht.
;;
;; UEBER HTTPS GILT NUR DER PRAEFIXIERTE NAME. Hier stand bis zum 04.08.2026, beide Namen wuerden
;; "dauerhaft" gelesen und der praefixierte habe Vorrang. Das war zu schwach: ein Vorrang wirkt nur,
;; solange BEIDE Cookies vorliegen — und das Uebergabe-Cookie lebt rund eine Sekunde. In jedem
;; anderen Moment wurde ein untergeschobenes `mb-sso-token` genommen (Sitzungsuebernahme, CWE-384).
;; Der unpraefixierte Name bleibt ausschliesslich fuer die lokale Entwicklung ohne TLS.
(def ^:private sso-token-cookie-secure "__Secure-mb-sso-token")
(def ^:private sso-token-cookie-plain "mb-sso-token")

(defn- sso-token
  "Der Uebergabe-Token aus dem Cookie.

  UEBER HTTPS GILT NUR DER PRAEFIXIERTE NAME (Audit 04.08.2026). Die erste Fassung las beide
  gleichrangig, mit dem praefixierten als Vorrang. Das schuetzt nur, solange BEIDE vorliegen — und
  das ist fast nie der Fall: das Uebergabe-Cookie lebt rund eine Sekunde (60 s Laufzeit, Loeschung
  beim Einloesen). In jedem anderen Moment wurde ein untergeschobenes `mb-sso-token` genommen.

  Damit war eine Sitzungsuebernahme moeglich (CWE-384): wer ueber `http://<host>` oder eine
  Nachbar-Subdomaene ein `mb-sso-token` mit dem Token SEINES Kontos setzt, laesst das Opfer in
  seiner Sitzung arbeiten — der Pruefpfad schreibt die Handlungen dann dem falschen Nutzer zu.

  Der unpraefixierte Name bleibt ausschliesslich fuer die lokale Entwicklung ohne TLS, wo der
  Browser ein `__Secure-`-Cookie verwirft. `u/https?` wertet dafuer `X-Forwarded-Proto` aus — genau
  die Kopfzeile, die der Proxy ueberschreibend setzt."
  [request]
  (let [mit-praefix (get-in request [:cookies sso-token-cookie-secure :value])]
    (cond
      (not (str/blank? mit-praefix)) mit-praefix
      (u/https? request)             nil
      :else                          (get-in request [:cookies sso-token-cookie-plain :value]))))

(defn- clear-sso-token-cookie
  "Loescht das Uebergabe-Cookie — und zwar in JEDEM Fall, auch bei Fehlern, und unter BEIDEN Namen.

  Damit ist die Uebergabe einmalig: ein Neu-Laden erzeugt keine zweite Sitzung aus demselben
  Token, und eine abgefangene Kopie ist wertlos, sobald der rechtmaessige Aufruf durch ist.
  Auch der Fehlerfall wird geloescht, sonst laeuft ein ungueltiges Cookie bei jedem Aufruf
  erneut ins 401 und die Ursache bleibt im Verborgenen.

  Beide Namen, weil sonst nach der Umstellung ein Cookie unter dem alten Namen im Browser
  liegenbleibt: es wuerde bei jedem Aufruf dieses Endpunkts mitgesendet und beim naechsten Fehler
  des praefixierten Namens still eingelesen. Ein Rest, der nur deshalb unauffaellig ist, weil er
  gerade nicht dran kommt."
  [resp]
  (let [attrs {:value "" :path "/auth/sso-local" :http-only true :same-site :lax :max-age 0}]
    (-> resp
        (assoc-in [:cookies sso-token-cookie-secure] (assoc attrs :secure true))
        (assoc-in [:cookies sso-token-cookie-plain] attrs))))

;;; ---- Session creation -----------------------------------------------------

(defn- create-session! [user-id]
  (let [session-key (session.model/generate-session-key)
        session-id  (session.model/generate-session-id)]
    (t2/insert! :model/Session {:id          session-id
                                :session_key session-key
                                :user_id     user-id})
    {:key  session-key
     :type :normal}))

;;; ---- Handler --------------------------------------------------------------

(defn- safe-return-to
  "Nur echte lokale Pfade als Redirect-Ziel zulassen, sonst die Startseite.

  Zwei Dinge, die einzeln jeweils nicht reichen:

  1. `starts-with \"/\"` allein laesst protokoll-relative Ziele durch: \"//evil.com\" und
     \"/\\evil.com\" beginnen ebenfalls mit \"/\", fuehren aber auf eine fremde Domain
     (CWE-601).

  2. Der Wert muss VOR der Pruefung so gelesen werden, wie ein Browser ihn liest. Browser
     entfernen beim Parsen einer URL Tabulator, LF und CR (WHATWG URL Standard, \"remove all
     ASCII tab or newline\"). \"/<TAB>/evil.com\" besteht damit eine Pruefung auf \"//\", der
     Browser sieht danach aber \"//evil.com\" -> die Pruefung bewertet einen anderen Wert als
     den, der wirkt. Am laufenden System nachgewiesen (30.07.): `return_to=/%09/evil.example`
     erzeugte `Location: /<TAB>/evil.example`.

  Deshalb werden diese Zeichen zuerst entfernt und anschliessend wird der *bereinigte* Wert
  geprueft und zurueckgegeben — nie der Rohwert, sonst normalisiert der Browser erneut."
  [rt]
  (let [seen (when (string? rt) (str/replace rt #"[\t\n\r]" ""))]
    (if (and seen
             (str/starts-with? seen "/")
             (not (str/starts-with? seen "//"))
             (not (str/starts-with? seen "/\\")))
      seen
      "/")))

(defn- handle-sso-local [request]
  ;; Der Token kommt aus dem Cookie, NICHT aus den Parametern (siehe oben). `return_to` und
  ;; `scheme` bleiben Parameter — beides ist kein Geheimnis.
  (let [token     (sso-token request)
        return-to (safe-return-to (get-in request [:params :return_to]))
        scheme    (get-in request [:params :scheme])
        secret    (env/env :mb-sso-local-secret)]
    (clear-sso-token-cookie
     (if-let [claims (validate-jwt token secret)]
       (let [email      (get claims "email")
             sub        (get claims "sub")
             verified?  (true? (get claims "email_verified"))
             first-name (get claims "first_name")
             last-name  (get claims "last_name")
             groups     (get claims "groups" [])]
         (cond
           (str/blank? email)
           {:status 400 :body "Missing email in SSO token" :headers {"Content-Type" "text/plain"}}

           ;; Ohne Kennung keine Anmeldung. Ein Rückfall auf die Adresse wäre bequem und würde die
           ;; geschlossene Lücke wieder öffnen — und zwar unsichtbar, denn er sähe wie Erfolg aus.
           (str/blank? sub)
           (do (log/warn "SSO local: abgelehnt — Token ohne sub (Keycloak-Kennung)")
               {:status 400 :body "Missing sub in SSO token" :headers {"Content-Type" "text/plain"}})

           :else
           (let [ergebnis (find-or-create-user! sub email first-name last-name verified?)]
            (if-let [fehler (:fehler ergebnis)]
              {:status 403 :body fehler :headers {"Content-Type" "text/plain; charset=utf-8"}}
              (try
             (let [user    (:user ergebnis)
                   _       (sync-groups! (:id user) groups)
                   session (create-session! (:id user))
                   resp    (cookies/set-session-cookies
                            request
                            (response/redirect return-to)
                            session
                            (t/zoned-date-time (t/zone-id "GMT")))]
               (if (contains? allowed-schemes scheme)
                 (assoc-in resp [:cookies embed-scheme-cookie]
                           {:value scheme :path "/" :http-only false :same-site :lax})
                 resp))
             (catch Exception e
               (log/error e "SSO local: error creating session")
               {:status 500 :body "SSO error" :headers {"Content-Type" "text/plain"}}))))))
       (do
         (log/warn "SSO local: kein, ungueltiges oder abgelaufenes Uebergabe-Cookie")
         {:status 401
          :body "Invalid or expired SSO token"
          :headers {"Content-Type" "text/plain"}})))))

(def routes
  (GET "/" request (handle-sso-local request)))
