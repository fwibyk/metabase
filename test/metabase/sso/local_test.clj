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
