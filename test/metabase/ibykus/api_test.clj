(ns metabase.ibykus.api-test
  "Tests fuer /api/ibykus (Berichtsexport des Analytics Studio, Change 2026-11-06-mcp-sql-und-berichte).

  Die Zusicherung ist: Liste und Zeichnen laufen mit den Rechten des Metabase-Kontos der PERSON, nicht mit denen
  des aufrufenden Superusers -- und `eingebettet` geht nur mit verriegeltem Wert."
  (:require
   [clojure.test :refer :all]
   [metabase.permissions.core :as perms]
   [metabase.test :as mt]))

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

(deftest unbekannter-parameter-test
  (mt/with-temp [:model/User _ (dissoc (person-attrs nil) ::group)
                 :model/Card {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}]
    (is (= "bericht.parameter_unbekannt"
           (:code (mt/user-http-request :crowberto :post 400 "ibykus/zeichnen"
                                        {:keycloak_sub sub :art "frage" :id card-id :parameter {:jahr "2025"}}))))))
