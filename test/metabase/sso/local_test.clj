(ns metabase.sso.local-test
  "Tests fuer die ibykus-Erweiterung /auth/sso-local.

  Schwerpunkt ist die Pruefung des Redirect-Ziels (`safe-return-to`). Sie ist die Stelle, an der
  ein Fehler nicht auffaellt: der Login funktioniert weiter, nur landet der Nutzer nach dem
  erfolgreichen SSO auf einer fremden Seite (CWE-601). Deshalb ist jeder Umgehungsweg hier als
  eigener Fall festgehalten."
  (:require
   [clojure.test :refer :all]
   [metabase.sso.local :as sso.local]))

(def ^:private safe-return-to #'sso.local/safe-return-to)
(def ^:private handle-sso-local #'sso.local/handle-sso-local)
(def ^:private sso-token-cookie @#'sso.local/sso-token-cookie)

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
           lassen"
    (let [resp (handle-sso-local {:params  {:return_to "/"}
                                  :cookies {sso-token-cookie {:value "abgelaufen"}}})
          ck   (get-in resp [:cookies sso-token-cookie])]
      (is (= 401 (:status resp)))
      (is (= "" (:value ck)))
      (is (= 0 (:max-age ck)))
      (is (= "/auth/sso-local" (:path ck))
          "Der Loesch-Pfad muss dem Setz-Pfad entsprechen, sonst bleibt das Cookie liegen"))))

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
