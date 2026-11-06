(ns metabase.ibykus.berichte
  "ibykus: Berichte einer Person auflisten und als PNG zeichnen -- für den Berichtsexport des Analytics Studio
  (Change 2026-11-06-mcp-sql-und-berichte im Repository reporting-base).

  WER ENTSCHEIDET. Metabase selbst: Liste und Abfragen laufen mit `with-current-user` für das Metabase-Konto der
  Person (gefunden über die Keycloak-Kennung, wie bei `/auth/sso-local`). Was die Person in Metabase nicht lesen
  darf, steht nicht in der Liste und lässt sich nicht zeichnen; Datenrechte prüft der Query-Processor wie immer.

  EINGEBETTET. Auswertungen mit Zeilenfilter laufen im Analytics Studio als signierte Einbettung mit verriegeltem
  Parameter -- ohne Personenkonto. Dieselbe Form gibt es hier: der Aufrufer (nur Superuser, also der API-Schlüssel des
  hopservice) übergibt die verriegelten Werte, und die Abfrage läuft mit ihnen wie eine Einbettung. Die Werte kommen
  aus dem hopservice (`SignedAnalysisService`), nie von der Person.

  WIEDERVERWENDET, nicht nachgebaut: Ausführung je Dashboard-Karte wie bei Dashboard-Abonnements
  (`notification.payload.execute`), Zeichnen wie für E-Mail/Slack (`channel.render`). Was Metabase auf dem Server
  nicht zeichnen kann (Karten u. a.), kommt als `darstellbar false` zurück -- nicht stillschweigend weggelassen."
  (:require
   [clojure.string :as str]
   [metabase.channel.render.core :as channel.render]
   [metabase.channel.shared :as channel.shared]
   [metabase.dashboards.models.dashboard-card :as dashboard-card]
   [metabase.models.interface :as mi]
   [metabase.notification.payload.execute :as execute]
   [metabase.query-processor.card :as qp.card]
   [metabase.query-processor.core :as qp]
   [metabase.request.core :as request]
   [metabase.sso.local :as sso.local]
   [metabase.util.log :as log]
   [toucan2.core :as t2])
  (:import
   (java.util Base64)))

(set! *warn-on-reflection* true)

(def bild-breite
  "Breite einer Karte über die volle Dashboard-Breite in Pixeln (wie Slack: lesbar auf A4 quer)."
  1200)

(def ^:private raster-spalten
  "Spalten des Dashboard-Rasters von Metabase."
  24)

(defn- bild-breite-fuer
  "Zeichenbreite einer Dashboard-Karte nach ihrem Anteil am Raster (06.11.2026): eine halbbreite Karte, auf 1200 px
  gezeichnet und im PDF auf die halbe Breite verkleinert, hätte Schrift unter Lesegröße. Untergrenze 500 px."
  [size-x]
  (if (pos-int? size-x)
    (max 500 (Math/round (double (/ (* bild-breite (min size-x raster-spalten)) raster-spalten))))
    bild-breite))

(defn- lage
  "Position der Karte im Dashboard-Raster, damit der Aufrufer die Anordnung nachbauen kann."
  [dc]
  {:reihe (:row dc) :spalte (:col dc) :breite (:size_x dc) :hoehe (:size_y dc)})

(def max-karten
  "Höchstzahl Karten je Dashboard. Der hopservice hat eine eigene, kleinere Grenze; diese hier schützt Metabase."
  50)

;;; ------------------------------------------------ Person ----------------------------------------------------------

(defn konto
  "Das aktive Metabase-Konto zur Keycloak-Kennung, oder nil. Gleiche Zuordnung wie bei der Anmeldung."
  [keycloak-sub]
  (let [u (sso.local/user-mit-sub keycloak-sub)]
    (when (and u (:is_active u)) u)))

;;; ----------------------------------------------- Parameter --------------------------------------------------------

(defn- parameter-beschreibung [p]
  {:slug    (:slug p)
   :name    (:name p)
   :typ     (some-> (:type p) name)
   :pflicht (boolean (:required p))
   :vorgabe (:default p)})

(defn- ohne-gesperrte [parameter gesperrt]
  (remove #(contains? gesperrt (:slug %)) parameter))

(defn parameter-setzen
  "Setzt Werte nach `slug` in die Parameter eines Berichts. Wirft 400 bei unbekannten Namen und wenn ein verriegelter
  Parameter am Bericht fehlt (dann würde die Abfrage UNGEFILTERT laufen -- lieber gar nicht)."
  [parameter werte gesperrt]
  (let [slugs      (set (map :slug parameter))
        unbekannt  (remove slugs (map name (keys werte)))
        fehlt      (remove slugs (map name (keys gesperrt)))]
    (when-let [doppelt (seq (filter (set (map name (keys gesperrt))) (map name (keys werte))))]
      (throw (ex-info (str "Verriegelte Parameter lassen sich nicht übergeben: " (str/join ", " doppelt))
                      {:status-code 400 :code "bericht.parameter_verriegelt"})))
    (when (seq unbekannt)
      (throw (ex-info (str "Unbekannte Parameter: " (str/join ", " unbekannt)
                           ". Vorhanden: " (str/join ", " (sort slugs)))
                      {:status-code 400 :code "bericht.parameter_unbekannt"})))
    (when (seq fehlt)
      (throw (ex-info (str "Verriegelter Parameter fehlt am Bericht: " (str/join ", " fehlt))
                      {:status-code 400 :code "bericht.parameter_verriegelt"})))
    (let [alle (merge (update-keys werte name) (update-keys gesperrt name))]
      (vec (for [p parameter
                 :let [wert (get alle (:slug p) ::kein)]]
             (cond
               (not= wert ::kein) (assoc (dissoc p :default) :value wert)
               (some? (:default p)) (assoc (dissoc p :default) :value (:default p))
               :else p))))))

;;; ------------------------------------------------ Liste -----------------------------------------------------------

(defn- sammlungsnamen []
  (t2/select-fn->fn :id :name :model/Collection))

(defn berichte
  "Fragen und Dashboards, die das Konto `user-id` lesen darf, mit ihren Parametern."
  [user-id]
  (request/with-current-user user-id
    (let [namen (sammlungsnamen)]
      (vec
       (concat
        (for [c (t2/select :model/Card :archived false :type :question {:order-by [[:name :asc]]})
              :when (mi/can-read? c)]
          {:art       "frage"
           :id        (:id c)
           :titel     (:name c)
           :sammlung  (get namen (:collection_id c))
           :parameter (mapv parameter-beschreibung (:parameters c))})
        (for [d (t2/select :model/Dashboard :archived false {:order-by [[:name :asc]]})
              :when (mi/can-read? d)]
          {:art       "dashboard"
           :id        (:id d)
           :titel     (:name d)
           :sammlung  (get namen (:collection_id d))
           :parameter (mapv parameter-beschreibung (:parameters d))}))))))

;;; ----------------------------------------------- Zeichnen ---------------------------------------------------------

(defn- png-base64 ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- zeichne-karte
  "Ein Teil (Karte + Ergebnis) als PNG. Fehler und nicht zeichenbare Darstellungen kommen als Kennzeichen zurück."
  [card dashcard result px]
  (let [titel (or (-> dashcard :visualization_settings :card.title) (:name card))]
    (try
      (cond
        (:error result)
        {:titel titel :darstellbar false :fehler (str (:error result))}

        (nil? (channel.render/detect-pulse-chart-type card dashcard (:data result)))
        {:titel titel :darstellbar false :fehler "Diese Darstellung zeichnet Metabase nur im Browser."}

        :else
        (let [tz   (channel.render/defaulted-timezone card)
              info (channel.render/render-pulse-card :inline tz card dashcard result)]
          {:titel titel :darstellbar true
           :png   (png-base64 (channel.render/png-from-render-info info px))}))
      (catch Throwable e
        (log/warnf e "ibykus/berichte: Karte %s nicht gezeichnet" (:id card))
        {:titel titel :darstellbar false :fehler (or (ex-message e) "Fehler beim Zeichnen")}))))

(defn- frage-ausfuehren
  "Über `qp.card/process-query-for-card` wie die Oberfläche und die Abonnements (`execute/execute-card`), NICHT als
  freie Abfrage: nur dieser Weg bindet `qp.perms/*card-id*`, und erst damit darf eine gespeicherte NATIVE Frage mit
  reinem Datenrecht laufen. Der frühere Nachbau verlangte das Recht auf eigene SQL-Abfragen (ibkefap121, 06.11.2026)."
  [card parameter]
  (qp.card/process-query-for-card
   (:id card) :api
   ;; Nur, was auch die Oberfläche schickt: process-query-for-card ergänzt den Rest aus der Karte und prüft :name
   ;; gegen die Template-Tags (Anzeigename "Preis" ≠ Tag "preis" -> 500).
   :parameters (into [] (comp (filter #(contains? % :value)) (map #(select-keys % [:id :type :target :value]))) parameter)
   :context    :pulse
   :middleware {:process-viz-settings? true
                :js-int-to-string?     false}
   :make-run   (fn [qp _export-format]
                 (fn [query info]
                   (qp (qp/userland-query query info) nil)))))

(defn- frage-zeichnen [card-id werte gesperrt]
  (let [card (t2/select-one :model/Card :id card-id :archived false)]
    (when-not (and card (mi/can-read? card))
      (throw (ex-info "Bericht nicht gefunden" {:status-code 404 :code "bericht.nicht_gefunden"})))
    (let [parameter (parameter-setzen (:parameters card) werte gesperrt)
          result    (frage-ausfuehren card parameter)]
      {:titel     (:name card)
       :parameter (mapv parameter-beschreibung (ohne-gesperrte (:parameters card) (set (map name (keys gesperrt)))))
       :karten    [(zeichne-karte card nil result bild-breite)]})))

(defn- dashboard-zeichnen [dashboard-id werte gesperrt]
  (let [dash (t2/select-one :model/Dashboard :id dashboard-id :archived false)]
    (when-not (and dash (mi/can-read? dash))
      (throw (ex-info "Bericht nicht gefunden" {:status-code 404 :code "bericht.nicht_gefunden"})))
    (let [parameter (parameter-setzen (:parameters dash) werte gesperrt)
          dashcards (sort dashboard-card/dashcard-comparator
                          (t2/select :model/DashboardCard :dashboard_id dashboard-id))]
      (when (> (count dashcards) max-karten)
        (throw (ex-info (str "Das Dashboard hat mehr als " max-karten " Karten.")
                        {:status-code 413 :code "bericht.zu_gross"})))
      {:titel  (:name dash)
       :karten (vec
                (keep
                 (fn [dc]
                   (cond
                     (execute/virtual-card-of-type? dc "text")
                     (assoc (lage dc) :text (-> (execute/process-virtual-dashcard dc parameter) :visualization_settings :text))

                     (execute/virtual-card-of-type? dc "heading")
                     (assoc (lage dc) :ueberschrift (-> (execute/process-virtual-dashcard dc parameter) :visualization_settings :text))

                     (:card_id dc)
                     (let [teil (channel.shared/maybe-realize-data-rows
                                 (execute/execute-dashboard-subscription-card dc parameter))]
                       (if teil
                         (merge (zeichne-karte (:card teil) (:dashcard teil) (:result teil) (bild-breite-fuer (:size_x dc)))
                                (lage dc))
                         ;; nil heißt: Abfrage gescheitert (execute protokolliert) oder leer und ausgeblendet.
                         (assoc (lage dc)
                                :titel       (or (-> dc :visualization_settings :card.title)
                                                 (t2/select-one-fn :name :model/Card :id (:card_id dc)))
                                :darstellbar false
                                :fehler      "Die Karte konnte nicht ausgeführt werden oder ist leer und ausgeblendet.")))

                     :else nil))
                 dashcards))})))

(defn zeichnen
  "Zeichnet einen Bericht. `kontext` ist `{:user-id id}` (als diese Person) oder `{:eingebettet true}` (wie eine
  signierte Einbettung: die Rechte des Aufrufers, Superuser, mit den verriegelten Werten `gesperrt`)."
  [{:keys [user-id eingebettet]} art id werte gesperrt]
  (let [lauf (fn []
               (case art
                 "frage"     (frage-zeichnen id werte gesperrt)
                 "dashboard" (dashboard-zeichnen id werte gesperrt)))]
    (cond
      user-id     (request/with-current-user user-id (lauf))
      ;; Ohne verriegelten Wert wäre „eingebettet“ einfach die Sicht des Superusers -- das ist nie gemeint.
      (and eingebettet (empty? gesperrt))
      (throw (ex-info "Eingebettet nur mit verriegeltem Wert" {:status-code 400 :code "bericht.parameter_verriegelt"}))

      eingebettet (lauf)
      :else       (throw (ex-info "Weder Person noch eingebettet" {:status-code 400})))))
