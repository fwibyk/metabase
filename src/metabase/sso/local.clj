(ns metabase.sso.local
  "Custom OSS SSO endpoint for iframe embedding via Keycloak.
   Validates a signed JWT and creates a Metabase session with cookie."
  (:require
   [clojure.string :as str]
   [compojure.core :refer [GET]]
   [java-time.api :as t]
   [environ.core :as env]
   [metabase.request.cookies :as cookies]
   [metabase.session.models.session :as session.model]
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

(defn- find-or-create-user! [email first-name last-name]
  (or (t2/select-one [:model/User :id :is_active] :%lower.email (str/lower-case email))
      (do
        (log/infof "SSO local: creating new Metabase user for %s" email)
        (t2/insert-returning-instance!
         [:model/User :id :is_active]
         {:email      email
          :first_name (or first-name "")
          :last_name  (or last-name "")
          :sso_source "sso"
          :is_active  true}))))

(defn- ensure-group! [group-name]
  (or (t2/select-one :permissions_group :name group-name)
      (t2/insert-returning-instance! :permissions_group {:name group-name})))

(defn- sync-groups! [user-id groups]
  (let [magic-ids (t2/select-fn-set :id :permissions_group
                                    :name [:in ["All Users" "Administrators"]])]
    (when (seq magic-ids)
      (t2/delete! :permissions_group_membership
                  :user_id user-id
                  :group_id [:not-in magic-ids])))
  (doseq [group-name groups]
    (let [{:keys [id]} (ensure-group! group-name)]
      (when (and id (not (t2/exists? :permissions_group_membership
                                     :user_id user-id :group_id id)))
        (t2/insert! :permissions_group_membership
                    {:user_id user-id :group_id id})))))

;;; ---- Color scheme cookie (embedded light/dark) ----------------------------
;; Der Einbettungs-Host (Analytics Studio) steuert Hell/Dunkel zentral ueber den
;; &scheme=-Param. Wir persistieren bewusst NICHT das user-local color-scheme (das
;; wuerde die persoenliche Standalone-Praeferenz ueberschreiben), sondern setzen ein
;; von JS lesbares Cookie, das der eingebettete Client (AppColorSchemeProvider)
;; auswertet, um im iframe nicht hart auf hell zu erzwingen.
(def ^:private embed-scheme-cookie "mb-embed-color-scheme")
(def ^:private allowed-schemes #{"light" "dark"})

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

(defn- safe-return-to [rt]
  ;; Nur echte lokale Pfade zulassen. `str/starts-with? "/"` allein reicht NICHT:
  ;; protokoll-relative Ziele wie "//evil.com" (bzw. "/\evil.com", das manche
  ;; Browser zu "//" normalisieren) beginnen ebenfalls mit "/" -> Open Redirect.
  (if (and (string? rt)
           (str/starts-with? rt "/")
           (not (str/starts-with? rt "//"))
           (not (str/starts-with? rt "/\\")))
    rt
    "/"))

(defn- handle-sso-local [request]
  (let [token     (get-in request [:params :token])
        return-to (safe-return-to (get-in request [:params :return_to]))
        scheme    (get-in request [:params :scheme])
        secret    (env/env :mb-sso-local-secret)]
    (if-let [claims (validate-jwt token secret)]
      (let [email      (get claims "email")
            first-name (get claims "first_name")
            last-name  (get claims "last_name")
            groups     (get claims "groups" [])]
        (if (str/blank? email)
          {:status 400 :body "Missing email in SSO token" :headers {"Content-Type" "text/plain"}}
          (try
            (let [user    (find-or-create-user! email first-name last-name)
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
              {:status 500 :body "SSO error" :headers {"Content-Type" "text/plain"}}))))
      (do
        (log/warn "SSO local: invalid or expired token")
        {:status 401 :body "Invalid or expired SSO token" :headers {"Content-Type" "text/plain"}}))))

(def routes
  (GET "/" request (handle-sso-local request)))
