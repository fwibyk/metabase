(ns metabase.ibykus.api-test
  "Tests fuer /api/ibykus (Berichtsexport des Analytics Studio, Change 2026-11-06-mcp-sql-und-berichte).

  Die Zusicherung ist: Liste und Zeichnen laufen mit den Rechten des Metabase-Kontos der PERSON, nicht mit denen
  des aufrufenden Superusers -- und `eingebettet` geht nur mit verriegeltem Wert."
  (:require
   [clojure.test :refer :all]
   [metabase.ibykus.berichte]
   [metabase.permissions.core :as perms]
   [metabase.query-processor.core :as qp]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]))

;; Ohne Initialisierung haengt das Ergebnis von der Reihenfolge ab: legt der erste Test ein Konto an, bevor ein
;; HTTP-Aufruf Metabase hochgefahren hat, scheitert er mit "Metabase instance has not been initialized".
(use-fixtures :once (fixtures/initialize :db :test-users :web-server))

(def ^:private sub "kc-ibykus-test-1")

(defn- person-attrs [group-id]
  {:sso_source       :sso
   :login_attributes {"keycloak_sub" sub}
   ::group           group-id})

(deftest nur-superuser-test
  (testing "ein normales Konto darf die Endpunkte nicht rufen"
    (mt/user-http-request :rasta :post 403 "ibykus/berichte" {:keycloak_sub sub})
    (mt/user-http-request :rasta :post 403 "ibykus/zeichnen" {:keycloak_sub sub :art "frage" :id 1})))

(deftest kein-konto-test
  (testing "ohne Konto zur Kennung: leere Liste mit Kennzeichen, kein Fehler"
    (is (= {:kein_konto true :berichte []}
           (mt/user-http-request :crowberto :post 200 "ibykus/berichte" {:keycloak_sub "gibt-es-nicht"}))))
  (testing "zeichnen ohne Konto: 404 mit Code"
    (is (= "bericht.kein_konto"
           (:code (mt/user-http-request :crowberto :post 404 "ibykus/zeichnen"
                                        {:keycloak_sub "gibt-es-nicht" :art "frage" :id 1}))))))

(deftest liste-folgt-den-sammlungsrechten-test
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp [:model/PermissionsGroup {gid :id} {}
                   :model/User {uid :id} (dissoc (person-attrs gid) ::group)
                   :model/PermissionsGroupMembership _ {:user_id uid :group_id gid}
                   :model/Collection {erlaubt :id} {:name "Leistungsbericht"}
                   :model/Collection {verboten :id} {:name "Geheim"}
                   :model/Card {sichtbar :id} {:name "Sichtbar" :collection_id erlaubt
                                               :dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                   :model/Card {unsichtbar :id} {:name "Unsichtbar" :collection_id verboten
                                                 :dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
      (perms/grant-collection-read-permissions! gid erlaubt)
      (let [ids (->> (mt/user-http-request :crowberto :post 200 "ibykus/berichte" {:keycloak_sub sub})
                     :berichte (filter #(= "frage" (:art %))) (map :id) set)]
        (is (contains? ids sichtbar) "Frage aus der lesbaren Sammlung erscheint")
        (is (not (contains? ids unsichtbar)) "Frage aus der fremden Sammlung erscheint NICHT, obwohl der Aufrufer Superuser ist")))))

(deftest zeichnen-als-person-test
  (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                 :model/Card {card-id :id} {:name "Anzahl" :display :scalar
                                            :dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
    (let [antwort (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                        {:keycloak_sub sub :art "frage" :id card-id})
          karte   (first (:karten antwort))]
      (is (= "Anzahl" (:titel antwort)))
      (is (= 1 (count (:karten antwort))))
      (is (true? (:darstellbar karte)) (pr-str (dissoc karte :png)))
      (is (string? (:png karte)))
      (testing "das PNG ist ein PNG"
        (let [b (.decode (java.util.Base64/getDecoder) ^String (:png karte))]
          (is (= [-119 80 78 71] (vec (take 4 b)))))))))

(deftest zeichnen-fremde-frage-test
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                   :model/Collection {verboten :id} {}
                   :model/Card {card-id :id} {:collection_id verboten
                                              :dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
      (is (= "bericht.nicht_gefunden"
             (:code (mt/user-http-request :crowberto :post 404 "ibykus/zeichnen"
                                          {:keycloak_sub sub :art "frage" :id card-id})))))))

(deftest eingebettet-nur-mit-verriegeltem-wert-test
  (mt/with-temp [:model/Card {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
    (testing "ohne gesperrt waere es die Sicht des Superusers"
      (is (= "bericht.parameter_verriegelt"
             (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                          {:eingebettet true :art "frage" :id card-id})))))
    (testing "ein verriegelter Wert, den der Bericht nicht kennt, laesst die Abfrage NICHT ungefiltert laufen"
      (is (= "bericht.parameter_verriegelt"
             (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                          {:eingebettet true :art "frage" :id card-id
                                           :gesperrt {:zeilenfilter ["07"]}})))))))

(deftest person-und-eingebettet-schliessen-sich-aus-test
  (is (= "bericht.kontext_mehrdeutig"
         (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                      {:keycloak_sub sub :eingebettet true :art "frage" :id 1})))))

;;; Ob ein Parameter WIRKT, zeigt das PNG nicht. Deshalb ersetzen diese Tests nur das Zeichnen und geben die Zeilen
;;; zurück, die bis dorthin gekommen sind -- Ausführung, Rechte und Parameter laufen echt.

(defn- zeilen-statt-bild [thunk]
  (with-redefs-fn {#'metabase.ibykus.berichte/zeichne-karte
                   (fn [card _dashcard result]
                     {:titel (:name card) :zeilen (-> result :data :rows) :fehler (some-> (:error result) str)})}
    thunk))

(def ^:private anzahl-sql "SELECT count(*) AS n FROM venues [[WHERE price = {{preis}}]]")

(defn- anzahl-karte []
  {:name          "Anzahl je Preis"
   :display       :scalar
   :dataset_query (mt/native-query {:query         anzahl-sql
                                    :template-tags {"preis" {:id "t-preis" :name "preis" :display-name "Preis"
                                                             :type :number}}})
   :parameters    [{:id "t-preis" :slug "preis" :name "Preis" :type :number/=
                    :target [:variable [:template-tag "preis"]]}]})

(defn- erwartete-anzahl [preis]
  (-> (mt/native-query {:query (str "SELECT count(*) AS n FROM venues WHERE price = " preis)})
      qp/process-query mt/rows ffirst))

(defn- zeilen [antwort]
  (-> antwort :karten first :zeilen ffirst))

(deftest parameter-wirkt-test
  (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                 :model/Card {card-id :id} (anzahl-karte)]
    (zeilen-statt-bild
     (fn []
       (let [ohne (zeilen (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                                {:keycloak_sub sub :art "frage" :id card-id}))
             mit  (zeilen (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                                {:keycloak_sub sub :art "frage" :id card-id :parameter {:preis 1}}))]
         (is (= 100 ohne) "ohne Wert: alle Lokale")
         (is (= (erwartete-anzahl 1) mit) "mit preis 1: nur die mit Preis 1")
         (is (< mit ohne)))))))

(deftest dashboard-filter-wirkt-test
  (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                 :model/Card {card-id :id} (anzahl-karte)
                 :model/Dashboard {dash-id :id} {:name       "Lokale"
                                                 :parameters [{:id "d-preis" :slug "preis" :name "Preis"
                                                               :type :number/=}]}
                 :model/DashboardCard _ {:dashboard_id       dash-id
                                         :card_id            card-id
                                         :parameter_mappings [{:parameter_id "d-preis" :card_id card-id
                                                               :target [:variable [:template-tag "preis"]]}]}]
    (zeilen-statt-bild
     (fn []
       (is (= (erwartete-anzahl 2)
              (zeilen (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                            {:keycloak_sub sub :art "dashboard" :id dash-id
                                             :parameter {:preis 2}}))))))))

(deftest verriegelter-parameter-wirkt-test
  (mt/with-temp [:model/Card {card-id :id} (anzahl-karte)]
    (zeilen-statt-bild
     (fn []
       (is (= (erwartete-anzahl 3)
              (zeilen (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                            {:eingebettet true :art "frage" :id card-id :gesperrt {:preis 3}})))
           "eingebettet läuft mit dem verriegelten Wert, nicht ungefiltert")
       (testing "der verriegelte Wert lässt sich über `parameter` nicht überschreiben"
         (is (= "bericht.parameter_verriegelt"
                (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                             {:eingebettet true :art "frage" :id card-id
                                              :gesperrt {:preis 3} :parameter {:preis 1}})))))))))

(deftest gespeicherte-native-frage-mit-reinem-datenrecht-test
  ;; ibkefap121, 06.11.2026: die Demo-Fachabteilung darf die Tabelle sehen, aber kein eigenes SQL schreiben. In der
  ;; Oberfläche und im Dashboard läuft ihre gespeicherte native Frage, als einzelne Frage exportiert kam „keine
  ;; Berechtigung“ -- der Export lief wie eine freie Abfrage statt wie eine gespeicherte Frage.
  (mt/with-no-data-perms-for-all-users!
    (perms/set-database-permission! (perms/all-users-group) (mt/id) :perms/view-data :unrestricted)
    (perms/set-database-permission! (perms/all-users-group) (mt/id) :perms/create-queries :no)
    (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                   :model/Card {card-id :id} (anzahl-karte)]
      (zeilen-statt-bild
       (fn []
         (let [antwort (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                             {:keycloak_sub sub :art "frage" :id card-id :parameter {:preis 1}})]
           (is (= (erwartete-anzahl 1) (zeilen antwort)) (pr-str (first (:karten antwort))))))))))

(deftest person-ohne-datenrecht-bekommt-fehler-statt-bild-test
  ;; Metabase prüft bei einer GESPEICHERTEN Frage die Tabellen aus ihren result_metadata (so kam auf ibkefap121
  ;; „keine Berechtigung, Daten der Tabelle 72 in result_metadata anzusehen“). Eine Karte ohne Metadaten hätte hier
  ;; nichts zu verweigern -- deshalb eine mit echten Metadaten.
  (mt/with-no-data-perms-for-all-users!
    (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                   :model/Card {card-id :id} (mt/card-with-metadata
                                              {:name "Lokale" :dataset_query (mt/mbql-query venues {:fields [$name] :limit 3})})]
      (let [karte (first (:karten (mt/user-http-request :crowberto :post 200 "ibykus/zeichnen"
                                                        {:keycloak_sub sub :art "frage" :id card-id})))]
        (is (false? (:darstellbar karte)) (pr-str karte))
        (is (string? (:fehler karte)))
        (is (nil? (:png karte)) "kein Bild, obwohl der Aufrufer Superuser ist")))))

(deftest unbekannter-parameter-test
  (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                 :model/Card {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
    (is (= "bericht.parameter_unbekannt"
           (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                        {:keycloak_sub sub :art "frage" :id card-id :parameter {:jahr "2025"}}))))))
