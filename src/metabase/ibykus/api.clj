(ns metabase.ibykus.api
  "ibykus: `/api/ibykus/...` -- Endpunkte für den hopservice des Analytics Studio (Berichtsexport).

  NUR FÜR SUPERUSER. Aufrufer ist der hopservice mit seinem API-Schlüssel. Die Angabe einer Person
  (`keycloak_sub`) kann die Rechte des Aufrufs nur EINSCHRÄNKEN: gezeichnet wird mit den Rechten ihres Kontos.
  Ein zusätzliches Geheimnis bringt deshalb nichts -- wer den Schlüssel hat, ist ohnehin Superuser."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.ibykus.berichte :as berichte]
   [metabase.sso.local :as sso.local]
   [metabase.util.malli.schema :as ms]))

(defn- mit-codes
  "Eigene Fehler (ex-data mit `:code`) als schlanke Antwort `{code, message}` statt Stacktrace."
  [thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [status-code code]} (ex-data e)]
        (if code
          {:status (or status-code 400) :body {:code code :message (ex-message e)}}
          (throw e))))))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :post "/berichte"
  "Fragen und Dashboards, die das Metabase-Konto zur Keycloak-Kennung lesen darf, mit Parametern. Gruppen werden
  vorher wie bei der Anmeldung abgeglichen, wenn `gruppen` übergeben wird. Ohne Konto: `kein_konto true`."
  [_route-params
   _query-params
   {:keys [keycloak_sub gruppen]} :- [:map
                                      [:keycloak_sub ms/NonBlankString]
                                      [:gruppen {:optional true} [:maybe [:sequential :string]]]]]
  (api/check-superuser)
  (if-let [konto (berichte/konto keycloak_sub)]
    (do
      (when (some? gruppen)
        (sso.local/sync-groups! (:id konto) gruppen))
      {:kein_konto false :berichte (berichte/berichte (:id konto))})
    {:kein_konto true :berichte []}))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :post "/zeichnen"
  "Zeichnet eine Frage oder ein Dashboard als PNG je Karte (Base64). Entweder als Person (`keycloak_sub`) oder
  `eingebettet` mit verriegelten Werten (`gesperrt`). `parameter` und `gesperrt` sind Abbildungen slug -> Wert."
  [_route-params
   _query-params
   {:keys [keycloak_sub eingebettet art id parameter gesperrt]}
   :- [:map
       [:keycloak_sub {:optional true} [:maybe ms/NonBlankString]]
       [:eingebettet {:optional true} [:maybe :boolean]]
       [:art [:enum "frage" "dashboard"]]
       [:id ms/PositiveInt]
       [:parameter {:optional true} [:maybe [:map-of :keyword :any]]]
       [:gesperrt {:optional true} [:maybe [:map-of :keyword :any]]]]]
  (api/check-superuser)
  (mit-codes
   (fn []
     (cond
       ;; Beides zugleich wäre mehrdeutig: wessen Rechte gelten? Lieber abweisen.
       (and keycloak_sub eingebettet)
       {:status 400 :body {:code "bericht.kontext_mehrdeutig" :message "keycloak_sub and eingebettet are exclusive"}}

       keycloak_sub
       (if-let [konto (berichte/konto keycloak_sub)]
         (if (seq gesperrt)
           {:status 400 :body {:code "bericht.parameter_verriegelt"
                               :message "Locked values are only allowed for embedded reports"}}
           (berichte/zeichnen {:user-id (:id konto)} art id (or parameter {}) {}))
         {:status 404 :body {:code "bericht.kein_konto" :message "No Metabase account for this person"}})

       eingebettet
       (berichte/zeichnen {:eingebettet true} art id (or parameter {}) (or gesperrt {}))

       :else
       {:status 400 :body {:code "bericht.kontext_fehlt" :message "keycloak_sub or eingebettet is required"}}))))
