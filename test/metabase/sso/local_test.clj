(ns metabase.sso.local-test
  "Tests fuer die ibykus-Erweiterung /auth/sso-local.

  Schwerpunkt ist die Pruefung des Redirect-Ziels (`safe-return-to`). Sie ist die Stelle, an der
  ein Fehler nicht auffaellt: der Login funktioniert weiter, nur landet der Nutzer nach dem
  erfolgreichen SSO auf einer fremden Seite (CWE-601). Deshalb ist jeder Umgehungsweg hier als
  eigener Fall festgehalten."
  (:require
   [clojure.test :refer :all]
   [metabase.sso.local :as sso.local]
   [metabase.test :as mt]))

(def ^:private safe-return-to #'sso.local/safe-return-to)
(def ^:private handle-sso-local #'sso.local/handle-sso-local)
(def ^:private sso-token #'sso.local/sso-token)
(def ^:private cookie-secure @#'sso.local/sso-token-cookie-secure)
(def ^:private cookie-plain @#'sso.local/sso-token-cookie-plain)

;;; ---- Token-Uebergabe ------------------------------------------------------
;;
;; Diese beiden Tests halten fest, dass der Token NICHT mehr aus der URL gelesen wird. Beide
;; kommen ohne Datenbank aus, weil sie den Fehlerpfad pruefen — und genau der ist die
;; Sicherheitsaussage: ein Token in den Parametern darf keine Sitzung erzeugen.

(deftest token-aus-parametern-wird-ignoriert-test
  (testing "ein Token als Query-Parameter erzeugt keine Sitzung mehr — sonst waere der
           Standardpfad wieder die URL, und damit stuende das Geheimnis im Zugriffsprotokoll
           des Proxys (CON.10.A7)"
    (let [resp (handle-sso-local {:params  {:token "beliebig.nicht.gueltig" :return_to "/"}
                                  :cookies {}})]
      (is (= 401 (:status resp))))))

(deftest uebergabe-cookie-wird-immer-geloescht-test
  (testing "auch im Fehlerfall — ein liegengebliebenes Cookie liefe bei jedem Aufruf erneut
           ins 401, und ein erfolgreich eingeloester Token darf sich nicht wiederverwenden
           lassen. Geloescht wird unter BEIDEN Namen, sonst bleibt nach der Umstellung ein
           Cookie unter dem alten Namen liegen (ASVS V3.3.1)"
    (let [resp (handle-sso-local {:params  {:return_to "/"}
                                  :cookies {cookie-plain {:value "abgelaufen"}}})]
      (is (= 401 (:status resp)))
      (doseq [name [cookie-secure cookie-plain]]
        (let [ck (get-in resp [:cookies name])]
          (is (= "" (:value ck)) (str "Wert nicht geleert: " name))
          (is (= 0 (:max-age ck)) (str "max-age nicht 0: " name))
          (is (= "/auth/sso-local" (:path ck))
              (str "Der Loesch-Pfad muss dem Setz-Pfad entsprechen, sonst bleibt das Cookie"
                   " liegen: " name)))))))

;;; ---- Welcher Cookie-Name gilt? --------------------------------------------
;;
;; Der einbettende Host waehlt den Namen nach Protokoll: ueber HTTPS mit `__Secure-`-Praefix, bei
;; lokaler Entwicklung ohne (ein `__Secure-`-Cookie ohne HTTPS wuerde der Browser verwerfen).
;; Diese Seite muss beide lesen — und die Vorrangregel ist die Sicherheitsaussage.

(deftest praefix-name-wird-gelesen-test
  (testing "ueber HTTPS setzt der einbettende Host den praefixierten Namen"
    (is (= "abc" (sso-token {:cookies {cookie-secure {:value "abc"}}})))))

(deftest unpraefixierter-name-bleibt-lesbar-test
  (testing "lokale Entwicklung ohne HTTPS — sonst zeigt sie keine Analysen mehr an"
    (is (= "abc" (sso-token {:scheme :http :cookies {cookie-plain {:value "abc"}}})))))

(deftest praefix-name-hat-vorrang-test
  (testing "liegen beide vor, gilt der praefixierte"
    (is (= "echt" (sso-token {:cookies {cookie-secure {:value "echt"}
                                        cookie-plain  {:value "untergeschoben"}}})))))

(deftest ueber-https-gilt-nur-der-praefixierte-name-test
  (testing "Der Vorrang allein genuegt nicht: er wirkt nur, solange BEIDE Cookies vorliegen — und
           das Uebergabe-Cookie lebt rund eine Sekunde. In jedem anderen Moment wurde ein
           untergeschobenes mb-sso-token genommen (Sitzungsuebernahme, CWE-384). Ueber HTTPS gilt
           deshalb nur der praefixierte Name."
    (is (nil? (sso-token {:headers {"x-forwarded-proto" "https"}
                          :cookies {cookie-plain {:value "untergeschoben"}}}))))
  (testing "ohne TLS bleibt der alte Name lesbar — sonst gaebe es lokal keine Anmeldung"
    (is (= "abc" (sso-token {:scheme :http
                             :cookies {cookie-plain {:value "abc"}}}))))
  (testing "der praefixierte Name wirkt auch ueber HTTPS"
    (is (= "abc" (sso-token {:headers {"x-forwarded-proto" "https"}
                             :cookies {cookie-secure {:value "abc"}}})))))

(deftest leerer-praefix-name-verdeckt-den-anderen-nicht-test
  (testing "ein vorhandenes, aber leeres Praefix-Cookie darf den gueltigen Wert nicht verdecken —
           genau das entsteht durch das Loeschen unter beiden Namen (ohne TLS, s.o.)"
    (is (= "abc" (sso-token {:scheme :http
                             :cookies {cookie-secure {:value ""}
                                       cookie-plain  {:value "abc"}}})))))

(deftest ohne-cookie-kein-token-test
  (testing "ohne Cookie gibt es keinen Token — und damit keine Sitzung"
    (is (nil? (sso-token {:cookies {}})))))

(deftest safe-return-to-erlaubt-lokale-pfade-test
  (testing "gewoehnliche Ziele bleiben unveraendert"
    (are [rt] (= rt (safe-return-to rt))
      "/"
      "/question/1"
      "/dashboard/2?tab=3"
      "/browse/databases"
      "/question#hash-mit-frage"
      ;; kodierter Schraegstrich bleibt Teil des Pfades und ist damit harmlos
      "/%2Fevil.example")))

(deftest safe-return-to-weist-protokoll-relative-ziele-ab-test
  (testing "protokoll-relative Ziele fuehren aus der Anwendung heraus"
    (are [rt] (= "/" (safe-return-to rt))
      "//evil.example"
      "////evil.example"
      "/\\evil.example"
      "/\\/evil.example")))

(deftest safe-return-to-weist-vom-browser-entfernte-zeichen-ab-test
  (testing "TAB, LF und CR werden vom Browser aus der URL entfernt (WHATWG URL Standard) —
           die Pruefung muss den Wert deshalb OHNE diese Zeichen bewerten, sonst wird aus
           '/<TAB>/evil.example' im Browser '//evil.example'"
    (are [rt] (= "/" (safe-return-to rt))
      "/\t/evil.example"
      "/\n/evil.example"
      "/\r/evil.example"
      "/\t\t//evil.example"
      "/\r\n/evil.example"
      "/\\\t/evil.example"))
  (testing "ein Tabulator mitten im Pfad wird entfernt, nicht durchgereicht — der Browser
           saehe sonst ein anderes Ziel als die Pruefung"
    (is (= "/question/1" (safe-return-to "/question\t/1")))))

(deftest safe-return-to-weist-absolute-und-ungueltige-ziele-ab-test
  (testing "alles, was kein lokaler Pfad ist, landet auf der Startseite"
    (are [rt] (= "/" (safe-return-to rt))
      "https://evil.example"
      "http://evil.example/pfad"
      "evil.example"
      "javascript:alert(1)"
      ""
      nil
      42
      {})))

;;; ---- Gruppen aus dem Token ------------------------------------------------
;;
;; `ensure-group!` sucht per Namen; bei "Administrators" fand es die EINGEBAUTE Gruppe, und die
;; Mitgliedschaft blieb dauerhaft, weil der Abgleich genau diese Gruppe vor dem Entfernen schuetzt.
;; Rechte auf dieser Ebene gehoeren nicht an einen Rollennamen.

(def ^:private gesperrte @#'sso.local/gesperrte-gruppennamen)

(deftest magische-gruppennamen-sind-gesperrt-test
  (testing "Administrators und All Users sind aus einem Token nicht ansprechbar"
    (is (contains? gesperrte "administrators"))
    (is (contains? gesperrte "all users")))
  (testing "gewoehnliche Rollennamen sind es sehr wohl"
    (is (not (contains? gesperrte "analyst")))
    (is (not (contains? gesperrte "pruefer")))))

;; Eine Rolle, zu der es noch keine Gruppe gibt (09.11.2026). Ueber die Rohtabelle lief der
;; entity-id-Hook nicht, und seit 0.65 ist die Spalte NOT NULL -- die Uebergabe brach mit 500 ab.
(def ^:private ensure-group! #'sso.local/ensure-group!)

(deftest neue-gruppe-aus-rolle-bekommt-entity-id-test
  (mt/with-model-cleanup [:model/PermissionsGroup]
    (let [gruppe (ensure-group! "zz-neue-rolle-aus-dem-token")]
      (testing "die Gruppe entsteht und traegt eine entity_id"
        (is (pos-int? (:id gruppe)))
        (is (string? (:entity_id gruppe))))
      (testing "ein zweiter Aufruf findet dieselbe Gruppe statt eine neue anzulegen"
        (is (= (:id gruppe) (:id (ensure-group! "zz-neue-rolle-aus-dem-token"))))))))

;;; ---- Kontozuordnung ueber die Keycloak-Kennung (04.08.2026) ----------------
;;
;; Diese Tests halten einen belegten Angriffsweg fest. Vorher entschied allein die E-Mail-Adresse,
;; wer welches Metabase-Konto bekommt. Am laufenden System nachgewiesen: Kollege A meldet sich an
;; und erhaelt Konto 12; A scheidet aus, sein Keycloak-Konto wird geloescht, das Metabase-Konto
;; bleibt (Metabase loescht keine Nutzer); B setzt die eigene Adresse auf die freigewordene -- was
;; jeder Nutzer selbst darf, `POST .../account` antwortet 204 -- und landet auf Konto 12, samt
;; persoenlicher Sammlung. Gegenbeleg damals: eine frische Adresse ergab Konto 13.
;;
;; Geprueft wird hier die REGEL, nicht der Datenbankzugriff: `zuordnung` ist genau deshalb von
;; `find-or-create-user!` getrennt. Der entscheidende Hebel ist `emailVerified` -- Keycloak setzt es
;; auf false, sobald jemand seine Adresse selbst aendert (am System gemessen).

(def ^:private zuordnung #'sso.local/zuordnung)
(def ^:private kc-sub-attr @#'sso.local/kc-sub-attr)

(defn- konto [id & [sub]]
  (cond-> {:id id :is_active true}
    sub (assoc :login_attributes {kc-sub-attr sub})))

(deftest zuordnung-kennung-schlaegt-adresse-test
  (testing "Ist die Kennung bekannt, zaehlt sie -- auch wenn die Adresse zu einem anderen Konto passt"
    (is (= :vorhanden (zuordnung "sub-1" true (konto 7 "sub-1") (konto 12 "sub-2"))))))

(deftest zuordnung-neues-konto-test
  (testing "Kein Konto zur Kennung und keins zur Adresse -> neu anlegen"
    (is (= :neu (zuordnung "sub-1" true nil nil)))
    (is (= :neu (zuordnung "sub-1" false nil nil))
        "Fuer ein NEUES Konto ist die Bestaetigung unerheblich -- es wird nichts geerbt")))

(deftest zuordnung-altkonto-nur-mit-bestaetigter-adresse-test
  (testing "Altkonto ohne Kennung wird uebernommen, wenn die Adresse bestaetigt ist"
    (is (= :altkonto (zuordnung "sub-1" true nil (konto 12)))))
  (testing "und NICHT, wenn sie es nicht ist -- das ist der belegte Uebernahmeweg"
    (is (= :unbestaetigt (zuordnung "sub-1" false nil (konto 12))))))

(deftest zuordnung-fremdes-konto-wird-nie-uebernommen-test
  (testing "Traegt das Konto eine ANDERE Kennung, wird es auch mit bestaetigter Adresse abgelehnt"
    (is (= :fremde-kennung (zuordnung "sub-1" true nil (konto 12 "sub-2"))))
    (is (= :fremde-kennung (zuordnung "sub-1" false nil (konto 12 "sub-2")))))
  (testing "die eigene Kennung am Adresskonto ist dagegen kein Fremdkonto"
    (is (= :altkonto (zuordnung "sub-1" true nil (konto 12 "sub-1")))
        "gleiche Kennung: nur der Sub-Index hat nicht gegriffen, das Konto gehoert dem Aufrufer")))
